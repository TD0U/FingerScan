package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.common.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MatchTimeoutTest {

    @TempDir
    static Path tmp;

    @BeforeAll
    static void init() {
        Logger.init(false, System.out, System.err);
        Config.init(tmp.resolve("work").toString() + java.io.File.separator);
    }

    @Test
    void timeoutCharSequenceThrows() {
        String s = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaX";
        long deadline = System.nanoTime() + 1_000_000L; // 1ms
        TimeoutCharSequence cs = new TimeoutCharSequence(s, deadline, "evil", 1, 1);
        // burn time then access
        long end = System.nanoTime() + 5_000_000L;
        while (System.nanoTime() < end) {
            // spin
        }
        assertThrows(MatchTimeoutException.class, () -> {
            // force many charAt
            for (int i = 0; i < s.length(); i++) {
                cs.charAt(i);
            }
        });
    }

    @Test
    void findWithTimeoutSkipsCatastrophicRegex() {
        // Classic slow-ish pattern on long 'a' run — with short timeout should throw/skip
        String re = "(a+)+b";
        Pattern p = Pattern.compile(re, RuleIndex.PATTERN_FLAGS);
        CompiledRule rule = new CompiledRule("evil", re, p, "0", "/", true,
                java.util.List.of(), java.util.List.of());
        String body = "a".repeat(30); // no trailing b → backtracking
        YamlRuleEngine engine = new YamlRuleEngine(null);
        try {
            Config.put(Config.KEY_MATCH_TIMEOUT_MS, "20");
            long t0 = System.nanoTime();
            boolean hit;
            try {
                hit = engine.findWithTimeout(rule, body, 20);
            } catch (MatchTimeoutException e) {
                hit = false;
                assertEquals("evil", e.getRuleName());
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertFalse(hit);
            // should not run for minutes; allow generous CI slop
            assertTrue(ms < 5000, "took " + ms + "ms");
        } finally {
            engine.dispose();
            Config.put(Config.KEY_MATCH_TIMEOUT_MS, "100");
        }
    }

    @Test
    void fastLiteralStillMatches() {
        Pattern p = Pattern.compile("Seeyon", RuleIndex.PATTERN_FLAGS);
        CompiledRule rule = new CompiledRule("ok", "Seeyon", p, "0", "/", false,
                java.util.List.of("seeyon"), java.util.List.of());
        YamlRuleEngine engine = new YamlRuleEngine(null);
        try {
            assertTrue(engine.findWithTimeout(rule, "hello Seeyon world", 100));
        } finally {
            engine.dispose();
        }
    }
}
