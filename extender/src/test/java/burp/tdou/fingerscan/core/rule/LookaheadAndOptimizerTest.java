package burp.tdou.fingerscan.core.rule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LookaheadAndOptimizerTest {

    @Test
    void swaggerWebjarsPattern() {
        List<String> lit = LookaheadAndOptimizer.tryExtractAndLiterals("(?=.*swagger)(?=.*webjars)");
        assertNotNull(lit);
        assertEquals(List.of("swagger", "webjars"), lit);
    }

    @Test
    void lazyDotStar() {
        List<String> lit = LookaheadAndOptimizer.tryExtractAndLiterals("(?=.*?foo)(?=.*?bar)");
        assertNotNull(lit);
        assertEquals(List.of("foo", "bar"), lit);
    }

    @Test
    void matchesAnd() {
        String text = "xxx SWAGGER yyy WebJars zzz".toLowerCase(Locale.ROOT);
        assertTrue(LookaheadAndOptimizer.matchesAllLiterals(text, List.of("swagger", "webjars")));
        assertFalse(LookaheadAndOptimizer.matchesAllLiterals(text, List.of("swagger", "missing")));
    }

    @Test
    void rejectsMixedRegex() {
        assertNull(LookaheadAndOptimizer.tryExtractAndLiterals("(?=.*swagger).*webjars"));
        assertNull(LookaheadAndOptimizer.tryExtractAndLiterals("swagger"));
        assertNull(LookaheadAndOptimizer.tryExtractAndLiterals("(?=.*a)|(?=.*b)"));
    }

    @Test
    void engineUsesContainsNotTimeout() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?=.*swagger)(?=.*webjars)", RuleIndex.PATTERN_FLAGS);
        List<String> and = LookaheadAndOptimizer.tryExtractAndLiterals("(?=.*swagger)(?=.*webjars)");
        CompiledRule rule = new CompiledRule("Doc File", "(?=.*swagger)(?=.*webjars)", p,
                "0", "/", true, List.of(), List.of(), and);
        assertTrue(rule.isLookaheadAndOptimized());

        YamlRuleEngine engine = new YamlRuleEngine(null);
        try {
            String big = "a".repeat(200_000) + "swagger" + "b".repeat(200_000) + "webjars";
            long t0 = System.nanoTime();
            assertTrue(engine.findWithTimeout(rule, big, 100));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(ms < 500, "optimized path should be fast, took " + ms + "ms");

            assertFalse(engine.findWithTimeout(rule, "only swagger here", 100));
        } finally {
            engine.dispose();
        }
    }
}
