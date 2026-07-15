package burp.tdou.fingerscan.config;

import burp.tdou.common.log.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified YAML config data source with mtime cache, index-based CRUD,
 * mergeUpdate that preserves Icon_Hash_List, and change listeners.
 */
public class YamlConfigStore {

    private volatile String configFilePath;
    private volatile Map<String, Object> cachedConfig;
    private volatile long cachedLastModified = -1;
    private volatile List<Map<String, Object>> cachedEnabledRules;
    private volatile List<String> cachedScanPaths;
    private volatile long cachedRulesLastModified = -1;

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public YamlConfigStore(String configFilePath) {
        this.configFilePath = configFilePath != null ? configFilePath : getDefaultConfigPath();
    }

    public void setConfigFilePath(String newPath) {
        if (newPath != null && !newPath.equals(this.configFilePath)) {
            this.configFilePath = newPath;
            invalidateCache();
            notifyListeners();
            Logger.debug("YamlConfigStore: config path updated to %s", newPath);
        }
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    private static String getDefaultConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "/Applications/Burp Suite Professional.app/Contents/Resources/app/Config_yaml.yaml";
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? appData : "") + "\\BurpSuite\\Config_yaml.yaml";
        } else {
            return System.getProperty("user.home") + "/.BurpSuite/Config_yaml.yaml";
        }
    }

    public void invalidateCache() {
        cachedConfig = null;
        cachedLastModified = -1;
        cachedEnabledRules = null;
        cachedScanPaths = null;
        cachedRulesLastModified = -1;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Logger.error("YamlConfigStore listener error: %s", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readConfig() {
        String currentPath = burp.tdou.fingerscan.common.Config.get("yaml_config_path");
        if (currentPath != null && !currentPath.isEmpty() && !currentPath.equals(configFilePath)) {
            setConfigFilePath(currentPath);
        }

        File file = new File(configFilePath);
        if (!file.exists()) {
            Logger.debug("YAML config file not found: %s", configFilePath);
            return new HashMap<>();
        }

        long lastModified = file.lastModified();
        if (cachedConfig != null && lastModified == cachedLastModified) {
            return cachedConfig;
        }

        try (InputStream is = new FileInputStream(file)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> data = yaml.load(is);
            cachedConfig = data != null ? data : new HashMap<>();
            cachedLastModified = lastModified;
            cachedEnabledRules = null;
            cachedScanPaths = null;
            return cachedConfig;
        } catch (Exception e) {
            Logger.error("Failed to read YAML config: %s", e.getMessage());
            return cachedConfig != null ? cachedConfig : new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFingerprintRules() {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> loadList = (List<Map<String, Object>>) config.get("Load_List");
        return loadList != null ? loadList : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEnabledRules() {
        File file = new File(configFilePath);
        long lastModified = file.exists() ? file.lastModified() : 0;
        if (cachedEnabledRules != null && lastModified == cachedRulesLastModified) {
            return cachedEnabledRules;
        }

        List<Map<String, Object>> rules = getFingerprintRules();
        List<Map<String, Object>> enabled = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Object loaded = rule.get("loaded");
            if (Boolean.TRUE.equals(loaded)) {
                enabled.add(rule);
            }
        }
        cachedEnabledRules = enabled;
        cachedRulesLastModified = lastModified;
        return enabled;
    }

    public List<String> getScanPaths() {
        File file = new File(configFilePath);
        long lastModified = file.exists() ? file.lastModified() : 0;
        if (cachedScanPaths != null && lastModified == cachedRulesLastModified) {
            return cachedScanPaths;
        }

        Set<String> pathSet = new HashSet<>();
        for (Map<String, Object> rule : getEnabledRules()) {
            Object urlObj = rule.get("url");
            if (urlObj != null) {
                String url = urlObj.toString().trim();
                if (!url.isEmpty()) {
                    pathSet.add(url);
                }
            }
        }
        cachedScanPaths = new ArrayList<>(pathSet);
        return cachedScanPaths;
    }

    @SuppressWarnings("unchecked")
    public List<String> getBypassList() {
        Map<String, Object> config = readConfig();
        List<String> bypassList = (List<String>) config.get("Bypass_List");
        return bypassList != null ? bypassList : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getIconHashRules() {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> list = (List<Map<String, Object>>) config.get("Icon_Hash_List");
        return list != null ? list : new ArrayList<>();
    }

    public List<String> generateRecursivePaths(String requestPath) {
        List<String> paths = new ArrayList<>();
        paths.add("/");
        if (requestPath == null || requestPath.isEmpty() || "/".equals(requestPath)) {
            return paths;
        }
        String cleanPath = requestPath.split("\\?")[0].split("#")[0];
        String[] segments = cleanPath.split("/");
        StringBuilder currentPath = new StringBuilder();
        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isEmpty()) {
                currentPath.append("/").append(segments[i]);
                if (i == segments.length - 1 && segments[i].contains(".")) {
                    break;
                }
                String path = currentPath.toString();
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    public void writeConfig(Map<String, Object> data) {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            try (PrintWriter writer = new PrintWriter(new File(configFilePath))) {
                yaml.dump(data, writer);
            }
            invalidateCache();
            notifyListeners();
        } catch (Exception e) {
            Logger.error("Failed to write YAML config: %s", e.getMessage());
            // do not notify — file not updated
        }
    }

    @SuppressWarnings("unchecked")
    public void addRule(Map<String, Object> rule) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
        rule.remove("id");
        loadList.add(rule);
        config.put("Load_List", loadList);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void updateRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
        if (index < 0 || index >= loadList.size()) {
            return;
        }
        updatedRule.remove("id");
        loadList.set(index, updatedRule);
        config.put("Load_List", loadList);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void removeRule(int index) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> loadList = new ArrayList<>(getFingerprintRules());
        if (index < 0 || index >= loadList.size()) {
            return;
        }
        loadList.remove(index);
        config.put("Load_List", loadList);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void addIconHashRule(Map<String, Object> rule) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
        list.add(rule);
        config.put("Icon_Hash_List", list);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void updateIconHashRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
        if (index < 0 || index >= list.size()) {
            return;
        }
        list.set(index, updatedRule);
        config.put("Icon_Hash_List", list);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void removeIconHashRule(int index) {
        Map<String, Object> config = new HashMap<>(readConfig());
        List<Map<String, Object>> list = new ArrayList<>(getIconHashRules());
        if (index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        config.put("Icon_Hash_List", list);
        writeConfig(config);
    }

    @SuppressWarnings("unchecked")
    public void mergeUpdate(Map<String, Object> newYamlData) {
        if (newYamlData == null) {
            return;
        }
        // Start from full existing config so Icon_Hash_List and unknown keys survive
        Map<String, Object> merged = new HashMap<>(readConfig());

        List<Map<String, Object>> oldLoadList = getFingerprintRules();
        List<Map<String, Object>> newLoadList = (List<Map<String, Object>>) newYamlData.get("Load_List");
        if (newLoadList != null) {
            List<Map<String, Object>> combined = new ArrayList<>(oldLoadList);
            for (Map<String, Object> newRule : newLoadList) {
                if (!isRuleInList(combined, newRule)) {
                    Map<String, Object> copy = new HashMap<>(newRule);
                    copy.remove("id");
                    combined.add(copy);
                }
            }
            merged.put("Load_List", combined);
        }

        List<String> oldBypass = getBypassList();
        List<String> newBypass = (List<String>) newYamlData.get("Bypass_List");
        if (newBypass != null) {
            List<String> combinedBypass = new ArrayList<>(oldBypass);
            for (String bypass : newBypass) {
                if (!combinedBypass.contains(bypass)) {
                    combinedBypass.add(bypass);
                }
            }
            merged.put("Bypass_List", combinedBypass);
        }

        List<Map<String, Object>> oldIcons = getIconHashRules();
        List<Map<String, Object>> newIcons = (List<Map<String, Object>>) newYamlData.get("Icon_Hash_List");
        if (newIcons != null) {
            List<Map<String, Object>> combinedIcons = new ArrayList<>(oldIcons);
            for (Map<String, Object> icon : newIcons) {
                if (!isIconInList(combinedIcons, icon)) {
                    combinedIcons.add(new HashMap<>(icon));
                }
            }
            merged.put("Icon_Hash_List", combinedIcons);
        }

        writeConfig(merged);
    }

    private boolean isRuleInList(List<Map<String, Object>> ruleList, Map<String, Object> targetRule) {
        return ruleList.stream().anyMatch(rule ->
                Objects.equals(rule.get("name"), targetRule.get("name"))
                        && Objects.equals(rule.get("method"), targetRule.get("method"))
                        && Objects.equals(rule.get("path"), targetRule.get("path")));
    }

    private boolean isIconInList(List<Map<String, Object>> list, Map<String, Object> target) {
        return list.stream().anyMatch(rule ->
                Objects.equals(rule.get("name"), target.get("name"))
                        && Objects.equals(rule.get("murmur_hash"), target.get("murmur_hash"))
                        && Objects.equals(rule.get("md5"), target.get("md5")));
    }
}
