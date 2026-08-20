package com.agentteams.salesagent.conversation;

import java.time.Instant;

/**
 * 平台内部会话记录。
 *
 * <p>首版只承接会话 ID、展示名称、扩展元信息和创建时间，
 * 不在这里混入接入层外部锚点或业务运行态字段。
 */
public record ConversationRecord(
        String conversationId,
        String conversationName,
        String metadataJson,
        Instant createdAt) {}
