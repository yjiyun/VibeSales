package com.agentteams.salesagent.tool.taskboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.integration.runtime.RuntimeApiResponse;
import com.agentteams.salesagent.tool.RuntimeToolScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务板同步 Tool。
 */
public final class SyncIntentQueueTool {

    private static final Logger log = LoggerFactory.getLogger(SyncIntentQueueTool.class);

    private final RuntimeToolScope scope;

    public SyncIntentQueueTool() {
        this(RuntimeToolScope.disabled());
    }

    public SyncIntentQueueTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public IntentQueueSyncResult sync(
            CustomerContext customerContext,
            String sessionId,
            String queueVersion,
            List<IntentQueueSyncUpdate> updates) {
        if (!scope.available()) {
            return new IntentQueueSyncResult(
                    customerContext.normalizedConversationId(),
                    customerContext.normalizedChatUser(),
                    parseQueueVersion(queueVersion),
                    "",
                    "",
                    "");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCode", scope.resolveClientCode(customerContext));
        body.put("cluster", scope.resolveCluster(customerContext));
        body.put("sceneCode", scope.resolveSceneCode(customerContext));
        body.put("conversationId", customerContext.normalizedConversationId());
        body.put("chatUser", customerContext.normalizedChatUser());
        body.put("sessionId", sessionId);
        body.put("queueVersion", parseQueueVersion(queueVersion));
        body.put("updates", toPayload(updates));

        RuntimeApiResponse response = scope.apiClient().syncIntentQueue(body);
        if (!response.success()) {
            log.warn(
                    "syncIntentQueue failed, conversationId={}, errorCode={}, error={}",
                    customerContext.normalizedConversationId(),
                    response.errorCode(),
                    response.error());
            throw new IllegalStateException(
                    "syncIntentQueue failed: "
                            + (response.error().isBlank() ? response.errorCode() : response.error()));
        }

        JsonNode data = response.data();
        return new IntentQueueSyncResult(
                safe(data, "conversationId"),
                safe(data, "chatUser"),
                data.path("queueVersion").asInt(parseQueueVersion(queueVersion)),
                safe(data, "activeIntentCode"),
                safe(data, "activeIntentKey"),
                safe(data, "taskBoardSummary"));
    }

    private static List<Map<String, Object>> toPayload(List<IntentQueueSyncUpdate> updates) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (IntentQueueSyncUpdate update : updates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intentKey", update.intentKey());
            item.put("intentCode", update.intentCode());
            item.put("action", update.action());
            item.put("priority", update.priority());
            item.put("context", update.context());
            item.put("taskSummary", update.taskSummary());
            item.put("surfaceSignals", update.surfaceSignals());
            item.put("lastActiveTurn", update.lastActiveTurn());
            payload.add(item);
        }
        return payload;
    }

    private static int parseQueueVersion(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value.trim());
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String safe(JsonNode data, String field) {
        return data == null ? "" : data.path(field).asText("");
    }
}
