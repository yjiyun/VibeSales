package com.agentteams.salesagent.tool.session;

/**
 * {@code createOrResumeSession} 的最小业务快照。
 */
public record SessionBootstrapSnapshot(
        String sessionId,
        String sessionCode,
        String status,
        boolean isNewSession,
        boolean isNewCustomer,
        String sessionAction,
        String matchedCustomerBy) {}
