package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则编译缓存：volatile 发布不可变快照；重建期间读者仍用旧快照。
 */
public final class RuleIndex {

    public static final int PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    public static final class Snapshot {
        public final List<CompiledRule> rules;

        Snapshot(List<CompiledRule> rules) {
            this.rules = rules != null
                    ? Collections.unmodifiableList(rules)
                    : Collections.emptyList();
        }

        public static Snapshot empty() {
            return new Snapshot(Collections.emptyList());
        }
    }

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile long lastRebuildNanos;

    public Snapshot current() {
        return snapshot;
    }

    public long lastRebuildNanos() {
        return lastRebuildNanos;
    }

    /**
     * 全量重建并发布。
     *
     * @param enabledRules YAML Load_List 中启用的规则 Map
     * @param minLiteralLen 字面量最短长度
     */
    public void rebuild(List<Map<String, Object>> enabledRules, int minLiteralLen) {
        long t0 = System.nanoTime();
        List<CompiledRule> compiled = new ArrayList<>();
        if (enabledRules != null) {
            for (Map<String, Object> rule : enabledRules) {
                if (rule == null) {
                    continue;
                }
                CompiledRule cr = compileOne(rule, minLiteralLen);
                if (cr != null) {
                    compiled.add(cr);
                }
            }
        }
        snapshot = new Snapshot(compiled);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        lastRebuildNanos = System.nanoTime() - t0;
        if (elapsedMs >= 200) {
            Logger.error("RuleIndex rebuild took %dms (%d rules)", elapsedMs, compiled.size());
        } else if (elapsedMs >= 50) {
            Logger.debug("RuleIndex rebuild took %dms (%d rules)", elapsedMs, compiled.size());
        }
    }

    private static CompiledRule compileOne(Map<String, Object> rule, int minLiteralLen) {
        String name = str(rule.get("name"));
        String regex = str(rule.get("re"));
        if (name == null || name.isEmpty() || regex == null || regex.isEmpty()) {
            return null;
        }
        String state = str(rule.get("state"));
        String url = str(rule.get("url"));

        // 字符串匹配：match=keyword（缺省/其它值均为 regex，兼容旧配置）
        String matchField = str(rule.get("match"));
        if (matchField != null && "keyword".equalsIgnoreCase(matchField.trim())) {
            KeywordSpec ks = KeywordSpec.parse(regex);
            if (ks == null) {
                Logger.debug("RuleIndex: invalid keyword rule %s, skip", name);
                return null;
            }
            return CompiledRule.keyword(name, regex, state, url, ks);
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, PATTERN_FLAGS);
        } catch (Exception e) {
            Logger.debug("RuleIndex: invalid regex for %s: %s", name, e.getMessage());
            return null;
        }

        // (?=.*a)(?=.*b) → contains AND，彻底避开 find 回溯
        List<String> lookaheadAnd = LookaheadAndOptimizer.tryExtractAndLiterals(regex);

        LiteralExtractor.ExtractResult er = LiteralExtractor.extract(regex, minLiteralLen);
        boolean disabled = er.disabled;
        List<String> must = er.must;
        List<List<String>> orGroups = er.orGroups;

        // 预过滤用小写字面量缓存（ASCII）；比较时 text 也 ROOT lower
        List<String> mustFolded = foldList(must);
        List<List<String>> orFolded = new ArrayList<>();
        for (List<String> g : orGroups) {
            orFolded.add(foldList(g));
        }

        return new CompiledRule(name, regex, pattern, state, url,
                disabled, mustFolded, orFolded, lookaheadAnd);
    }

    private static List<String> foldList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(s == null ? "" : s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
