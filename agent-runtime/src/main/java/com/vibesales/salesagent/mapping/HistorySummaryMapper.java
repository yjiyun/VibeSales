package com.agentteams.salesagent.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.agentteams.salesagent.tool.history.HistorySummarySnapshot;

/**
 * 把 {@code GET /history-summary} 的响应 DTO 转成 {@link HistorySummarySnapshot}。
 *
 * <p>后端这个接口返回约 40 个平铺字段（含完整的 {@code recover*} 系列、{@code latestProfileSnapshot}、
 * {@code missingFieldsSnapshot} 等），当前快照对象只消费其中三个——摘要文本、是否处于恢复待确认态、
 * 当前活跃意图码。剩余字段等对应的 Skill（{@code ConversationClosureSkill} 等）开发时再按需扩展，
 * 不预先把快照对象撑成 40 个字段的大对象。
 */
public final class HistorySummaryMapper {

    private HistorySummaryMapper() {}

    /**
     * @param data {@code GET /history-summary} 成功响应里的 {@code data} 节点
     */
    public static HistorySummarySnapshot fromResponse(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return emptyHistory();
        }

        String summaryText = data.path("historySummary").asText("");
        if (summaryText.isBlank()) {
            summaryText = "后端暂无该会话的历史摘要（新会话或尚未产生摘要）。";
        }

        // recoverPending 是 RecoveryDetectionRule 的强信号输入：为 true 时无条件判定为续接消息
        boolean recoveryPending = data.path("recoverPending").asBoolean(false);
        String activeIntentCode = data.path("activeIntentCode").asText("");

        return new HistorySummarySnapshot(summaryText, recoveryPending, activeIntentCode);
    }

    public static HistorySummarySnapshot emptyHistory() {
        return new HistorySummarySnapshot("后端暂无该会话的历史摘要记录。", false, "");
    }
}
