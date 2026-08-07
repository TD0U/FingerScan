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

class KeywordMatchEngineTest {

    @TempDir
    static Path tmp;

    static YamlConfigStore store;

    @BeforeAll
    static void setup() throws Exception {
        Logger.init(false, System.out, System.err);
        Config.init(tmp.resolve("work").toString() + File.separator);
        File yaml = tmp.resolve("kw-rules.yaml").toFile();
        writeRules(yaml);
        Config.put("yaml_config_path", yaml.getAbsolutePath());
        store = new YamlConfigStore(yaml.getAbsolutePath());
    }

    private static void writeRules(File f) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("Load_List", List.of(
                rule("DocKW", "keyword", "swagger && webjars", "0", "/"),
                rule("OrKW", "keyword", "foo || bar", "0", "/"),
                rule("LegacyRegex", "regex", "Seeyon", "0", "/"),
                rule("NoMatchField", null, "ActiveMQ", "0", "/"),
                rule("LiteralPlus", "keyword", "a+b", "0", "/")
        ));
        root.put("Bypass_List", List.of());
        try (PrintWriter pw = new PrintWriter(f, StandardCharsets.UTF_8)) {
            new Yaml().dump(root, pw);
        }
    }

    private static Map<String, Object> rule(String name, String match, String re, String state, String url) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("re", re);
        m.put("loaded", true);
        m.put("state", state);
        m.put("url", url);
        m.put("method", "GET");
        if (match != null) {
            m.put("match", match);
        }
        return m;
    }

    private static Set<String> names(List<MatchResult> r) {
        return r.stream().map(MatchResult::getRuleName).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void keywordAnd() {
        YamlRuleEngine engine = new YamlRuleEngine(store);
        try {
            byte[] both = "HTTP/1.1 200\r\n\r\nx swagger y webjars z".getBytes(StandardCharsets.UTF_8);
            Set<String> hit = names(engine.match(null, both, "/"));
            assertTrue(hit.contains("DocKW"), hit.toString());

            byte[] one = "HTTP/1.1 200\r\n\r\nonly swagger here".getBytes(StandardCharsets.UTF_8);
            Set<String> hit1 = names(engine.match(null, one, "/"));
            assertFalse(hit1.contains("DocKW"), hit1.toString());
        } finally {
            engine.dispose();
        }
    }

    @Test
    void keywordOrAndLegacy() {
        YamlRuleEngine engine = new YamlRuleEngine(store);
        try {
            byte[] body = "HTTP/1.1 200\r\n\r\nfoo and Seeyon and ActiveMQ and a+b"
                    .getBytes(StandardCharsets.UTF_8);
            Set<String> hit = names(engine.match(null, body, "/"));
            assertTrue(hit.contains("OrKW"), hit.toString());
            assertTrue(hit.contains("LegacyRegex"), hit.toString());
            assertTrue(hit.contains("NoMatchField"), hit.toString());
            // a+b is literal keyword, not regex quantifier
            assertTrue(hit.contains("LiteralPlus"), hit.toString());
        } finally {
            engine.dispose();
        }
    }
}
