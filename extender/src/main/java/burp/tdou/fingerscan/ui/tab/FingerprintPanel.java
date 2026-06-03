/**
 * 指纹管理面板
 * 提供图形化界面编辑指纹规则（基于YAML配置）
 * 替换原有的FingerprintTab，支持RouteVulScanPro1的YAML格式
 * 
 * @author OneScan Team
 * @version 2.0
 */
package burp.tdou.fingerscan.ui.tab;

import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.core.YamlConfigManager;
import burp.tdou.fingerscan.ui.widget.FilterRuleDialog;
import burp.tdou.fingerscan.ui.widget.FingerprintRuleDialog;
import burp.tdou.fingerscan.ui.widget.IconHashRuleDialog;
import burp.tdou.fingerscan.ui.widget.TestRuleDialog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

public class FingerprintPanel extends JPanel implements ActionListener, KeyListener {
    
    private YamlConfigManager configManager;
    private Runnable onReloadCallback;

    // UI组件
    private JTable fingerprintTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> tableSorter;
    private JTextField searchField;
    private JLabel countLabel;
    private JTextField configPathField;
    private JPanel groupTabBar;
    private ButtonGroup groupButtonGroup;
    private String selectedGroupName;

    // 当前过滤状态
    private RowFilter<DefaultTableModel, Object> groupFilter;
    private RowFilter<DefaultTableModel, Object> searchFilter;

    // Icon Hash UI组件
    private JTable iconHashTable;
    private DefaultTableModel iconHashTableModel;
    private TableRowSorter<DefaultTableModel> iconHashTableSorter;
    private JTextField iconHashSearchField;
    private JLabel iconHashCountLabel;

    // 过滤规则 UI组件
    private JTable filterTable;
    private DefaultTableModel filterTableModel;
    private TableRowSorter<DefaultTableModel> filterTableSorter;
    private JLabel filterCountLabel;
    
    // 表格列名
    private static final String[] COLUMN_NAMES = {
        "ID", "名称", "启用", "方法", "URL路径", "正则表达式", "类型", "状态码", "描述"
    };

    private static final String[] ICON_HASH_COLUMN_NAMES = {
        "名称", "MurmurHash3", "MD5", "类型", "描述"
    };

    private static final String[] FILTER_COLUMN_NAMES = {
        "名称", "正则表达式", "启用"
    };
    
    /**
     * 构造函数
     * @param configManager YAML配置管理器
     * @param scanner 指纹扫描器
     */
    public FingerprintPanel(YamlConfigManager configManager) {
        this.configManager = configManager;
        initializeUI();
        loadFingerprintRules();
        loadIconHashRules();
        loadFilterRules();
    }

    public void setOnReloadCallback(Runnable callback) {
        this.onReloadCallback = callback;
    }

