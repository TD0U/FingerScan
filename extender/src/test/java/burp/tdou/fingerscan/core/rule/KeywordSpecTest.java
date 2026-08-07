package burp.tdou.fingerscan.core.rule;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class KeywordSpecTest {

    @Test
    void single() {
        KeywordSpec k = KeywordSpec.parse("swagger");
        assertNotNull(k);
        assertEquals(KeywordSpec.Op.SINGLE, k.op);
        assertEquals(1, k.literals.size());
        assertEquals("swagger", k.literals.get(0));
        assertTrue(k.matches("xx SWAGGER yy".toLowerCase(Locale.ROOT)));
        assertFalse(k.matches("nothing"));
    }

    @Test
    void and() {
        KeywordSpec k = KeywordSpec.parse("swagger && webjars");
        assertNotNull(k);
        assertEquals(KeywordSpec.Op.AND, k.op);
        assertEquals(2, k.literals.size());
        String text = "xx SWAGGER yy WebJars zz".toLowerCase(Locale.ROOT);
        assertTrue(k.matches(text));
        assertFalse(k.matches("only swagger".toLowerCase(Locale.ROOT)));
    }

    @Test
    void or() {
        KeywordSpec k = KeywordSpec.parse("a || b || c");
        assertNotNull(k);
        assertEquals(KeywordSpec.Op.OR, k.op);
        assertEquals(3, k.literals.size());
        assertTrue(k.matches("xb y".toLowerCase(Locale.ROOT)));
        assertFalse(k.matches("zzz"));
    }

    @Test
    void mixedRejected() {
        assertNull(KeywordSpec.parse("a && b || c"));
    }

    @Test
    void emptyRejected() {
        assertNull(KeywordSpec.parse(null));
        assertNull(KeywordSpec.parse("  "));
        assertNull(KeywordSpec.parse("swagger &&"));
        assertNull(KeywordSpec.parse("|| a"));
    }

    @Test
    void caseFoldedLiterals() {
        KeywordSpec k = KeywordSpec.parse("Swagger && WebJars");
        assertNotNull(k);
        assertEquals("swagger", k.literals.get(0));
        assertEquals("webjars", k.literals.get(1));
    }

    @Test
    void singleAfterSplitStillOk() {
        // trailing would be invalid; single segment with delimiter only on one side invalid
        KeywordSpec k = KeywordSpec.parse("onlyone");
        assertEquals(KeywordSpec.Op.SINGLE, k.op);
    }
}
