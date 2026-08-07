package burp.tdou.fingerscan.core.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 字符串匹配规则：单关键字 / AND(&amp;&amp;) / OR(||)。
 * 字面量按 {@link Locale#ROOT} 小写存储；匹配对已 lower 的正文做 contains。
 */
public final class KeywordSpec {

    public enum Op {
        SINGLE, AND, OR
    }

    public final Op op;
    /** 非空，已 trim + ROOT lower */
    public final List<String> literals;

    private KeywordSpec(Op op, List<String> literals) {
        this.op = op;
        this.literals = Collections.unmodifiableList(literals);
    }

    /**
     * @param raw 用户输入的关键字表达式
     * @return null 若非法（空、混用 &amp;&amp;/||、空段）
     */
    public static KeywordSpec parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean hasAnd = s.contains("&&");
        boolean hasOr = s.contains("||");
        if (hasAnd && hasOr) {
            return null;
        }
        if (hasAnd) {
            List<String> parts = splitLiterals(s, "&&");
            if (parts == null) {
                return null;
            }
            if (parts.size() == 1) {
                return new KeywordSpec(Op.SINGLE, parts);
            }
            return new KeywordSpec(Op.AND, parts);
        }
        if (hasOr) {
            List<String> parts = splitLiterals(s, "||");
            if (parts == null) {
                return null;
            }
            if (parts.size() == 1) {
                return new KeywordSpec(Op.SINGLE, parts);
            }
            return new KeywordSpec(Op.OR, parts);
        }
        return new KeywordSpec(Op.SINGLE,
                Collections.singletonList(s.toLowerCase(Locale.ROOT)));
    }

    private static List<String> splitLiterals(String s, String sep) {
        String[] segs = s.split(java.util.regex.Pattern.quote(sep), -1);
        List<String> out = new ArrayList<>(segs.length);
        for (String seg : segs) {
            String t = seg.trim();
            if (t.isEmpty()) {
                return null;
            }
            out.add(t.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            return null;
        }
        return out;
    }

    public boolean matches(String textFolded) {
        if (textFolded == null || literals.isEmpty()) {
            return false;
        }
        if (op == Op.OR) {
            for (String lit : literals) {
                if (lit != null && !lit.isEmpty() && textFolded.contains(lit)) {
                    return true;
                }
            }
            return false;
        }
        // SINGLE or AND：全部包含
        for (String lit : literals) {
            if (lit == null || lit.isEmpty() || !textFolded.contains(lit)) {
                return false;
            }
        }
        return true;
    }
}
