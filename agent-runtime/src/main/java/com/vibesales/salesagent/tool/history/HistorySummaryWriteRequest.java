package com.agentteams.salesagent.tool.history;

/**
 * 写回历史摘要所需的最小字段。
 */
public record HistorySummaryWriteRequest(
        String historySummary,
        String latestUserMessage,
        String latestAgentReply,
        String lastIntent,
        String lastRouteTarget,
        String lastNextStep,
        String lastReplyScenario,
        String lastFlowStage,
        boolean needHumanHandoff,
        int collectTurns,
        String lastQFocus,
        boolean wantsDirectReco,
        boolean hasPrimaryNeed,
        boolean recoverPending,
        String recoverTargetIntent,
        String recoverTargetIntentKey,
        String recoverMode) {}
