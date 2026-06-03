package burp.tdou.fingerscan.ui.widget;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全局过滤规则编辑对话框
 * 用于添加/编辑响应内容过滤器规则（匹配到则跳过指纹识别）
 */
public class FilterRuleDialog extends JDialog implements ActionListener {

    private Map<String, Object> originalRule;
    private Map<String, Object> resultRule;

    private JTextField nameField;
    private JCheckBox enabledCheckBox;
    private JTextArea regexArea;

    public FilterRuleDialog(JFrame parent, String title, Map<String, Object> rule) {
        super(parent, title, true);
        this.originalRule = rule;
        this.resultRule = null;
        initializeUI();
        if (rule != null) {
            populateFields(rule);
        }
        pack();
        setLocationRelativeTo(parent);
        setMinimumSize(new Dimension(450, 250));
    }

    private void initializeUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // 名称
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("名称:*"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        nameField = new JTextField(25);
        formPanel.add(nameField, gbc);

        // 启用
        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("启用:"), gbc);
        gbc.gridx = 1;
        enabledCheckBox = new JCheckBox("启用此过滤规则");
        enabledCheckBox.setSelected(true);
        formPanel.add(enabledCheckBox, gbc);

        // 正则表达式
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.NORTHWEST;
        formPanel.add(new JLabel("正则表达式:*"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0;
        regexArea = new JTextArea(4, 25);
        regexArea.setLineWrap(true);
        regexArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(regexArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("匹配响应内容的正则表达式"));
        formPanel.add(scrollPane, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        // 按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("确定");
        okButton.setActionCommand("ok");
        okButton.addActionListener(this);
        buttonPanel.add(okButton);
        JButton cancelButton = new JButton("取消");
        cancelButton.setActionCommand("cancel");
        cancelButton.addActionListener(this);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);
    }

    private void populateFields(Map<String, Object> rule) {
        nameField.setText(rule.get("name") != null ? rule.get("name").toString() : "");
        Object loaded = rule.get("loaded");
        enabledCheckBox.setSelected(loaded == null || Boolean.TRUE.equals(loaded));
        regexArea.setText(rule.get("re") != null ? rule.get("re").toString() : "");
    }

    private boolean validateInput() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入规则名称！", "输入错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (regexArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入正则表达式！", "输入错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        try {
            Pattern.compile(regexArea.getText().trim());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "正则表达式格式错误：" + e.getMessage(), "输入错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private Map<String, Object> createRule() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("name", nameField.getText().trim());
        rule.put("re", regexArea.getText().trim());
        rule.put("loaded", enabledCheckBox.isSelected());
        return rule;
    }

    public Map<String, Object> showDialog() {
        setVisible(true);
        return resultRule;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if ("ok".equals(e.getActionCommand())) {
            if (validateInput()) {
                resultRule = createRule();
                dispose();
            }
        } else if ("cancel".equals(e.getActionCommand())) {
            resultRule = null;
            dispose();
        }
    }
}
