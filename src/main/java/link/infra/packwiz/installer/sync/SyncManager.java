package link.infra.packwiz.installer.sync;

import link.infra.packwiz.installer.config.InstallerConfig;
import link.infra.packwiz.installer.metadata.*;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.HttpUrlPath;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;
import link.infra.packwiz.installer.util.Log;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 同步管理器。
 * 基于 git+packwiz 工作流：元数据从本地读取，仅联网下载实际文件。
 * 使用 ManifestFile (packwiz.json) 跟踪安装状态，实现完整的增量同步、清理和回滚。
 */
public class SyncManager {
    private final InstallerConfig config;
    private final Path rootDir;
    private final ClientHolder clientHolder;

    // ===== 数据结构 =====

    public static class SyncResult {
        public final List<ModChange> added = new ArrayList<>();
        public final List<ModChange> updated = new ArrayList<>();
        public final List<ModChange> removed = new ArrayList<>();
        public final List<ModChange> failed = new ArrayList<>();
        public String packName = "";
        public String lastSyncTime = "";
        public boolean unchanged = false;
    }

    public static class ModChange {
        public final String name;
        public final String destPath;
        public final String downloadUrl;
        public final String hash;
        public final String changeType; // "added" | "updated" | "removed" | "failed" | "cleaned"
        public final String error;
        public final String oldVersion;
        public final String newVersion;

        public ModChange(String name, String destPath, String downloadUrl, String hash, String changeType) {
            this(name, destPath, downloadUrl, hash, changeType, "", "", "");
        }

        public ModChange(String name, String destPath, String downloadUrl, String hash, String changeType,
                         String error, String oldVersion, String newVersion) {
            this.name = name;
            this.destPath = destPath;
            this.downloadUrl = downloadUrl;
            this.hash = hash;
            this.changeType = changeType;
            this.error = error;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }
    }

    private record DownloadInfo(PackwizPath<?> source, String displayUrl, HashFormat hashFormat, String expectedHash) {}
    private record DownloadTaskResult(ModChange change, Exception error) {}
    private record ExpectedHash(HashFormat format, String hash) {}

    public SyncManager(InstallerConfig config, Path rootDir) {
        this.config = config;
        this.rootDir = rootDir;
        this.clientHolder = new ClientHolder();
    }

    // ===== 同步检查 =====

