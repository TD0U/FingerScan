package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.config.YamlConfigStore;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * YAML 规则引擎：RuleIndex 快照 + 字面量预过滤（失败开放）+ Pattern.find 最终裁决。
 * 正文不截断。单条规则 find 支持超时（TimeoutCharSequence），避免灾难性回溯钉死分析线程。
 */
public class YamlRuleEngine implements RuleEngine {

    private final YamlConfigStore configStore;
    private final RuleIndex ruleIndex = new RuleIndex();
    private final Runnable changeListener = this::rebuildIndex;

    private final AtomicLong matchTimeoutTotal = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> timeoutByRule = new ConcurrentHashMap<>();
    private final AtomicLong timeoutLogWindowStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong timeoutCountAtWindowStart = new AtomicLong(0);

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
        List<Map<String, Object>> rules = configStore != null
                ? configStore.getEnabledRules()
                : Collections.emptyList();
        ruleIndex.rebuild(rules, minLen);
    }

    public RuleIndex getRuleIndex() {
        return ruleIndex;
    }

    public long getMatchTimeoutTotal() {
        return matchTimeoutTotal.get();
    }

    public void resetMatchTimeoutTotal() {
        matchTimeoutTotal.set(0);
        timeoutByRule.clear();
        timeoutCountAtWindowStart.set(0);
        timeoutLogWindowStartMs.set(System.currentTimeMillis());
    }

    /** 返回超时次数最多的规则快照（最多 limit 条），用于日志/UI */
    public List<Map.Entry<String, Long>> topTimeoutRules(int limit) {
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : timeoutByRule.entrySet()) {
            list.add(Map.entry(e.getKey(), e.getValue().get()));
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
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
        long timeoutMs = 100L;
        try {
            prefilterOn = Config.getBoolean(Config.KEY_MATCH_LITERAL_PREFILTER);
            timeoutMs = Config.getInt(Config.KEY_MATCH_TIMEOUT_MS);
        } catch (Exception ignored) {
        }
        if (timeoutMs < 0) {
            timeoutMs = 0;
        }

        // 预过滤 + lookahead-AND 优化都需要 folded 文本
        String textFolded = responseStr.toLowerCase(Locale.ROOT);

        List<MatchResult> results = new ArrayList<>();
        for (CompiledRule rule : snap.rules) {
            try {
                if (!matchStatusCode(statusCode, rule.getState())) {
                    continue;
                }
                if (!matchPath(requestPath, rule.getUrl())) {
                    continue;
                }
                // (?=.*a)(?=.*b) → 纯 contains AND，不进 Pattern.find
                if (rule.isLookaheadAndOptimized()) {
                    if (LookaheadAndOptimizer.matchesAllLiterals(
                            textFolded, rule.getLookaheadAndLiterals())) {
                        results.add(MatchResult.fromYamlRule(rule.getName(), rule.getRegex()));
                    }
                    continue;
                }
                if (prefilterOn && !rule.isPrefilterDisabled()) {
                    if (!prefilterAllows(rule, textFolded)) {
                        continue;
                    }
                }
                if (findWithTimeout(rule, responseStr, timeoutMs)) {
                    results.add(MatchResult.fromYamlRule(rule.getName(), rule.getRegex()));
                }
            } catch (MatchTimeoutException te) {
                onRuleTimeout(te.getRuleName(), te.getTimeoutMs(), requestPath);
            } catch (Exception e) {
                Logger.debug("YamlRuleEngine match error: %s", e.getMessage());
            }
        }
        return results;
    }

    /**
     * @return true 若 find 命中；超时抛 {@link MatchTimeoutException}
     */
    boolean findWithTimeout(CompiledRule rule, String responseStr, long timeoutMs) {
        if (rule.isLookaheadAndOptimized()) {
            String folded = responseStr.toLowerCase(Locale.ROOT);
            return LookaheadAndOptimizer.matchesAllLiterals(
                    folded, rule.getLookaheadAndLiterals());
        }
        if (timeoutMs <= 0) {
            Matcher matcher = rule.getPattern().matcher(responseStr);
            return matcher.find();
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        CharSequence cs = new TimeoutCharSequence(responseStr, deadline, rule.getName(), timeoutMs);
        Matcher matcher = rule.getPattern().matcher(cs);
        return matcher.find();
    }

    private void onRuleTimeout(String ruleName, long timeoutMs, String requestPath) {
        long total = matchTimeoutTotal.incrementAndGet();
        String name = ruleName != null ? ruleName : "?";
        timeoutByRule.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();

        long now = System.currentTimeMillis();
        long windowStart = timeoutLogWindowStartMs.get();
        long since = now - windowStart;
        long delta = total - timeoutCountAtWindowStart.get();
        // 每条超时先 debug；窗口汇总 error（Logger 无 warn）
        Logger.debug("regex match timeout: rule=%s limitMs=%d path=%s totalTimeouts=%d",
                name, timeoutMs, requestPath != null ? requestPath : "", total);

        if (since > 30_000L || delta >= 20L) {
            if (timeoutLogWindowStartMs.compareAndSet(windowStart, now)) {
                timeoutCountAtWindowStart.set(total);
                StringBuilder top = new StringBuilder();
                for (Map.Entry<String, Long> e : topTimeoutRules(5)) {
                    if (top.length() > 0) {
                        top.append(", ");
                    }
                    top.append(e.getKey()).append('=').append(e.getValue());
                }
                Logger.error("regex match timeouts: +%d in %dms (total=%d). top: %s",
                        delta, since, total, top);
            }
        }
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
