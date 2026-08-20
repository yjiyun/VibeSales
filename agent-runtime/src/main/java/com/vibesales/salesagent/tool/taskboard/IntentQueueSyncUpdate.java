package com.agentteams.salesagent.tool.taskboard;

import java.util.Map;

/**
 * {@code syncIntentQueue} 的单条更新动作。
 */
public record IntentQueueSyncUpdate(
        String intentKey,
        String intentCode,
        String action,
        String priority,
        String context,
        String taskSummary,
        Map<String, Object> surfaceSignals,
        Integer lastActiveTurn) {}
