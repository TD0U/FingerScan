package burp.tdou.fingerscan;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.common.Constants;
import burp.tdou.fingerscan.config.YamlConfigStore;
import burp.tdou.fingerscan.core.iconhash.IconHashStore;
import burp.tdou.fingerscan.core.path.PathStore;
import burp.tdou.fingerscan.ui.tab.ConfigPanel;
import burp.tdou.fingerscan.ui.tab.DataBoardTab;
import burp.tdou.fingerscan.ui.tab.FingerprintPanel;
import burp.tdou.fingerscan.ui.tab.DataPanel;
import burp.tdou.fingerscan.ui.tab.IconDataPanel;
import burp.tdou.fingerscan.ui.tab.PathCollectPanel;

import javax.swing.*;

/**
 * 插件主类
 */
public class FingerScan extends JTabbedPane {

    private DataBoardTab mDataBoardTab;
    private ConfigPanel mConfigPanel;
    private FingerprintPanel mFingerprintPanel;
    private DataPanel mDataPanel;
    private IconDataPanel mIconDataPanel;
    private PathCollectPanel mPathCollectPanel;
    private YamlConfigStore mConfigStore;

    public FingerScan(YamlConfigStore configStore) {
        Logger.info(Constants.BANNER);
        mConfigStore = configStore;
        initView();
    }

    private void initView() {
        mDataBoardTab = new DataBoardTab();
        addTab(mDataBoardTab.getTitleName(), mDataBoardTab);

        mConfigPanel = new ConfigPanel();
        addTab(mConfigPanel.getTitleName(), mConfigPanel);

        try {
            mFingerprintPanel = new FingerprintPanel(mConfigStore);
            addTab("指纹管理", mFingerprintPanel);
        } catch (Exception e) {
            Logger.debug("YAML 指纹面板初始化失败: %s", e.getMessage());
        }

        mDataPanel = new DataPanel();
        addTab("扫描记录", mDataPanel);
    }

    public DataBoardTab getDataBoardTab() { return mDataBoardTab; }
    public ConfigPanel getConfigPanel() { return mConfigPanel; }
    public FingerprintPanel getFingerprintPanel() { return mFingerprintPanel; }
    public DataPanel getDataPanel() { return mDataPanel; }
    public IconDataPanel getIconDataPanel() { return mIconDataPanel; }
    public PathCollectPanel getPathCollectPanel() { return mPathCollectPanel; }

    public void initIconDataPanel(IconHashStore store) {
        mIconDataPanel = new IconDataPanel(store, mConfigStore);
        addTab("图标数据", mIconDataPanel);
        mIconDataPanel.loadIcons();
    }

    public void initPathCollectPanel(PathStore pathStore) {
        mPathCollectPanel = new PathCollectPanel(pathStore);
        addTab("路径收集", mPathCollectPanel);
    }

    public static void main(String[] args) {
        Logger.init(true, System.out, System.err);
        Config.init(null);
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            Logger.error(e.getMessage());
        }
        YamlConfigStore store = new YamlConfigStore(Config.get("yaml_config_path"));
        JFrame frame = new JFrame(Constants.PLUGIN_NAME + " v" + Constants.PLUGIN_VERSION);
        frame.setSize(1400, 700);
        FingerScan fingerScan = new FingerScan(store);
        fingerScan.getDataBoardTab().testInit();
        frame.setContentPane(fingerScan);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
