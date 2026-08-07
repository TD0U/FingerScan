package burp.tdou.fingerscan.ui.tab.config;

import burp.tdou.common.helper.UIHelper;
import burp.tdou.common.layout.HLayout;
import burp.tdou.common.utils.StringUtils;
import burp.tdou.common.utils.Utils;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.common.L;
import burp.tdou.fingerscan.common.NumberFilter;
import burp.tdou.fingerscan.ui.base.BaseConfigTab;

import javax.swing.*;

/**
 * Other设置
 * <p>
 * Created by vaycore on 2022-08-21.
 */
public class OtherTab extends BaseConfigTab {

    public static final String EVENT_UNLOAD_PLUGIN = "event-unload-plugin";
    /** 匹配 CPU 相关配置保存后，通知 Extender 热更新池/信号量/索引 */
    public static final String EVENT_MATCH_CPU_SETTINGS = "event-match-cpu-settings";

    protected void initView() {
        // 请求响应最大长度
        addTextConfigPanel(L.get("maximum_display_length"), L.get("maximum_display_length_sub_title"),
                20, Config.KEY_MAX_DISPLAY_LENGTH).addKeyListener(new NumberFilter(8));
        // 指纹 URL 精确匹配开关
        addEnabledConfigPanel(L.get("fingerprint_url_match"), L.get("fingerprint_url_match_sub_title"),
                Config.KEY_FINGERPRINT_URL_MATCH);

        // —— 匹配 CPU 优化 ——
        addEnabledConfigPanel(L.get("match_literal_prefilter"), L.get("match_literal_prefilter_sub_title"),
                Config.KEY_MATCH_LITERAL_PREFILTER);
        addEnabledConfigPanel(L.get("match_skip_binary"), L.get("match_skip_binary_sub_title"),
                Config.KEY_MATCH_SKIP_BINARY);
        addTextConfigPanel(L.get("match_literal_min_length"), L.get("match_literal_min_length_sub_title"),
                10, Config.KEY_MATCH_LITERAL_MIN_LENGTH).addKeyListener(new NumberFilter(2));
        addTextConfigPanel(L.get("analysis_thread_count"), L.get("analysis_thread_count_sub_title"),
                10, Config.KEY_ANALYSIS_THREAD_COUNT).addKeyListener(new NumberFilter(3));
        addTextConfigPanel(L.get("analysis_queue_size"), L.get("analysis_queue_size_sub_title"),
                10, Config.KEY_ANALYSIS_QUEUE_SIZE).addKeyListener(new NumberFilter(6));
        addTextConfigPanel(L.get("match_concurrency"), L.get("match_concurrency_sub_title"),
                10, Config.KEY_MATCH_CONCURRENCY).addKeyListener(new NumberFilter(3));

        addConfigItem(L.get("analysis_discard_count"), L.get("analysis_discard_count_sub_title"),
                buildDiscardPanel());

        addReadOnlyPathPanel(L.get("config_directory"), L.get("config_directory_sub_title"), Config.getWorkDir());
        addReadOnlyPathPanel(L.get("database_path"), L.get("database_path_sub_title"), Config.getWorkDir() + "icon_hash.db");
    }

    private JPanel buildDiscardPanel() {
        JPanel panel = new JPanel(new HLayout(5));
        JLabel countLabel = new JLabel(L.get("analysis_discard_count_value")
                .replace("{0}", "—"));
        countLabel.setName("analysisDiscardLabel");
        panel.add(countLabel);
        JButton refresh = new JButton(L.get("refresh"));
        refresh.addActionListener(e -> sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "refresh-discard"));
        panel.add(refresh);
        JButton reset = new JButton(L.get("reset_discard_count"));
        reset.addActionListener(e -> sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "reset-discard"));
        panel.add(reset);
        return panel;
    }

    private void addReadOnlyPathPanel(String title, String subTitle, String path) {
        JPanel panel = new JPanel(new HLayout(3));
        JTextField textField = new JTextField(path, 35);
        textField.setEditable(false);
        panel.add(textField);
        JButton copyBtn = new JButton(L.get("copy"));
        copyBtn.addActionListener(e -> {
            Utils.setSysClipboardText(textField.getText());
            UIHelper.showTipsDialog(L.get("save_success"));
        });
        panel.add(copyBtn);
        addConfigItem(title, subTitle, panel);
    }

    @Override
    public String getTitleName() {
        return L.get("tab_name.other");
    }

    @Override
    protected boolean onTextConfigSave(String configKey, String text) {
        int value = StringUtils.parseInt(text, -1);
        if (Config.KEY_MAX_DISPLAY_LENGTH.equals(configKey)) {
            if (value == 0) {
                text = String.valueOf(value);
                Config.put(configKey, text);
                return true;
            }
            if (value < 100000 || value > 99999999) {
                UIHelper.showTipsDialog(L.get("maximum_display_length_value_invalid"));
                return false;
            }
            text = String.valueOf(value);
            Config.put(configKey, text);
            return true;
        }
        if (Config.KEY_MATCH_LITERAL_MIN_LENGTH.equals(configKey)) {
            if (value < 1 || value > 32) {
                UIHelper.showTipsDialog(L.get("match_literal_min_length_invalid"));
                return false;
            }
            Config.put(configKey, String.valueOf(value));
            sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "apply");
            return true;
        }
        if (Config.KEY_ANALYSIS_THREAD_COUNT.equals(configKey)) {
            if (value < 1 || value > 64) {
                UIHelper.showTipsDialog(L.get("analysis_thread_count_invalid"));
                return false;
            }
            Config.put(configKey, String.valueOf(value));
            sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "apply");
            return true;
        }
        if (Config.KEY_ANALYSIS_QUEUE_SIZE.equals(configKey)) {
            if (value < 1 || value > 100000) {
                UIHelper.showTipsDialog(L.get("analysis_queue_size_invalid"));
                return false;
            }
            Config.put(configKey, String.valueOf(value));
            sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "apply");
            return true;
        }
        if (Config.KEY_MATCH_CONCURRENCY.equals(configKey)) {
            if (value < 1 || value > 64) {
                UIHelper.showTipsDialog(L.get("match_concurrency_invalid"));
                return false;
            }
            Config.put(configKey, String.valueOf(value));
            sendTabEvent(EVENT_MATCH_CPU_SETTINGS, "apply");
            return true;
        }
        return super.onTextConfigSave(configKey, text);
    }
}
