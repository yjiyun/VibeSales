package com.agentteams.salesagent.tool.taskboard;

/**
 * 任务板同步结果快照。
 */
public record IntentQueueSyncResult(
        String conversationId,
        String chatUser,
        int queueVersion,
        String activeIntentCode,
        String activeIntentKey,
        String taskBoardSummary) {}
