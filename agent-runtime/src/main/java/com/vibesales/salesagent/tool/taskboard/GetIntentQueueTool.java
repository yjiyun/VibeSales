package com.vibesales.salesagent.tool.taskboard;

import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.RuntimeApiResponse;
import com.vibesales.salesagent.mapping.IntentQueueMapper;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多意图任务板读取 Tool。
 *
 * <p>调用后端 {@code GET /api/agent/runtime/intent-queue}，这是场景卡片8（多意图并发处理）
 * 的读取入口——后端是任务板的<b>远程权威真值</b>，Java 侧不维护自己的一份队列状态。
 *
 * <p>输出的 {@code queueVersion} 是乐观锁版本号，将来接入 {@code POST /intent-queue/sync}
 * 写操作时必须原值回传，后端会校验版本冲突（冲突时返回 409，不静默覆盖）。
 *
 * <p>后端调用失败时回退到空队列并记 warn 日志，不打断对话主链路。
 */
public final class GetIntentQueueTool {

    private static final Logger log = LoggerFactory.getLogger(GetIntentQueueTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端，行为与接入前一致。 */
    public GetIntentQueueTool() {
        this(RuntimeToolScope.disabled());
    }

    public GetIntentQueueTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public IntentTaskSnapshot load(CustomerContext customerContext) {
        String fallbackQueueVersion = "bootstrap-" + customerContext.normalizedConversationId();

        if (!scope.available()) {
            return IntentQueueMapper.emptyQueue(fallbackQueueVersion);
        }

        String conversationId = customerContext.normalizedConversationId();
        RuntimeApiResponse response =
                scope.apiClient()
                        .getIntentQueue(
                                scope.resolveClientCode(customerContext),
                                scope.resolveCluster(customerContext),
                                scope.resolveSceneCode(customerContext),
                                conversationId,
                                customerContext.normalizedChatUser());

        if (response.success()) {
            return IntentQueueMapper.fromResponse(response.data(), fallbackQueueVersion);
        }

        if (response.notFound()) {
            log.debug("intent queue not found (new conversation), conversationId={}", conversationId);
            return IntentQueueMapper.emptyQueue(fallbackQueueVersion);
        }

        log.warn(
                "intent queue call failed, falling back to empty queue. conversationId={}, errorCode={}, error={}",
                conversationId,
                response.errorCode(),
                response.error());
        return IntentQueueMapper.emptyQueue(fallbackQueueVersion);
    }
}
