package com.agentteams.salesagent.tool.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.integration.runtime.RuntimeApiResponse;
import com.agentteams.salesagent.tool.RuntimeToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务会话入口 Tool。
 *
 * <p>它调用 {@code POST /api/agent/runtime/sessions}，确保当前 {@code conversationId/chatUser}
 * 在 marketing 侧有对应的客户主档与业务会话作用域。
 */
public final class CreateOrResumeSessionTool {

    private static final Logger log = LoggerFactory.getLogger(CreateOrResumeSessionTool.class);

    private final RuntimeToolScope scope;

    public CreateOrResumeSessionTool() {
        this(RuntimeToolScope.disabled());
    }

    public CreateOrResumeSessionTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public SessionBootstrapSnapshot ensureSession(CustomerContext customerContext) {
        if (!scope.available()) {
            return new SessionBootstrapSnapshot("", "", "skipped", false, false, "skipped", "");
        }

        RuntimeApiResponse response =
                scope.apiClient()
                        .createOrResumeSession(
                                scope.resolveClientCode(customerContext),
                                scope.resolveCluster(customerContext),
                                scope.resolveSceneCode(customerContext),
                                "sales-customer-agent",
                                customerContext.normalizedConversationId(),
                                customerContext.normalizedChatUser(),
                                customerContext.normalizedUserId(),
                                customerContext.normalizedUserName());

        if (!response.success()) {
            log.warn(
                    "createOrResumeSession failed, conversationId={}, chatUser={}, errorCode={}, error={}",
                    customerContext.normalizedConversationId(),
                    customerContext.normalizedChatUser(),
                    response.errorCode(),
                    response.error());
            throw new IllegalStateException(
                    "createOrResumeSession failed: "
                            + (response.error().isBlank() ? response.errorCode() : response.error()));
        }

        JsonNode data = response.data();
        return new SessionBootstrapSnapshot(
                safe(data, "sessionId"),
                safe(data, "sessionCode"),
                safe(data, "status"),
                data.path("isNewSession").asBoolean(false),
                data.path("isNewCustomer").asBoolean(false),
                safe(data, "sessionAction"),
                safe(data, "matchedCustomerBy"));
    }

    private static String safe(JsonNode data, String field) {
        return data == null ? "" : data.path(field).asText("");
    }
}
