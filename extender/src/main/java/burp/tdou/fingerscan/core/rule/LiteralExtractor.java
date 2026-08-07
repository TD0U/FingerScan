package burp.tdou.fingerscan.core.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从 Java 正则字符串中保守抽取「匹配成功则必现」的字面量，供预过滤使用。
 * 失败开放：看不懂 / 字符类 / 类别转义 / 非 ASCII → disabled。
 */
public final class LiteralExtractor {

    private LiteralExtractor() {
    }

    public static final class ExtractResult {
        public final boolean disabled;
        public final List<String> must;
        public final List<List<String>> orGroups;

        private ExtractResult(boolean disabled, List<String> must, List<List<String>> orGroups) {
            this.disabled = disabled;
            this.must = must != null ? must : Collections.emptyList();
            this.orGroups = orGroups != null ? orGroups : Collections.emptyList();
        }

        public static ExtractResult disabled() {
            return new ExtractResult(true, Collections.emptyList(), Collections.emptyList());
        }

        public static ExtractResult ofMust(List<String> must) {
            return new ExtractResult(false, imm(must), Collections.emptyList());
        }

        public static ExtractResult ofOr(List<List<String>> groups) {
            List<List<String>> copy = new ArrayList<>(groups.size());
            for (List<String> g : groups) {
                copy.add(imm(g));
            }
            return new ExtractResult(false, Collections.emptyList(), Collections.unmodifiableList(copy));
        }

        private static List<String> imm(List<String> in) {
            if (in == null || in.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<>(in));
        }
    }

    public static ExtractResult extract(String pattern, int minLen) {
        if (pattern == null || pattern.isEmpty()) {
            return ExtractResult.disabled();
        }
        try {
            Parser p = new Parser(pattern);
            ExtractResult r = p.parseAlt(false);
            if (r.disabled) {
                return r;
            }
            // 顶层未消费完且不是正常结束
            if (!p.eof()) {
                return ExtractResult.disabled();
            }
            r = dropShortLiterals(r, minLen);
            if (r.disabled || isEmpty(r)) {
                return ExtractResult.disabled();
            }
            if (anyNonAscii(r)) {
                return ExtractResult.disabled();
            }
            return r;
        } catch (Exception e) {
            return ExtractResult.disabled();
        }
    }

    private static boolean isEmpty(ExtractResult r) {
        return r.must.isEmpty() && r.orGroups.isEmpty();
    }

