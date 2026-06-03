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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 配置加载器（带缓存 + SafeConstructor）
 *
 * 改进:
 * - 使用 SafeConstructor 防止反序列化攻击 (CVE-2022-1471)
 * - 基于文件修改时间的缓存，避免高频磁盘读取
 * - try-with-resources 保证资源释放
 * - 平台感知的默认路径
 */
public class YamlConfigLoader {

    private volatile String configFilePath;

    // 缓存
    private volatile Map<String, Object> cachedConfig;
    private volatile long cachedLastModified = -1;

    // 预处理缓存
    private volatile List<Map<String, Object>> cachedEnabledRules;
    private volatile List<String> cachedScanPaths;
    private volatile long cachedRulesLastModified = -1;

    // 过滤规则缓存
    private volatile List<Map<String, Object>> cachedEnabledFilters;
    private volatile long cachedFiltersLastModified = -1;
    private final ConcurrentHashMap<String, Pattern> filterPatternCache = new ConcurrentHashMap<>();

    public YamlConfigLoader(String configFilePath) {
        this.configFilePath = configFilePath != null ? configFilePath : getDefaultConfigPath();
    }

    /**
     * 更新配置文件路径并清除所有缓存
     */
    public void setConfigFilePath(String newPath) {
        if (newPath != null && !newPath.equals(this.configFilePath)) {
            this.configFilePath = newPath;
            invalidateCache();
            Logger.debug("YamlConfigLoader: config path updated to %s", newPath);
        }
    }

