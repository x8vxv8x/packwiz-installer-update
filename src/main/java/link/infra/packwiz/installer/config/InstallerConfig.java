package link.infra.packwiz.installer.config;

import com.moandjiezana.toml.Toml;
import link.infra.packwiz.installer.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 配置文件 .packwiz-installer/config.toml 的读写和管理。
 */
public class InstallerConfig {
    public static final String CONFIG_DIR_NAME = ".packwiz-installer";
    public static final String CONFIG_FILE_NAME = "config.toml";
    public static final String MANIFEST_FILE_NAME = "packwiz.json";
    public static final String OVERRIDES_IGNORE_FILE_NAME = "overrides.ignore";

    public enum SyncMode {
        MODS_ONLY("mods-only"),
        CONFIGURED_FILES("configured-files");

        private final String configValue;

        SyncMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static SyncMode fromConfigValue(String value) {
            if (value == null || value.isBlank()) return MODS_ONLY;
            return switch (value.trim().toLowerCase()) {
                case "mods-only", "mods", "mods_only" -> MODS_ONLY;
                case "configured-files", "configured", "all", "configured_files" -> CONFIGURED_FILES;
                default -> {
                    Log.warn("未知同步模式，使用默认值 mods-only: " + value);
                    yield MODS_ONLY;
                }
            };
        }

        public static SyncMode fromLegacyFolders(List<String> folders) {
            if (folders == null || folders.isEmpty()) return MODS_ONLY;
            boolean onlyMods = folders.size() == 1 && "mods".equalsIgnoreCase(folders.get(0));
            return onlyMods ? MODS_ONLY : CONFIGURED_FILES;
        }
    }

    private String packUrl = "";
    private String side = "both";
    private String syncSide = "both";
    private String exportSide = "both";
    private String installFolder = ".";
    private String metaFile = MANIFEST_FILE_NAME;
    private String multimcFolder = "";
    private long timeout = 10;
    private String title = "";
    private SyncMode syncMode = SyncMode.MODS_ONLY;
    private List<String> syncFolders = new ArrayList<>(Arrays.asList("mods"));
    private String compatJarTabName = "本地 Jars";
    private String compatJarFolder = "mods";

    // ===== 路径 =====

    /** 获取配置目录路径 (.packwiz-installer/) */
    public static Path getConfigDir(Path rootDir) {
        return rootDir.resolve(CONFIG_DIR_NAME).toAbsolutePath();
    }

    /** 获取配置文件路径 (.packwiz-installer/config.toml) */
    public static Path getConfigFile(Path rootDir) {
        return getConfigDir(rootDir).resolve(CONFIG_FILE_NAME);
    }

    /** 获取清单文件路径 (.packwiz-installer/packwiz.json) */
    public static Path getManifestFile(Path rootDir) {
        return getConfigDir(rootDir).resolve(MANIFEST_FILE_NAME);
    }

    public static Path getOverridesIgnoreFile(Path rootDir) {
        return getConfigDir(rootDir).resolve(OVERRIDES_IGNORE_FILE_NAME);
    }

