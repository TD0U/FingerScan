package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.common.Config;
import burp.tdou.fingerscan.config.YamlConfigStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 黄金集：prefilter ON/OFF 命中 ruleName 集合一致；大 body 末尾指纹不截断。
 */
class YamlRuleEnginePrefilterTest {

    @TempDir
    static Path tmp;

    static File yamlFile;
    static YamlConfigStore store;

    @BeforeAll
    static void setup() throws Exception {
        Logger.init(false, System.out, System.err);
        Config.init(tmp.resolve("work").toString() + File.separator);
        yamlFile = tmp.resolve("test-rules.yaml").toFile();
        writeRules(yamlFile);
        Config.put("yaml_config_path", yamlFile.getAbsolutePath());
        store = new YamlConfigStore(yamlFile.getAbsolutePath());
    }

    private static void writeRules(File f) throws Exception {
        // SnakeYAML 友好结构
        Map<String, Object> root = new HashMap<>();
        List<Map<String, Object>> load = List.of(
                rule("Seeyon", "Seeyon", "0", "/"),
                rule("FooBar", "foo.*bar", "0", "/"),
                rule("FooOrBar", "foo|bar", "0", "/"),
                rule("OptFooBar", "(foo)?bar", "0", "/"),
                rule("CharClass", "[Ss]eeyon", "0", "/"),
                rule("EscDot", "foo\\.bar", "0", "/")
        );
        root.put("Load_List", load);
        root.put("Bypass_List", List.of());
        try (PrintWriter pw = new PrintWriter(f, StandardCharsets.UTF_8)) {
            new Yaml().dump(root, pw);
        }
    }

    private static Map<String, Object> rule(String name, String re, String state, String url) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("re", re);
        m.put("state", state);
        m.put("url", url);
        m.put("loaded", true);
        return m;
    }

    private static Set<String> names(List<MatchResult> results) {
        return results.stream().map(MatchResult::getRuleName).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> matchBoth(byte[] body) {
        YamlRuleEngine engine = new YamlRuleEngine(store);
        try {
            Config.put(Config.KEY_MATCH_LITERAL_PREFILTER, "true");
            engine.rebuildIndex();
            Set<String> on = names(engine.match(null, body, "/"));

            Config.put(Config.KEY_MATCH_LITERAL_PREFILTER, "false");
            // prefilter 开关只影响 match 路径，不必 rebuild
            Set<String> off = names(engine.match(null, body, "/"));

            assertEquals(off, on, "prefilter ON/OFF must agree for body");
            return on;
        } finally {
            engine.dispose();
            Config.put(Config.KEY_MATCH_LITERAL_PREFILTER, "true");
        }
    }

    @Test
    void emptyBody() {
        YamlRuleEngine engine = new YamlRuleEngine(store);
        try {
            assertTrue(engine.match(null, new byte[0], "/").isEmpty());
            assertTrue(engine.match(null, null, "/").isEmpty());
        } finally {
            engine.dispose();
        }
    }

    @Test
    void seeyonText() {
        byte[] body = "HTTP/1.1 200 OK\r\n\r\nHello Seeyon World".getBytes(StandardCharsets.UTF_8);
        Set<String> hit = matchBoth(body);
        assertTrue(hit.contains("Seeyon") || hit.contains("CharClass"), "hit=" + hit);
    }

    @Test
    void multiFingerprintHtml() {
        String html = "HTTP/1.1 200 OK\r\n\r\n<html>foo and bar and Seeyon and foo.bar</html>";
        Set<String> hit = matchBoth(html.getBytes(StandardCharsets.UTF_8));
        assertFalse(hit.isEmpty(), "should hit something");
    }

    @Test
    void largeBodyTrailingFingerprint() {
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n\r\n");
        // >1MB padding
        for (int i = 0; i < 70_000; i++) {
            sb.append("xxxxxxxxxxxxxxxx");
        }
        sb.append("TRAILER-Seeyon-END");
        assertTrue(sb.length() > 1_000_000);
        Set<String> hit = matchBoth(sb.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(hit.contains("Seeyon") || hit.contains("CharClass"),
                "must not truncate; hit=" + hit);
    }
}
