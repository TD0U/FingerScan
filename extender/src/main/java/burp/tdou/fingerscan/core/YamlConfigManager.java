/**
 * YAML配置管理器
 * 负责指纹规则的读取、写入、更新、删除等操作
 * 基于RouteVulScanPro1的YamlUtil逻辑进行适配
 * 
 * @author OneScan Team
 * @version 2.0
 */
package burp.tdou.fingerscan.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.util.*;

public class YamlConfigManager {
    
    private String configFilePath;
    private static final String DEFAULT_CONFIG_FILE = "/Applications/Burp Suite Professional.app/Contents/Resources/app/Config_yaml.yaml";
    
    /**
     * 构造函数
     * @param configFilePath YAML配置文件路径
     */
    public YamlConfigManager(String configFilePath) {
        this.configFilePath = configFilePath != null ? configFilePath : DEFAULT_CONFIG_FILE;
    }
    
    /**
     * 读取YAML配置文件
     * @return 配置数据Map
     */
    public Map<String, Object> readYamlConfig() {
        File file = new File(configFilePath);
        Map<String, Object> data = null;
        try {
            if (!file.exists()) {
                // 如果文件不存在，创建默认配置
                createDefaultConfig();
            }
            InputStream inputStream = new FileInputStream(file);
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            data = yaml.load(inputStream);
            inputStream.close();
        } catch (IOException e) {
            System.err.println("读取YAML配置文件失败: " + e.getMessage());
            e.printStackTrace();
        }
        return data != null ? data : new HashMap<>();
    }
    
    /**
     * 写入YAML配置文件
     * @param data 配置数据
     */
    public void writeYamlConfig(Map<String, Object> data) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try {
            PrintWriter writer = new PrintWriter(new File(configFilePath));
            yaml.dump(data, writer);
            writer.close();
        } catch (FileNotFoundException e) {
            System.err.println("写入YAML配置文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取指纹规则列表
     * @return 指纹规则列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFingerprintRules() {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> loadList = (List<Map<String, Object>>) yamlData.get("Load_List");
        return loadList != null ? loadList : new ArrayList<>();
    }
    
    /**
     * 获取绕过路径列表
     * @return 绕过路径列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getBypassList() {
        Map<String, Object> yamlData = readYamlConfig();
        List<String> bypassList = (List<String>) yamlData.get("Bypass_List");
        return bypassList != null ? bypassList : new ArrayList<>();
    }
    
    /**
     * 添加指纹规则
     * @param rule 指纹规则
     */
    public void addFingerprintRule(Map<String, Object> rule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();
        rule.remove("id");
        loadList.add(rule);
        yamlData.put("Load_List", loadList);
        writeYamlConfig(yamlData);
    }

    /**
     * 更新指纹规则（按索引）
     */
    public void updateFingerprintRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();
        if (index >= 0 && index < loadList.size()) {
            updatedRule.remove("id");
            loadList.set(index, updatedRule);
            yamlData.put("Load_List", loadList);
            writeYamlConfig(yamlData);
        }
    }

    /**
     * 删除指纹规则（按索引）
     */
    public void removeFingerprintRule(int index) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> loadList = getFingerprintRules();
        if (index >= 0 && index < loadList.size()) {
            loadList.remove(index);
            yamlData.put("Load_List", loadList);
            writeYamlConfig(yamlData);
        }
    }

