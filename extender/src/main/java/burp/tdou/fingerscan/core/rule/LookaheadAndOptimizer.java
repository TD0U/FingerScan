package burp.tdou.fingerscan.core.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将「仅由多个 (?=.*字面量) / (?=.*?字面量) 组成的 AND 规则」优化为多次 contains。
 * <p>
 * 典型危险写法（会触发 CharPropertyGreedy 灾难回溯）：
 * {@code (?=.*swagger)(?=.*webjars)}
 * <p>
 * 语义：正文（忽略大小写）须同时包含各字面量；与 Java 在
 * {@code CASE_INSENSITIVE|DOTALL} 下对纯字面量前瞻的常见意图一致。
 * 无法安全解析则返回 null，调用方回退原 Pattern.find（仍受超时保护）。
 */
public final class LookaheadAndOptimizer {

    /**
     * 单个前瞻：(?=.*LIT) 或 (?=.*?LIT)，LIT 为不含元字符的字面量（允许简单转义）。
     * 允许 LIT 前后有可选的 [\s\S]* 等价的 .*
     */
    private static final Pattern ONE_LOOKAHEAD = Pattern.compile(
            "^\\(\\?[=]"                          // (?=
                    + "(?:\\.\\*|\\.\\*\\?|[\\s\\S]*?)?" // optional leading .* / .*?
                    + "((?:\\\\.|[^\\\\()\\[\\].*+?{}|^$])+)" // literal run
                    + "(?:\\.\\*|\\.\\*\\?|[\\s\\S]*?)?" // optional trailing
                    + "\\)$");

    private LookaheadAndOptimizer() {
    }

    /**
     * @return 小写字面量列表（AND）；无法优化返回 null
     */
    public static List<String> tryExtractAndLiterals(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        String re = regex.trim();
        // 必须整串都是紧密相连的 (?=...) 段
        List<String> parts = splitTopLevelLookaheads(re);
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        List<String> literals = new ArrayList<>(parts.size());
        for (String part : parts) {
            Matcher m = ONE_LOOKAHEAD.matcher(part);
            if (!m.matches()) {
                return null;
            }
            String lit = unescapeSimple(m.group(1));
            if (lit == null || lit.isEmpty()) {
                return null;
            }
            // 拒绝仍含正则元字符的“字面量”
            if (containsUnescapedMeta(lit)) {
                return null;
            }
            literals.add(lit.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(literals);
    }

    public static boolean matchesAllLiterals(String textFolded, List<String> literals) {
        if (textFolded == null || literals == null || literals.isEmpty()) {
            return false;
        }
        for (String lit : literals) {
            if (lit == null || lit.isEmpty() || !textFolded.contains(lit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将整串拆成 top-level (?=...) (?! 不支持) 段；若夹杂其它内容返回 null。
     */
    static List<String> splitTopLevelLookaheads(String re) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = re.length();
        while (i < n) {
            if (i + 2 < n && re.charAt(i) == '(' && re.charAt(i + 1) == '?'
                    && re.charAt(i + 2) == '=') {
                int depth = 0;
                int start = i;
                boolean esc = false;
                for (; i < n; i++) {
                    char c = re.charAt(i);
                    if (esc) {
                        esc = false;
                        continue;
                    }
                    if (c == '\\') {
                        esc = true;
                        continue;
                    }
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            i++;
                            parts.add(re.substring(start, i));
                            break;
                        }
                    }
                }
                if (depth != 0) {
                    return null;
                }
            } else if (Character.isWhitespace(re.charAt(i))) {
                i++;
            } else {
                return null;
            }
        }
        return parts.isEmpty() ? null : parts;
    }

    private static String unescapeSimple(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                // 仅允许转义元字符为字面量
                sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean containsUnescapedMeta(String lit) {
        // unescape 后若仍出现未意图的控制符则拒绝（字面量不应含正则语法）
        for (int i = 0; i < lit.length(); i++) {
            char c = lit.charAt(i);
            if (c == '*' || c == '+' || c == '?' || c == '(' || c == ')' || c == '['
                    || c == '{' || c == '|' || c == '^' || c == '$') {
                return true;
            }
        }
        return false;
    }
}
