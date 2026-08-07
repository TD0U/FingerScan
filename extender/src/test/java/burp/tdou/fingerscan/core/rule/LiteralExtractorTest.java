package burp.tdou.fingerscan.core.rule;

import burp.tdou.fingerscan.core.rule.LiteralExtractor.ExtractResult;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LiteralExtractorTest {

    private static ExtractResult ex(String re) {
        return LiteralExtractor.extract(re, 3);
    }

    private static ExtractResult ex(String re, int min) {
        return LiteralExtractor.extract(re, min);
    }

    private static Set<String> set(List<String> list) {
        return new HashSet<>(list);
    }

    @Test
    void pureLiteral() {
        ExtractResult r = ex("Seeyon");
        assertFalse(r.disabled);
        assertTrue(r.orGroups.isEmpty());
        assertEquals(Set.of("Seeyon"), set(r.must));
    }

    @Test
    void concatWithDotStar() {
        ExtractResult r = ex("foo.*bar");
        assertFalse(r.disabled);
        assertEquals(Set.of("foo", "bar"), set(r.must));
    }

    @Test
    void topLevelOr() {
        ExtractResult r = ex("foo|bar");
        assertFalse(r.disabled);
        assertTrue(r.must.isEmpty());
        assertEquals(2, r.orGroups.size());
        Set<String> flat = new HashSet<>();
        for (List<String> g : r.orGroups) {
            flat.addAll(g);
        }
        assertEquals(Set.of("foo", "bar"), flat);
    }

    @Test
    void optionalGroupThenLiteral() {
        ExtractResult r = ex("(foo)?bar");
        assertFalse(r.disabled);
        assertEquals(Set.of("bar"), set(r.must));
    }

    @Test
    void quantifierOnLastChar() {
        // foo{2,4} => fo + o{2,4} => must contains fo and o, after minLen=3 may keep longer flush "foo"
        ExtractResult r = ex("foo{2,4}", 1);
        assertFalse(r.disabled);
        // 至少应包含 fo（设计）；实现可能 flush 为 foo
        String joined = String.join("", r.must);
        assertTrue(joined.contains("fo") || r.must.stream().anyMatch(s -> s.contains("fo")),
                "must=" + r.must);
    }

    @Test
    void nonCapturingGroupQuantified() {
        ExtractResult r = ex("(?:foo){2,4}");
        assertFalse(r.disabled);
        assertTrue(r.must.contains("foo") || set(r.must).contains("foo"),
                "must=" + r.must);
    }

    @Test
    void nestedOrFlattened() {
        ExtractResult r = ex("(foo|(bar|baz))");
        assertFalse(r.disabled);
        Set<String> flat = new HashSet<>();
        if (!r.orGroups.isEmpty()) {
            for (List<String> g : r.orGroups) {
                flat.addAll(g);
            }
        } else {
            flat.addAll(r.must);
        }
        assertTrue(flat.contains("foo"));
        assertTrue(flat.contains("bar"));
        assertTrue(flat.contains("baz"));
    }

    @Test
    void charClassDisabled() {
        assertTrue(ex("[Ss]eeyon").disabled);
    }

    @Test
    void digitClassEscapeDisabled() {
        assertTrue(ex("\\d+abc").disabled);
    }

    @Test
    void shortLiteralDisabled() {
        assertTrue(ex("ab", 3).disabled);
    }

    @Test
    void nonAsciiDisabled() {
        assertTrue(ex("用友").disabled);
        assertTrue(ex("fooß").disabled);
    }

    @Test
    void escapedDotLiteral() {
        ExtractResult r = ex("foo\\.bar");
        assertFalse(r.disabled);
        // 应为一段 foo.bar 或 foo + . + bar 合并
        String joined = String.join("", r.must);
        assertTrue(joined.contains("foo") && joined.contains("bar"), "must=" + r.must);
        assertTrue(joined.contains("."), "must=" + r.must);
    }

    @Test
    void wordClassEscapeDisabled() {
        assertTrue(ex("\\w+\\.cn").disabled);
    }
}
