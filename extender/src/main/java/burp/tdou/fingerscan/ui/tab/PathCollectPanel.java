package burp.tdou.fingerscan.ui.tab;

import burp.tdou.fingerscan.core.path.PathStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.List;
import java.util.regex.Pattern;

public class PathCollectPanel extends JPanel implements ActionListener, KeyListener {

    private final PathStore pathStore;

    private JTable pathTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> tableSorter;
    private JTextField searchField;
    private JLabel countLabel;

    private static final String[] COLUMN_NAMES = {
        "路径", "来源主机", "命中次数", "首次发现", "最后发现"
    };

    public PathCollectPanel(PathStore pathStore) {
        this.pathStore = pathStore;
        initializeUI();
        loadPaths();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        topPanel.add(new JLabel("已收集路径数:"));
        countLabel = new JLabel("0");
        topPanel.add(countLabel);
        add(topPanel, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 2) return Integer.class;
                return String.class;
            }
        };

        pathTable = new JTable(tableModel);
        tableSorter = new TableRowSorter<>(tableModel);
        pathTable.setRowSorter(tableSorter);
        // 允许单元格级别框选
        pathTable.setCellSelectionEnabled(true);
        pathTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        pathTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // 右键菜单：复制选中单元格
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem copyPathItem = new JMenuItem("复制选中内容");
        copyPathItem.addActionListener(e -> copySelectedCells());
        popupMenu.add(copyPathItem);
        pathTable.setComponentPopupMenu(popupMenu);

        // Ctrl+C 复制选中单元格
        pathTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK), "copyCells");
        pathTable.getActionMap().put("copyCells", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { copySelectedCells(); }
        });

        int[] widths = {150, 300, 80, 160, 160};
        for (int i = 0; i < widths.length && i < pathTable.getColumnCount(); i++) {
            pathTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scrollPane = new JScrollPane(pathTable);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchPanel.setBorder(new TitledBorder("搜索"));
        searchPanel.add(new JLabel("搜索:"));
        searchField = new JTextField(20);
        searchField.addKeyListener(this);
        searchPanel.add(searchField);

        JButton searchBtn = new JButton("搜索");
        searchBtn.setActionCommand("search");
        searchBtn.addActionListener(this);
        searchPanel.add(searchBtn);

        JButton clearSearchBtn = new JButton("清除");
        clearSearchBtn.setActionCommand("clear-search");
        clearSearchBtn.addActionListener(this);
        searchPanel.add(clearSearchBtn);

        bottomPanel.add(searchPanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton refreshBtn = new JButton("刷新");
        refreshBtn.setActionCommand("refresh");
        refreshBtn.addActionListener(this);
        buttonPanel.add(refreshBtn);

        JButton exportBtn = new JButton("导出字典");
        exportBtn.setActionCommand("export");
        exportBtn.addActionListener(this);
        buttonPanel.add(exportBtn);

        JButton deleteBtn = new JButton("删除选中");
        deleteBtn.setActionCommand("delete");
        deleteBtn.addActionListener(this);
        buttonPanel.add(deleteBtn);

        JButton clearBtn = new JButton("清空全部");
        clearBtn.setActionCommand("clear-all");
        clearBtn.addActionListener(this);
        buttonPanel.add(clearBtn);

        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    public void loadPaths() {
        tableModel.setRowCount(0);
        List<String[]> paths = pathStore.getAllPaths();
        for (String[] row : paths) {
            tableModel.addRow(new Object[]{
                row[0],
                row[1],
                Integer.parseInt(row[2]),
                row[3],
                row[4]
            });
        }
        countLabel.setText(String.valueOf(pathStore.getPathCount()));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        switch (e.getActionCommand()) {
            case "refresh":
                loadPaths();
                break;
            case "export":
                exportDict();
                break;
            case "delete":
                deleteSelected();
                break;
            case "clear-all":
                clearAll();
                break;
            case "search":
                performSearch();
                break;
            case "clear-search":
                searchField.setText("");
                tableSorter.setRowFilter(null);
                break;
        }
    }

    private void exportDict() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new java.io.File("path_dict.txt"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(fileChooser.getSelectedFile()))) {
                List<String> paths = pathStore.getDistinctPaths();
                for (String path : paths) {
                    writer.write(path);
                    writer.newLine();
                }
                JOptionPane.showMessageDialog(this,
                    "导出成功! 共 " + paths.size() + " 条路径",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "导出失败: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void copySelectedCells() {
        int[] selectedRows = pathTable.getSelectedRows();
        int[] selectedCols = pathTable.getSelectedColumns();
        if (selectedRows.length == 0 || selectedCols.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int viewRow : selectedRows) {
            int modelRow = pathTable.convertRowIndexToModel(viewRow);
            StringBuilder rowSb = new StringBuilder();
            for (int viewCol : selectedCols) {
                int modelCol = pathTable.convertColumnIndexToModel(viewCol);
                Object val = tableModel.getValueAt(modelRow, modelCol);
                if (rowSb.length() > 0) rowSb.append("\t");
                rowSb.append(val != null ? val.toString() : "");
            }
            sb.append(rowSb).append("\n");
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString().trim()), null);
    }

    private void deleteSelected() {
        int[] selectedRows = pathTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的路径", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
            "确定要删除选中的 " + selectedRows.length + " 条路径吗？",
            "确认删除", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                int modelRow = pathTable.convertRowIndexToModel(selectedRows[i]);
                String path = tableModel.getValueAt(modelRow, 0).toString();
                pathStore.deletePath(path);
            }
            loadPaths();
        }
    }

    private void clearAll() {
        int result = JOptionPane.showConfirmDialog(this,
            "确定要清空所有已收集的路径吗？",
            "确认清空", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            pathStore.clearAll();
            loadPaths();
        }
    }

    private void performSearch() {
        String text = searchField.getText().trim();
        if (text.isEmpty()) {
            tableSorter.setRowFilter(null);
        } else {
            try {
                tableSorter.setRowFilter(RowFilter.regexFilter(
                    "(?i)" + Pattern.quote(text)));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "搜索表达式无效: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER && e.getSource() == searchField) {
            performSearch();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getSource() == searchField) {
            performSearch();
        }
    }
}
