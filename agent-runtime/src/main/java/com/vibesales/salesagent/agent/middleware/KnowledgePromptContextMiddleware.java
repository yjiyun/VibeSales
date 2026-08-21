package com.vibesales.salesagent.agent.middleware;

import com.vibesales.salesagent.knowledge.TenantKnowledgeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/** 把租户知识库的可用性说明追加到系统提示词。 */
public final class KnowledgePromptContextMiddleware implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(
            Agent agent, RuntimeContext runtimeContext, String systemPrompt) {
        if (runtimeContext == null) {
            return Mono.just(systemPrompt);
        }
        TenantKnowledgeContext knowledgeContext = runtimeContext.get(TenantKnowledgeContext.class);
        if (knowledgeContext == null || !knowledgeContext.retrievalAvailable()) {
            return Mono.just(systemPrompt);
        }
        return Mono.just(appendKnowledgeContext(systemPrompt, knowledgeContext));
    }

    private static String appendKnowledgeContext(
            String systemPrompt, TenantKnowledgeContext knowledgeContext) {
        StringBuilder builder = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.append(systemPrompt.trim()).append("\n\n");
        }
        builder.append("【当前轮次知识检索上下文】\n")
                .append("- 检索提供方：")
                .append(safe(knowledgeContext.provider()))
                .append('\n')
                .append("- 默认知识库编码：")
                .append(safe(knowledgeContext.defaultKnowledgeBaseCode()))
                .append('\n')
                .append("- 可用知识库编码：")
                .append(
                        knowledgeContext.availableKnowledgeBaseCodes().isEmpty()
                                ? "无"
                                : String.join("、", knowledgeContext.availableKnowledgeBaseCodes()))
                .append('\n')
                .append("要求：\n")
                .append("1. 客户问产品事实、用法、会员规则、售后政策、包裹卡规则、风险提示时，先调用 retrieveKnowledgeBase。\n")
                .append("2. 没有显式指定 knowledgeBaseCode 时，直接使用默认知识库。\n")
                .append("3. 若检索为空或 tool 返回失败，只能按“去确认一下”的口径回复，不能自行编造规则和产品事实。");
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }
}