    // ============================================================
    // Icon Hash 规则 CRUD
    // ============================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getIconHashRules() {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = (List<Map<String, Object>>) yamlData.get("Icon_Hash_List");
        return list != null ? list : new ArrayList<>();
    }

    public void addIconHashRule(Map<String, Object> rule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getIconHashRules();
        list.add(rule);
        yamlData.put("Icon_Hash_List", list);
        writeYamlConfig(yamlData);
    }

    public void updateIconHashRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getIconHashRules();
        if (index >= 0 && index < list.size()) {
            list.set(index, updatedRule);
            yamlData.put("Icon_Hash_List", list);
            writeYamlConfig(yamlData);
        }
    }

    public void removeIconHashRule(int index) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getIconHashRules();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            yamlData.put("Icon_Hash_List", list);
            writeYamlConfig(yamlData);
        }
    }
    
    // ============================================================
    // 全局过滤规则 CRUD
    // ============================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFilterRules() {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = (List<Map<String, Object>>) yamlData.get("Filter_List");
        return list != null ? list : new ArrayList<>();
    }

    public void addFilterRule(Map<String, Object> rule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getFilterRules();
        list.add(rule);
        yamlData.put("Filter_List", list);
        writeYamlConfig(yamlData);
    }

    public void updateFilterRule(int index, Map<String, Object> updatedRule) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getFilterRules();
        if (index >= 0 && index < list.size()) {
            list.set(index, updatedRule);
            yamlData.put("Filter_List", list);
            writeYamlConfig(yamlData);
        }
    }

    public void removeFilterRule(int index) {
        Map<String, Object> yamlData = readYamlConfig();
        List<Map<String, Object>> list = getFilterRules();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            yamlData.put("Filter_List", list);
            writeYamlConfig(yamlData);
        }
    }

    /**
     * 从字符串解析YAML
     * @param yamlString YAML字符串
     * @return 解析后的数据
     */
    public Map<String, Object> parseYamlString(String yamlString) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try {
            return yaml.load(yamlString);
        } catch (Exception e) {
            System.err.println("解析YAML字符串失败: " + e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * 合并更新YAML配置
     * @param newYamlData 新的YAML数据
     */
    @SuppressWarnings("unchecked")
    public void mergeUpdateYamlConfig(Map<String, Object> newYamlData) {
        Map<String, Object> oldYamlData = readYamlConfig();
        List<Map<String, Object>> oldLoadList = (List<Map<String, Object>>) oldYamlData.get("Load_List");
        List<Map<String, Object>> newLoadList = (List<Map<String, Object>>) newYamlData.get("Load_List");
        
        if (oldLoadList == null) {
            oldLoadList = new ArrayList<>();
        }
        
        // 合并指纹规则列表
        for (Map<String, Object> newRule : newLoadList) {
            if (!isRuleInList(oldLoadList, newRule)) {
                newRule.remove("id");
                oldLoadList.add(newRule);
            }
        }
        
        // 合并绕过列表
        List<String> oldBypassList = (List<String>) oldYamlData.get("Bypass_List");
        List<String> newBypassList = (List<String>) newYamlData.get("Bypass_List");
        
        if (oldBypassList == null) {
            oldBypassList = new ArrayList<>();
        }
        
        if (newBypassList != null) {
            for (String bypass : newBypassList) {
                if (!oldBypassList.contains(bypass)) {
                    oldBypassList.add(bypass);
                }
            }
        }
        
        // 保存合并后的配置
        Map<String, Object> mergedData = new HashMap<>();
        mergedData.put("Load_List", oldLoadList);
        mergedData.put("Bypass_List", oldBypassList);
        writeYamlConfig(mergedData);
    }
    
    /**
     * 检查规则是否已存在于列表中
     * @param ruleList 规则列表
     * @param targetRule 目标规则
     * @return 是否存在
     */
    private boolean isRuleInList(List<Map<String, Object>> ruleList, Map<String, Object> targetRule) {
        return ruleList.stream().anyMatch(rule -> isRuleEqual(rule, targetRule));
    }
    
    /**
     * 比较两个规则是否相等（忽略loaded、id、type字段）
     * @param rule1 规则1
     * @param rule2 规则2
     * @return 是否相等
     */
    private boolean isRuleEqual(Map<String, Object> rule1, Map<String, Object> rule2) {
        // 使用Java 8兼容的方式创建Set
        Set<String> keyFields = new HashSet<>();
        keyFields.add("name");
        keyFields.add("method");
        keyFields.add("path");
        
        for (String key : keyFields) {
            Object value1 = rule1.get(key);
            Object value2 = rule2.get(key);
            if (!Objects.equals(value1, value2)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 创建默认配置文件
     */
    private void createDefaultConfig() {
        Map<String, Object> defaultConfig = new HashMap<>();
        defaultConfig.put("Load_List", new ArrayList<>());
        defaultConfig.put("Bypass_List", new ArrayList<>());
        writeYamlConfig(defaultConfig);
    }
    
    /**
     * 获取配置文件路径
     * @return 配置文件路径
     */
    public String getConfigFilePath() {
        return configFilePath;
    }
    
    /**
     * 设置配置文件路径
     * @param configFilePath 新的配置文件路径
     */
    public void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
    }
    
    /**
     * 基于请求路径生成递归扫描路径列表
     * 从请求路径中提取各级目录，用于递归扫描
     * @param requestPath 原始请求路径
     * @return 递归扫描路径列表
     */
    public List<String> generateRecursivePaths(String requestPath) {
        List<String> recursivePaths = new ArrayList<>();
        
        try {
            System.out.println("[YamlConfigManager] 开始生成递归路径，原始请求路径: " + requestPath);
            
            // 添加根路径
            recursivePaths.add("/");
            
            if (requestPath != null && !requestPath.isEmpty() && !requestPath.equals("/")) {
                // 清理路径，移除查询参数和锚点
                String cleanPath = requestPath.split("\\?")[0].split("#")[0];
                
                // 分割路径段
                String[] segments = cleanPath.split("/");
                StringBuilder currentPath = new StringBuilder();
                
                for (int i = 1; i < segments.length; i++) {
                    if (!segments[i].isEmpty()) {
                        currentPath.append("/").append(segments[i]);
                        
                        // 如果是最后一个段且包含文件扩展名，则跳过（不扫描文件路径）
                        if (i == segments.length - 1 && segments[i].contains(".")) {
                            break;
                        }
                        
                        String path = currentPath.toString();
                        if (!recursivePaths.contains(path)) {
                            recursivePaths.add(path);
                            System.out.println("[YamlConfigManager] 添加递归路径: " + path);
                        }
                    }
                }
            }
            
            System.out.println("[YamlConfigManager] 递归路径生成完成，总数: " + recursivePaths.size());
            System.out.println("[YamlConfigManager] 递归路径列表: " + recursivePaths);
            
            return recursivePaths;
            
        } catch (Exception e) {
            System.err.println("[YamlConfigManager] 生成递归路径时发生异常: " + e.getMessage());
            e.printStackTrace();
            // 异常情况下返回默认根路径
            List<String> defaultPaths = new ArrayList<>();
            defaultPaths.add("/");
            return defaultPaths;
        }
    }

    /**
     * 获取指纹规则中的扫描路径列表
     * 从所有指纹规则中提取url字段，用于被动扫描
     * @return 扫描路径列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getScanPaths() {
        Set<String> scanPathsSet = new HashSet<>();
        
        try {
            List<Map<String, Object>> fingerprintRules = getFingerprintRules();
            System.out.println("[YamlConfigManager] 开始提取扫描路径，指纹规则数量: " + fingerprintRules.size());
            
            for (Map<String, Object> rule : fingerprintRules) {
                // 从url字段提取路径，因为Config_yaml.yaml中使用的是url字段
                Object urlObj = rule.get("url");
                if (urlObj != null) {
                    String url = urlObj.toString().trim();
                    if (!url.isEmpty()) {
                        boolean added = scanPathsSet.add(url);
                        if (added) {
                            System.out.println("[YamlConfigManager] 添加扫描路径: " + url);
                        }
                    }
                }
            }
            
            // 转换为List
            List<String> scanPaths = new ArrayList<>(scanPathsSet);
            
            // 如果没有配置路径，返回默认根路径
            if (scanPaths.isEmpty()) {
                scanPaths.add("/");
                System.out.println("[YamlConfigManager] 没有找到配置路径，使用默认根路径: /");
            }
            
            System.out.println("[YamlConfigManager] 扫描路径提取完成，总数: " + scanPaths.size());
            System.out.println("[YamlConfigManager] 扫描路径列表: " + scanPaths);
            
            return scanPaths;
            
        } catch (Exception e) {
            System.err.println("[YamlConfigManager] 提取扫描路径时发生异常: " + e.getMessage());
            e.printStackTrace();
            // 异常情况下返回默认路径
            List<String> defaultPaths = new ArrayList<>();
            defaultPaths.add("/");
            return defaultPaths;
        }
    }
    
    /**
     * 获取忽略的文件扩展名列表
     * 用于被动监听时过滤不需要扫描的文件类型
     * @return 忽略扩展名列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getIgnoreExtensions() {
        Map<String, Object> yamlData = readYamlConfig();
        Map<String, Object> filterConfig = (Map<String, Object>) yamlData.get("filter_config");
        
        if (filterConfig != null) {
            List<String> ignoreExtensions = (List<String>) filterConfig.get("ignore_extensions");
            return ignoreExtensions != null ? ignoreExtensions : new ArrayList<>();
        }
        
        // 返回默认忽略扩展名
        List<String> defaultIgnoreExtensions = new ArrayList<>();
        defaultIgnoreExtensions.add("css");
        defaultIgnoreExtensions.add("js");
        defaultIgnoreExtensions.add("png");
        defaultIgnoreExtensions.add("jpg");
        defaultIgnoreExtensions.add("jpeg");
        defaultIgnoreExtensions.add("gif");
        defaultIgnoreExtensions.add("ico");
        defaultIgnoreExtensions.add("svg");
        return defaultIgnoreExtensions;
    }
}