    /**
     * 获取平台感知的默认配置路径
     */
    private static String getDefaultConfigPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "/Applications/Burp Suite Professional.app/Contents/Resources/app/Config_yaml.yaml";
        } else if (os.contains("win")) {
            return System.getenv("APPDATA") + "\\BurpSuite\\Config_yaml.yaml";
        } else {
            return System.getProperty("user.home") + "/.BurpSuite/Config_yaml.yaml";
        }
    }

    /**
     * 读取 YAML 配置（带缓存）
     * 每次读取前检查 Config 中的路径是否变更
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readConfig() {
        // 检查路径是否被外部更新
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

        // 检查缓存是否有效
        if (cachedConfig != null && lastModified == cachedLastModified) {
            return cachedConfig;
        }

        // 重新加载
        try (InputStream is = new FileInputStream(file)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> data = yaml.load(is);
            cachedConfig = data != null ? data : new HashMap<>();
            cachedLastModified = lastModified;
            // 清除预处理缓存
            cachedEnabledRules = null;
            cachedScanPaths = null;
            return cachedConfig;
        } catch (Exception e) {
            Logger.error("Failed to read YAML config: %s", e.getMessage());
            return cachedConfig != null ? cachedConfig : new HashMap<>();
        }
    }

    /**
     * 写入 YAML 配置
     */
    public void writeConfig(Map<String, Object> data) {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            try (PrintWriter writer = new PrintWriter(new File(configFilePath))) {
                yaml.dump(data, writer);
            }
            // 清除缓存
            cachedConfig = null;
            cachedLastModified = -1;
            cachedEnabledRules = null;
            cachedScanPaths = null;
        } catch (Exception e) {
            Logger.error("Failed to write YAML config: %s", e.getMessage());
        }
    }

    /**
     * 获取所有指纹规则
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFingerprintRules() {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> loadList = (List<Map<String, Object>>) config.get("Load_List");
        return loadList != null ? loadList : new ArrayList<>();
    }

    /**
     * 获取已启用的指纹规则（带缓存）
     */
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
            if (loaded != null && Boolean.TRUE.equals(loaded)) {
                enabled.add(rule);
            }
        }

        cachedEnabledRules = enabled;
        cachedRulesLastModified = lastModified;
        return enabled;
    }

    /**
     * 获取扫描路径列表（仅从已启用的规则中提取去重的 url 字段）
     */
    public List<String> getScanPaths() {
        File file = new File(configFilePath);
        long lastModified = file.exists() ? file.lastModified() : 0;

        if (cachedScanPaths != null && lastModified == cachedRulesLastModified) {
            return cachedScanPaths;
        }

        Set<String> pathSet = new HashSet<>();
        List<Map<String, Object>> rules = getEnabledRules();

        for (Map<String, Object> rule : rules) {
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

    /**
     * 获取绕过路径列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getBypassList() {
        Map<String, Object> config = readConfig();
        List<String> bypassList = (List<String>) config.get("Bypass_List");
        return bypassList != null ? bypassList : new ArrayList<>();
    }

    /**
     * 基于请求路径生成递归扫描路径
     */
    public List<String> generateRecursivePaths(String requestPath) {
        List<String> paths = new ArrayList<>();
        paths.add("/");

        if (requestPath == null || requestPath.isEmpty() || "/".equals(requestPath)) {
            return paths;
        }

        // 清理路径
        String cleanPath = requestPath.split("\\?")[0].split("#")[0];
        String[] segments = cleanPath.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isEmpty()) {
                currentPath.append("/").append(segments[i]);

                // 最后一段如果有扩展名则跳过
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

    /**
     * 添加指纹规则
     */
    public void addRule(Map<String, Object> rule) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();

        // 安全检查 ID
        Object idObj = rule.get("id");
        if (idObj == null) {
            int maxId = loadList.stream()
                    .filter(r -> r.get("id") != null)
                    .mapToInt(r -> {
                        try { return Integer.parseInt(r.get("id").toString()); }
                        catch (NumberFormatException e) { return 0; }
                    })
                    .max()
                    .orElse(0);
            rule.put("id", maxId + 1);
        } else {
            // 检查重复 ID
            String ruleId = idObj.toString();
            boolean exists = loadList.stream()
                    .anyMatch(r -> r.get("id") != null && r.get("id").toString().equals(ruleId));
            if (exists) {
                return;
            }
        }

        loadList.add(rule);
        config.put("Load_List", loadList);
        writeConfig(config);
    }

    /**
     * 更新指纹规则
     */
    public void updateRule(Map<String, Object> updatedRule) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();
        List<Map<String, Object>> newList = new ArrayList<>();

        String targetId = updatedRule.get("id") != null ? updatedRule.get("id").toString() : null;
        if (targetId == null) return;

        for (Map<String, Object> rule : loadList) {
            if (rule.get("id") != null && rule.get("id").toString().equals(targetId)) {
                newList.add(updatedRule);
            } else {
                newList.add(rule);
            }
        }

        config.put("Load_List", newList);
        writeConfig(config);
    }

    /**
     * 删除指纹规则
     */
    public void removeRule(String ruleId) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();
        List<Map<String, Object>> newList = new ArrayList<>();

        for (Map<String, Object> rule : loadList) {
            if (rule.get("id") == null || !rule.get("id").toString().equals(ruleId)) {
                newList.add(rule);
            }
        }

        config.put("Load_List", newList);
        writeConfig(config);
    }

    // ============================================================
    // 全局过滤规则（Response Body Filter）
    // ============================================================

    /**
     * 获取所有过滤规则
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFilterRules() {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> list = (List<Map<String, Object>>) config.get("Filter_List");
        return list != null ? list : new ArrayList<>();
    }

    /**
     * 获取已启用的过滤规则（带缓存）
     */
    public List<Map<String, Object>> getEnabledFilterRules() {
        File file = new File(configFilePath);
        long lastModified = file.exists() ? file.lastModified() : 0;

        if (cachedEnabledFilters != null && lastModified == cachedFiltersLastModified) {
            return cachedEnabledFilters;
        }

        List<Map<String, Object>> rules = getFilterRules();
        List<Map<String, Object>> enabled = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Object loaded = rule.get("loaded");
            if (loaded == null || Boolean.TRUE.equals(loaded)) {
                enabled.add(rule);
            }
        }

        cachedEnabledFilters = enabled;
        cachedFiltersLastModified = lastModified;
        return enabled;
    }

    /**
     * 检查响应内容是否匹配任意已启用的过滤规则
     *
     * @param responseBytes 响应原始字节
     * @return true 表示应跳过指纹识别
     */
    public boolean isResponseFiltered(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return false;
        }

        List<Map<String, Object>> filters = getEnabledFilterRules();
        if (filters.isEmpty()) {
            return false;
        }

        String responseStr = new String(responseBytes);
        for (Map<String, Object> filter : filters) {
            Object regexObj = filter.get("re");
            if (regexObj == null) continue;
            String regex = regexObj.toString();
            if (regex.isEmpty()) continue;
            try {
                Pattern pattern = filterPatternCache.computeIfAbsent(regex, r -> {
                    try {
                        return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                    } catch (Exception e) {
                        Logger.debug("Invalid filter regex: %s", r);
                        return null;
                    }
                });
                if (pattern != null && pattern.matcher(responseStr).find()) {
                    return true;
                }
            } catch (Exception e) {
                Logger.debug("Filter regex match error: %s", e.getMessage());
            }
        }
        return false;
    }

    /**
     * 添加过滤规则
     */
    public void addFilterRule(Map<String, Object> rule) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> list = getFilterRules();
        list.add(rule);
        config.put("Filter_List", list);
        writeConfig(config);
        cachedEnabledFilters = null;
        filterPatternCache.clear();
    }

    /**
     * 更新过滤规则（按索引）
     */
    public void updateFilterRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> list = getFilterRules();
        if (index >= 0 && index < list.size()) {
            list.set(index, updatedRule);
            config.put("Filter_List", list);
            writeConfig(config);
            cachedEnabledFilters = null;
            filterPatternCache.clear();
        }
    }

    /**
     * 删除过滤规则（按索引）
     */
    public void removeFilterRule(int index) {
        Map<String, Object> config = readConfig();
        List<Map<String, Object>> list = getFilterRules();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            config.put("Filter_List", list);
            writeConfig(config);
            cachedEnabledFilters = null;
            filterPatternCache.clear();
        }
    }

    /**
     * 强制清除缓存
     */
    public void invalidateCache() {
        cachedConfig = null;
        cachedLastModified = -1;
        cachedEnabledRules = null;
        cachedScanPaths = null;
        cachedRulesLastModified = -1;
        cachedEnabledFilters = null;
        cachedFiltersLastModified = -1;
        filterPatternCache.clear();
    }

    public String getConfigFilePath() {
        return configFilePath;
    }
}
