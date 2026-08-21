package com.vibesales.salesagent.tool.knowledge;

import com.vibesales.salesagent.knowledge.AccountKnowledgeBinding;
import com.vibesales.salesagent.knowledge.BailianKnowledgeSearchService;
import com.vibesales.salesagent.knowledge.TenantKnowledgeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;

/** 模型可动态调用的知识库检索 Tool。 */
public final class RetrieveKnowledgeBaseTool {

    public static final String TOOL_NAME = "retrieveKnowledgeBase";

    private final BailianKnowledgeSearchService knowledgeSearchService;

    public RetrieveKnowledgeBaseTool(BailianKnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Tool(
            name = TOOL_NAME,
            description =
                    "检索当前账号可用的知识库，用于查询产品事实、使用方法、会员规则、售后政策、包裹卡规则与风险提示。"
                            + "如果 knowledgeBaseCode 留空，会自动使用当前账号的默认知识库。",
            readOnly = true,
            strict = false)
    public KnowledgeRetrieveResult retrieveKnowledgeBase(
            @ToolParam(name = "query", description = "本次要检索的问题，建议带上产品名、场景或规则关键词")
                    String query,
            @ToolParam(name = "limit", required = false, description = "返回条数，默认 3，建议 1-5")
                    Integer limit,
            @ToolParam(
                            name = "scoreThreshold",
                            required = false,
                            description = "可选分数阈值，0-1 之间；不传则不过滤")
                    Double scoreThreshold,
            @ToolParam(
                            name = "knowledgeBaseCode",
                            required = false,
                            description = "可选知识库编码；不传则使用当前账号默认知识库")
                    String knowledgeBaseCode,
            TenantKnowledgeContext knowledgeContext) {
        String normalizedQuery = safe(query);
        if (normalizedQuery.isEmpty()) {
            return KnowledgeRetrieveResult.failed(
                    "invalid_query", "query 不能为空", knowledgeContext, knowledgeBaseCode, normalizedQuery);
        }
        if (knowledgeContext == null || !knowledgeContext.retrievalAvailable()) {
            return KnowledgeRetrieveResult.failed(
                    "knowledge_unavailable",
                    "当前账号没有配置可用知识库",
                    knowledgeContext,
                    knowledgeBaseCode,
                    normalizedQuery);
        }
        if (knowledgeSearchService == null || !knowledgeSearchService.available()) {
            return KnowledgeRetrieveResult.failed(
                    "provider_unavailable",
                    "知识库检索客户端未初始化",
                    knowledgeContext,
                    knowledgeBaseCode,
                    normalizedQuery);
        }

        AccountKnowledgeBinding binding = knowledgeContext.binding();
        AccountKnowledgeBinding.KnowledgeBaseConfig base =
                binding.findKnowledgeBase(knowledgeBaseCode).orElse(null);
        if (base == null) {
            return KnowledgeRetrieveResult.failed(
                    "knowledge_base_not_allowed",
                    "请求的 knowledgeBaseCode 不在当前账号允许范围内",
                    knowledgeContext,
                    knowledgeBaseCode,
                    normalizedQuery);
        }

        int effectiveLimit = clamp(limit == null ? 3 : limit, 1, 5);
        Double effectiveThreshold = normalizeThreshold(scoreThreshold);
        try {
            List<Map<String, Object>> documents =
                    knowledgeSearchService.retrieve(
                            binding, base, normalizedQuery, effectiveLimit, effectiveThreshold);
            return KnowledgeRetrieveResult.success(
                    knowledgeContext,
                    base.knowledgeBaseCodeOrEmpty(),
                    normalizedQuery,
                    documents,
                    effectiveThreshold);
        } catch (Exception exception) {
            return KnowledgeRetrieveResult.failed(
                    "retrieve_failed",
                    safe(exception.getMessage()).isEmpty() ? "知识库检索失败" : safe(exception.getMessage()),
                    knowledgeContext,
                    base.knowledgeBaseCodeOrEmpty(),
                    normalizedQuery);
        }
    }

    public record KnowledgeRetrieveResult(
            String status,
            String message,
            String clientCode,
            String cluster,
            String provider,
            String knowledgeBaseCode,
            String query,
            Double scoreThreshold,
            int hitCount,
            List<Map<String, Object>> documents) {

        static KnowledgeRetrieveResult success(
                TenantKnowledgeContext knowledgeContext,
                String knowledgeBaseCode,
                String query,
                List<Map<String, Object>> documents,
                Double scoreThreshold) {
            List<Map<String, Object>> safeDocuments = documents == null ? List.of() : List.copyOf(documents);
            return new KnowledgeRetrieveResult(
                    "ok",
                    safeDocuments.isEmpty() ? "未检索到结果" : "检索成功",
                    knowledgeContext == null ? "" : safe(knowledgeContext.clientCode()),
                    knowledgeContext == null ? "" : safe(knowledgeContext.cluster()),
                    knowledgeContext == null ? "" : safe(knowledgeContext.provider()),
                    safe(knowledgeBaseCode),
                    safe(query),
                    scoreThreshold,
                    safeDocuments.size(),
                    safeDocuments);
        }

        static KnowledgeRetrieveResult failed(
                String status,
                String message,
                TenantKnowledgeContext knowledgeContext,
                String knowledgeBaseCode,
                String query) {
            return new KnowledgeRetrieveResult(
                    status,
                    safe(message),
                    knowledgeContext == null ? "" : safe(knowledgeContext.clientCode()),
                    knowledgeContext == null ? "" : safe(knowledgeContext.cluster()),
                    knowledgeContext == null ? "" : safe(knowledgeContext.provider()),
                    safe(knowledgeBaseCode),
                    safe(query),
                    null,
                    0,
                    List.of());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Double normalizeThreshold(Double threshold) {
        if (threshold == null) {
            return null;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
