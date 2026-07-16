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

    protected void initView() {
        // 请求响应最大长度
        addTextConfigPanel(L.get("maximum_display_length"), L.get("maximum_display_length_sub_title"),
                20, Config.KEY_MAX_DISPLAY_LENGTH).addKeyListener(new NumberFilter(8));
        // 指纹 URL 精确匹配开关
        addEnabledConfigPanel(L.get("fingerprint_url_match"), L.get("fingerprint_url_match_sub_title"),
                Config.KEY_FINGERPRINT_URL_MATCH);
        addReadOnlyPathPanel(L.get("config_directory"), L.get("config_directory_sub_title"), Config.getWorkDir());
        addReadOnlyPathPanel(L.get("database_path"), L.get("database_path_sub_title"), Config.getWorkDir() + "icon_hash.db");
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
        return super.onTextConfigSave(configKey, text);
    }
}
