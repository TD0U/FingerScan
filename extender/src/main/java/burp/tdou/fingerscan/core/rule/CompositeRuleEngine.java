package burp.tdou.fingerscan.core.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 组合规则引擎
 * 聚合所有注册的规则引擎，统一执行匹配和路径收集
 */
public class CompositeRuleEngine implements RuleEngine {

    private final List<RuleEngine> engines = new ArrayList<>();

    /**
     * 注册规则引擎
     */
    public CompositeRuleEngine register(RuleEngine engine) {
        if (engine != null) {
            engines.add(engine);
        }
        return this;
    }

    /**
     * 移除规则引擎
     */
    public void unregister(RuleEngine engine) {
        engines.remove(engine);
    }

    @Override
    public List<MatchResult> match(byte[] request, byte[] response, String requestPath) {
        List<MatchResult> allResults = new ArrayList<>();
        for (RuleEngine engine : engines) {
            try {
                List<MatchResult> results = engine.match(request, response, requestPath);
                if (results != null) {
                    allResults.addAll(results);
                }
            } catch (Exception e) {
                // 单个引擎异常不影响其他引擎
            }
        }
        return allResults;
    }

    @Override
    public List<String> getScanPaths() {
        return engines.stream()
                .flatMap(e -> e.getScanPaths().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 注册的引擎数量
     */
    public int getEngineCount() {
        return engines.size();
    }
}
