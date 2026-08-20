package com.agentteams.salesagent.tool.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.integration.runtime.RuntimeApiResponse;
import com.agentteams.salesagent.tool.RuntimeToolScope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 历史摘要写回 Tool。
 */
public final class SaveHistorySummaryTool {

    private static final Logger log = LoggerFactory.getLogger(SaveHistorySummaryTool.class);

    private final RuntimeToolScope scope;

    public SaveHistorySummaryTool() {
        this(RuntimeToolScope.disabled());
    }

    public SaveHistorySummaryTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public HistorySummaryWriteResult save(
            CustomerContext customerContext, HistorySummaryWriteRequest request) {
        if (!scope.available()) {
            return new HistorySummaryWriteResult(
                    customerContext.normalizedConversationId(),
                    customerContext.normalizedChatUser(),
                    request.historySummary(),
                    request.lastIntent(),
                    request.lastNextStep(),
                    0);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCode", scope.resolveClientCode(customerContext));
        body.put("cluster", scope.resolveCluster(customerContext));
        body.put("sceneCode", scope.resolveSceneCode(customerContext));
        body.put("conversationId", customerContext.normalizedConversationId());
        body.put("chatUser", customerContext.normalizedChatUser());
        body.put("historySummary", request.historySummary());
        body.put("latestUserMessage", request.latestUserMessage());
        body.put("latestAgentReply", request.latestAgentReply());
        body.put("lastIntent", request.lastIntent());
        body.put("lastRouteTarget", request.lastRouteTarget());
        body.put("lastNextStep", request.lastNextStep());
        body.put("lastReplyScenario", request.lastReplyScenario());
        body.put("lastFlowStage", request.lastFlowStage());
        body.put("needHumanHandoff", request.needHumanHandoff());
        body.put("collectTurns", request.collectTurns());
        body.put("lastQFocus", request.lastQFocus());
        body.put("wantsDirectReco", request.wantsDirectReco());
        body.put("hasPrimaryNeed", request.hasPrimaryNeed());
        body.put("recoverPending", request.recoverPending());
        body.put("recoverTargetIntent", request.recoverTargetIntent());
        body.put("recoverTargetIntentKey", request.recoverTargetIntentKey());
        body.put("recoverMode", request.recoverMode());

        RuntimeApiResponse response = scope.apiClient().saveHistorySummary(body);
        if (!response.success()) {
            log.warn(
                    "saveHistorySummary failed, conversationId={}, errorCode={}, error={}",
                    customerContext.normalizedConversationId(),
                    response.errorCode(),
                    response.error());
            throw new IllegalStateException(
                    "saveHistorySummary failed: "
                            + (response.error().isBlank() ? response.errorCode() : response.error()));
        }

        JsonNode data = response.data();
        return new HistorySummaryWriteResult(
                safe(data, "conversationId"),
                safe(data, "chatUser"),
                safe(data, "historySummary"),
                safe(data, "lastIntent"),
                safe(data, "lastNextStep"),
                data.path("summaryVersion").asInt(0));
    }

    private static String safe(JsonNode data, String field) {
        return data == null ? "" : data.path(field).asText("");
    }
}
