package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.config.YamlConfigLoader;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 规则引擎
 * 基于 YAML 配置的正则匹配指纹识别，替代原来的 FingerprintScanner 中分散的匹配逻辑
 *
 * 改进:
 * - Pattern 缓存避免重复编译
 * - 通过 YamlConfigLoader 使用带缓存的配置读取
 * - 线程安全的 Pattern 缓存
 */
public class YamlRuleEngine implements RuleEngine {

    private final YamlConfigLoader configLoader;
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    public YamlRuleEngine(YamlConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public List<MatchResult> match(byte[] request, byte[] response, String requestPath) {
        if (response == null || response.length == 0) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> rules = configLoader.getEnabledRules();
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }

        // 全局响应过滤器：匹配到过滤规则则跳过指纹识别
        if (configLoader.isResponseFiltered(response)) {
            return Collections.emptyList();
        }

        String responseStr = new String(response);
        int statusCode = parseStatusCode(responseStr);
        List<MatchResult> results = new ArrayList<>();

        for (Map<String, Object> rule : rules) {
            try {
                String regex = getStringField(rule, "re");
                String name = getStringField(rule, "name");

                if (regex == null || regex.isEmpty() || name == null) {
                    continue;
                }

                if (!matchStatusCode(statusCode, getStringField(rule, "state"))) {
                    continue;
                }

                // 路径比对：url 为 null、空或 "/" 时不限制路径，否则要求请求路径匹配
                if (!matchPath(requestPath, getStringField(rule, "url"))) {
                    continue;
                }

                if (matchesRegex(responseStr, regex)) {
                    results.add(MatchResult.fromYamlRule(name, regex));
                }
            } catch (Exception e) {
                Logger.debug("YamlRuleEngine match error: %s", e.getMessage());
            }
        }

        return results;
    }

    @Override
    public List<String> getScanPaths() {
        return configLoader.getScanPaths();
    }

    /**
     * 正则匹配（带 Pattern 缓存）
     */
    private boolean matchesRegex(String content, String regex) {
        try {
            Pattern pattern = patternCache.computeIfAbsent(regex, r -> {
                try {
                    return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                } catch (Exception e) {
                    Logger.debug("Invalid regex: %s, error: %s", r, e.getMessage());
                    return null;
                }
            });

            if (pattern == null) {
                return false;
            }

            Matcher matcher = pattern.matcher(content);
            return matcher.find();
        } catch (Exception e) {
            Logger.debug("Regex match error: %s", e.getMessage());
            return false;
        }
    }

    private String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 从响应首行解析 HTTP 状态码
     */
    private int parseStatusCode(String responseStr) {
        int lineEnd = responseStr.indexOf('\r');
        if (lineEnd < 0) lineEnd = responseStr.indexOf('\n');
        if (lineEnd < 0) return -1;
        String statusLine = responseStr.substring(0, lineEnd);
        // HTTP/1.1 200 OK
        String[] parts = statusLine.split("\\s+", 3);
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 校验请求路径是否匹配规则的 url 字段
     * url 为 null、空或 "/" 时忽略路径条件（始终匹配，纯内容正则）
     */
    private boolean matchPath(String requestPath, String ruleUrl) {
        if (ruleUrl == null || ruleUrl.isEmpty() || "/".equals(ruleUrl)) {
            return true;
        }
        if (requestPath == null || requestPath.isEmpty()) {
            return false;
        }
        // 清理请求路径中的查询参数和锚点
        String cleanPath = requestPath.split("\\?")[0].split("#")[0];
        // 兼容完整 URL 格式（如 http://host/path），提取纯路径部分
        if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
            try {
                cleanPath = new URL(cleanPath).getPath();
            } catch (Exception e) {
                return false;
            }
        }
        // 确保路径以 "/" 开头
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        return cleanPath.equals(ruleUrl);
    }

    /**
     * 校验响应状态码是否匹配规则的 state 字段
     * state 为 null、空、"0" 时忽略状态码条件（始终匹配）
     */
    private boolean matchStatusCode(int statusCode, String state) {
        if (state == null || state.isEmpty() || "0".equals(state)) {
            return true;
        }
        try {
            return statusCode == Integer.parseInt(state);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * 清空 Pattern 缓存
     */
    public void clearPatternCache() {
        patternCache.clear();
    }

    /**
     * 获取缓存大小
     */
    public int getPatternCacheSize() {
        return patternCache.size();
    }
}
