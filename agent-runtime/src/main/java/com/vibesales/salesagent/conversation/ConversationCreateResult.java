package com.agentteams.salesagent.conversation;

import java.time.Instant;

/**
 * 创建会话后的领域返回对象。
 */
public record ConversationCreateResult(
        String conversationId,
        String conversationName,
        String metadataJson,
        Instant createdAt) {}