    /**
     * 执行同步检查：从本地读取索引，对比清单，返回变更预览。
     */
    public SyncResult checkForChanges() throws Exception {
        SyncResult result = new SyncResult();
        PackwizFilePath packFolder = new PackwizFilePath(config.resolveInstallFolder(rootDir));

        // 加载清单
        Path manifestPath = InstallerConfig.getManifestFile(rootDir);
        ManifestFile manifest = ManifestFile.load(manifestPath, packFolder);

        // 本地读取 pack.toml
        PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
        InputStream packSource = packFilePath.source(clientHolder);
        Hash.HashingInputStream packHashing = HashFormat.SHA256.createSource(packSource);
        PackFile pf;
        try (packHashing) {
            pf = PackFile.fromToml(packHashing, packFilePath);
        }
        result.packName = pf.name != null ? pf.name : "未知";
        Hash<?> currentPackHash = new Hash<>(packHashing.getDigest(), Hash.Encoding.HEX);

        // 本地读取 index.toml
        PackwizPath<?> indexUri = pf.index.file();
        InputStream indexSource = indexUri.source(clientHolder);
        IndexFile indexFile;
        try (indexSource) {
            indexFile = IndexFile.fromToml(indexSource, indexUri.parent());
        }

        // 过滤同步范围内的条目
        List<IndexFile.FileEntry> syncedEntries = new ArrayList<>();
        for (var entry : indexFile.files) {
            if (isEntryInSyncScope(entry, packFolder)) {
                syncedEntries.add(entry);
            }
        }
        Log.info("同步范围: " + syncedEntries.size() + " / " + indexFile.files.size() + " 个条目");

        // 读取本地 .pw.toml 元数据
        boolean metadataReadFailed = false;
        var syncedIterator = syncedEntries.iterator();
        while (syncedIterator.hasNext()) {
            var entry = syncedIterator.next();
            if (entry.metafile) {
                try {
                    entry.readLocalMeta(indexFile, packFolder);
                } catch (Exception e) {
                    metadataReadFailed = true;
                    syncedIterator.remove();
                    Log.warn("读取元数据失败: " + entry.file + " - " + e.getMessage());
                }
            }
        }

        // Side 过滤
        if (!"both".equals(config.getSide())) {
            Side targetSide = Side.from(config.getSide());
            syncedEntries.removeIf(entry -> {
                if (entry.linkedFile != null && !entry.linkedFile.side.hasSide(targetSide)) {
                    Log.info("跳过 (侧不匹配): " + entry.getName());
                    return true;
                }
                return false;
            });
        }

        // 构建 index map: destPath -> FileEntry
        Map<String, IndexFile.FileEntry> indexMap = new LinkedHashMap<>();
        for (var entry : syncedEntries) {
            String destPath = relativeDestPath(entry, packFolder);
            if (destPath != null) {
                indexMap.put(destPath, entry);
            }
        }

        // 磁盘失效检查
        boolean invalidateAll = !"both".equals(config.getSide()) && config.getSide() != null
            && !config.getSide().equals(manifest.cachedSide != null ? manifest.cachedSide.name().toLowerCase() : "");
        Set<String> invalidatedPaths = new HashSet<>();
        if (!invalidateAll) {
            for (var manifestEntry : manifest.cachedFiles.entrySet()) {
                ManifestFile.File file = manifestEntry.getValue();
                if (file.onlyOtherSide) continue;
                if (!file.isOptional || file.optionValue) {
                    if (file.cachedLocation != null) {
                        if (!Files.exists(file.cachedLocation.nioPath())) {
                            invalidatedPaths.add(normalizeRelativePath(manifestEntry.getKey().path()));
                            Log.info("文件已失效: " + manifestEntry.getKey());
                        }
                    } else {
                        invalidatedPaths.add(normalizeRelativePath(manifestEntry.getKey().path()));
                    }
                }
            }
        }

        // Pack Hash 快速跳过
        if (!metadataReadFailed && !invalidateAll && currentPackHash.equals(manifest.packFileHash) && invalidatedPaths.isEmpty()) {
            Log.info("Pack 未变化，无需同步");
            result.unchanged = true;
            return result;
        }

        // Diff: index vs manifest
        for (var indexEntry : indexMap.entrySet()) {
            String destPath = indexEntry.getKey();
            var fileEntry = indexEntry.getValue();

            String modName = fileEntry.getName();
            var dlInfo = getDownloadInfo(fileEntry, indexFile);
            String downloadUrl = dlInfo != null ? dlInfo.displayUrl() : "";
            String downloadHash = dlInfo != null ? dlInfo.expectedHash() : "";
            String indexHash = fileEntry.hash;

            // 在 manifest 中查找对应条目；禁用的 mods/*.jar 会以 enabled key + disabled cachedLocation 保存。
            ManifestFile.File manifestFile = previousManifestEntry(manifest, packFolder, destPath);
            if (manifestFile == null) {
                var change = new ModChange(modName, destPath, downloadUrl, downloadHash, "added");
                if (!tryRecordExistingModsJar(change, fileEntry, manifest, indexFile, packFolder, "新增")) {
                    result.added.add(change);
                }
            } else if (invalidateAll
                || invalidatedPaths.contains(normalizeRelativePath(destPath))
                || !indexHash.equals(manifestFile.hash != null ? manifestFile.hash.toString() : "")) {
                var change = new ModChange(modName, destPath, downloadUrl, downloadHash, "updated",
                    "", "", modName);
                if (!tryRecordExistingModsJar(change, fileEntry, manifest, indexFile, packFolder, "更新")) {
                    result.updated.add(change);
                }
            }
        }

        // Manifest 有, index 无 → 将删除/清理
        Set<String> indexDestPaths = indexMap.keySet();
        for (var manifestEntry : manifest.cachedFiles.entrySet()) {
            PackwizFilePath manifestKey = manifestEntry.getKey();
            ManifestFile.File file = manifestEntry.getValue();
            String relPath = normalizeRelativePath(manifestKey.path());
            if (file.cachedLocation != null && relPath != null && !indexDestPaths.contains(relPath)) {
                result.removed.add(new ModChange(
                    relPath, relPath, "", "", "removed"
                ));
            }
        }

        foldRenamedUpdates(result, indexMap, manifest, packFolder);

        if (!metadataReadFailed && result.added.isEmpty() && result.updated.isEmpty() && result.removed.isEmpty()) {
            manifest.packFileHash = currentPackHash;
            manifest.cachedSide = Side.from(config.getSide());
            manifest.save(manifestPath, packFolder);
            result.unchanged = true;
        }

        return result;
    }

    // ===== 执行同步 =====

