package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.config.YamlConfigStore;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * YAML 规则引擎：RuleIndex 快照 + 字面量预过滤（失败开放）+ Pattern.find 最终裁决。
 * 正文不截断。
 */
public class YamlRuleEngine implements RuleEngine {

    private final YamlConfigStore configStore;
    private final RuleIndex ruleIndex = new RuleIndex();
    private final Runnable changeListener = this::rebuildIndex;

    public YamlRuleEngine(YamlConfigStore configStore) {
        this.configStore = configStore;
        rebuildIndex();
        if (configStore != null) {
            configStore.addChangeListener(changeListener);
        }
    }

    /** 测试或手动刷新 */
    public void rebuildIndex() {
        int minLen = 3;
        try {
            minLen = Config.getInt(Config.KEY_MATCH_LITERAL_MIN_LENGTH);
        } catch (Exception ignored) {
            // Config 未 init 时用默认
        }
        if (minLen < 1) {
            minLen = 3;
        }
        List<java.util.Map<String, Object>> rules = configStore != null
                ? configStore.getEnabledRules()
                : Collections.emptyList();
        ruleIndex.rebuild(rules, minLen);
    }

    public RuleIndex getRuleIndex() {
        return ruleIndex;
    }

    /** 插件卸载时移除监听 */
    public void dispose() {
        if (configStore != null) {
            configStore.removeChangeListener(changeListener);
        }
    }

    @Override
    public List<MatchResult> match(byte[] request, byte[] response, String requestPath) {
        // 空 body 双保险（pipeline 应已短路）
        if (response == null || response.length == 0) {
            return Collections.emptyList();
        }

        RuleIndex.Snapshot snap = ruleIndex.current();
        if (snap.rules.isEmpty()) {
            return Collections.emptyList();
        }

        String responseStr = new String(response);
        int statusCode = parseStatusCode(responseStr);
        boolean prefilterOn = true;
        try {
            prefilterOn = Config.getBoolean(Config.KEY_MATCH_LITERAL_PREFILTER);
        } catch (Exception ignored) {
        }

        String textFolded = null;
        if (prefilterOn) {
            textFolded = responseStr.toLowerCase(Locale.ROOT);
        }

        List<MatchResult> results = new ArrayList<>();
        for (CompiledRule rule : snap.rules) {
            try {
                if (!matchStatusCode(statusCode, rule.getState())) {
                    continue;
                }
                if (!matchPath(requestPath, rule.getUrl())) {
                    continue;
                }
                if (prefilterOn && !rule.isPrefilterDisabled()) {
                    if (!prefilterAllows(rule, textFolded)) {
                        continue;
                    }
                }
                Matcher matcher = rule.getPattern().matcher(responseStr);
                if (matcher.find()) {
                    results.add(MatchResult.fromYamlRule(rule.getName(), rule.getRegex()));
                }
            } catch (Exception e) {
                Logger.debug("YamlRuleEngine match error: %s", e.getMessage());
            }
        }
        return results;
    }

    /**
     * must 全在 textFolded 中；若有 orGroups，至少一组全在。
     * 字面量在 RuleIndex 编译时已 ROOT lower。
     */
    static boolean prefilterAllows(CompiledRule rule, String textFolded) {
        if (textFolded == null) {
            return true;
        }
        for (String lit : rule.getMustLiterals()) {
            if (lit != null && !lit.isEmpty() && !textFolded.contains(lit)) {
                return false;
            }
        }
        List<List<String>> orGroups = rule.getOrGroups();
        if (orGroups != null && !orGroups.isEmpty()) {
            boolean any = false;
            for (List<String> g : orGroups) {
                boolean all = true;
                for (String lit : g) {
                    if (lit != null && !lit.isEmpty() && !textFolded.contains(lit)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> getScanPaths() {
        return configStore != null ? configStore.getScanPaths() : Collections.emptyList();
    }

    private int parseStatusCode(String responseStr) {
        int lineEnd = responseStr.indexOf('\r');
        if (lineEnd < 0) {
            lineEnd = responseStr.indexOf('\n');
        }
        if (lineEnd < 0) {
            return -1;
        }
        String statusLine = responseStr.substring(0, lineEnd);
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

    private boolean matchPath(String requestPath, String ruleUrl) {
        if (!Config.getBoolean(Config.KEY_FINGERPRINT_URL_MATCH)) {
            return true;
        }
        if (ruleUrl == null || ruleUrl.isEmpty() || "/".equals(ruleUrl)) {
            return true;
        }
        if (requestPath == null || requestPath.isEmpty()) {
            return false;
        }
        String cleanPath = requestPath.split("\\?")[0].split("#")[0];
        if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
            try {
                cleanPath = new URL(cleanPath).getPath();
            } catch (Exception e) {
                return false;
            }
        }
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        return cleanPath.equals(ruleUrl);
    }
}
