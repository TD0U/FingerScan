package burp.tdou.fingerscan.core.rule;

import burp.tdou.fingerscan.common.Config;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentTypeGateTest {

    @TempDir
    static Path tmp;

    @BeforeAll
    static void initConfig() {
        Config.init(tmp.resolve("work").toString() + java.io.File.separator);
        Config.put(Config.KEY_MATCH_SKIP_BINARY, "true");
    }

    @Test
    void imagePngSkipped() {
        assertFalse(ContentTypeGate.allowYamlMatch("image/png", "/a.png"));
    }

    @Test
    void imageSvgAllowed() {
        assertTrue(ContentTypeGate.allowYamlMatch("image/svg+xml", "/a.svg"));
    }

    @Test
    void textHtmlAllowed() {
        assertTrue(ContentTypeGate.allowYamlMatch("text/html; charset=utf-8", "/"));
    }

    @Test
    void missingCtAllowed() {
        assertTrue(ContentTypeGate.allowYamlMatch(null, "/x"));
        assertTrue(ContentTypeGate.allowYamlMatch("", "/x"));
    }

    @Test
    void octetStreamWithPngSuffixSkipped() {
        assertFalse(ContentTypeGate.allowYamlMatch("application/octet-stream", "/static/a.png"));
    }

    @Test
    void octetStreamNoSuffixAllowed() {
        assertTrue(ContentTypeGate.allowYamlMatch("application/octet-stream", "/api/download"));
    }

    @Test
    void skipBinaryOffAllowsImage() {
        Config.put(Config.KEY_MATCH_SKIP_BINARY, "false");
        try {
            assertTrue(ContentTypeGate.allowYamlMatch("image/png", "/a.png"));
        } finally {
            Config.put(Config.KEY_MATCH_SKIP_BINARY, "true");
        }
    }
}
