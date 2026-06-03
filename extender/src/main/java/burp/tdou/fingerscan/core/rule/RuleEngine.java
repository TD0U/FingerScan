package burp.tdou.fingerscan.core.rule;

import java.util.List;

/**
 * 统一规则引擎接口
 * JSON 规则和 YAML 规则都实现此接口，通过 CompositeRuleEngine 聚合
 */
public interface RuleEngine {

    /**
     * 对请求/响应数据进行指纹匹配
     *
     * @param request     请求原始字节（可为 null）
     * @param response    响应原始字节（可为 null）
     * @param requestPath 请求路径（可为 null），用于与规则 url 字段做路径比对
     * @return 匹配结果列表（无匹配返回空列表，永不返回 null）
     */
    List<MatchResult> match(byte[] request, byte[] response, String requestPath);

    /**
     * 获取本引擎需要扫描的路径列表（用于递归扫描策略）
     *
     * @return 扫描路径列表（无路径返回空列表）
     */
    List<String> getScanPaths();
}