    /**
     * 执行同步：清理已删除文件、下载新增/更新文件、更新清单。
     */
    public SyncResult executeSync(SyncResult preview, java.util.function.BiConsumer<Integer, String> progressCallback) {
        SyncResult result = new SyncResult();
        result.packName = preview.packName;
        result.lastSyncTime = preview.lastSyncTime;

        PackwizFilePath packFolder = new PackwizFilePath(config.resolveInstallFolder(rootDir));
        Path manifestPath = InstallerConfig.getManifestFile(rootDir);
        ManifestFile manifest = ManifestFile.load(manifestPath, packFolder);

        // 重新加载 index（用于 CurseForge 解析）
        final IndexFile indexFile = loadIndexFile(packFolder);

        // Phase 0: 清理 manifest 中已不在 index 中的文件
        if (indexFile != null) {
            int cleaned = cleanupRemovedFiles(manifest, indexFile, packFolder);
            if (cleaned > 0) Log.info("已清理 " + cleaned + " 个已移除的文件");
        }

        // 计算总任务数
        List<ModChange> downloadTasks = new ArrayList<>();
        downloadTasks.addAll(preview.added);
        downloadTasks.addAll(preview.updated);
        int total = downloadTasks.size() + preview.removed.size();
        int completed = 0;

        // Phase 1: 删除移除的文件
        for (var change : preview.removed) {
            try {
                ManifestFile.File cached = previousManifestEntry(manifest, packFolder, change.destPath);
                Path filePath = cached != null && cached.cachedLocation != null
                    ? resolveInsidePack(packFolder, cached.cachedLocation.path())
                    : resolveInsidePack(packFolder, change.destPath);
                boolean deleted = Files.deleteIfExists(filePath);
                if (!deleted && isDirectModsJar(change.destPath)) {
                    deleted = Files.deleteIfExists(resolveInsidePack(packFolder, enabledPath(change.destPath)));
                    deleted |= Files.deleteIfExists(resolveInsidePack(packFolder, disabledPath(change.destPath)));
                }
                if (deleted) {
                    Log.info("已删除: " + change.destPath);
                }
                manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), enabledPath(change.destPath)));
                manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), disabledPath(change.destPath)));
                result.removed.add(change);
            } catch (IOException e) {
                Log.warn("删除失败: " + change.destPath + " - " + e.getMessage());
            }
            completed++;
            if (progressCallback != null) progressCallback.accept(completed, "删除: " + change.name);
        }

        // Phase 1.8: 本地 mods/*.jar 已存在且 hash 正确时跳过下载
        if (indexFile != null && !downloadTasks.isEmpty()) {
            var remainingTasks = new ArrayList<ModChange>();
            for (var change : downloadTasks) {
                try {
                    IndexFile.FileEntry entry = findIndexEntry(indexFile, change.destPath, packFolder);
                    if (entry != null && trySkipExistingModsJar(change, entry, manifest, indexFile, packFolder)) {
                        addSuccessfulChange(result, change);
                        completed++;
                        if (progressCallback != null) progressCallback.accept(completed, "跳过: " + change.name);
                    } else {
                        remainingTasks.add(change);
                    }
                } catch (Exception e) {
                    Log.warn("检查本地文件失败，继续下载: " + change.destPath + " - " + e.getMessage());
                    remainingTasks.add(change);
                }
            }
            downloadTasks = remainingTasks;
        }

        // Phase 2: 解析 CurseForge URLs
        if (indexFile != null) {
            var cfEntries = downloadTasks.stream()
                .map(change -> findIndexEntry(indexFile, change.destPath, packFolder))
                .filter(Objects::nonNull)
                .filter(e -> e.linkedFile != null && e.linkedFile.update.containsKey("curseforge"))
                .filter(e -> !e.linkedFile.resolvedUpdateData.containsKey("curseforge"))
                .collect(Collectors.toList());

            if (!cfEntries.isEmpty()) {
                Log.info("解析 CurseForge 元数据 (" + cfEntries.size() + " 个)...");
                if (progressCallback != null) progressCallback.accept(completed, "解析 CurseForge...");
                var failures = CurseForgeSourcer.resolveCfMetadata(cfEntries, packFolder, clientHolder);
                for (var failure : failures) {
                    Log.warn("CurseForge 解析失败: " + failure.name() + " - " + failure.exception().getMessage());
                    if (failure.manualOnly() && failure.entry() != null) {
                        String destPath = relativeDestPath(failure.entry(), packFolder);
                        ModChange failedChange = removeTaskByDestPath(downloadTasks, destPath);
                        if (failedChange != null) {
                            boolean acceptedExisting = tryAcceptManualExisting(
                                failure.entry(), failedChange, manifest, indexFile, packFolder);
                            if (acceptedExisting) {
                                addSuccessfulChange(result, failedChange);
                                Log.info("跳过手动下载，文件已存在: " + failedChange.destPath);
                            } else {
                                result.failed.add(new ModChange(
                                    failedChange.name, failedChange.destPath, failure.url(), failedChange.hash,
                                    failedChange.changeType, failure.exception().getMessage(), "", ""
                                ));
                            }
                            completed++;
                            if (progressCallback != null) progressCallback.accept(completed,
                                acceptedExisting ? "跳过: " + failedChange.name : "需手动下载: " + failedChange.name);
                        }
                    }
                }
            }
        }

        // Phase 3: 并行下载
        var threadPool = Executors.newFixedThreadPool(10, runnable -> {
            Thread thread = new Thread(runnable, "packworkbench-download");
            thread.setDaemon(true);
            return thread;
        });
        var completionService = new ExecutorCompletionService<DownloadTaskResult>(threadPool);

        for (var change : downloadTasks) {
            completionService.submit(() -> {
                try {
                    downloadFile(change, packFolder, indexFile, manifest);
                    return new DownloadTaskResult(change, null);
                } catch (Exception e) {
                    return new DownloadTaskResult(change, e);
                }
            });
        }

        for (int i = 0; i < downloadTasks.size(); i++) {
            try {
                DownloadTaskResult taskResult = completionService.take().get();
                ModChange completedChange = taskResult.change();
                if (taskResult.error() == null) {
                    IndexFile.FileEntry entry = indexFile != null
                        ? findIndexEntry(indexFile, completedChange.destPath, packFolder) : null;
                    try {
                        putManifestEntry(manifest, entry, indexFile, packFolder, completedChange);
                        cleanupRenamedOldPath(manifest, packFolder, completedChange);
                        String status = completedChange.changeType.equals("added") ? "已新增" : "已更新";
                        addSuccessfulChange(result, completedChange);
                        Log.info(status + ": " + completedChange.name);
                    } catch (IOException e) {
                        String errorMsg = e.getMessage() != null ? e.getMessage() : "更新清单失败";
                        result.failed.add(new ModChange(
                            completedChange.name, completedChange.destPath, completedChange.downloadUrl, completedChange.hash,
                            completedChange.changeType, errorMsg, "", ""
                        ));
                        Log.warn("更新清单失败: " + completedChange.name + " - " + errorMsg);
                    }
                } else {
                    String errorMsg = taskResult.error().getMessage() != null ? taskResult.error().getMessage() : "未知错误";
                    result.failed.add(new ModChange(
                        completedChange.name, completedChange.destPath, completedChange.downloadUrl, completedChange.hash,
                        completedChange.changeType, errorMsg, "", ""
                    ));
                    Log.warn("下载失败: " + completedChange.name + " - " + errorMsg);
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : "未知错误";
                Log.warn("下载任务失败: " + errorMsg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            completed++;
            if (progressCallback != null) progressCallback.accept(completed, "下载完成");
        }

        threadPool.shutdownNow();

        // 保存清单
        manifest.cachedSide = Side.from(config.getSide());
        if (result.failed.isEmpty()) {
            // 只有全部成功时才更新 pack hash
            try {
                PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
                InputStream packSource = packFilePath.source(clientHolder);
                Hash.HashingInputStream hashing = HashFormat.SHA256.createSource(packSource);
                try (hashing) { hashing.transferTo(new OutputStream() { @Override public void write(int b) {} }); }
                manifest.packFileHash = new Hash<>(hashing.getDigest(), Hash.Encoding.HEX);
            } catch (Exception e) {
                Log.warn("计算 pack hash 失败: " + e.getMessage());
            }
        }
        manifest.save(manifestPath, packFolder);

        return result;
    }

    // ===== 内部方法 =====

    /** 从本地加载 index.toml 文件 */
    private IndexFile loadIndexFile(PackwizFilePath packFolder) {
        try {
            PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
            InputStream packSource = packFilePath.source(clientHolder);
            PackFile pf;
            try (packSource) {
                pf = PackFile.fromToml(packSource, packFilePath);
            }
            PackwizPath<?> indexUri = pf.index.file();
            InputStream indexSource = indexUri.source(clientHolder);
            IndexFile indexFile;
            try (indexSource) {
                indexFile = IndexFile.fromToml(indexSource, indexUri.parent());
            }
            // 读取元数据
            var iterator = indexFile.files.iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.metafile) {
                    try {
                        entry.readLocalMeta(indexFile, packFolder);
                    } catch (Exception e) {
                        iterator.remove();
                        Log.warn("读取元数据失败，执行阶段将视为已移除: " + entry.file + " - " + e.getMessage());
                    }
                }
            }
            return indexFile;
        } catch (Exception e) {
            Log.warn("加载索引失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清理 manifest 中存在但 index 中不存在的文件。
     */
    private int cleanupRemovedFiles(ManifestFile manifest, IndexFile indexFile, PackwizFilePath packFolder) {
        int cleaned = 0;
        Set<String> indexDestPaths = new HashSet<>();
        for (var entry : indexFile.files) {
            if (!isEntryInSyncScope(entry, packFolder)) continue;
            String destPath = relativeDestPath(entry, packFolder);
            indexDestPaths.add(destPath);
        }

        var iterator = manifest.cachedFiles.entrySet().iterator();
        while (iterator.hasNext()) {
            var manifestEntry = iterator.next();
            PackwizFilePath path = manifestEntry.getKey();
            ManifestFile.File file = manifestEntry.getValue();
            String relPath = normalizeRelativePath(path.path());

            // 只清理同步范围内的文件
            if (!shouldSync(relPath)) continue;

            if (file.cachedLocation != null && !indexDestPaths.contains(relPath)) {
                try {
                    Path deletePath = file.cachedLocation != null
                        ? resolveInsidePack(packFolder, file.cachedLocation.path())
                        : resolveInsidePack(packFolder, relPath);
                    boolean deleted = Files.deleteIfExists(deletePath);
                    if (!deleted && isDirectModsJar(relPath)) {
                        deleted = Files.deleteIfExists(resolveInsidePack(packFolder, disabledPath(relPath)));
                    }
                    if (deleted) {
                        Log.info("已清理 (从索引移除): " + relPath);
                        cleaned++;
                    }
                } catch (IOException e) {
                    Log.warn("清理失败: " + relPath + " - " + e.getMessage());
                }
                iterator.remove();
            }
        }
        return cleaned;
    }

    /**
     * 下载单个文件（含 hash 校验）。
     */
    private void downloadFile(ModChange change, PackwizFilePath packFolder, IndexFile indexFile,
                              ManifestFile manifest) throws Exception {
        // Preserve 检查
        IndexFile.FileEntry entry = indexFile != null ? findIndexEntry(indexFile, change.destPath, packFolder) : null;
        Path destPath = resolveActualDestPath(packFolder, change, manifest);
        if (entry != null && entry.preserve) {
            if (Files.exists(destPath)) {
                Log.info("跳过 (preserve): " + relativePath(packFolder, destPath));
                return;
            }
        }

        DownloadInfo dlInfo = entry != null ? getDownloadInfo(entry, indexFile) : null;
        if (dlInfo == null || dlInfo.source() == null) {
            throw new Exception("无法获取下载 URL: " + change.name);
        }

        Files.createDirectories(destPath.getParent());
        Path tempPath = Files.createTempFile(destPath.getParent(), ".packwiz-download-", ".tmp");

        try {
            InputStream source = dlInfo.source().source(clientHolder);
            Hash.HashingInputStream hashSource = dlInfo.hashFormat().createSource(source);
            try (hashSource; var out = Files.newOutputStream(tempPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = hashSource.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            verifyDigest(hashSource.getDigest(), dlInfo.hashFormat(), dlInfo.expectedHash());
            moveReplacing(tempPath, destPath);
            removeOppositeModsStateFile(packFolder, change.destPath, destPath);
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** 解析下载 URL（支持 HTTP、file: URI、本地路径） */
    private PackwizPath<?> resolvePackUrl(String url) {
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("URL 为空");
        if (url.matches("(?i)^https?://.*")) {
            var uri = URI.create(url);
            return new HttpUrlPath(uri);
        }
        if (url.matches("(?i)^file:.*")) {
            Path path = Path.of(URI.create(url));
            return new PackwizFilePath(path.getParent(), path.getFileName().toString());
        }
        Path path = Path.of(url.replace("\\", "/"));
        if (path.getParent() != null) {
            return new PackwizFilePath(path.getParent(), path.getFileName().toString());
        }
        return new PackwizFilePath(Path.of("."), path.getFileName().toString());
    }

    /**
     * 获取文件的下载信息。
     * CurseForge 条目在 API 解析前返回 null。
     */
    private DownloadInfo getDownloadInfo(IndexFile.FileEntry entry, IndexFile indexFile) {
        if (entry.linkedFile != null) {
            ExpectedHash expected = expectedDownloadHash(entry, indexFile);
            // CurseForge 解析后的 URL
            var resolved = entry.linkedFile.resolvedUpdateData.get("curseforge");
            if (resolved != null) {
                return new DownloadInfo(resolved, resolved.toString(), expected.format(), expected.hash());
            }
            // 直接 URL
            if (entry.linkedFile.download != null && entry.linkedFile.download.url() != null) {
                return new DownloadInfo(
                    entry.linkedFile.download.url(),
                    entry.linkedFile.download.url().toString(),
                    expected.format(),
                    expected.hash()
                );
            }
            // CurseForge 模式但尚未解析 → 返回 null
            if (entry.linkedFile.download != null
                && entry.linkedFile.download.mode() == DownloadMode.CURSEFORGE) {
                return null;
            }
        }
        ExpectedHash expected = expectedDownloadHash(entry, indexFile);
        return new DownloadInfo(entry.file, entry.file.toString(), expected.format(), expected.hash());
    }

    /** 判断文件是否在同步范围内 */
    private boolean shouldSync(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (normalized == null || normalized.isEmpty() || normalized.contains("../")) return false;
        int slashIdx = normalized.indexOf('/');
        if (slashIdx < 0) return false; // 根目录文件不同步
        String topFolder = normalized.substring(0, slashIdx);
        if (InstallerConfig.CONFIG_DIR_NAME.equals(topFolder)) return false;
        return switch (config.getSyncMode()) {
            case MODS_ONLY -> "mods".equals(topFolder);
            case CONFIGURED_FILES -> true;
        };
    }

    private boolean isEntryInSyncScope(IndexFile.FileEntry entry, PackwizFilePath packFolder) {
        if (config.getSyncMode() == InstallerConfig.SyncMode.MODS_ONLY) {
            if (!entry.metafile) return false;
            String metaPath = normalizeRelativePath(entry.file.rebase(packFolder).path());
            if (metaPath == null || !metaPath.startsWith("mods/.index/") || !metaPath.endsWith(".pw.toml")) {
                return false;
            }
            if (entry.linkedFile == null) return true;
            return isDirectModsJar(relativeDestPath(entry, packFolder));
        }
        return shouldSync(relativeDestPath(entry, packFolder));
    }

    /** 在 index 中查找对应的 FileEntry */
    private IndexFile.FileEntry findIndexEntry(IndexFile indexFile, String destPath, PackwizFilePath packFolder) {
        if (indexFile == null) return null;
        String normalizedDest = normalizeRelativePath(destPath);
        for (var entry : indexFile.files) {
            if (!isEntryInSyncScope(entry, packFolder)) continue;
            String entryDest = relativeDestPath(entry, packFolder);
            if (entryDest.equals(normalizedDest)) return entry;
        }
        return null;
    }

    private String normalizeRelativePath(String path) {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private boolean isDirectModsJar(String destPath) {
        String normalized = normalizeRelativePath(destPath);
        if (normalized == null || !normalized.startsWith("mods/")) return false;
        String rest = normalized.substring("mods/".length());
        String lower = rest.toLowerCase(Locale.ROOT);
        return !rest.isEmpty() && !rest.contains("/")
            && (lower.endsWith(".jar") || lower.endsWith(".jar.disabled"));
    }

    private String relativeDestPath(IndexFile.FileEntry entry, PackwizFilePath packFolder) {
        return normalizeRelativePath(entry.getDestURI().rebase(packFolder).path());
    }

    private Path resolveInsidePack(PackwizFilePath packFolder, String destPath) throws IOException {
        String normalized = normalizeRelativePath(destPath);
        if (normalized == null || normalized.isEmpty() || normalized.contains("../") || normalized.equals("..")) {
            throw new IOException("非法目标路径: " + destPath);
        }
        Path base = packFolder.nioPath().toAbsolutePath().normalize();
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("目标路径位于安装目录外: " + destPath);
        }
        return resolved;
    }

    private String enabledPath(String destPath) {
        String normalized = normalizeRelativePath(destPath);
        if (normalized == null) return null;
        return normalized.toLowerCase(Locale.ROOT).endsWith(".disabled")
            ? normalized.substring(0, normalized.length() - ".disabled".length())
            : normalized;
    }

    private String disabledPath(String destPath) {
        String enabled = enabledPath(destPath);
        return enabled == null ? null : enabled + ".disabled";
    }

    private ManifestFile.File previousManifestEntry(ManifestFile manifest, PackwizFilePath packFolder, String destPath) {
        synchronized (manifest) {
            String enabled = enabledPath(destPath);
            String disabled = disabledPath(destPath);
            for (String rel : new String[]{enabled, disabled, normalizeRelativePath(destPath)}) {
                if (rel == null) continue;
                ManifestFile.File file = manifest.cachedFiles.get(new PackwizFilePath(packFolder.nioPath(), rel));
                if (file != null) return file;
            }
            Set<String> candidates = new HashSet<>();
            for (String rel : new String[]{enabled, disabled, normalizeRelativePath(destPath)}) {
                if (rel != null) candidates.add(rel);
            }
            for (var entry : manifest.cachedFiles.entrySet()) {
                ManifestFile.File file = entry.getValue();
                String cached = file.cachedLocation != null ? normalizeRelativePath(file.cachedLocation.path()) : null;
                if (cached != null && candidates.contains(cached)) return file;
            }
        }
        return null;
    }

    private boolean shouldKeepDisabled(ManifestFile manifest, PackwizFilePath packFolder, String destPath) {
        ManifestFile.File previous = previousManifestEntry(manifest, packFolder, destPath);
        return previous != null && previous.disabled && isDirectModsJar(destPath);
    }

    private boolean shouldKeepDisabled(ManifestFile manifest, PackwizFilePath packFolder, ModChange change) {
        if (change == null) return false;
        if (shouldKeepDisabled(manifest, packFolder, change.destPath)) return true;
        return change.oldVersion != null && !change.oldVersion.isBlank()
            && shouldKeepDisabled(manifest, packFolder, change.oldVersion)
            && isDirectModsJar(change.destPath);
    }

    private Path resolveActualDestPath(PackwizFilePath packFolder, ModChange change, ManifestFile manifest) throws IOException {
        return resolveInsidePack(packFolder, shouldKeepDisabled(manifest, packFolder, change)
            ? disabledPath(change.destPath)
            : enabledPath(change.destPath));
    }

    private String relativePath(PackwizFilePath packFolder, Path path) {
        Path base = packFolder.nioPath().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) return path.toString();
        return normalizeRelativePath(base.relativize(absolute).toString());
    }

    private void removeOppositeModsStateFile(PackwizFilePath packFolder, String destPath, Path actualDestPath) throws IOException {
        if (!isDirectModsJar(destPath)) return;
        Path enabled = resolveInsidePack(packFolder, enabledPath(destPath));
        Path disabled = resolveInsidePack(packFolder, disabledPath(destPath));
        Path opposite = actualDestPath.toAbsolutePath().normalize().equals(enabled.toAbsolutePath().normalize()) ? disabled : enabled;
        Files.deleteIfExists(opposite);
    }

    private ExpectedHash expectedDownloadHash(IndexFile.FileEntry entry, IndexFile indexFile) {
        if (entry.linkedFile != null && entry.linkedFile.download != null) {
            return new ExpectedHash(entry.linkedFile.download.hashFormat(), entry.linkedFile.download.hash());
        }
        return new ExpectedHash(entry.effectiveHashFormat(indexFile), entry.hash);
    }

    private void verifyDigest(byte[] digest, HashFormat format, String expectedHash) throws Exception {
        if (expectedHash == null || expectedHash.isEmpty()) return;
        Hash<?> expected = format.fromString(expectedHash);
        Hash<?> actual = new Hash<>(digest, format.encoding());
        if (!expected.equals(actual)) {
            throw new Exception("Hash 不匹配: 期望 " + expected + ", 实际 " + actual);
        }
    }

    private Hash<?> hashLocalFile(Path path, HashFormat format) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Hash.HashingInputStream hashing = format.createSource(in)) {
            hashing.transferTo(OutputStream.nullOutputStream());
            return new Hash<>(hashing.getDigest(), format.encoding());
        }
    }

    private boolean trySkipExistingModsJar(ModChange change, IndexFile.FileEntry entry, ManifestFile manifest,
                                           IndexFile indexFile, PackwizFilePath packFolder) throws Exception {
        if (!isDirectModsJar(change.destPath) || !shouldSync(change.destPath)) return false;
        Path localPath = resolveInsidePack(packFolder, change.destPath);
        Path disabledLocalPath = resolveInsidePack(packFolder, disabledPath(change.destPath));
        Path actualPath = Files.exists(localPath) ? localPath : disabledLocalPath;
        if (!Files.exists(actualPath)) return false;

        if (entry.preserve) {
            putManifestEntry(manifest, entry, indexFile, packFolder, change.destPath, relativePath(packFolder, actualPath));
            Log.info("跳过下载 (preserve): " + relativePath(packFolder, actualPath));
            return true;
        }

        ExpectedHash expected = expectedDownloadHash(entry, indexFile);
        Hash<?> actual = hashLocalFile(actualPath, expected.format());
        Hash<?> expectedHash = expected.format().fromString(expected.hash());
        if (expectedHash.equals(actual)) {
            putManifestEntry(manifest, entry, indexFile, packFolder, change.destPath, relativePath(packFolder, actualPath));
            Log.info("跳过下载，文件已存在且 Hash 正确: " + relativePath(packFolder, actualPath));
            return true;
        }
        Log.info("本地文件 Hash 不匹配，将重新下载: " + relativePath(packFolder, actualPath));
        return false;
    }

    private boolean tryRecordExistingModsJar(ModChange change, IndexFile.FileEntry entry, ManifestFile manifest,
                                             IndexFile indexFile, PackwizFilePath packFolder, String action) {
        try {
            if (!isDirectModsJar(change.destPath) || !shouldSync(change.destPath)) return false;
            Path enabledLocalPath = resolveInsidePack(packFolder, enabledPath(change.destPath));
            Path disabledLocalPath = resolveInsidePack(packFolder, disabledPath(change.destPath));
            Path actualPath = Files.exists(enabledLocalPath) ? enabledLocalPath : disabledLocalPath;
            if (!Files.exists(actualPath)) return false;

            if (!entry.preserve) {
                ExpectedHash expected = expectedDownloadHash(entry, indexFile);
                Hash<?> actual = hashLocalFile(actualPath, expected.format());
                Hash<?> expectedHash = expected.format().fromString(expected.hash());
                if (!expectedHash.equals(actual)) return false;
            }

            putManifestEntry(manifest, entry, indexFile, packFolder, change.destPath, relativePath(packFolder, actualPath));
            manifest.save(InstallerConfig.getManifestFile(rootDir), packFolder);
            Log.info("跳过" + action + "，本地文件已存在且 Hash 正确: " + relativePath(packFolder, actualPath));
            return true;
        } catch (Exception e) {
            Log.warn("检查本地文件失败，将继续同步: " + change.destPath + " - " + e.getMessage());
            return false;
        }
    }

    /** 接受 CurseForge API 排除项的本地手动下载文件，并写入 manifest。 */
    private boolean tryAcceptManualExisting(IndexFile.FileEntry entry, ModChange change,
                                            ManifestFile manifest, IndexFile indexFile,
                                            PackwizFilePath packFolder) {
        try {
            if (entry == null || change == null || !isDirectModsJar(change.destPath)) return false;
            Path enabled = resolveInsidePack(packFolder, enabledPath(change.destPath));
            Path disabled = resolveInsidePack(packFolder, disabledPath(change.destPath));
            Path actual = Files.exists(enabled) ? enabled : disabled;
            if (!Files.exists(actual) || !Files.isRegularFile(actual)) return false;

            ExpectedHash expected = expectedDownloadHash(entry, indexFile);
            if (expected.hash() != null && !expected.hash().isBlank()) {
                Hash<?> actualHash = hashLocalFile(actual, expected.format());
                if (!expected.format().fromString(expected.hash()).equals(actualHash)) return false;
            }
            putManifestEntry(manifest, entry, indexFile, packFolder, change.destPath,
                relativePath(packFolder, actual));
            return true;
        } catch (Exception e) {
            Log.warn("检查手动下载文件失败: " + change.destPath + " - " + e.getMessage());
            return false;
        }
    }

    private void foldRenamedUpdates(SyncResult result, Map<String, IndexFile.FileEntry> indexMap,
                                    ManifestFile manifest, PackwizFilePath packFolder) {
        var addedIterator = result.added.iterator();
        while (addedIterator.hasNext()) {
            ModChange added = addedIterator.next();
            IndexFile.FileEntry addedEntry = indexMap.get(normalizeRelativePath(added.destPath));
            if (addedEntry == null || addedEntry.hash == null) continue;
            String addedMetaPath = metaFilePath(addedEntry, packFolder);

            ModChange matchingRemoved = null;
            for (ModChange removed : result.removed) {
                ManifestFile.File oldFile = manifest.cachedFiles.get(new PackwizFilePath(
                    packFolder.nioPath(), removed.destPath
                ));
                String oldMetaPath = oldFile != null && oldFile.metaFile != null
                    ? normalizeRelativePath(oldFile.metaFile.path()) : null;
                String oldHash = oldFile != null && oldFile.hash != null ? oldFile.hash.toString() : null;
                if ((addedMetaPath != null && addedMetaPath.equals(oldMetaPath))
                    || (oldMetaPath == null && addedEntry.hash.equals(oldHash))
                    || (oldMetaPath == null && sameMetaFileBasename(addedMetaPath, removed.destPath))) {
                    matchingRemoved = removed;
                    break;
                }
            }
            if (matchingRemoved == null) continue;

            addedIterator.remove();
            result.removed.remove(matchingRemoved);
            result.updated.add(new ModChange(
                added.name, added.destPath, added.downloadUrl, added.hash,
                "updated", "", matchingRemoved.destPath, added.destPath
            ));
        }
    }

    private String metaFilePath(IndexFile.FileEntry entry, PackwizFilePath packFolder) {
        if (entry == null || !entry.metafile || entry.file == null) return null;
        return normalizeRelativePath(entry.file.rebase(packFolder).path());
    }

    private boolean sameMetaFileBasename(String metaPath, String removedDestPath) {
        String metaBase = fileStem(metaPath, ".pw.toml");
        String oldJarBase = fileStem(removedDestPath, ".jar");
        return metaBase != null && oldJarBase != null
            && (oldJarBase.equals(metaBase) || oldJarBase.startsWith(metaBase + "-"));
    }

    private String fileStem(String path, String suffix) {
        String normalized = normalizeRelativePath(path);
        if (normalized == null) return null;
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(lowerSuffix)) return null;
        return name.substring(0, name.length() - suffix.length()).toLowerCase(Locale.ROOT);
    }

    private void putManifestEntry(ManifestFile manifest, IndexFile.FileEntry entry, IndexFile indexFile,
                                  PackwizFilePath packFolder, String destPath) throws IOException {
        putManifestEntry(manifest, entry, indexFile, packFolder, destPath,
            shouldKeepDisabled(manifest, packFolder, destPath) ? disabledPath(destPath) : enabledPath(destPath));
    }

    private void putManifestEntry(ManifestFile manifest, IndexFile.FileEntry entry, IndexFile indexFile,
                                  PackwizFilePath packFolder, ModChange change) throws IOException {
        putManifestEntry(manifest, entry, indexFile, packFolder, change.destPath,
            shouldKeepDisabled(manifest, packFolder, change) ? disabledPath(change.destPath) : enabledPath(change.destPath));
    }

    private void putManifestEntry(ManifestFile manifest, IndexFile.FileEntry entry, IndexFile indexFile,
                                  PackwizFilePath packFolder, String destPath, String actualDestPath) throws IOException {
        synchronized (manifest) {
            String enabledDestPath = enabledPath(destPath);
            String actualPath = normalizeRelativePath(actualDestPath);
            PackwizFilePath key = new PackwizFilePath(packFolder.nioPath(), enabledDestPath);
            ManifestFile.File previous = previousManifestEntry(manifest, packFolder, destPath);
            ManifestFile.File manifestFile = new ManifestFile.File();
            if (entry != null) {
                manifestFile.hash = entry.getHashObj(indexFile);
                manifestFile.linkedFileHash = entry.linkedFile != null && entry.linkedFile.download != null ? entry.linkedFile.getHash() : null;
                String metaPath = metaFilePath(entry, packFolder);
                manifestFile.metaFile = metaPath != null ? new PackwizFilePath(packFolder.nioPath(), metaPath) : null;
                manifestFile.isOptional = entry.linkedFile != null ? entry.linkedFile.option.optional() : entry.optional;
                manifestFile.optionValue = previous != null ? previous.optionValue
                    : entry.linkedFile != null ? entry.linkedFile.option.defaultValue() : true;
                manifestFile.onlyOtherSide = false;
            }
            boolean disabled = actualPath != null && actualPath.toLowerCase(Locale.ROOT).endsWith(".disabled");
            manifestFile.disabled = disabled;
            manifestFile.cachedLocation = new PackwizFilePath(packFolder.nioPath(), actualPath);
            resolveInsidePack(packFolder, enabledDestPath);
            resolveInsidePack(packFolder, actualPath);
            manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), disabledPath(enabledDestPath)));
            manifest.cachedFiles.put(key, manifestFile);
        }
    }

    private void cleanupRenamedOldPath(ManifestFile manifest, PackwizFilePath packFolder, ModChange change) {
        if (!"updated".equals(change.changeType) || change.oldVersion == null || change.oldVersion.isBlank()) return;
        String oldPath = normalizeRelativePath(change.oldVersion);
        String newPath = normalizeRelativePath(change.destPath);
        if (oldPath == null || oldPath.equals(newPath)) return;
        try {
            Files.deleteIfExists(resolveInsidePack(packFolder, oldPath));
            Files.deleteIfExists(resolveInsidePack(packFolder, disabledPath(oldPath)));
        } catch (IOException e) {
            Log.warn("删除旧版本文件失败: " + oldPath + " - " + e.getMessage());
        }
        synchronized (manifest) {
            manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), oldPath));
            manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), disabledPath(oldPath)));
        }
    }

    private void addSuccessfulChange(SyncResult result, ModChange change) {
        if ("updated".equals(change.changeType)) {
            result.updated.add(change);
        } else {
            result.added.add(change);
        }
    }

    private ModChange removeTaskByDestPath(List<ModChange> tasks, String destPath) {
        String normalized = normalizeRelativePath(destPath);
        var iterator = tasks.iterator();
        while (iterator.hasNext()) {
            ModChange change = iterator.next();
            if (Objects.equals(normalizeRelativePath(change.destPath), normalized)) {
                iterator.remove();
                return change;
            }
        }
        return null;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void close() {
        clientHolder.close();
    }
}
