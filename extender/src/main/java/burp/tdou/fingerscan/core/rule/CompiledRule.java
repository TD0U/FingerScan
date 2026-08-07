package burp.tdou.fingerscan.core.rule;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 单条 YAML 指纹规则的编译视图（不可变）。
 */
public final class CompiledRule {

    private final String name;
    private final String regex;
    private final Pattern pattern;
    private final String state;
    private final String url;
    private final boolean prefilterDisabled;
    private final List<String> mustLiterals;
    private final List<List<String>> orGroups;

    public CompiledRule(String name, String regex, Pattern pattern,
                        String state, String url,
                        boolean prefilterDisabled,
                        List<String> mustLiterals,
                        List<List<String>> orGroups) {
        this.name = name;
        this.regex = regex;
        this.pattern = pattern;
        this.state = state;
        this.url = url;
        this.prefilterDisabled = prefilterDisabled;
        this.mustLiterals = mustLiterals != null
                ? Collections.unmodifiableList(mustLiterals)
                : Collections.emptyList();
        this.orGroups = orGroups != null
                ? Collections.unmodifiableList(orGroups)
                : Collections.emptyList();
    }

    public String getName() {
        return name;
    }

    public String getRegex() {
        return regex;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getState() {
        return state;
    }

    public String getUrl() {
        return url;
    }

    public boolean isPrefilterDisabled() {
        return prefilterDisabled;
    }

    public List<String> getMustLiterals() {
        return mustLiterals;
    }

    public List<List<String>> getOrGroups() {
        return orGroups;
    }
}
