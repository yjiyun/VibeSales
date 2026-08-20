package com.agentteams.salesagent.tool.history;

import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.integration.runtime.RuntimeApiResponse;
import com.agentteams.salesagent.mapping.HistorySummaryMapper;
import com.agentteams.salesagent.tool.RuntimeToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 历史摘要读取 Tool。
 *
 * <p>调用后端 {@code GET /api/agent/runtime/history-summary}。这里返回的是<b>业务真值</b>，
 * 不是 AgentScope 框架自己的 {@code MEMORY.md} 会话记忆——两者是不同的东西，不要混用
 * （见02号文档对这条边界的强调）。
 *
 * <p>输出里的 {@code recoveryPending} 是 {@code RecoveryDetectionRule} 的强信号输入：
 * 为 {@code true} 时无条件判定当前消息是续接之前挂起的话题。
 *
 * <p>后端调用失败时回退到占位摘要并记 warn 日志，不打断对话主链路。
 */
public final class GetHistorySummaryTool {

    private static final Logger log = LoggerFactory.getLogger(GetHistorySummaryTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端，行为与接入前一致。 */
    public GetHistorySummaryTool() {
        this(RuntimeToolScope.disabled());
    }

    public GetHistorySummaryTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public HistorySummarySnapshot load(CustomerContext customerContext) {
        if (!scope.available()) {
            return placeholderSnapshot(customerContext);
        }

        String conversationId = customerContext.normalizedConversationId();
        String chatUser = customerContext.normalizedChatUser();

        RuntimeApiResponse response =
                scope.apiClient()
                        .getHistorySummary(
                                scope.resolveClientCode(customerContext),
                                scope.resolveCluster(customerContext),
                                scope.resolveSceneCode(customerContext),
                                conversationId,
                                chatUser);

        if (response.success()) {
            return HistorySummaryMapper.fromResponse(response.data());
        }

        if (response.notFound()) {
            log.debug(
                    "history summary not found (new conversation), conversationId={}, chatUser={}",
                    conversationId,
                    chatUser);
            return HistorySummaryMapper.emptyHistory();
        }

        log.warn(
                "history summary call failed, falling back to placeholder. conversationId={}, errorCode={}, error={}",
                conversationId,
                response.errorCode(),
                response.error());
        return placeholderSnapshot(customerContext);
    }

    private static HistorySummarySnapshot placeholderSnapshot(CustomerContext customerContext) {
        String summary =
                "后端历史摘要接口不可用，当前返回占位摘要（内部 conversationId="
                        + customerContext.normalizedConversationId()
                        + "）。";
        return new HistorySummarySnapshot(summary, false, "");
    }
}