    /**
     * 初始化UI界面
     */
    private void initializeUI() {
        setLayout(new BorderLayout());

        // 创建顶部面板（配置路径和统计信息）
        add(createTopPanel(), BorderLayout.NORTH);

        // 创建 Tab 切换面板
        JTabbedPane tabbedPane = new JTabbedPane();

        // Tab 1: 正则规则
        JPanel regexPanel = new JPanel(new BorderLayout());
        groupTabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        groupButtonGroup = new ButtonGroup();
        JScrollPane groupTabScroll = new JScrollPane(groupTabBar,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        groupTabScroll.setBorder(BorderFactory.createEmptyBorder());
        groupTabScroll.setPreferredSize(new Dimension(0, 32));
        regexPanel.add(groupTabScroll, BorderLayout.NORTH);
        regexPanel.add(createCenterPanel(), BorderLayout.CENTER);
        regexPanel.add(createBottomPanel(), BorderLayout.SOUTH);
        tabbedPane.addTab("正则规则", regexPanel);

        // Tab 2: Icon Hash 规则
        tabbedPane.addTab("Icon Hash 规则", createIconHashPanel());

        // Tab 3: 过滤规则
        tabbedPane.addTab("过滤规则", createFilterPanel());

        add(tabbedPane, BorderLayout.CENTER);
    }
    
    /**
     * 创建顶部面板
     */
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));
        
        // 配置文件路径面板
        JPanel pathPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pathPanel.add(new JLabel("配置文件路径:"));
        
        configPathField = new JTextField(configManager.getConfigFilePath(), 30);
        configPathField.setEditable(false);
        pathPanel.add(configPathField);
        
        JButton browseButton = new JButton("浏览");
        browseButton.setActionCommand("browse-config");
        browseButton.addActionListener(this);
        pathPanel.add(browseButton);
        
        JButton reloadButton = new JButton("重新加载");
        reloadButton.setActionCommand("reload-config");
        reloadButton.addActionListener(this);
        pathPanel.add(reloadButton);
        
        panel.add(pathPanel, BorderLayout.WEST);
        
        // 统计信息面板
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statsPanel.add(new JLabel("指纹规则数量:"));
        countLabel = new JLabel("0");
        statsPanel.add(countLabel);
        
        panel.add(statsPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * 创建中间面板
     */
    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // 创建表格
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                // 只有"启用"列可以直接编辑
                return column == 2;
            }
            
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2) { // 启用列
                    return Boolean.class;
                }
                return String.class;
            }
        };
        
        fingerprintTable = new JTable(tableModel);
        tableSorter = new TableRowSorter<>(tableModel);
        fingerprintTable.setRowSorter(tableSorter);
        
        // 设置表格属性
        fingerprintTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fingerprintTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        // 设置列宽
        setColumnWidths();
        
        // 添加表格监听器
        fingerprintTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == 2) { // 启用列变化
                int row = e.getFirstRow();
                updateRuleEnabledStatus(row);
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(fingerprintTable);
        scrollPane.setPreferredSize(new Dimension(800, 400));
        
        // 创建操作按钮面板
        JPanel buttonPanel = createButtonPanel();
        
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * 创建操作按钮面板
     */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(new EmptyBorder(5, 0, 0, 0));
        
        // 添加按钮
        addButton(panel, "添加规则", "add-rule");
        addButton(panel, "编辑规则", "edit-rule");
        addButton(panel, "删除规则", "delete-rule");
        addButton(panel, "复制规则", "copy-rule");
        
        panel.add(new JSeparator(SwingConstants.VERTICAL));
        
        addButton(panel, "导入配置", "import-config");
        addButton(panel, "导出配置", "export-config");
        
        panel.add(new JSeparator(SwingConstants.VERTICAL));
        
        addButton(panel, "测试规则", "test-rule");
        addButton(panel, "批量启用", "batch-enable");
        addButton(panel, "批量禁用", "batch-disable");
        
        return panel;
    }
    
    /**
     * 添加按钮到面板
     */
    private void addButton(JPanel panel, String text, String actionCommand) {
        JButton button = new JButton(text);
        button.setActionCommand(actionCommand);
        button.addActionListener(this);
        panel.add(button);
    }
    
    /**
     * 创建底部面板
     */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("搜索和过滤"));
        
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchPanel.add(new JLabel("搜索:"));
        
        searchField = new JTextField(20);
        searchField.addKeyListener(this);
        searchPanel.add(searchField);
        
        JButton searchButton = new JButton("搜索");
        searchButton.setActionCommand("search");
        searchButton.addActionListener(this);
        searchPanel.add(searchButton);
        
        JButton clearButton = new JButton("清除");
        clearButton.setActionCommand("clear-search");
        clearButton.addActionListener(this);
        searchPanel.add(clearButton);
        
        panel.add(searchPanel, BorderLayout.WEST);
        
        return panel;
    }
    
    /**
     * 设置表格列宽
     */
    private void setColumnWidths() {
        int[] columnWidths = {50, 150, 60, 80, 200, 250, 80, 80, 200};
        for (int i = 0; i < columnWidths.length && i < fingerprintTable.getColumnCount(); i++) {
            fingerprintTable.getColumnModel().getColumn(i).setPreferredWidth(columnWidths[i]);
        }
    }
    
    /**
     * 加载指纹规则到表格
     */
    public void loadFingerprintRules() {
        tableModel.setRowCount(0);

        List<Map<String, Object>> rules = configManager.getFingerprintRules();

        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            Object[] rowData = new Object[COLUMN_NAMES.length];
            rowData[0] = String.valueOf(i + 1);
            rowData[1] = str(rule.get("name"));
            rowData[2] = rule.get("loaded");
            rowData[3] = str(rule.get("method"));
            rowData[4] = str(rule.get("url"));
            rowData[5] = str(rule.get("re"));
            rowData[6] = str(rule.get("type"));
            rowData[7] = str(rule.get("state"));
            rowData[8] = rule.get("info");

            tableModel.addRow(rowData);
        }

        updateCountLabel();
        buildGroupTabs();
    }
    
    /**
     * 构建分组选项卡
     */
    private void buildGroupTabs() {
        groupTabBar.removeAll();
        groupButtonGroup = new ButtonGroup();

        // 统计每个类型的数量
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        int total = tableModel.getRowCount();
        for (int i = 0; i < total; i++) {
            String type = str(tableModel.getValueAt(i, 6));
            if (type.isEmpty()) type = "未分类";
            typeCounts.merge(type, 1, Integer::sum);
        }

        // "全部" 按钮
        addGroupButton(null, "全部 (" + total + ")");

        // 各分组按钮
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            addGroupButton(entry.getKey(), entry.getKey() + " (" + entry.getValue() + ")");
        }

        groupTabBar.revalidate();
        groupTabBar.repaint();
        applyGroupFilter();
    }

    private void addGroupButton(String groupName, String label) {
        JToggleButton btn = new JToggleButton(label);
        btn.setFocusPainted(false);
        btn.putClientProperty("groupName", groupName);
        btn.addActionListener(e -> {
            selectedGroupName = groupName;
            applyGroupFilter();
        });
        if (groupName == null && selectedGroupName == null
                || groupName != null && groupName.equals(selectedGroupName)) {
            btn.setSelected(true);
        }
        groupButtonGroup.add(btn);
        groupTabBar.add(btn);
    }

    /**
     * 根据选中的分组 tab 设置过滤器
     */
    private void applyGroupFilter() {
        if (selectedGroupName == null) {
            groupFilter = null;
        } else {
            String matchValue = "未分类".equals(selectedGroupName) ? "" : selectedGroupName;
            groupFilter = new RowFilter<DefaultTableModel, Object>() {
                @Override
                public boolean include(Entry<? extends DefaultTableModel, ? extends Object> entry) {
                    String type = str(entry.getValue(6));
                    return type.equals(matchValue);
                }
            };
        }
        applyFilters();
    }

    /**
     * 合并分组过滤和搜索过滤
     */
    private void applyFilters() {
        List<RowFilter<DefaultTableModel, Object>> filters = new ArrayList<>();
        if (groupFilter != null) filters.add(groupFilter);
        if (searchFilter != null) filters.add(searchFilter);
        if (filters.isEmpty()) {
            tableSorter.setRowFilter(null);
        } else if (filters.size() == 1) {
            tableSorter.setRowFilter(filters.get(0));
        } else {
            tableSorter.setRowFilter(RowFilter.andFilter(filters));
        }
    }

    /**
     * 更新规则启用状态
     */
    private void updateRuleEnabledStatus(int row) {
        if (row < 0 || row >= tableModel.getRowCount()) {
            return;
        }

        Boolean enabled = (Boolean) tableModel.getValueAt(row, 2);

        List<Map<String, Object>> rules = configManager.getFingerprintRules();
        if (row < rules.size()) {
            Map<String, Object> rule = rules.get(row);
            rule.put("loaded", enabled);
            configManager.updateFingerprintRule(row, rule);
        }
    }

    /**
     * 更新数量标签
     */
    private void updateCountLabel() {
        int totalCount = tableModel.getRowCount();
        int enabledCount = 0;
        
        for (int i = 0; i < totalCount; i++) {
            Boolean enabled = (Boolean) tableModel.getValueAt(i, 2);
            if (enabled != null && enabled) {
                enabledCount++;
            }
        }
        
        countLabel.setText(totalCount + " (启用: " + enabledCount + ")");
    }
    
    /**
     * 获取选中的规则
     */
    private int getSelectedModelIndex() {
        int selectedRow = fingerprintTable.getSelectedRow();
        if (selectedRow < 0) return -1;
        return fingerprintTable.convertRowIndexToModel(selectedRow);
    }

    private Map<String, Object> getSelectedRule() {
        int modelRow = getSelectedModelIndex();
        if (modelRow < 0) return null;
        List<Map<String, Object>> rules = configManager.getFingerprintRules();
        return modelRow < rules.size() ? rules.get(modelRow) : null;
    }
    
    @Override
    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        
        switch (command) {
            case "browse-config":
                browseConfigFile();
                break;
            case "reload-config":
                reloadConfig();
                break;
            case "add-rule":
                addRule();
                break;
            case "edit-rule":
                editRule();
                break;
            case "delete-rule":
                deleteRule();
                break;
            case "copy-rule":
                copyRule();
                break;
            case "import-config":
                importConfig();
                break;
            case "export-config":
                exportConfig();
                break;
            case "test-rule":
                testRule();
                break;
            case "batch-enable":
                batchEnable(true);
                break;
            case "batch-disable":
                batchEnable(false);
                break;
            case "search":
                performSearch();
                break;
            case "clear-search":
                clearSearch();
                break;
            case "icon-hash-add":
                addIconHashRule();
                break;
            case "icon-hash-edit":
                editIconHashRule();
                break;
            case "icon-hash-delete":
                deleteIconHashRule();
                break;
            case "icon-hash-search":
                performIconHashSearch();
                break;
            case "icon-hash-clear-search":
                clearIconHashSearch();
                break;
            case "filter-add":
                addFilterRule();
                break;
            case "filter-edit":
                editFilterRule();
                break;
            case "filter-delete":
                deleteFilterRule();
                break;
        }
    }
    
    /**
     * 浏览配置文件
     */
    private void browseConfigFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("YAML文件", "yaml", "yml"));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            configManager.setConfigFilePath(selectedPath);
            Config.put("yaml_config_path", selectedPath);
            configPathField.setText(selectedPath);
            reloadConfig();
        }
    }
    
    /**
     * 重新加载配置
     */
    private void reloadConfig() {
        loadFingerprintRules();
        loadIconHashRules();
        loadFilterRules();
        if (onReloadCallback != null) {
            onReloadCallback.run();
        }
        JOptionPane.showMessageDialog(this, "配置重新加载成功！", "提示", JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * 添加新规则
     */
    private void addRule() {
        FingerprintRuleDialog dialog = new FingerprintRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this), 
            "添加指纹规则", 
            null
        );
        
        Map<String, Object> newRule = dialog.showDialog();
        if (newRule != null) {
            try {
                configManager.addFingerprintRule(newRule);
                
                // 刷新表格
                loadFingerprintRules();
                
                JOptionPane.showMessageDialog(this, "规则添加成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "添加规则失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * 编辑选中规则
     */
    private void editRule() {
        int modelIndex = getSelectedModelIndex();
        Map<String, Object> selectedRule = getSelectedRule();
        if (selectedRule == null) {
            JOptionPane.showMessageDialog(this, "请先选择要编辑的规则！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        FingerprintRuleDialog dialog = new FingerprintRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this),
            "编辑指纹规则",
            selectedRule
        );

        Map<String, Object> editedRule = dialog.showDialog();
        if (editedRule != null) {
            try {
                configManager.updateFingerprintRule(modelIndex, editedRule);
                loadFingerprintRules();
                JOptionPane.showMessageDialog(this, "规则更新成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "更新规则失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * 删除选中规则
     */
    private void deleteRule() {
        int[] selectedRows = fingerprintTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String message = selectedRows.length == 1 ? 
            "确定要删除选中的规则吗？" : 
            "确定要删除选中的 " + selectedRows.length + " 条规则吗？";
            
        int result = JOptionPane.showConfirmDialog(this, message, "确认删除", 
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            
        if (result == JOptionPane.YES_OPTION) {
            try {
                // 从后往前删除，避免索引变化
                for (int i = selectedRows.length - 1; i >= 0; i--) {
                    int modelRow = fingerprintTable.convertRowIndexToModel(selectedRows[i]);
                    configManager.removeFingerprintRule(modelRow);
                }
                
                // 刷新表格
                loadFingerprintRules();
                
                String successMessage = selectedRows.length == 1 ? 
                    "规则删除成功！" : 
                    "成功删除 " + selectedRows.length + " 条规则！";
                JOptionPane.showMessageDialog(this, successMessage, "成功", JOptionPane.INFORMATION_MESSAGE);
                
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "删除规则失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * 复制选中规则
     */
    private void copyRule() {
        int selectedRow = fingerprintTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(this, "请先选择要复制的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        try {
            int modelRow = fingerprintTable.convertRowIndexToModel(selectedRow);
            List<Map<String, Object>> rules = configManager.getFingerprintRules();
            if (modelRow >= rules.size()) return;

            Map<String, Object> copiedRule = new HashMap<>(rules.get(modelRow));
            copiedRule.remove("id");
            copiedRule.put("name", copiedRule.get("name") + " (副本)");

            configManager.addFingerprintRule(copiedRule);
            loadFingerprintRules();
            
            JOptionPane.showMessageDialog(this, "规则复制成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "复制规则失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 导入配置
     */
    private void importConfig() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("YAML文件", "yaml", "yml"));
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String importPath = fileChooser.getSelectedFile().getAbsolutePath();
                YamlConfigManager importManager = new YamlConfigManager(importPath);
                Map<String, Object> importData = importManager.readYamlConfig();
                
                configManager.mergeUpdateYamlConfig(importData);
                loadFingerprintRules();
                
                JOptionPane.showMessageDialog(this, "配置导入成功！", "提示", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "导入失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * 导出配置
     */
    private void exportConfig() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("YAML文件", "yaml", "yml"));
        fileChooser.setSelectedFile(new java.io.File("fingerprint_rules_export.yaml"));
        
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String exportPath = fileChooser.getSelectedFile().getAbsolutePath();
                Map<String, Object> configData = configManager.readYamlConfig();
                
                YamlConfigManager exportManager = new YamlConfigManager(exportPath);
                exportManager.writeYamlConfig(configData);
                
                JOptionPane.showMessageDialog(this, "配置导出成功！", "提示", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void testRule() {
        Map<String, Object> selectedRule = getSelectedRule();
        if (selectedRule == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个指纹规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TestRuleDialog dialog = new TestRuleDialog(
            SwingUtilities.getWindowAncestor(this), selectedRule);
        dialog.showDialog();
    }
    
    /**
     * 批量启用/禁用
     */
    private void batchEnable(boolean enable) {
        String action = enable ? "启用" : "禁用";
        boolean isFiltered = groupFilter != null || searchFilter != null;
        String scope = isFiltered ? "当前筛选的" : "所有";
        int result = JOptionPane.showConfirmDialog(this,
            "确定要" + action + scope + "规则吗？",
            "确认" + action,
            JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            for (int viewRow = 0; viewRow < fingerprintTable.getRowCount(); viewRow++) {
                int modelRow = fingerprintTable.convertRowIndexToModel(viewRow);
                tableModel.setValueAt(enable, modelRow, 2);
                updateRuleEnabledStatus(modelRow);
            }
            updateCountLabel();
        }
    }
    
    /**
     * 执行搜索
     */
    private void performSearch() {
        String searchText = searchField.getText().trim();
        if (searchText.isEmpty()) {
            searchFilter = null;
        } else {
            try {
                searchFilter = RowFilter.regexFilter(
                    "(?i)" + Pattern.quote(searchText));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "搜索表达式无效：" + ex.getMessage(),
                    "搜索错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        applyFilters();
    }

    /**
     * 清除搜索
     */
    private void clearSearch() {
        searchField.setText("");
        searchFilter = null;
        applyFilters();
    }

    // ============================================================
    // Icon Hash 规则管理
    // ============================================================

    private JPanel createIconHashPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        iconHashTableModel = new DefaultTableModel(ICON_HASH_COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        iconHashTable = new JTable(iconHashTableModel);
        iconHashTableSorter = new TableRowSorter<>(iconHashTableModel);
        iconHashTable.setRowSorter(iconHashTableSorter);
        iconHashTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        int[] widths = {150, 150, 280, 100, 250};
        for (int i = 0; i < widths.length && i < iconHashTable.getColumnCount(); i++) {
            iconHashTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scrollPane = new JScrollPane(iconHashTable);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        JButton addBtn = new JButton("添加");
        addBtn.setActionCommand("icon-hash-add");
        addBtn.addActionListener(this);
        buttonPanel.add(addBtn);
        JButton editBtn = new JButton("编辑");
        editBtn.setActionCommand("icon-hash-edit");
        editBtn.addActionListener(this);
        buttonPanel.add(editBtn);
        JButton deleteBtn = new JButton("删除");
        deleteBtn.setActionCommand("icon-hash-delete");
        deleteBtn.addActionListener(this);
        buttonPanel.add(deleteBtn);

        buttonPanel.add(new JSeparator(SwingConstants.VERTICAL));

        iconHashCountLabel = new JLabel("0");
        buttonPanel.add(new JLabel("规则数量: "));
        buttonPanel.add(iconHashCountLabel);

        // 搜索和过滤面板
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(new TitledBorder("搜索和过滤"));
        JPanel searchInner = new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchInner.add(new JLabel("搜索:"));
        iconHashSearchField = new JTextField(20);
        iconHashSearchField.addKeyListener(this);
        searchInner.add(iconHashSearchField);
        JButton searchBtn = new JButton("搜索");
        searchBtn.setActionCommand("icon-hash-search");
        searchBtn.addActionListener(this);
        searchInner.add(searchBtn);
        JButton clearBtn = new JButton("清除");
        clearBtn.setActionCommand("icon-hash-clear-search");
        clearBtn.addActionListener(this);
        searchInner.add(clearBtn);
        searchPanel.add(searchInner, BorderLayout.WEST);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);
        bottomPanel.add(searchPanel, BorderLayout.SOUTH);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    public void loadIconHashRules() {
        iconHashTableModel.setRowCount(0);
        List<Map<String, Object>> rules = configManager.getIconHashRules();
        for (Map<String, Object> rule : rules) {
            Object[] rowData = {
                rule.get("name"),
                rule.get("murmur_hash"),
                rule.get("md5"),
                rule.get("type"),
                rule.get("info")
            };
            iconHashTableModel.addRow(rowData);
        }
        iconHashCountLabel.setText(String.valueOf(rules.size()));
    }

    private void addIconHashRule() {
        IconHashRuleDialog dialog = new IconHashRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this), "添加 Icon Hash 规则", null);
        Map<String, Object> newRule = dialog.showDialog();
        if (newRule != null) {
            configManager.addIconHashRule(newRule);
            loadIconHashRules();
        }
    }

    private void editIconHashRule() {
        int selectedRow = iconHashTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "请先选择要编辑的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = iconHashTable.convertRowIndexToModel(selectedRow);
        List<Map<String, Object>> rules = configManager.getIconHashRules();
        if (modelRow >= rules.size()) return;

        IconHashRuleDialog dialog = new IconHashRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this), "编辑 Icon Hash 规则", rules.get(modelRow));
        Map<String, Object> edited = dialog.showDialog();
        if (edited != null) {
            configManager.updateIconHashRule(modelRow, edited);
            loadIconHashRules();
        }
    }

    private void deleteIconHashRule() {
        int selectedRow = iconHashTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "确定要删除选中的规则吗？", "确认删除",
            JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            int modelRow = iconHashTable.convertRowIndexToModel(selectedRow);
            configManager.removeIconHashRule(modelRow);
            loadIconHashRules();
        }
    }

    private void performIconHashSearch() {
        String searchText = iconHashSearchField.getText().trim();
        if (searchText.isEmpty()) {
            iconHashTableSorter.setRowFilter(null);
        } else {
            try {
                RowFilter<DefaultTableModel, Object> filter = RowFilter.regexFilter(
                    "(?i)" + Pattern.quote(searchText));
                iconHashTableSorter.setRowFilter(filter);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "搜索表达式无效：" + ex.getMessage(),
                    "搜索错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void clearIconHashSearch() {
        iconHashSearchField.setText("");
        iconHashTableSorter.setRowFilter(null);
    }

    // ============================================================
    // 全局过滤规则管理
    // ============================================================

    private JPanel createFilterPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        filterTableModel = new DefaultTableModel(FILTER_COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 2;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 2 ? Boolean.class : String.class;
            }
        };

        filterTable = new JTable(filterTableModel);
        filterTableSorter = new TableRowSorter<>(filterTableModel);
        filterTable.setRowSorter(filterTableSorter);
        filterTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        filterTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == 2) {
                int row = e.getFirstRow();
                updateFilterRuleEnabledStatus(row);
            }
        });

        int[] widths = {200, 350, 60};
        for (int i = 0; i < widths.length && i < filterTable.getColumnCount(); i++) {
            filterTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scrollPane = new JScrollPane(filterTable);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        JButton addBtn = new JButton("添加");
        addBtn.setActionCommand("filter-add");
        addBtn.addActionListener(this);
        buttonPanel.add(addBtn);
        JButton editBtn = new JButton("编辑");
        editBtn.setActionCommand("filter-edit");
        editBtn.addActionListener(this);
        buttonPanel.add(editBtn);
        JButton deleteBtn = new JButton("删除");
        deleteBtn.setActionCommand("filter-delete");
        deleteBtn.addActionListener(this);
        buttonPanel.add(deleteBtn);

        buttonPanel.add(new JSeparator(SwingConstants.VERTICAL));

        filterCountLabel = new JLabel("0");
        buttonPanel.add(new JLabel("规则数量: "));
        buttonPanel.add(filterCountLabel);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    public void loadFilterRules() {
        filterTableModel.setRowCount(0);
        List<Map<String, Object>> rules = configManager.getFilterRules();
        for (Map<String, Object> rule : rules) {
            Object[] rowData = {
                str(rule.get("name")),
                str(rule.get("re")),
                rule.get("loaded") == null || Boolean.TRUE.equals(rule.get("loaded"))
            };
            filterTableModel.addRow(rowData);
        }
        filterCountLabel.setText(String.valueOf(rules.size()));
    }

    private void updateFilterRuleEnabledStatus(int row) {
        if (row < 0 || row >= filterTableModel.getRowCount()) return;
        List<Map<String, Object>> rules = configManager.getFilterRules();
        if (row < rules.size()) {
            Map<String, Object> rule = rules.get(row);
            rule.put("loaded", filterTableModel.getValueAt(row, 2));
            configManager.updateFilterRule(row, rule);
        }
    }

    private void addFilterRule() {
        FilterRuleDialog dialog = new FilterRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this), "添加过滤规则", null);
        Map<String, Object> newRule = dialog.showDialog();
        if (newRule != null) {
            configManager.addFilterRule(newRule);
            loadFilterRules();
            onReloadCallback.run();  // 刷新运行时缓存
        }
    }

    private void editFilterRule() {
        int selectedRow = filterTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "请先选择要编辑的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = filterTable.convertRowIndexToModel(selectedRow);
        List<Map<String, Object>> rules = configManager.getFilterRules();
        if (modelRow >= rules.size()) return;

        FilterRuleDialog dialog = new FilterRuleDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this), "编辑过滤规则", rules.get(modelRow));
        Map<String, Object> edited = dialog.showDialog();
        if (edited != null) {
            configManager.updateFilterRule(modelRow, edited);
            loadFilterRules();
            onReloadCallback.run();
        }
    }

    private void deleteFilterRule() {
        int selectedRow = filterTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的规则", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "确定要删除选中的过滤规则吗？", "确认删除",
            JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            int modelRow = filterTable.convertRowIndexToModel(selectedRow);
            configManager.removeFilterRule(modelRow);
            loadFilterRules();
            onReloadCallback.run();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // 不需要实现
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            if (e.getSource() == searchField) {
                performSearch();
            } else if (e.getSource() == iconHashSearchField) {
                performIconHashSearch();
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getSource() == searchField) {
            performSearch();
        } else if (e.getSource() == iconHashSearchField) {
            performIconHashSearch();
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}