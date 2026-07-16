package burp.tdou.fingerscan.ui.tab;

import burp.tdou.fingerscan.core.iconhash.IconHashStore;
import burp.tdou.fingerscan.config.YamlConfigStore;
import burp.tdou.fingerscan.ui.widget.IconHashRuleDialog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public class IconDataPanel extends JPanel {

    private final IconHashStore store;
    private final YamlConfigStore configStore;
    private JTable iconTable;
    private DefaultTableModel tableModel;
    private JLabel iconPreview;
    private JTable sourceTable;
    private DefaultTableModel sourceTableModel;
    private JLabel statusLabel;

    private static final String[] ICON_COLUMNS = {
        "MurmurHash3", "MD5", "Content-Type", "大小(B)", "首次发现", "匹配结果", "来源数", "备注"
    };
    private static final String[] SOURCE_COLUMNS = {"Host", "路径", "发现时间"};

    public IconDataPanel(IconHashStore store, YamlConfigStore configStore) {
        this.store = store;
        this.configStore = configStore;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // top: toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshBtn = new JButton("刷新");
        refreshBtn.addActionListener(e -> loadIcons());
        toolbar.add(refreshBtn);

        JButton deleteBtn = new JButton("删除");
        deleteBtn.addActionListener(e -> deleteSelected());
        toolbar.add(deleteBtn);

        JButton exportBtn = new JButton("导出图标");
        exportBtn.addActionListener(e -> exportSelected());
        toolbar.add(exportBtn);

        JButton toRuleBtn = new JButton("转为指纹规则");
        toRuleBtn.addActionListener(e -> convertToRule());
        toolbar.add(toRuleBtn);

        toolbar.add(Box.createHorizontalStrut(20));
        statusLabel = new JLabel("共 0 个图标");
        toolbar.add(statusLabel);

        add(toolbar, BorderLayout.NORTH);

        // center: split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.6);

        // upper: icon table
        tableModel = new DefaultTableModel(ICON_COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return column == 7; }
        };
        iconTable = new JTable(tableModel);
        iconTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        iconTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onIconSelected();
        });
        tableModel.addTableModelListener(e -> {
            if (e.getType() == javax.swing.event.TableModelEvent.UPDATE && e.getColumn() == 7) {
                int row = e.getFirstRow();
                String murmurHash = (String) tableModel.getValueAt(row, 0);
                String remark = (String) tableModel.getValueAt(row, 7);
                if (store != null && murmurHash != null) {
                    store.updateRemark(murmurHash, remark);
                }
            }
        });
        iconTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        iconTable.getColumnModel().getColumn(1).setPreferredWidth(260);
        iconTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        iconTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        iconTable.getColumnModel().getColumn(4).setPreferredWidth(140);
        iconTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        iconTable.getColumnModel().getColumn(6).setPreferredWidth(60);
        iconTable.getColumnModel().getColumn(7).setPreferredWidth(200);

        splitPane.setTopComponent(new JScrollPane(iconTable));

        // lower: preview + sources
        JPanel detailPanel = new JPanel(new BorderLayout());

        // icon preview on the left
        iconPreview = new JLabel("选择一个图标查看预览", SwingConstants.CENTER);
        iconPreview.setPreferredSize(new Dimension(128, 128));
        iconPreview.setBorder(BorderFactory.createTitledBorder("图标预览"));
        iconPreview.setVerticalAlignment(SwingConstants.CENTER);
        detailPanel.add(iconPreview, BorderLayout.WEST);

        // sources table on the right
        sourceTableModel = new DefaultTableModel(SOURCE_COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        sourceTable = new JTable(sourceTableModel);

        // 右键菜单：复制 URL
        JPopupMenu sourceMenu = new JPopupMenu();
        JMenuItem copyUrlItem = new JMenuItem("复制 URL");
        copyUrlItem.addActionListener(e -> {
            int row = sourceTable.getSelectedRow();
            if (row < 0) return;
            int modelRow = sourceTable.convertRowIndexToModel(row);
            String host = (String) sourceTableModel.getValueAt(modelRow, 0);
            String path = (String) sourceTableModel.getValueAt(modelRow, 1);
            if (host == null) return;
            String url = host.startsWith("http") ? host + path : "https://" + host + path;
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(url), null);
        });
        sourceMenu.add(copyUrlItem);
        sourceTable.setComponentPopupMenu(sourceMenu);

        JScrollPane sourceScroll = new JScrollPane(sourceTable);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("来源站点"));
        detailPanel.add(sourceScroll, BorderLayout.CENTER);

        splitPane.setBottomComponent(detailPanel);

        add(splitPane, BorderLayout.CENTER);
    }

    public void loadIcons() {
        tableModel.setRowCount(0);
        sourceTableModel.setRowCount(0);
        iconPreview.setIcon(null);
        iconPreview.setText("选择一个图标查看预览");

        if (store == null) {
            statusLabel.setText("数据库未初始化");
            return;
        }

        List<String[]> icons = store.getAllIcons();
        for (String[] row : icons) {
            tableModel.addRow(row);
        }
        statusLabel.setText("共 " + icons.size() + " 个图标");
    }

    private void onIconSelected() {
        int row = iconTable.getSelectedRow();
        if (row < 0 || store == null) return;

        int modelRow = iconTable.convertRowIndexToModel(row);
        String murmurHash = (String) tableModel.getValueAt(modelRow, 0);

        // load preview
        byte[] data = store.getIconData(murmurHash);
        if (data != null && data.length > 0) {
            try {
                BufferedImage img = parseIcon(data);
                if (img != null) {
                    Image scaled = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    iconPreview.setIcon(new ImageIcon(scaled));
                    iconPreview.setText(img.getWidth() + "x" + img.getHeight() + " | " + data.length + " bytes");
                } else {
                    iconPreview.setIcon(null);
                    iconPreview.setText("无法解析图标 (" + data.length + " bytes)");
                }
            } catch (Exception e) {
                iconPreview.setIcon(null);
                iconPreview.setText("解析失败: " + e.getMessage());
            }
        } else {
            iconPreview.setIcon(null);
            iconPreview.setText("无数据");
        }

        // load sources
        sourceTableModel.setRowCount(0);
        List<String[]> sources = store.getIconSources(murmurHash);
        for (String[] s : sources) {
            sourceTableModel.addRow(s);
        }
    }

    /**
     * 解析图标字节数据为 BufferedImage
     * 支持 PNG、GIF、JPEG（ImageIO 原生）和 ICO 格式（手动解析）
     */
    private BufferedImage parseIcon(byte[] data) {
        if (data == null || data.length < 4) return null;

        // 先尝试 ImageIO（处理 PNG/GIF/JPEG）
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) return img;
        } catch (Exception ignored) {
        }

        // ICO 格式: 前4字节 00 00 01 00
        if (data.length > 22 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) {
            return parseIco(data);
        }

        return null;
    }

    /**
     * 解析 ICO 文件，提取最大的那张图片
     * ICO 结构: 6字节头 + N * 16字节目录项 + 图片数据（PNG 或 BMP）
     */
    private BufferedImage parseIco(byte[] data) {
        try {
            int count = readUint16LE(data, 4);
            if (count <= 0 || count > 256) return null;

            int bestIdx = 0;
            int bestSize = 0;

            for (int i = 0; i < count; i++) {
                int offset = 6 + i * 16;
                if (offset + 16 > data.length) break;
                int imgSize = readInt32LE(data, offset + 8);
                if (imgSize > bestSize) {
                    bestSize = imgSize;
                    bestIdx = i;
                }
            }

            int dirOffset = 6 + bestIdx * 16;
            int imgSize = readInt32LE(data, dirOffset + 8);
            int imgOffset = readInt32LE(data, dirOffset + 12);

            if (imgOffset < 0 || imgOffset + imgSize > data.length) return null;

            byte[] imgData = new byte[imgSize];
            System.arraycopy(data, imgOffset, imgData, 0, imgSize);

            // 嵌入的图片可能是 PNG
            if (imgData.length > 8 && imgData[0] == (byte) 0x89 && imgData[1] == 0x50
                    && imgData[2] == 0x4E && imgData[3] == 0x47) {
                return ImageIO.read(new ByteArrayInputStream(imgData));
            }

            // 嵌入的是 BMP（DIB 格式，无文件头）
            return parseDib(imgData, data, dirOffset);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 ICO 内嵌的 DIB (Device Independent Bitmap) 数据
     */
    private BufferedImage parseDib(byte[] dib, byte[] icoData, int dirOffset) {
        try {
            if (dib.length < 40) return null;

            int width = readInt32LE(dib, 4);
            int height = readInt32LE(dib, 8) / 2; // ICO 中 height 是实际高度的两倍（含 mask）
            int bpp = readUint16LE(dib, 14);
            int colorsUsed = readInt32LE(dib, 32);

            if (width <= 0 || width > 256 || height <= 0 || height > 256) {
                int w = icoData[dirOffset] & 0xFF;
                int h = icoData[dirOffset + 1] & 0xFF;
                width = w == 0 ? 256 : w;
                height = h == 0 ? 256 : h;
            }

            if (bpp == 32) {
                return parseDib32(dib, width, height);
            } else if (bpp == 24) {
                return parseDib24(dib, width, height);
            } else if (bpp == 8) {
                int paletteSize = (colorsUsed > 0 && colorsUsed <= 256) ? colorsUsed : 256;
                return parseDibPalette(dib, width, height, paletteSize, 1);
            } else if (bpp == 4) {
                int paletteSize = (colorsUsed > 0 && colorsUsed <= 16) ? colorsUsed : 16;
                return parseDibPalette(dib, width, height, paletteSize, 0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private BufferedImage parseDibPalette(byte[] dib, int width, int height, int paletteSize, int bppMode) {
        // bppMode: 1=8bpp, 0=4bpp
        int paletteOffset = 40;
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            int pos = paletteOffset + i * 4;
            if (pos + 3 >= dib.length) break;
            int b = dib[pos] & 0xFF;
            int g = dib[pos + 1] & 0xFF;
            int r = dib[pos + 2] & 0xFF;
            palette[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        int dataOffset = paletteOffset + paletteSize * 4;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (bppMode == 1) {
            // 8bpp: 每字节一个像素索引
            int rowSize = ((width + 3) / 4) * 4;
            if (dataOffset + rowSize * height > dib.length) return null;
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    int pos = dataOffset + (height - 1 - y) * rowSize + x;
                    int idx = dib[pos] & 0xFF;
                    img.setRGB(x, y, idx < palette.length ? palette[idx] : 0xFF000000);
                }
            }
        } else {
            // 4bpp: 每字节两个像素索引
            int rowSize = ((width + 7) / 8) * 4;
            if (dataOffset + rowSize * height > dib.length) return null;
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    int pos = dataOffset + (height - 1 - y) * rowSize + x / 2;
                    int b = dib[pos] & 0xFF;
                    int idx = (x % 2 == 0) ? (b >> 4) & 0x0F : b & 0x0F;
                    img.setRGB(x, y, idx < palette.length ? palette[idx] : 0xFF000000);
                }
            }
        }
        return img;
    }

    private BufferedImage parseDib32(byte[] dib, int width, int height) {
        int dataOffset = 40;
        if (dataOffset + width * height * 4 > dib.length) return null;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int pos = dataOffset + ((height - 1 - y) * width + x) * 4;
                int b = dib[pos] & 0xFF;
                int g = dib[pos + 1] & 0xFF;
                int r = dib[pos + 2] & 0xFF;
                int a = dib[pos + 3] & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private BufferedImage parseDib24(byte[] dib, int width, int height) {
        int rowSize = ((width * 3 + 3) / 4) * 4;
        int dataOffset = 40;
        if (dataOffset + rowSize * height > dib.length) return null;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int pos = dataOffset + (height - 1 - y) * rowSize + x * 3;
                int b = dib[pos] & 0xFF;
                int g = dib[pos + 1] & 0xFF;
                int r = dib[pos + 2] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private int readUint16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
             | ((data[offset + 1] & 0xFF) << 8)
             | ((data[offset + 2] & 0xFF) << 16)
             | ((data[offset + 3] & 0xFF) << 24);
    }

    private void convertToRule() {
        int row = iconTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "请先选择要转换的图标", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (configStore == null) {
            JOptionPane.showMessageDialog(this, "指纹配置管理器未初始化", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int modelRow = iconTable.convertRowIndexToModel(row);
        String murmurHash = (String) tableModel.getValueAt(modelRow, 0);
        String md5 = (String) tableModel.getValueAt(modelRow, 1);
        String matchResult = (String) tableModel.getValueAt(modelRow, 5);

        Map<String, Object> prefill = new HashMap<>();
        prefill.put("murmur_hash", murmurHash != null ? murmurHash : "");
        prefill.put("md5", md5 != null ? md5 : "");
        prefill.put("name", matchResult != null && !matchResult.isEmpty() ? matchResult : "");
        prefill.put("type", "Application");
        prefill.put("info", "");

        Window ancestor = SwingUtilities.getWindowAncestor(this);
        JFrame frame = ancestor instanceof JFrame ? (JFrame) ancestor : null;
        IconHashRuleDialog dialog = new IconHashRuleDialog(frame, "转为 Icon Hash 指纹规则", prefill);
        Map<String, Object> result = dialog.showDialog();
        if (result != null) {
            configStore.addIconHashRule(result);
            String ruleName = String.valueOf(result.get("name"));
            String oldMatch = (String) tableModel.getValueAt(modelRow, 5);
            String newMatch;
            if (oldMatch != null && !oldMatch.isEmpty()) {
                newMatch = oldMatch + ", " + ruleName;
            } else {
                newMatch = ruleName;
            }
            store.updateMatchResult(murmurHash, newMatch);
            tableModel.setValueAt(newMatch, modelRow, 5);
            JOptionPane.showMessageDialog(this, "指纹规则添加成功!", "成功", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void deleteSelected() {
        int row = iconTable.getSelectedRow();
        if (row < 0 || store == null) {
            JOptionPane.showMessageDialog(this, "请先选择要删除的图标", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "确定要删除选中的图标吗？", "确认删除", JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            int modelRow = iconTable.convertRowIndexToModel(row);
            String murmurHash = (String) tableModel.getValueAt(modelRow, 0);
            store.deleteIcon(murmurHash);
            loadIcons();
        }
    }

    private void exportSelected() {
        int row = iconTable.getSelectedRow();
        if (row < 0 || store == null) {
            JOptionPane.showMessageDialog(this, "请先选择要导出的图标", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = iconTable.convertRowIndexToModel(row);
        String murmurHash = (String) tableModel.getValueAt(modelRow, 0);
        String contentType = (String) tableModel.getValueAt(modelRow, 2);

        byte[] data = store.getIconData(murmurHash);
        if (data == null || data.length == 0) {
            JOptionPane.showMessageDialog(this, "图标数据为空", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String ext = ".ico";
        if (contentType != null) {
            if (contentType.contains("png")) ext = ".png";
            else if (contentType.contains("svg")) ext = ".svg";
            else if (contentType.contains("gif")) ext = ".gif";
        }

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(murmurHash + ext));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                java.nio.file.Files.write(fc.getSelectedFile().toPath(), data);
                JOptionPane.showMessageDialog(this, "导出成功", "提示", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
