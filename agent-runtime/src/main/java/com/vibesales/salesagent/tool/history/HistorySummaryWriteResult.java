package com.agentteams.salesagent.tool.history;

/**
 * 历史摘要写回结果快照。
 */
public record HistorySummaryWriteResult(
        String conversationId,
        String chatUser,
        String historySummary,
        String lastIntent,
        String lastNextStep,
        int summaryVersion) {}