    private static boolean anyNonAscii(ExtractResult r) {
        for (String s : r.must) {
            if (nonAscii(s)) {
                return true;
            }
        }
        for (List<String> g : r.orGroups) {
            for (String s : g) {
                if (nonAscii(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nonAscii(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp > 127) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static ExtractResult dropShortLiterals(ExtractResult r, int minLen) {
        if (!r.orGroups.isEmpty()) {
            List<List<String>> groups = new ArrayList<>();
            for (List<String> g : r.orGroups) {
                List<String> ng = filterMin(g, minLen);
                if (ng.isEmpty()) {
                    return ExtractResult.disabled();
                }
                groups.add(ng);
            }
            return ExtractResult.ofOr(groups);
        }
        List<String> must = filterMin(r.must, minLen);
        if (must.isEmpty()) {
            return ExtractResult.disabled();
        }
        return ExtractResult.ofMust(must);
    }

    private static List<String> filterMin(List<String> in, int minLen) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && s.length() >= minLen) {
                out.add(s);
            }
        }
        return out;
    }

    /** 将 OR 结果规范为扁平 orGroups 或单支 must */
    private static ExtractResult flattenOr(List<ExtractResult> branches) {
        List<List<String>> groups = new ArrayList<>();
        for (ExtractResult b : branches) {
            if (b.disabled) {
                return ExtractResult.disabled();
            }
            if (!b.orGroups.isEmpty()) {
                for (List<String> g : b.orGroups) {
                    if (g.isEmpty()) {
                        return ExtractResult.disabled();
                    }
                    groups.add(new ArrayList<>(g));
                }
            } else {
                if (b.must.isEmpty()) {
                    return ExtractResult.disabled();
                }
                groups.add(new ArrayList<>(b.must));
            }
        }
        if (groups.size() == 1) {
            return ExtractResult.ofMust(groups.get(0));
        }
        return ExtractResult.ofOr(groups);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        char peek() {
            return eof() ? '\0' : s.charAt(i);
        }

        char next() {
            return s.charAt(i++);
        }

        ExtractResult parseAlt(boolean inGroup) {
            List<ExtractResult> branches = new ArrayList<>();
            branches.add(parseConcat(inGroup));
            while (!eof() && peek() == '|') {
                next();
                branches.add(parseConcat(inGroup));
            }
            if (branches.size() == 1) {
                return branches.get(0);
            }
            return flattenOr(branches);
        }

        ExtractResult parseConcat(boolean inGroup) {
            List<String> must = new ArrayList<>();
            StringBuilder lit = new StringBuilder();

            while (!eof()) {
                char c = peek();
                if (c == '|' || (c == ')' && inGroup)) {
                    break;
                }
                if (c == ')') {
                    // 顶层多余 )
                    throw new IllegalStateException("extra )");
                }
                if (c == '*' || c == '+' || c == '?' || c == '{') {
                    throw new IllegalStateException("dangling quantifier");
                }

                Atom atom = parseAtom();
                int min = parseQuantMin();

                if (atom.fail) {
                    return ExtractResult.disabled();
                }

                boolean required = min >= 1;
                if (!required) {
                    flush(lit, must);
                    continue;
                }

                switch (atom.type) {
                    case CHAR:
                        lit.append(atom.ch);
                        break;
                    case DOT:
                        flush(lit, must);
                        break;
                    case GROUP:
                        flush(lit, must);
                        ExtractResult gr = atom.group;
                        if (gr.disabled) {
                            return ExtractResult.disabled();
                        }
                        if (!gr.orGroups.isEmpty()) {
                            // 组内 OR 且必现：前缀 must × 各分支，再与后续 concat 合并
                            List<String> prefix = new ArrayList<>(must);
                            must.clear();
                            ExtractResult rest = parseConcat(inGroup);
                            if (rest.disabled) {
                                return ExtractResult.disabled();
                            }
                            if (!rest.orGroups.isEmpty()) {
                                // 双重 OR 连接：保守 disabled
                                return ExtractResult.disabled();
                            }
                            List<List<String>> merged = new ArrayList<>();
                            for (List<String> g : gr.orGroups) {
                                List<String> row = new ArrayList<>(prefix);
                                row.addAll(g);
                                row.addAll(rest.must);
                                if (row.isEmpty()) {
                                    return ExtractResult.disabled();
                                }
                                merged.add(row);
                            }
                            return flattenOrFromGroups(merged);
                        }
                        must.addAll(gr.must);
                        break;
                    default:
                        return ExtractResult.disabled();
                }
            }
            flush(lit, must);
            return ExtractResult.ofMust(must);
        }

        private static ExtractResult flattenOrFromGroups(List<List<String>> merged) {
            if (merged.size() == 1) {
                return ExtractResult.ofMust(merged.get(0));
            }
            return ExtractResult.ofOr(merged);
        }

        private void flush(StringBuilder lit, List<String> must) {
            if (lit.length() > 0) {
                must.add(lit.toString());
                lit.setLength(0);
            }
        }

        private enum Type {CHAR, DOT, GROUP}

        private static final class Atom {
            final Type type;
            final char ch;
            final ExtractResult group;
            final boolean fail;

            static Atom ch(char c) {
                return new Atom(Type.CHAR, c, null, false);
            }

            static Atom dot() {
                return new Atom(Type.DOT, '.', null, false);
            }

            static Atom group(ExtractResult r) {
                return new Atom(Type.GROUP, '\0', r, false);
            }

            static Atom fail() {
                return new Atom(Type.DOT, '\0', null, true);
            }

            private Atom(Type type, char ch, ExtractResult group, boolean fail) {
                this.type = type;
                this.ch = ch;
                this.group = group;
                this.fail = fail;
            }
        }

        private Atom parseAtom() {
            char c = next();
            if (c == '\\') {
                return parseEscape();
            }
            if (c == '.') {
                return Atom.dot();
            }
            if (c == '^' || c == '$') {
                return Atom.dot();
            }
            if (c == '[') {
                skipClass();
                return Atom.fail();
            }
            if (c == '(') {
                return parseGroup();
            }
            return Atom.ch(c);
        }

        private Atom parseEscape() {
            if (eof()) {
                return Atom.fail();
            }
            char e = next();
            switch (e) {
                case '\\':
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '*':
                case '+':
                case '?':
                case '|':
                case '^':
                case '$':
                    return Atom.ch(e);
                default:
                    return Atom.fail();
            }
        }

        private void skipClass() {
            boolean esc = false;
            while (!eof()) {
                char c = next();
                if (esc) {
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == ']') {
                    return;
                }
            }
            throw new IllegalStateException("unclosed class");
        }

        private Atom parseGroup() {
            if (!eof() && peek() == '?') {
                next();
                if (eof()) {
                    return Atom.fail();
                }
                char flag = next();
                if (flag == ':') {
                    ExtractResult inner = parseAlt(true);
                    expectClose();
                    return Atom.group(inner);
                }
                // 其它 (?...) → fail，跳过到闭合
                skipGroupTail();
                return Atom.fail();
            }
            ExtractResult inner = parseAlt(true);
            expectClose();
            return Atom.group(inner);
        }

        private void expectClose() {
            if (eof() || next() != ')') {
                throw new IllegalStateException("unclosed group");
            }
        }

        private void skipGroupTail() {
            int depth = 1;
            boolean esc = false;
            while (!eof() && depth > 0) {
                char c = next();
                if (esc) {
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth != 0) {
                throw new IllegalStateException("unclosed");
            }
        }

        /** @return quantifier minimum count (default 1 if no quantifier) */
        private int parseQuantMin() {
            if (eof()) {
                return 1;
            }
            char c = peek();
            if (c == '*') {
                next();
                eatLazyPoss();
                return 0;
            }
            if (c == '+') {
                next();
                eatLazyPoss();
                return 1;
            }
            if (c == '?') {
                next();
                eatLazyPoss();
                return 0;
            }
            if (c == '{') {
                next();
                int min = readNum();
                if (!eof() && peek() == ',') {
                    next();
                    if (!eof() && peek() != '}') {
                        readNum(); // max ignored
                    }
                }
                if (eof() || next() != '}') {
                    throw new IllegalStateException("bad {}");
                }
                eatLazyPoss();
                return min;
            }
            return 1;
        }

        private void eatLazyPoss() {
            if (!eof() && (peek() == '?' || peek() == '+')) {
                next();
            }
        }

        private int readNum() {
            int start = i;
            while (!eof() && Character.isDigit(peek())) {
                next();
            }
            if (start == i) {
                throw new IllegalStateException("num");
            }
            return Integer.parseInt(s.substring(start, i));
        }
    }
}
