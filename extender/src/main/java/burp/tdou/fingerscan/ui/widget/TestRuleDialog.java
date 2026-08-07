package burp.tdou.fingerscan.ui.widget;

import burp.tdou.fingerscan.core.rule.KeywordSpec;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestRuleDialog extends JDialog implements ActionListener {

    private final Map<String, Object> rule;
    private JTextPane responseArea;
    private JLabel resultLabel;
    private JTextField urlField;

    private static final Color MATCH_COLOR = new Color(255, 255, 0, 160);
    private static final Color SUCCESS_COLOR = new Color(0, 128, 0);
    private static final Color FAIL_COLOR = new Color(200, 0, 0);

    public TestRuleDialog(Window parent, Map<String, Object> rule) {
        super(parent, "测试规则 - " + rule.get("name"), ModalityType.APPLICATION_MODAL);
        this.rule = rule;
        initializeUI();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(700, 550));

        JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        mainPanel.add(createRuleInfoPanel(), BorderLayout.NORTH);
        mainPanel.add(createResponsePanel(), BorderLayout.CENTER);
        mainPanel.add(createBottomPanel(), BorderLayout.SOUTH);

        add(mainPanel);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private JPanel createRuleInfoPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("规则信息"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        String name = str(rule.get("name"));
        String method = str(rule.get("method"));
        String url = str(rule.get("url"));
        String regex = str(rule.get("re"));
        String state = str(rule.get("state"));

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("名称:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(name), gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("方法:"), gbc);
        gbc.gridx = 3; gbc.weightx = 0.3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(method), gbc);

        gbc.gridx = 4; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("状态码:"), gbc);
        gbc.gridx = 5; gbc.weightx = 0.2; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(state), gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("URL路径:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 5; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(url), gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("正则:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 5; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField regexField = new JTextField(regex);
        regexField.setEditable(false);
        regexField.setBackground(panel.getBackground());
        panel.add(regexField, gbc);

        return panel;
    }

    private JPanel createResponsePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(BorderFactory.createTitledBorder("响应内容（粘贴HTTP响应或网页源码进行测试）"));

        responseArea = new JTextPane();
        responseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(responseArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hintPanel.add(new JLabel("提示: 粘贴目标URL的HTTP响应内容，点击「开始测试」验证正则是否匹配"));
        panel.add(hintPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(5, 0, 0, 0));

        resultLabel = new JLabel(" ");
        resultLabel.setFont(resultLabel.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(resultLabel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton testButton = new JButton("开始测试");
        testButton.setActionCommand("test");
        testButton.addActionListener(this);
        buttonPanel.add(testButton);

        JButton clearButton = new JButton("清空");
        clearButton.setActionCommand("clear");
        clearButton.addActionListener(this);
        buttonPanel.add(clearButton);

        JButton closeButton = new JButton("关闭");
        closeButton.setActionCommand("close");
        closeButton.addActionListener(this);
        buttonPanel.add(closeButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        switch (e.getActionCommand()) {
            case "test":
                performTest();
                break;
            case "clear":
                responseArea.setText("");
                resultLabel.setText(" ");
                break;
            case "close":
                dispose();
                break;
        }
    }

    private void performTest() {
        String content = responseArea.getText();
        if (content == null || content.trim().isEmpty()) {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("请先粘贴响应内容");
            return;
        }

        String patternText = str(rule.get("re"));
        if (patternText.isEmpty()) {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("规则匹配内容为空");
            return;
        }

        String matchMode = str(rule.get("match"));
        boolean keyword = "keyword".equalsIgnoreCase(matchMode);

        try {
            StyledDocument doc = responseArea.getStyledDocument();
            Style defaultStyle = StyleContext.getDefaultStyleContext()
                    .getStyle(StyleContext.DEFAULT_STYLE);
            doc.setCharacterAttributes(0, doc.getLength(), defaultStyle, true);

            Style highlightStyle = doc.addStyle("highlight", null);
            StyleConstants.setBackground(highlightStyle, MATCH_COLOR);
            StyleConstants.setBold(highlightStyle, true);

            if (keyword) {
                performKeywordTest(content, patternText, doc, highlightStyle);
            } else {
                performRegexTest(content, patternText, doc, highlightStyle);
            }
        } catch (Exception ex) {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("测试错误: " + ex.getMessage());
        }
    }

    private void performKeywordTest(String content, String raw, StyledDocument doc, Style highlightStyle) {
        KeywordSpec ks = KeywordSpec.parse(raw);
        if (ks == null) {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("关键字格式无效（检查 && / || 是否混用或空段）");
            return;
        }
        String folded = content.toLowerCase(Locale.ROOT);
        boolean ok = ks.matches(folded);
        int matchCount = 0;
        int firstPos = -1;
        for (String lit : ks.literals) {
            if (lit == null || lit.isEmpty()) {
                continue;
            }
            int from = 0;
            while (from <= folded.length() - lit.length()) {
                int idx = folded.indexOf(lit, from);
                if (idx < 0) {
                    break;
                }
                matchCount++;
                if (firstPos < 0) {
                    firstPos = idx;
                }
                doc.setCharacterAttributes(idx, lit.length(), highlightStyle, false);
                from = idx + Math.max(1, lit.length());
            }
        }
        if (ok && matchCount > 0) {
            resultLabel.setForeground(SUCCESS_COLOR);
            resultLabel.setText("匹配成功! 找到 " + matchCount + " 处关键字命中（"
                    + ks.op + "）");
            if (firstPos >= 0) {
                responseArea.setCaretPosition(firstPos);
            }
        } else {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("匹配失败 - 未满足关键字条件（" + ks.op + "）");
        }
    }

    private void performRegexTest(String content, String regex, StyledDocument doc, Style highlightStyle) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
            doc.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(),
                    highlightStyle, false);
        }
        if (matchCount > 0) {
            resultLabel.setForeground(SUCCESS_COLOR);
            resultLabel.setText("匹配成功! 找到 " + matchCount + " 处匹配");
            Matcher first = pattern.matcher(content);
            if (first.find()) {
                responseArea.setCaretPosition(first.start());
            }
        } else {
            resultLabel.setForeground(FAIL_COLOR);
            resultLabel.setText("匹配失败 - 响应内容中未找到匹配项");
        }
    }

    public void showDialog() {
        setVisible(true);
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