    /** 确保配置目录存在 */
    public static void ensureConfigDir(Path rootDir) throws IOException {
        Files.createDirectories(getConfigDir(rootDir));
        Path ignore = getOverridesIgnoreFile(rootDir);
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, defaultOverridesIgnore(), StandardCharsets.UTF_8);
        }
    }

    private static String defaultOverridesIgnore() {
        return """
            # PackWorkbench CurseForge overrides ignore
            # Syntax is similar to .gitignore. One pattern per line; # starts a comment.
            # These rules apply only when building overrides/ for CurseForge export.

            .git/
            .gradle/
            .idea/
            build/
            gradle/
            src/
            .packwiz-installer/

            pack.toml
            index.toml
            PLAN.md
            README.md
            LICENSE
            *.zip

            mods/.index/
            resourcepacks/.index/
            shaderpacks/.index/
            mods/*.jar
            """;
    }

    // ===== 加载和保存 =====

    public static InstallerConfig load(Path rootDir) {
        Path configFile = getConfigFile(rootDir);
        if (!Files.exists(configFile)) {
            return new InstallerConfig();
        }
        try {
            Toml toml = new Toml().read(configFile.toFile());
            InstallerConfig config = new InstallerConfig();

            Toml pack = toml.getTable("pack");
            if (pack != null) {
                config.packUrl = pack.getString("url", "");
                config.side = pack.getString("side", "both");
            }

            Toml install = toml.getTable("install");
            if (install != null) {
                config.installFolder = install.getString("folder", ".");
                config.metaFile = install.getString("meta-file", MANIFEST_FILE_NAME);
                config.migrateModsFolderInstallRoot();
            }

            Toml multimc = toml.getTable("multimc");
            if (multimc != null) {
                config.multimcFolder = multimc.getString("folder", "");
            }

            Toml options = toml.getTable("options");
            if (options != null) {
                config.timeout = options.getLong("timeout", 10L);
                config.title = options.getString("title", "");
            }

            Toml sync = toml.getTable("sync");
            if (sync != null) {
                String mode = sync.getString("mode");
                if (mode != null) {
                    config.syncMode = SyncMode.fromConfigValue(mode);
                    config.syncFolders = foldersForMode(config.syncMode);
                } else {
                    List<String> folders = sync.getList("folders");
                    if (folders != null && !folders.isEmpty()) {
                        config.syncFolders = new ArrayList<>(folders);
                        config.syncMode = SyncMode.fromLegacyFolders(folders);
                    }
                }
            }

            Toml workbench = toml.getTable("workbench");
            if (workbench != null) {
                config.syncSide = workbench.getString("sync-side", "both");
                config.exportSide = workbench.getString("export-side", "both");
                Toml compatJars = workbench.getTable("compat-jars");
                if (compatJars != null) {
                    config.compatJarTabName = compatJars.getString("name", "本地 Jars");
                    config.compatJarFolder = compatJars.getString("folder", "mods");
                }
            }

            return config;
        } catch (Exception e) {
            Log.warn("读取配置文件失败，使用默认值: " + e.getMessage());
            return new InstallerConfig();
        }
    }

    public void save(Path rootDir) {
        Path configFile = getConfigFile(rootDir);
        StringBuilder sb = new StringBuilder();
        sb.append("# packwiz-installer 配置文件\n\n");

        sb.append("[pack]\n");
        sb.append("url = \"").append(escapeToml(packUrl)).append("\"\n");
        sb.append("side = \"").append(escapeToml(side)).append("\"\n\n");

        sb.append("[install]\n");
        sb.append("folder = \"").append(escapeToml(installFolder)).append("\"\n");
        sb.append("meta-file = \"").append(escapeToml(metaFile)).append("\"\n\n");

        sb.append("[multimc]\n");
        sb.append("folder = \"").append(escapeToml(multimcFolder)).append("\"\n\n");

        sb.append("[options]\n");
        sb.append("timeout = ").append(timeout).append("\n");
        sb.append("title = \"").append(escapeToml(title)).append("\"\n\n");

        sb.append("[sync]\n");
        sb.append("mode = \"").append(syncMode.configValue()).append("\"\n");
        sb.append("[workbench]\n");
        sb.append("sync-side = \"").append(escapeToml(syncSide)).append("\"\n");
        sb.append("export-side = \"").append(escapeToml(exportSide)).append("\"\n\n");
        sb.append("[workbench.compat-jars]\n");
        sb.append("name = \"").append(escapeToml(compatJarTabName)).append("\"\n");
        sb.append("folder = \"").append(escapeToml(compatJarFolder)).append("\"\n");

        try {
            Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("保存配置文件失败: " + e.getMessage());
        }
    }

    // ===== 自动检测 =====

    public static List<Path> scanForPackToml(Path dir) {
        Log.info("扫描 pack.toml，目录: " + dir);
        List<Path> results = new ArrayList<>();
        Path packToml = dir.resolve("pack.toml");
        if (Files.exists(packToml) && isPackToml(packToml)) {
            Log.info("找到 pack.toml: " + packToml.toAbsolutePath());
            results.add(packToml.toAbsolutePath());
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                .filter(sub -> !sub.getFileName().toString().startsWith("."))
                .forEach(sub -> {
                    Path subPack = sub.resolve("pack.toml");
                    if (Files.exists(subPack) && isPackToml(subPack)) {
                        Log.info("找到 pack.toml: " + subPack.toAbsolutePath());
                        results.add(subPack.toAbsolutePath());
                    }
                });
        } catch (IOException e) {
            Log.warn("扫描目录失败: " + e.getMessage());
        }
        if (results.isEmpty()) Log.info("未找到 pack.toml");
        return results;
    }

    public static List<Path> scanForModsFolder(Path dir) {
        Log.info("扫描 mods 目录，目录: " + dir);
        List<Path> results = new ArrayList<>();
        Path modsHere = dir.resolve("mods");
        if (Files.isDirectory(modsHere) && isNonEmptyDir(modsHere)) {
            results.add(modsHere.toAbsolutePath());
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                .filter(sub -> !sub.getFileName().toString().startsWith("."))
                .forEach(sub -> {
                    Path modsInSub = sub.resolve("mods");
                    if (Files.isDirectory(modsInSub) && isNonEmptyDir(modsInSub)) {
                        results.add(modsInSub.toAbsolutePath());
                    }
                    Path dotMc = sub.resolve(".minecraft");
                    if (Files.isDirectory(dotMc)) {
                        Path modsInDotMc = dotMc.resolve("mods");
                        if (Files.isDirectory(modsInDotMc) && isNonEmptyDir(modsInDotMc)) {
                            results.add(modsInDotMc.toAbsolutePath());
                        }
                    }
                });
        } catch (IOException e) {
            Log.warn("扫描目录失败: " + e.getMessage());
        }
        if (results.isEmpty()) Log.info("未找到 mods 目录");
        return results;
    }

    private static boolean isPackToml(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.contains("name") && content.contains("pack-format");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isNonEmptyDir(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    // ===== 路径解析 =====

    public Path resolveInstallFolder(Path rootDir) {
        Path p = Path.of(installFolder);
        if (p.isAbsolute()) return p;
        return rootDir.resolve(p).toAbsolutePath();
    }

    public Path resolveMultimcFolder(Path rootDir) {
        if (multimcFolder.isEmpty()) return null;
        Path p = Path.of(multimcFolder);
        if (p.isAbsolute()) return p;
        return rootDir.resolve(p).toAbsolutePath();
    }

    public Path resolveManifestFile(Path rootDir) {
        return resolveInstallFolder(rootDir).resolve(metaFile);
    }

    private void migrateModsFolderInstallRoot() {
        if (installFolder == null || installFolder.isBlank()) return;
        Path path = Path.of(installFolder);
        Path fileName = path.getFileName();
        if (fileName != null && "mods".equalsIgnoreCase(fileName.toString()) && path.getParent() != null) {
            installFolder = path.getParent().toString();
            Log.info("检测到安装目录指向 mods 文件夹，已迁移为整合包根目录: " + installFolder);
        }
    }

    // ===== Getter/Setter =====

    public String getPackUrl() { return packUrl; }
    public void setPackUrl(String packUrl) { this.packUrl = packUrl; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getSyncSide() { return syncSide; }
    public void setSyncSide(String side) { this.syncSide = normalizeSide(side); }
    public String getExportSide() { return exportSide; }
    public void setExportSide(String side) { this.exportSide = normalizeSide(side); }

    public String getInstallFolder() { return installFolder; }
    public void setInstallFolder(String installFolder) { this.installFolder = installFolder; }

    public String getMetaFile() { return metaFile; }
    public void setMetaFile(String metaFile) { this.metaFile = metaFile; }

    public String getMultimcFolder() { return multimcFolder; }
    public void setMultimcFolder(String multimcFolder) { this.multimcFolder = multimcFolder; }

    public long getTimeout() { return timeout; }
    public void setTimeout(long timeout) { this.timeout = timeout; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<String> getSyncFolders() { return syncFolders; }
    public void setSyncFolders(List<String> syncFolders) {
        this.syncFolders = syncFolders != null ? new ArrayList<>(syncFolders) : new ArrayList<>();
        this.syncMode = SyncMode.fromLegacyFolders(this.syncFolders);
    }

    public SyncMode getSyncMode() { return syncMode; }
    public void setSyncMode(SyncMode syncMode) {
        this.syncMode = syncMode != null ? syncMode : SyncMode.MODS_ONLY;
        this.syncFolders = foldersForMode(this.syncMode);
    }

    public String getCompatJarTabName() { return compatJarTabName; }
    public void setCompatJarTabName(String compatJarTabName) {
        this.compatJarTabName = compatJarTabName == null || compatJarTabName.isBlank() ? "本地 Jars" : compatJarTabName;
    }

    public String getCompatJarFolder() { return compatJarFolder; }
    public void setCompatJarFolder(String compatJarFolder) {
        this.compatJarFolder = compatJarFolder == null || compatJarFolder.isBlank() ? "mods" : compatJarFolder;
    }

    private static List<String> foldersForMode(SyncMode mode) {
        return switch (mode != null ? mode : SyncMode.MODS_ONLY) {
            case MODS_ONLY -> new ArrayList<>(List.of("mods"));
            case CONFIGURED_FILES -> new ArrayList<>(List.of("*"));
        };
    }

    private static String escapeToml(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeSide(String side) {
        if (side == null) return "both";
        String value = side.toLowerCase();
        return value.equals("client") || value.equals("server") || value.equals("both") ? value : "both";
    }
}
