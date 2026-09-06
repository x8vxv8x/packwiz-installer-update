package link.infra.packwiz.installer.ui.gui;

import com.formdev.flatlaf.FlatClientProperties;
import link.infra.packwiz.installer.config.InstallerConfig;
import link.infra.packwiz.installer.exporter.CurseForgeExportBuilder;
import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ManifestFile;
import link.infra.packwiz.installer.metadata.ModFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData;
import link.infra.packwiz.installer.project.CurseForgeProjectService;
import link.infra.packwiz.installer.project.IndexRefresher;
import link.infra.packwiz.installer.project.LinkMetadataResolver;
import link.infra.packwiz.installer.project.MetadataWriter;
import link.infra.packwiz.installer.project.ModMetadataEditor;
import link.infra.packwiz.installer.project.ModLoaderVersionService;
import link.infra.packwiz.installer.project.PackInitializer;
import link.infra.packwiz.installer.project.PackRepository;
import link.infra.packwiz.installer.sync.SyncManager;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.util.AppShutdown;
import link.infra.packwiz.installer.util.Log;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class WorkbenchWindow extends JFrame {
    private InstallerConfig config;
    private Path projectRoot;
    private PackRepository repository;
    private final ModLoaderVersionService initVersionService = new ModLoaderVersionService();
    private final AtomicInteger initLoaderVersionRequest = new AtomicInteger();
    private boolean initVersionControlsUpdating;

    private final JTextField projectField = new JTextField();
    private final JLabel packSummary = new JLabel("未打开项目");
    private final JTextArea logArea = new JTextArea();

    private final AssetTableModel modsModel = new AssetTableModel();
    private final AssetTableModel resourcepacksModel = new AssetTableModel();
    private final AssetTableModel shaderpacksModel = new AssetTableModel();
    private final LocalJarTableModel localJarsModel = new LocalJarTableModel();
    private final JTabbedPane assetTabs = new JTabbedPane();
    private JTabbedPane sideTabs;
    private final JTextArea metadataEditor = new JTextArea();
    private final JLabel metadataPathLabel = new JLabel("未选择元数据");
    private Path metadataEditorPath;
    private JTable modsTable;
    private JTable resourcepacksTable;
    private JTable shaderpacksTable;
    private JTable localJarsTable;
    private final JTextField compatJarTabNameField = new JTextField();
    private final JTextField compatJarFolderField = new JTextField();
    private final JTextField modsSearchField = new JTextField();
    private final JLabel modsFilterSummary = new JLabel("显示 0 / 0");
    private TableRowSorter<AssetTableModel> modsSorter;

    private final JComboBox<String> addCategoryBox = new JComboBox<>(new String[]{"mods", "resourcepacks", "shaderpacks"});
    private final JComboBox<String> addSourceBox = new JComboBox<>(new String[]{"自动", "CurseForge", "URL"});
    private final JTextField addLinkField = new JTextField();
    private final JComboBox<String> addSideBox = new JComboBox<>(new String[]{"both", "client", "server"});
    private final JCheckBox addDependenciesBox = new JCheckBox("导入前置依赖", true);
    private final JCheckBox addOptionalBox = new JCheckBox("可选");
    private final JCheckBox addDefaultBox = new JCheckBox("默认启用", true);
    private final JTextArea resolvedPreview = new JTextArea(8, 24);

    private final JComboBox<String> syncSideBox = new JComboBox<>(new String[]{"both", "client", "server"});
    private final JComboBox<String> exportSideBox = new JComboBox<>(new String[]{"both", "client", "server"});
    private final JTextField exportProjectField = new JTextField("0");
    private final JTextField exportPathField = new JTextField();

    private final JTextField initNameField = new JTextField();
    private final JTextField initAuthorField = new JTextField();
    private final JTextField initVersionField = new JTextField("1.0.0");
    private final JComboBox<String> initMinecraftBox = new JComboBox<>(new String[]{
        "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.7.10"
    });
    private final JComboBox<String> initLoaderBox = new JComboBox<>(new String[]{"quilt", "fabric", "forge", "neoforge", "liteloader", "none"});
    private final JComboBox<String> initLoaderVersionBox = new JComboBox<>(new String[]{
        "", "47.4.0", "43.4.0", "40.3.0", "36.2.42", "14.23.5.2860", "0.16.14"
    });
    private final JCheckBox initOverwriteBox = new JCheckBox("覆盖已有 pack.toml");

    public WorkbenchWindow(InstallerConfig config, Path rootDir) {
        super("PackWorkbench - CurseForge");
        this.config = config;
        this.projectRoot = rootDir.toAbsolutePath().normalize();
        this.repository = new PackRepository(projectRoot);
        configureTextFieldColumns(projectField, 28);
        configureTextFieldColumns(addLinkField, 28);
        configureTextFieldColumns(exportPathField, 28);
        configureTextFieldColumns(compatJarFolderField, 28);
        initMinecraftBox.setEditable(true);
        initLoaderVersionBox.setEditable(true);
        AppShutdown.register(initVersionService);
        setupInitVersionControls();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        setMinimumSize(new Dimension(1120, 720));
        setSize(1280, 780);
        setLocationRelativeTo(null);
        buildUI();
        setupLogListener();
        openProject(projectRoot);
        refreshInitMinecraftVersions();
    }

    private void buildUI() {
        var root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildLogPanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JComponent buildTopBar() {
        var panel = new JPanel(new BorderLayout(8, 8));
        projectField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "项目根目录");
        JButton browse = new JButton("打开项目");
        browse.addActionListener(e -> chooseProject());
        JButton reload = new JButton("重新扫描");
        reload.addActionListener(e -> reloadProject());
        JButton refresh = new JButton("刷新 index");
        refresh.addActionListener(e -> refreshIndex());

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(browse);
        buttons.add(reload);
        buttons.add(refresh);
        panel.add(projectField, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.EAST);
        panel.add(packSummary, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildCenter() {
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildAssetsPanel(), buildSidePanel());
        split.setResizeWeight(0.68);
        split.setBorder(null);
        return split;
    }

    private JComponent buildAssetsPanel() {
        modsTable = createTable(modsModel);
        modsSorter = new TableRowSorter<>(modsModel);
        modsTable.setRowSorter(modsSorter);
        resourcepacksTable = createTable(resourcepacksModel);
        shaderpacksTable = createTable(shaderpacksModel);
        localJarsTable = createLocalJarTable();
        assetTabs.addTab("Mods (0)", buildModsPanel());
        assetTabs.addTab("Resource Packs (0)", new JScrollPane(resourcepacksTable));
        assetTabs.addTab("Shader Packs (0)", new JScrollPane(shaderpacksTable));
        assetTabs.addTab(config.getCompatJarTabName() + " (0)", buildCompatJarPanel());
        var panel = new JPanel(new BorderLayout(8, 8));
        var title = new JLabel("项目资产");
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        panel.add(title, BorderLayout.NORTH);
        panel.add(assetTabs, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildModsPanel() {
        modsSearchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "按名称搜索 Mods");
        modsSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyModsFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyModsFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyModsFilter(); }
        });

        var tools = new JPanel(new BorderLayout(8, 8));
        tools.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        tools.add(modsSearchField, BorderLayout.CENTER);
        tools.add(modsFilterSummary, BorderLayout.EAST);

        var panel = new JPanel(new BorderLayout(0, 8));
        panel.add(tools, BorderLayout.NORTH);
        panel.add(new JScrollPane(modsTable), BorderLayout.CENTER);
        return panel;
    }

    private JTable createTable(AssetTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (isMetadataEditorSelected() && !e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
                AssetRow row = model.rowAt(modelRow);
                if (row.metaPath() != null && !row.metaPath().isBlank()) {
                    loadMetadataEditor(projectRoot.resolve(row.metaPath()));
                }
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public void setValue(Object value) {
                if (value instanceof Number n) setText(n.longValue() < 0 ? "?" : LocalJarRow.humanSize(n.longValue()));
                else setText(value == null ? "" : value.toString());
            }
        });
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; showVerticalLines: false");
        for (int column : List.of(2, 3, 4, 5)) {
            table.getColumnModel().getColumn(column).setMinWidth(56);
            table.getColumnModel().getColumn(column).setPreferredWidth(column == 3 ? 90 : 64);
            table.getColumnModel().getColumn(column).setMaxWidth(column == 3 ? 120 : 76);
        }
        table.getColumnModel().getColumn(2).setCellEditor(new SideCellEditor());
        model.onSideChanged = (row, side) -> updateRowSide(row, side);
        table.setComponentPopupMenu(assetMenu(table, model));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectPopupRow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                selectPopupRow(e);
            }

            private void selectPopupRow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) table.setRowSelectionInterval(row, row);
            }
        });
        return table;
    }

    private JTable createLocalJarTable() {
        return createLocalJarTable(localJarsModel);
    }

    private JTable createLocalJarTable(LocalJarTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true; showVerticalLines: false");
        table.getColumnModel().getColumn(1).setMinWidth(56);
        table.getColumnModel().getColumn(1).setPreferredWidth(64);
        table.getColumnModel().getColumn(1).setMaxWidth(72);
        table.getColumnModel().getColumn(3).setMinWidth(72);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setMaxWidth(120);
        table.setComponentPopupMenu(localJarMenu(table, model));
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { selectPopupRow(e); }
            @Override public void mouseReleased(MouseEvent e) { selectPopupRow(e); }
            private void selectPopupRow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) table.setRowSelectionInterval(row, row);
            }
        });
        return table;
    }

    private JComponent buildCompatJarPanel() {
        compatJarTabNameField.setText(config.getCompatJarTabName());
        compatJarFolderField.setText(config.getCompatJarFolder());

        var editor = new JPanel(new GridBagLayout());
        editor.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        addCompactRow(editor, 0, "名称", compatJarTabNameField);
        addCompactRow(editor, 1, "文件夹", compatJarFolderField);

        JButton browse = new JButton("选择");
        browse.addActionListener(e -> chooseCompatJarFolder());
        JButton save = new JButton("保存");
        save.addActionListener(e -> saveCompatJarSettings());
        JButton refresh = new JButton("刷新");
        refresh.addActionListener(e -> reloadCompatJars());
        JButton detect = new JButton("转为 CF 索引");
        detect.addActionListener(e -> detectSelectedLocalJarFolder());
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(browse);
        buttons.add(save);
        buttons.add(refresh);
        buttons.add(detect);
        var gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets = new Insets(4, 4, 0, 4);
        editor.add(buttons, gbc);

        var panel = new JPanel(new BorderLayout());
        panel.add(editor, BorderLayout.NORTH);
        panel.add(new JScrollPane(localJarsTable), BorderLayout.CENTER);
        return panel;
    }

    private void addCompactRow(JPanel panel, int row, String label, JComponent component) {
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(component, gbc);
    }

    private JComponent buildSidePanel() {
        sideTabs = new JTabbedPane();
        sideTabs.addTab("编辑", buildMetadataEditorPanel());
        sideTabs.addTab("添加", buildAddPanel());
        sideTabs.addTab("同步/导出", buildExportPanel());
        sideTabs.addTab("新建 Pack", buildInitPanel());
        sideTabs.setSelectedIndex(1);
        sideTabs.setPreferredSize(new Dimension(390, 500));
        return sideTabs;
    }

    private JComponent buildMetadataEditorPanel() {
        metadataEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JButton reload = new JButton("重新加载");
        reload.addActionListener(e -> loadMetadataEditor(metadataEditorPath));
        JButton save = new JButton("保存");
        save.addActionListener(e -> saveMetadataEditor());
        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.add(metadataPathLabel, BorderLayout.CENTER);
        top.add(buttonLine(reload, save), BorderLayout.EAST);
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(metadataEditor), BorderLayout.CENTER);
        return panel;
    }

    private void loadMetadataEditor(Path path) {
        if (path == null || !Files.isRegularFile(path)) return;
        try {
            metadataEditor.setText(Files.readString(path));
            metadataEditor.setCaretPosition(0);
            metadataEditorPath = path;
            metadataPathLabel.setText(projectRoot.relativize(path).toString().replace(File.separatorChar, '/'));
        } catch (Exception e) { Log.warn("读取元数据失败: " + e.getMessage()); }
    }

    private boolean isMetadataEditorSelected() {
        return sideTabs != null && sideTabs.getSelectedIndex() == 0;
    }

    private void saveMetadataEditor() {
        if (metadataEditorPath == null) return;
        try {
            Files.writeString(metadataEditorPath, metadataEditor.getText());
            new IndexRefresher(repository).refreshAndWrite();
            reloadProject();
            Log.info("已保存元数据: " + metadataPathLabel.getText());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "保存失败:\n" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JComponent buildAddPanel() {
        var panel = formPanel();
        addRow(panel, "分类", addCategoryBox);
        addRow(panel, "类型", addSourceBox);
        addRow(panel, "链接", addLinkField);
        addRow(panel, "Side", addSideBox);
        addRow(panel, "", addDependenciesBox);
        addRow(panel, "", addOptionalBox);
        addRow(panel, "", addDefaultBox);
        resolvedPreview.setEditable(false);
        resolvedPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resolvedPreview.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "解析结果");
        addRow(panel, "预览", new JScrollPane(resolvedPreview));
        JButton parse = new JButton("解析");
        parse.addActionListener(e -> previewLink());
        JButton add = new JButton("添加");
        add.addActionListener(e -> addAsset());
        addRow(panel, "", buttonLine(parse, add));
        return new JScrollPane(panel);
    }

    private JComponent buildExportPanel() {
        var panel = formPanel();
        syncSideBox.addActionListener(e -> {
            if (config != null) {
                config.setSyncSide(String.valueOf(syncSideBox.getSelectedItem()));
                config.save(projectRoot);
            }
        });
        exportSideBox.addActionListener(e -> {
            if (config != null) {
                config.setExportSide(String.valueOf(exportSideBox.getSelectedItem()));
                config.save(projectRoot);
            }
        });
        addRow(panel, "同步端", syncSideBox);
        JButton sync = new JButton("下载同步");
        sync.addActionListener(e -> startSync());
        addRow(panel, "", sync);
        JButton checkUpdates = new JButton("批量检查更新");
        checkUpdates.addActionListener(e -> checkAllUpdates());
        addRow(panel, "", checkUpdates);
        addRow(panel, "导出端", exportSideBox);
        addRow(panel, "CF Project ID", exportProjectField);
        addRow(panel, "输出 zip", exportPathField);
        JButton choose = new JButton("选择输出");
        choose.addActionListener(e -> chooseExportPath());
        JButton export = new JButton("导出 CurseForge zip");
        export.addActionListener(e -> exportCurseForge());
        addRow(panel, "", buttonLine(choose, export));
        return new JScrollPane(panel);
    }

    private JComponent buildInitPanel() {
        var panel = formPanel();
        addRow(panel, "名称", initNameField);
        addRow(panel, "作者", initAuthorField);
        addRow(panel, "版本", initVersionField);
        addRow(panel, "MC 版本", initMinecraftBox);
        addRow(panel, "Loader", initLoaderBox);
        addRow(panel, "Loader 版本", initLoaderVersionBox);
        addRow(panel, "", initOverwriteBox);
        JButton init = new JButton("初始化当前目录");
        init.addActionListener(e -> initializePack());
        addRow(panel, "", init);
        return new JScrollPane(panel);
    }

    private void setupInitVersionControls() {
        initMinecraftBox.addActionListener(e -> refreshInitLoaderVersions());
        initLoaderBox.addActionListener(e -> refreshInitLoaderVersions());
    }

    private void refreshInitMinecraftVersions() {
        Thread thread = new Thread(() -> {
            try {
                var versions = initVersionService.fetchMinecraftVersions();
                SwingUtilities.invokeLater(() -> {
                    initVersionControlsUpdating = true;
                    try {
                        setComboItems(initMinecraftBox, versions.versions(), versions.latestRelease());
                    } finally {
                        initVersionControlsUpdating = false;
                    }
                    refreshInitLoaderVersions();
                });
            } catch (Exception e) {
                Log.warn("获取 Minecraft 版本失败，将使用内置列表: " + e.getMessage());
                SwingUtilities.invokeLater(this::refreshInitLoaderVersions);
            }
        }, "packworkbench-init-minecraft-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshInitLoaderVersions() {
        if (initVersionControlsUpdating) return;

        int requestId = initLoaderVersionRequest.incrementAndGet();
        String loader = String.valueOf(initLoaderBox.getSelectedItem()).trim().toLowerCase(Locale.ROOT);
        if (loader.isBlank() || "none".equals(loader)) {
            initVersionControlsUpdating = true;
            try {
                initLoaderVersionBox.setModel(new DefaultComboBoxModel<>(new String[]{""}));
                initLoaderVersionBox.setSelectedItem("");
                initLoaderVersionBox.setEnabled(false);
            } finally {
                initVersionControlsUpdating = false;
            }
            return;
        }

        initLoaderVersionBox.setEnabled(true);
        String minecraftVersion = String.valueOf(initMinecraftBox.getSelectedItem()).trim();
        if (minecraftVersion.isBlank()) return;

        setComboItems(initLoaderVersionBox, List.of("加载中..."), "加载中...");
        initLoaderVersionBox.setEnabled(false);

        Thread thread = new Thread(() -> {
            try {
                var versions = initVersionService.fetchLoaderVersions(loader, minecraftVersion);
                SwingUtilities.invokeLater(() -> {
                    if (requestId != initLoaderVersionRequest.get()) return;
                    initLoaderVersionBox.setEnabled(true);
                    setComboItems(initLoaderVersionBox, versions.versions(), versions.latest());
                });
            } catch (Exception e) {
                Log.warn("获取 " + loader + " 版本失败，可手动填写: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    if (requestId != initLoaderVersionRequest.get()) return;
                    initLoaderVersionBox.setEnabled(true);
                    setComboItems(initLoaderVersionBox, List.of(""), "");
                });
            }
        }, "packworkbench-init-loader-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void setComboItems(JComboBox<String> comboBox, List<String> items, String selected) {
        String current = comboBox.isEditable() && comboBox.getSelectedItem() != null
            ? String.valueOf(comboBox.getSelectedItem()).trim()
            : "";
        if ("加载中...".equals(current)) current = "";
        var model = new DefaultComboBoxModel<String>();
        for (String item : items) {
            if (item != null && !item.isBlank()) model.addElement(item);
        }
        if (model.getSize() == 0) model.addElement("");
        comboBox.setModel(model);
        if (selected != null && !selected.isBlank()) {
            comboBox.setSelectedItem(selected);
        } else if (!current.isBlank()) {
            comboBox.setSelectedItem(current);
        } else {
            comboBox.setSelectedIndex(0);
        }
    }

    private JPopupMenu assetMenu(JTable table, AssetTableModel model) {
        var menu = new JPopupMenu();
        var delete = new JMenuItem("删除索引");
        delete.addActionListener(e -> deleteSelectedIndex(table, model));
        var deleteFile = new JMenuItem("删除文件");
        deleteFile.addActionListener(e -> deleteSelectedAssetFile(table, model));
        var toggle = new JMenuItem("禁用/解禁");
        toggle.addActionListener(e -> toggleSelectedAssetFile(table, model));
        var pin = new JMenuItem("锁定更新");
        pin.addActionListener(e -> pinSelected(table, model, true));
        var unpin = new JMenuItem("解除锁定");
        unpin.addActionListener(e -> pinSelected(table, model, false));
        var check = new JMenuItem("检查更新");
        check.addActionListener(e -> checkSelectedUpdate(table, model));
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                AssetRow row = selectedRow(table, model);
                boolean curseForge = row != null && row.curseForge();
                pin.setEnabled(curseForge && !row.locked());
                unpin.setEnabled(curseForge && row.locked());
                check.setEnabled(curseForge);
                delete.setEnabled(row != null);
                deleteFile.setEnabled(row != null && row.actualFile() != null);
                toggle.setEnabled(row != null && row.type().equals("mod") && row.actualFile() != null && row.toggleTarget() != null);
                if (row != null) toggle.setText(row.disabled() ? "解禁" : "禁用");
            }

            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        menu.add(toggle);
        menu.add(deleteFile);
        menu.addSeparator();
        menu.add(delete);
        menu.addSeparator();
        menu.add(pin);
        menu.add(unpin);
        menu.addSeparator();
        menu.add(check);
        return menu;
    }

    private JPopupMenu localJarMenu(JTable table, LocalJarTableModel model) {
        var menu = new JPopupMenu();
        var delete = new JMenuItem("删除文件");
        delete.addActionListener(e -> deleteSelectedLocalJar(table, model));
        var toggle = new JMenuItem("禁用/解禁");
        toggle.addActionListener(e -> toggleSelectedLocalJar(table, model));
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                LocalJarRow row = selectedLocalJar(table, model);
                delete.setEnabled(row != null);
                toggle.setEnabled(row != null && row.toggleTarget() != null);
                if (row != null) toggle.setText(row.disabled() ? "解禁" : "禁用");
            }

            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        menu.add(delete);
        menu.add(toggle);
        return menu;
    }

    private JPanel buildLogPanel() {
        var panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("日志"));
        logArea.setRows(5);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel formPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return panel;
    }

    private void configureTextFieldColumns(JTextField field, int columns) {
        field.setColumns(columns);
    }

    private void addRow(JPanel panel, String label, Component component) {
        int row = panel.getComponentCount() / 2;
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 4);
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(component, gbc);
    }

    private JComponent buttonLine(JButton first, JButton second) {
        var panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.add(first);
        panel.add(second);
        return panel;
    }

    private void setupLogListener() {
        Log.addListener((level, message) -> SwingUtilities.invokeLater(() -> {
            logArea.append("[" + level + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));
    }

    private void chooseProject() {
        var chooser = new JFileChooser(projectRoot.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openProject(chooser.getSelectedFile().toPath());
        }
    }

    private void openProject(Path root) {
        projectRoot = root.toAbsolutePath().normalize();
        if (sideTabs != null) sideTabs.setSelectedIndex(1);
        metadataEditorPath = null;
        metadataEditor.setText("");
        metadataPathLabel.setText("未选择元数据");
        try {
            InstallerConfig.ensureConfigDir(projectRoot);
        } catch (Exception e) {
            Log.warn("初始化配置目录失败: " + e.getMessage());
        }
        config = InstallerConfig.load(projectRoot);
        repository = new PackRepository(projectRoot);
        syncSideBox.setSelectedItem(config.getSyncSide());
        exportSideBox.setSelectedItem(config.getExportSide());
        compatJarTabNameField.setText(config.getCompatJarTabName());
        compatJarFolderField.setText(config.getCompatJarFolder());
        projectField.setText(projectRoot.toString());
        reloadProject();
    }

    private void reloadProject() {
        try {
            if (!Files.exists(projectRoot.resolve("pack.toml"))) {
                packSummary.setText("未找到 pack.toml: " + projectRoot);
                setAssetRows(List.of());
                reloadCompatJars();
                return;
            }
            PackFile pack = repository.loadPack();
            IndexFile index = repository.loadIndexWithMetadata(pack);
            var rows = toRows(index);
            setAssetRows(rows);
            int projectId = repository.getCurseForgeProjectId(pack);
            exportProjectField.setText(String.valueOf(projectId));
            exportPathField.setText(projectRoot.resolve(defaultOutputName(pack)).toString());
            packSummary.setText(String.format("Pack: %s | MC: %s | Mods: %d | Resources: %d | Shaders: %d",
                pack.name != null ? pack.name : "(unnamed)",
                pack.versions.getOrDefault("minecraft", "?"),
                modsModel.getRowCount(),
                resourcepacksModel.getRowCount(),
                shaderpacksModel.getRowCount()));
            reloadCompatJars();
        } catch (Exception e) {
            packSummary.setText("加载失败: " + e.getMessage());
            Log.warn("加载项目失败: " + e.getMessage());
        }
    }

    private void setAssetRows(List<AssetRow> rows) {
        modsModel.setRows(rows.stream().filter(row -> row.type().equals("mod")).toList());
        resourcepacksModel.setRows(rows.stream().filter(row -> row.type().equals("resourcepack")).toList());
        shaderpacksModel.setRows(rows.stream().filter(row -> row.type().equals("shaderpack")).toList());
        applyModsFilter();
        assetTabs.setTitleAt(0, "Mods (" + modsModel.getRowCount() + ")");
        assetTabs.setTitleAt(1, "Resource Packs (" + resourcepacksModel.getRowCount() + ")");
        assetTabs.setTitleAt(2, "Shader Packs (" + shaderpacksModel.getRowCount() + ")");
    }

    private void reloadCompatJars() {
        try {
            localJarsModel.setRows(scanLocalJarRows(resolveCompatJarFolder()));
            if (assetTabs.getTabCount() > 3) {
                assetTabs.setTitleAt(3, config.getCompatJarTabName() + " (" + localJarsModel.getRowCount() + ")");
            }
        } catch (Exception e) {
            localJarsModel.setRows(List.of());
            Log.warn("扫描本地 jar 失败: " + e.getMessage());
        }
    }

    private List<LocalJarRow> scanLocalJarRows(Path folder) throws Exception {
        var rows = new ArrayList<LocalJarRow>();
        if (Files.isDirectory(folder)) {
            try (var stream = Files.list(folder)) {
                stream.filter(Files::isRegularFile)
                    .filter(WorkbenchWindow::isLocalModFile)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(path -> rows.add(LocalJarRow.from(projectRoot, path)));
            }
        }
        return rows;
    }

    private static boolean isLocalModFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".jar.disabled") || lower.endsWith(".disabled");
    }

    private List<AssetRow> toRows(IndexFile index) {
        var rows = new ArrayList<AssetRow>();
        for (var entry : index.files) rows.add(AssetRow.from(entry, repository.rootPath(), projectRoot));
        return rows;
    }

    private void refreshIndex() {
        runBackground("刷新 index", () -> {
            int count = new IndexRefresher(repository).refreshAndWrite();
            Log.info("index.toml 已刷新: " + count + " 个文件");
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void previewLink() {
        runBackground("解析链接", () -> {
            if (shouldAddAsUrl()) {
                var resolved = resolveLink(LinkMetadataResolver.SourcePreference.URL);
                SwingUtilities.invokeLater(() -> resolvedPreview.setText(formatResolved(resolved)));
            } else {
                var resolved = new CurseForgeProjectService(repository).resolveProjectPreview(addLinkField.getText().trim());
                SwingUtilities.invokeLater(() -> resolvedPreview.setText(formatCurseForgeProject(resolved)));
            }
        });
    }

    private void addAsset() {
        if (shouldAddAsUrl()) {
            addUrl();
        } else {
            addCurseForgeProject();
        }
    }

    private void addUrl() {
        runBackground("添加链接", () -> {
            var resolved = resolveLink(LinkMetadataResolver.SourcePreference.URL);
            var writer = new MetadataWriter(repository);
            Path meta = writer.writeUrlMetadata(
                resolved.category(),
                resolved.name(),
                resolved.filename(),
                resolved.url(),
                resolved.hashFormat(),
                resolved.hash(),
                String.valueOf(addSideBox.getSelectedItem()),
                addOptionalBox.isSelected(),
                addDefaultBox.isSelected()
            );
            Log.info("已写入: " + meta);
            new IndexRefresher(repository).refreshAndWrite();
            SwingUtilities.invokeLater(() -> {
                resolvedPreview.setText(formatResolved(resolved));
                reloadProject();
            });
        });
    }

    private void addCurseForgeProject() {
        runBackground("添加 CurseForge 项目", () -> {
            var service = new CurseForgeProjectService(repository);
            var plan = service.planProjectImport(
                addLinkField.getText().trim(),
                String.valueOf(addCategoryBox.getSelectedItem()),
                String.valueOf(addSideBox.getSelectedItem()),
                addOptionalBox.isSelected(),
                addDefaultBox.isSelected()
            );
            List<Integer> selectedDependencyIds = List.of();
            if (addDependenciesBox.isSelected() && !plan.dependencies().isEmpty()) {
                final boolean[] confirmed = {false};
                final AtomicReference<List<Integer>> selected = new AtomicReference<>(List.of());
                SwingUtilities.invokeAndWait(() -> {
                    var dialog = new DependencySelectionWindow(this, plan);
                    dialog.setVisible(true);
                    confirmed[0] = dialog.isConfirmed();
                    selected.set(dialog.selectedProjectIds());
                });
                if (!confirmed[0]) {
                    Log.info("已取消导入 CurseForge 项目");
                    return;
                }
                selectedDependencyIds = selected.get();
            }
            var files = service.importProject(plan, selectedDependencyIds);
            Log.info("已添加 CurseForge 元数据 " + files.size() + " 个");
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void applyModsFilter() {
        if (modsSorter == null) return;
        String input = modsSearchField.getText() == null ? "" : modsSearchField.getText().trim();
        if (input.isBlank()) {
            modsSorter.setRowFilter(null);
        } else {
            String normalizedQuery = normalizeSearchKey(input);
            modsSorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends AssetTableModel, ? extends Integer> entry) {
                    Object value = entry.getValue(0);
                    if (value == null) return false;
                    String normalizedName = normalizeSearchKey(value.toString());
                    return normalizedName.contains(normalizedQuery);
                }
            });
        }
        modsFilterSummary.setText("显示 " + modsTable.getRowCount() + " / " + modsModel.getRowCount());
    }

    private void updateRowSide(AssetRow row, String side) {
        if (row == null || row.metaPath() == null || row.metaPath().isBlank()) return;
        if (side.equalsIgnoreCase(row.side())) return;
        runBackground("修改 Side", () -> {
            ModMetadataEditor.setSide(projectRoot.resolve(row.metaPath()), side);
            new IndexRefresher(repository).refreshAndWrite();
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private static String normalizeSearchKey(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private void pinSelected(JTable table, AssetTableModel model, boolean pinned) {
        AssetRow row = selectedRow(table, model);
        if (row == null || !row.curseForge() || row.metaPath() == null || row.metaPath().isBlank()) return;
        runBackground(pinned ? "锁定更新" : "解除锁定", () -> {
            ModMetadataEditor.setPinned(projectRoot.resolve(row.metaPath()), pinned);
            new IndexRefresher(repository).refreshAndWrite();
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void checkSelectedUpdate(JTable table, AssetTableModel model) {
        AssetRow row = selectedRow(table, model);
        if (row == null || !row.curseForge() || row.entry() == null) return;
        runBackground("检查更新", () -> {
            var service = new CurseForgeProjectService(repository);
            var result = service.checkSingleUpdate(row.entry());
            SwingUtilities.invokeLater(() -> showUpdateWindow(
                result == null ? List.of() : List.of(result),
                service,
                "检查更新",
                false
            ));
        });
    }

    private void deleteSelectedIndex(JTable table, AssetTableModel model) {
        AssetRow row = selectedRow(table, model);
        if (row == null || row.entry() == null) return;
        boolean deleteActualFile = false;
        if (row.metaPath() != null && !row.metaPath().isBlank()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "删除 index 条目并删除元数据文件？\n" + row.metaPath(),
                "删除索引", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.OK_OPTION) return;
        } else {
            Object[] options = {"删除索引和文件", "只删除索引", "取消"};
            int choice = JOptionPane.showOptionDialog(this,
                "这是普通 file 条目。是否同时删除实际文件？\n" + row.path(),
                "删除索引", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
            deleteActualFile = choice == 0;
        }
        boolean shouldDeleteActualFile = deleteActualFile;
        runBackground("删除索引", () -> {
            PackFile pack = repository.loadPack();
            IndexFile index = repository.loadIndexWithMetadata(pack);
            String target = row.entry().file.rebase(repository.rootPath()).path();
            index.files.removeIf(entry -> target.equals(entry.file.rebase(repository.rootPath()).path()));
            if (row.metaPath() != null && !row.metaPath().isBlank()) {
                Files.deleteIfExists(projectRoot.resolve(row.metaPath()));
            } else if (shouldDeleteActualFile) {
                Path actualFile = resolveProjectRelativePath(row.entry().file.rebase(repository.rootPath()).path());
                if (!Files.isRegularFile(actualFile)) {
                    throw new IllegalStateException("不是可删除的普通文件: " + actualFile);
                }
                Files.deleteIfExists(actualFile);
            }
            repository.writeIndex(index, index.hashFormat);
            SwingUtilities.invokeLater(() -> {
                reloadProject();
                reloadCompatJars();
            });
        });
    }

    private void deleteSelectedAssetFile(JTable table, AssetTableModel model) {
        AssetRow row = selectedRow(table, model);
        if (row == null || row.actualFile() == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "删除本地文件？\n" + row.actualPath(),
            "删除文件", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;
        runBackground("删除本地文件", () -> {
            Files.deleteIfExists(row.actualFile());
            removeLocalManifestState(row.actualFile());
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void toggleSelectedAssetFile(JTable table, AssetTableModel model) {
        AssetRow row = selectedRow(table, model);
        if (row == null || row.actualFile() == null || row.toggleTarget() == null) return;
        if (Files.exists(row.toggleTarget())) {
            JOptionPane.showMessageDialog(this,
                "目标文件已存在:\n" + row.toggleTarget(),
                row.disabled() ? "解禁" : "禁用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runBackground(row.disabled() ? "解禁本地 mod" : "禁用本地 mod", () -> {
            Files.move(row.actualFile(), row.toggleTarget());
            persistLocalDisabledState(row.actualFile(), row.toggleTarget(), !row.disabled());
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void deleteSelectedLocalJar(JTable table, LocalJarTableModel model) {
        LocalJarRow row = selectedLocalJar(table, model);
        if (row == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
            "删除本地 jar 文件？\n" + row.path(),
            "删除文件", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;
        runBackground("删除本地 jar", () -> {
            Files.deleteIfExists(row.file());
            removeLocalManifestState(row.file());
            SwingUtilities.invokeLater(this::reloadCompatJars);
        });
    }

    private void toggleSelectedLocalJar(JTable table, LocalJarTableModel model) {
        LocalJarRow row = selectedLocalJar(table, model);
        if (row == null || row.toggleTarget() == null) return;
        Path source = row.file();
        Path target = row.toggleTarget();
        if (Files.exists(target)) {
            JOptionPane.showMessageDialog(this,
                "目标文件已存在:\n" + target,
                row.disabled() ? "解禁" : "禁用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runBackground(row.disabled() ? "解禁本地 mod" : "禁用本地 mod", () -> {
            Files.move(source, target);
            persistLocalDisabledState(source, target, !row.disabled());
            SwingUtilities.invokeLater(this::reloadCompatJars);
        });
    }

    private void detectSelectedLocalJarFolder() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "检测当前兼容页文件夹中的 jar，并将匹配到的文件转为 CurseForge .pw.toml？\n匹配成功后会删除原 jar 文件。",
            "检测 CurseForge 指纹", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;
        runBackground("检测 CurseForge 指纹", () -> {
            var report = new CurseForgeProjectService(repository).detectLocalJars(resolveCompatJarFolder(), true);
            SwingUtilities.invokeLater(() -> {
                reloadProject();
                reloadCompatJars();
                JOptionPane.showMessageDialog(this,
                    "扫描: " + report.scanned()
                        + "\n已转索引: " + report.matched()
                        + "\n已存在跳过: " + report.skippedInstalled()
                        + "\n未匹配: " + report.unmatched().size(),
                    "检测完成", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private void checkAllUpdates() {
        runBackground("批量检查更新", () -> {
            var service = new CurseForgeProjectService(repository);
            var results = service.checkAllUpdates();
            SwingUtilities.invokeLater(() -> showUpdateWindow(results, service, "批量更新", true));
        });
    }

    private void showUpdateWindow(List<CurseForgeProjectService.UpdateResult> results,
                                  CurseForgeProjectService service,
                                  String title,
                                  boolean batch) {
        if (results.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可检查的 CurseForge 条目。", title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean hasUpdates = results.stream().anyMatch(CurseForgeProjectService.UpdateResult::updateAvailable);
        if (!hasUpdates) {
            String message = results.size() == 1 ? results.get(0).message() : "没有可更新的 CurseForge 条目。";
            JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        var dialog = new UpdateSelectionWindow(this, title, results, service, !batch);
        dialog.setVisible(true);
        var selected = dialog.selectedUpdates();
        if (selected.isEmpty()) return;
        String taskTitle = batch ? "批量应用更新" : "应用更新";
        runBackground(taskTitle, () -> {
            new CurseForgeProjectService(repository).applyUpdates(selected);
            Log.info(taskTitle + "完成: " + selected.size() + " 个");
            SwingUtilities.invokeLater(() -> {
                reloadProject();
                JOptionPane.showMessageDialog(this,
                    "已应用更新: " + selected.size() + " 个\n请执行下载同步以获取新的文件。",
                    title, JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private AssetRow selectedRow(JTable table, AssetTableModel model) {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.rowAt(table.convertRowIndexToModel(row));
    }

    private LocalJarRow selectedLocalJar(JTable table, LocalJarTableModel model) {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.rowAt(table.convertRowIndexToModel(row));
    }

    private void persistLocalDisabledState(Path oldPath, Path newPath, boolean disabled) throws Exception {
        String oldRel = projectRelativePathOrNull(oldPath);
        String newRel = projectRelativePathOrNull(newPath);
        if (oldRel == null || newRel == null) return;

        InstallerConfig.ensureConfigDir(projectRoot);
        PackwizFilePath packFolder = new PackwizFilePath(projectRoot);
        ManifestFile manifest = ManifestFile.load(InstallerConfig.getManifestFile(projectRoot), packFolder);
        String enabledRel = disabled ? stripDisabledSuffix(newRel) : newRel;
        String disabledRel = appendDisabledSuffix(enabledRel);
        ManifestFile.File file = findLocalManifestFile(manifest, packFolder, oldRel, newRel, enabledRel, disabledRel);
        if (file == null) return;
        file.disabled = disabled;
        file.cachedLocation = new PackwizFilePath(packFolder.nioPath(), newRel);

        removeLocalManifestKeys(manifest, packFolder, oldRel, newRel, disabledRel);
        manifest.cachedFiles.put(new PackwizFilePath(packFolder.nioPath(), enabledRel), file);
        manifest.save(InstallerConfig.getManifestFile(projectRoot), packFolder);
    }

    private void removeLocalManifestState(Path filePath) throws Exception {
        String rel = projectRelativePathOrNull(filePath);
        if (rel == null) return;
        PackwizFilePath packFolder = new PackwizFilePath(projectRoot);
        Path manifestPath = InstallerConfig.getManifestFile(projectRoot);
        if (!Files.exists(manifestPath)) return;

        ManifestFile manifest = ManifestFile.load(manifestPath, packFolder);
        String enabledRel = stripDisabledSuffix(rel);
        String disabledRel = appendDisabledSuffix(enabledRel);
        removeLocalManifestKeys(manifest, packFolder, rel, enabledRel, disabledRel);
        manifest.save(manifestPath, packFolder);
    }

    private ManifestFile.File findLocalManifestFile(ManifestFile manifest, PackwizFilePath packFolder, String... relPaths) {
        for (String relPath : relPaths) {
            ManifestFile.File file = manifest.cachedFiles.get(new PackwizFilePath(packFolder.nioPath(), relPath));
            if (file != null) return file;
        }
        var candidates = new java.util.HashSet<String>();
        for (String relPath : relPaths) candidates.add(normalizeRelativePath(relPath));
        for (var entry : manifest.cachedFiles.entrySet()) {
            ManifestFile.File file = entry.getValue();
            String cached = file.cachedLocation != null ? normalizeRelativePath(file.cachedLocation.path()) : null;
            if (cached != null && candidates.contains(cached)) return file;
        }
        return null;
    }

    private void removeLocalManifestKeys(ManifestFile manifest, PackwizFilePath packFolder, String... relPaths) {
        var candidates = new java.util.HashSet<String>();
        for (String relPath : relPaths) {
            String normalized = normalizeRelativePath(relPath);
            if (normalized == null || normalized.isBlank()) continue;
            candidates.add(normalized);
            manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), normalized));
        }
        manifest.cachedFiles.entrySet().removeIf(entry -> {
            ManifestFile.File file = entry.getValue();
            String key = normalizeRelativePath(entry.getKey().path());
            String cached = file.cachedLocation != null ? normalizeRelativePath(file.cachedLocation.path()) : null;
            return candidates.contains(key) || candidates.contains(cached);
        });
    }

    private String projectRelativePathOrNull(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(projectRoot)) return null;
        return normalizeRelativePath(projectRoot.relativize(absolute).toString());
    }

    private static String normalizeRelativePath(String path) {
        if (path == null) return null;
        return path.replace(File.separatorChar, '/').replace('\\', '/');
    }

    private static String stripDisabledSuffix(String path) {
        String normalized = normalizeRelativePath(path);
        if (normalized == null) return null;
        return normalized.toLowerCase(Locale.ROOT).endsWith(".disabled")
            ? normalized.substring(0, normalized.length() - ".disabled".length())
            : normalized;
    }

    private static String appendDisabledSuffix(String path) {
        String normalized = normalizeRelativePath(path);
        if (normalized == null) return null;
        return normalized.toLowerCase(Locale.ROOT).endsWith(".disabled") ? normalized : normalized + ".disabled";
    }

    private void chooseCompatJarFolder() {
        var chooser = new JFileChooser(resolveCompatJarFolder().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (selected.startsWith(projectRoot)) {
                compatJarFolderField.setText(projectRoot.relativize(selected).toString().replace(File.separatorChar, '/'));
            } else {
                compatJarFolderField.setText(selected.toString());
            }
        }
    }

    private void saveCompatJarSettings() {
        config.setCompatJarTabName(compatJarTabNameField.getText().trim());
        config.setCompatJarFolder(compatJarFolderField.getText().trim());
        config.save(projectRoot);
        reloadCompatJars();
    }

    private Path resolveCompatJarFolder() {
        String folder = compatJarFolderField.getText().trim().isBlank()
            ? config.getCompatJarFolder()
            : compatJarFolderField.getText().trim();
        Path path = Path.of(folder);
        if (path.isAbsolute()) return path.normalize();
        return projectRoot.resolve(path).normalize();
    }

    private void initializePack() {
        String loader = String.valueOf(initLoaderBox.getSelectedItem());
        String loaderVersion = String.valueOf(initLoaderVersionBox.getSelectedItem()).trim();
        if (!"none".equalsIgnoreCase(loader) && "加载中...".equals(loaderVersion)) {
            JOptionPane.showMessageDialog(this, "Loader 版本仍在加载，请稍后再初始化。", "初始化 Pack", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        runBackground("初始化 Pack", () -> {
            PackInitializer.initialize(
                projectRoot,
                initNameField.getText().trim().isBlank() ? projectRoot.getFileName().toString() : initNameField.getText().trim(),
                initAuthorField.getText().trim(),
                initVersionField.getText().trim().isBlank() ? "1.0.0" : initVersionField.getText().trim(),
                String.valueOf(initMinecraftBox.getSelectedItem()).trim(),
                loader,
                loaderVersion,
                initOverwriteBox.isSelected()
            );
            SwingUtilities.invokeLater(() -> {
                reloadProject();
                JOptionPane.showMessageDialog(this,
                    "初始化完成:\n" + projectRoot.resolve("pack.toml"),
                    "初始化 Pack", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private LinkMetadataResolver.ResolvedLink resolveLink(LinkMetadataResolver.SourcePreference preference) throws Exception {
        String category = LinkMetadataResolver.inferCategory(addLinkField.getText(), String.valueOf(addCategoryBox.getSelectedItem()));
        addCategoryBox.setSelectedItem(category);
        return new LinkMetadataResolver().resolve(addLinkField.getText(), category, preference);
    }

    private boolean shouldAddAsUrl() {
        String preference = String.valueOf(addSourceBox.getSelectedItem());
        String input = addLinkField.getText() == null ? "" : addLinkField.getText().trim().toLowerCase(Locale.ROOT);
        if ("URL".equals(preference)) return true;
        if ("CurseForge".equals(preference)) return false;
        return input.matches("^(https?|file):.*") && !input.contains("curseforge.com");
    }

    private String formatResolved(LinkMetadataResolver.ResolvedLink resolved) {
        return "类型: " + resolved.type() + "\n"
            + "分类: " + resolved.category() + "\n"
            + "名称: " + resolved.name() + "\n"
            + "文件: " + resolved.filename() + "\n"
            + "Hash: " + resolved.hashFormat().name().toLowerCase(Locale.ROOT) + " " + resolved.hash() + "\n"
            + (resolved.type() == LinkMetadataResolver.SourceType.CURSEFORGE
                ? "CurseForge: " + resolved.curseForgeProjectId() + " / " + resolved.curseForgeFileId() + "\n"
                : "URL: " + resolved.url() + "\n");
    }

    private String formatCurseForgeProject(link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer.CurseForgeProjectFile resolved) {
        return "类型: CurseForge 项目\n"
            + "名称: " + resolved.name() + "\n"
            + "文件: " + resolved.filename() + "\n"
            + "Hash: sha1 " + resolved.sha1() + "\n"
            + "CurseForge: " + resolved.projectId() + " / " + resolved.fileId() + "\n"
            + "依赖: " + resolved.requiredDependencies().size() + "\n";
    }

    private void startSync() {
        config.setPackUrl(projectRoot.resolve("pack.toml").toString());
        config.setInstallFolder(projectRoot.toString());
        config.setSyncSide(String.valueOf(syncSideBox.getSelectedItem()));
        config.setSide(config.getSyncSide());
        config.setSyncMode(InstallerConfig.SyncMode.CONFIGURED_FILES);
        config.save(projectRoot);
        runBackground("同步检查", () -> {
            var syncManager = new SyncManager(config, projectRoot);
            try {
                var preview = syncManager.checkForChanges();
                final boolean[] confirmed = {false};
                SwingUtilities.invokeAndWait(() -> {
                    var previewWindow = new SyncPreviewWindow(this, preview);
                    previewWindow.setVisible(true);
                    confirmed[0] = previewWindow.isConfirmed();
                });
                if (confirmed[0]) {
                    var result = syncManager.executeSync(preview, (completed, status) -> Log.info(status + " (" + completed + ")"));
                    SwingUtilities.invokeLater(() -> new SyncResultWindow(this, result).setVisible(true));
                }
            } finally {
                syncManager.close();
            }
        });
    }

    private void chooseExportPath() {
        var chooser = new JFileChooser(projectRoot.toFile());
        chooser.setSelectedFile(Path.of(exportPathField.getText()).toFile());
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            exportPathField.setText(chooser.getSelectedFile().toString());
        }
    }

    private void exportCurseForge() {
        config.setExportSide(String.valueOf(exportSideBox.getSelectedItem()));
        config.save(projectRoot);
        runBackground("导出 CurseForge", () -> {
            int projectId = parseInt(exportProjectField.getText().trim(), 0);
            repository.setCurseForgeProjectId(projectId);
            var report = new CurseForgeExportBuilder(repository).export(
                String.valueOf(exportSideBox.getSelectedItem()),
                Path.of(exportPathField.getText().trim()),
                projectId
            );
            Log.info("导出完成: " + report.output() + " | CF 文件 " + report.curseForgeFiles()
                + " | overrides " + report.overrideFiles());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                "导出完成:\n" + report.output(), "CurseForge 导出", JOptionPane.INFORMATION_MESSAGE));
            SwingUtilities.invokeLater(this::reloadProject);
        });
    }

    private void runBackground(String title, ThrowingRunnable runnable) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        Thread thread = new Thread(() -> {
            try {
                Log.info(title + "...");
                runnable.run();
            } catch (Exception e) {
                Log.warn(title + "失败: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    title + "失败:\n" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> setCursor(Cursor.getDefaultCursor()));
            }
        }, "packworkbench-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void shutdown() {
        AppShutdown.unregister(initVersionService);
        try {
            initVersionService.close();
        } catch (Exception e) {
            Log.warn("关闭版本查询服务失败: " + e.getMessage());
        }
        AppShutdown.exit(0);
    }

    private String defaultOutputName(PackFile pack) {
        String name = pack.name == null || pack.name.isBlank() ? "modpack" : pack.name;
        return name.replaceAll("[\\\\/:*?\"<>|]+", "-") + ".zip";
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Path resolveProjectRelativePath(String path) {
        Path resolved = projectRoot.resolve(path.replace('/', File.separatorChar)).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalStateException("路径超出项目根目录: " + path);
        }
        return resolved;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class AssetTableModel extends AbstractTableModel {
        private final String[] columns = {"名称", "路径", "Side", "大小", "状态", "锁定"};
        private List<AssetRow> rows = List.of();
        private java.util.function.BiConsumer<AssetRow, String> onSideChanged;

        void setRows(List<AssetRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 3 ? Long.class : String.class; }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 2 && rows.get(rowIndex).metaPath() != null && !rows.get(rowIndex).metaPath().isBlank();
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            AssetRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.name();
                case 1 -> row.actualPath() == null || row.actualPath().isBlank() ? row.path() : row.actualPath();
                case 2 -> row.side();
                case 3 -> row.sizeBytes();
                case 4 -> row.status();
                case 5 -> row.curseForge() ? (row.locked() ? "锁定" : "未锁") : "-";
                default -> "";
            };
        }
        @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 2 && value != null && onSideChanged != null) onSideChanged.accept(rows.get(rowIndex), value.toString());
        }

        AssetRow rowAt(int row) {
            return rows.get(row);
        }
    }

    private static final class SideCellEditor extends DefaultCellEditor {
        SideCellEditor() {
            super(new JComboBox<>(new String[]{"both", "client", "server"}));
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected,
                                                       int row, int column) {
            Component component = super.getTableCellEditorComponent(table, value, selected, row, column);
            if (component instanceof JComboBox<?> combo) combo.setSelectedItem(value);
            return component;
        }
    }

    private static class LocalJarTableModel extends AbstractTableModel {
        private final String[] columns = {"名称", "状态", "路径", "大小"};
        private List<LocalJarRow> rows = List.of();

        void setRows(List<LocalJarRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            LocalJarRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.name();
                case 1 -> row.disabled() ? "禁用" : "启用";
                case 2 -> row.path();
                case 3 -> row.size();
                default -> "";
            };
        }

        LocalJarRow rowAt(int row) {
            return rows.get(row);
        }
    }

    private static class DependencySelectionWindow extends JDialog {
        private final DependencySelectionTableModel model;
        private boolean confirmed;

        DependencySelectionWindow(Frame owner, CurseForgeProjectService.ImportPlan plan) {
            super(owner, "选择前置依赖", true);
            this.model = new DependencySelectionTableModel(plan.dependencies());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(760, 460);
            setLocationRelativeTo(owner);
            setContentPane(buildContent(plan));
        }

        boolean isConfirmed() {
            return confirmed;
        }

        List<Integer> selectedProjectIds() {
            return model.selectedProjectIds();
        }

        private JComponent buildContent(CurseForgeProjectService.ImportPlan plan) {
            var root = new JPanel(new BorderLayout(12, 12));
            root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            long installedCount = plan.dependencies().stream().filter(CurseForgeProjectService.ImportEntry::alreadyInstalled).count();

            var summary = new JTextArea();
            summary.setEditable(false);
            summary.setOpaque(false);
            summary.setLineWrap(true);
            summary.setWrapStyleWord(true);
            summary.setText("主项目: " + plan.main().name()
                + "\n检测到前置依赖 " + plan.dependencies().size() + " 个，其中已存在 " + installedCount + " 个。"
                + "\n已存在条目会标记为“已存在，无需导入”，不会重复写入。");
            root.add(summary, BorderLayout.NORTH);

            JTable table = new JTable(model);
            table.setRowHeight(28);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(0).setMinWidth(56);
            table.getColumnModel().getColumn(0).setMaxWidth(72);
            table.getColumnModel().getColumn(2).setMinWidth(90);
            table.getColumnModel().getColumn(2).setMaxWidth(110);
            table.getColumnModel().getColumn(3).setMinWidth(100);
            table.getColumnModel().getColumn(3).setMaxWidth(150);
            table.getColumnModel().getColumn(4).setMinWidth(70);
            table.getColumnModel().getColumn(4).setMaxWidth(90);
            table.getColumnModel().getColumn(5).setMinWidth(90);
            table.getColumnModel().getColumn(5).setMaxWidth(110);
            root.add(new JScrollPane(table), BorderLayout.CENTER);

            JButton selectAll = new JButton("全选");
            selectAll.addActionListener(e -> model.setAllSelected(true));
            JButton clear = new JButton("清空");
            clear.addActionListener(e -> model.setAllSelected(false));
            JButton cancel = new JButton("取消");
            cancel.addActionListener(e -> dispose());
            JButton confirm = new JButton("确认导入");
            confirm.addActionListener(e -> {
                confirmed = true;
                dispose();
            });

            var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.add(selectAll);
            left.add(clear);
            var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.add(cancel);
            right.add(confirm);
            var buttons = new JPanel(new BorderLayout());
            buttons.add(left, BorderLayout.WEST);
            buttons.add(right, BorderLayout.EAST);
            root.add(buttons, BorderLayout.SOUTH);
            return root;
        }
    }

    private static class DependencySelectionTableModel extends AbstractTableModel {
        private final String[] columns = {"导入", "名称", "分类", "状态", "Side", "Project ID", "文件名"};
        private final List<DependencySelectionRow> rows;

        DependencySelectionTableModel(List<CurseForgeProjectService.ImportEntry> entries) {
            this.rows = new ArrayList<>();
            for (var entry : entries) {
                rows.add(new DependencySelectionRow(!entry.alreadyInstalled(), entry));
            }
        }

        void setAllSelected(boolean selected) {
            for (var row : rows) row.selected = selected;
            fireTableDataChanged();
        }

        List<Integer> selectedProjectIds() {
            return rows.stream()
                .filter(row -> row.selected)
                .map(row -> row.entry.projectId())
                .toList();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 && !rows.get(rowIndex).entry.alreadyInstalled();
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            DependencySelectionRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.selected;
                case 1 -> row.entry.name();
                case 2 -> row.entry.category();
                case 3 -> row.entry.alreadyInstalled() ? "已存在，无需导入" : "待导入";
                case 4 -> row.entry.side();
                case 5 -> String.valueOf(row.entry.projectId());
                case 6 -> row.entry.file().filename();
                default -> "";
            };
        }
        @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rows.get(rowIndex).entry.alreadyInstalled()) return;
            rows.get(rowIndex).selected = Boolean.TRUE.equals(aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        private static class DependencySelectionRow {
            private boolean selected;
            private final CurseForgeProjectService.ImportEntry entry;

            private DependencySelectionRow(boolean selected, CurseForgeProjectService.ImportEntry entry) {
                this.selected = selected;
                this.entry = entry;
            }
        }
    }

    private record LocalJarRow(String name, String path, String size, Path file, boolean disabled, Path toggleTarget) {
        static LocalJarRow from(Path projectRoot, Path file) {
            Path absolute = file.toAbsolutePath().normalize();
            String displayPath = absolute.startsWith(projectRoot)
                ? projectRoot.relativize(absolute).toString().replace(File.separatorChar, '/')
                : absolute.toString();
            String fileName = file.getFileName().toString();
            boolean disabled = fileName.toLowerCase(Locale.ROOT).endsWith(".disabled");
            String targetName = disabled
                ? fileName.substring(0, fileName.length() - ".disabled".length())
                : fileName + ".disabled";
            Path toggleTarget = absolute.resolveSibling(targetName).toAbsolutePath().normalize();
            String size;
            try {
                size = humanSize(Files.size(absolute));
            } catch (Exception e) {
                size = "?";
            }
            return new LocalJarRow(fileName, displayPath, size, absolute, disabled, toggleTarget);
        }

        static String humanSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            double kib = bytes / 1024.0;
            if (kib < 1024) return String.format(Locale.ROOT, "%.1f KB", kib);
            double mib = kib / 1024.0;
            if (mib < 1024) return String.format(Locale.ROOT, "%.1f MB", mib);
            return String.format(Locale.ROOT, "%.1f GB", mib / 1024.0);
        }
    }

    private record AssetRow(String type, String name, String path, String side, boolean locked,
                            String metaPath, IndexFile.FileEntry entry, boolean curseForge,
                            String status, String size, long sizeBytes, String actualPath, Path actualFile,
                            boolean disabled, Path toggleTarget) {
        static AssetRow from(IndexFile.FileEntry entry, link.infra.packwiz.installer.target.path.PackwizFilePath root,
                             Path projectRoot) {
            String path = entry.getDestURI().rebase(root).path();
            String metaPath = entry.metafile ? entry.file.rebase(root).path() : "";
            ModFile mod = entry.linkedFile;
            boolean locked = mod != null && mod.pin;
            boolean curseForge = mod != null && mod.update.get("curseforge") instanceof CurseForgeUpdateData;
            String side = mod != null && mod.side != null ? mod.side.name().toLowerCase(Locale.ROOT) : "both";
            LocalState local = LocalState.from(projectRoot, path);
            return new AssetRow(typeFor(path), entry.getName(), path, side, locked, metaPath, entry, curseForge,
                local.status(), local.size(), local.sizeBytes(), local.actualPath(), local.actualFile(), local.disabled(), local.toggleTarget());
        }

        private static String typeFor(String path) {
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (lower.startsWith("resourcepacks/")) return "resourcepack";
            if (lower.startsWith("shaderpacks/")) return "shaderpack";
            return "mod";
        }
    }

    private record LocalState(String status, String size, long sizeBytes, String actualPath, Path actualFile,
                              boolean disabled, Path toggleTarget) {
        static LocalState from(Path projectRoot, String relPath) {
            String normalized = normalizeRelativePath(relPath);
            if (normalized == null || normalized.isBlank()) {
                return new LocalState("缺失", "", -1L, "", null, false, null);
            }
            Path enabled = projectRoot.resolve(normalized.replace('/', File.separatorChar)).toAbsolutePath().normalize();
            Path disabledPath = projectRoot.resolve(appendDisabledSuffix(normalized).replace('/', File.separatorChar)).toAbsolutePath().normalize();
            Path actual = null;
            boolean disabled = false;
            if (Files.isRegularFile(enabled)) {
                actual = enabled;
            } else if (Files.isRegularFile(disabledPath)) {
                actual = disabledPath;
                disabled = true;
            }
            if (actual == null) {
                return new LocalState("缺失", "", -1L, "", null, false, null);
            }
            Path toggleTarget = null;
            if (isDirectModsJarPath(normalized)) {
                toggleTarget = disabled ? enabled : disabledPath;
            }
            String actualPath = actual.startsWith(projectRoot)
                ? projectRoot.relativize(actual).toString().replace(File.separatorChar, '/')
                : actual.toString();
            long sizeBytes;
            try { sizeBytes = Files.size(actual); } catch (Exception e) { sizeBytes = -1L; }
            return new LocalState(disabled ? "禁用" : "启用", humanSize(actual), sizeBytes, actualPath, actual, disabled, toggleTarget);
        }

        private static String humanSize(Path path) {
            try {
                return LocalJarRow.humanSize(Files.size(path));
            } catch (Exception e) {
                return "?";
            }
        }

        private static boolean isDirectModsJarPath(String path) {
            String normalized = normalizeRelativePath(path);
            if (normalized == null || !normalized.startsWith("mods/")) return false;
            String rest = normalized.substring("mods/".length());
            return !rest.isEmpty() && !rest.contains("/") && rest.toLowerCase(Locale.ROOT).endsWith(".jar");
        }
    }
}
