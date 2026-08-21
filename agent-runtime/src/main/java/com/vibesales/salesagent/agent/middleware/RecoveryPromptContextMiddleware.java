package com.vibesales.salesagent.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 把恢复链路的结构化结果追加到系统提示词。
 *
 * <p>这里只做"读取 RuntimeContext 中已算好的上下文并转成提示词文本"，不在 Middleware 里自行调用 Tool
 * 或重算业务判断，避免业务逻辑回流到 Agent 编排层。
 */
public final class RecoveryPromptContextMiddleware implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(
            Agent agent, RuntimeContext runtimeContext, String systemPrompt) {
        if (runtimeContext == null) {
            return Mono.just(systemPrompt);
        }

        RecoveryPromptContext promptContext = runtimeContext.get(RecoveryPromptContext.class);
        if (promptContext == null) {
            return Mono.just(systemPrompt);
        }

        return Mono.just(appendPromptContext(systemPrompt, promptContext));
    }

    private static String appendPromptContext(
            String systemPrompt, RecoveryPromptContext promptContext) {
        StringBuilder builder = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.append(systemPrompt.trim()).append("\n\n");
        }
        builder.append("【当前轮次系统上下文】\n")
                .append("- 轮次类型：")
                .append(
                        promptContext.recoveryDecision().recoveryMessage()
                                ? "续接上一轮未完成话题"
                                : "新的处理轮次")
                .append('\n')
                .append("- 恢复模式：")
                .append(safe(promptContext.recoveryDecision().recoveryMode()))
                .append('\n')
                .append("- 目标意图：")
                .append(safe(promptContext.recoveryDecision().targetIntent()))
                .append('\n')
                .append("- 恢复提示：")
                .append(safe(promptContext.recoveryDecision().promptHint()))
                .append('\n')
                .append("- 历史摘要：")
                .append(safe(promptContext.historySummary().summaryText()))
                .append('\n')
                .append("- 当前活跃意图：")
                .append(safe(promptContext.historySummary().activeIntentCode()))
                .append('\n')
                .append("- 任务板摘要：总任务=")
                .append(promptContext.intentTaskSnapshot().totalTasks())
                .append("，进行中=")
                .append(promptContext.intentTaskSnapshot().activeTasks())
                .append("，挂起=")
                .append(promptContext.intentTaskSnapshot().suspendedTasks())
                .append("，queueVersion=")
                .append(safe(promptContext.intentTaskSnapshot().queueVersion()))
                .append('\n')
                .append("- 客户画像摘要：")
                .append(safe(promptContext.customerProfile().summary()))
                .append('\n')
                .append("要求：\n")
                .append("1. 上述内容来自系统预取结果，不是客户原话，不要改写成客户曾明确说过的话。\n")
                .append("2. 若轮次类型为续接，直接沿目标意图续接处理；若为新轮次，按新话题组织回复。\n")
                .append("3. 不要声称已经完成外部系统写回，除非本轮真实调用了对应 Tool。");
        return builder.toString();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "无";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
