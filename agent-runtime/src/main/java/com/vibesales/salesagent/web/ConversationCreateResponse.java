package com.vibesales.salesagent.web;

/**
 * 平台创建会话接口的返回体。
 */
public final class ConversationCreateResponse {
    private final String status;
    private final String conversationId;
    private final String conversationName;
    private final String createdAt;
    private final String metadataJson;

    public ConversationCreateResponse(
            String status,
            String conversationId,
            String conversationName,
            String createdAt,
            String metadataJson) {
        this.status = status;
        this.conversationId = conversationId;
        this.conversationName = conversationName;
        this.createdAt = createdAt;
        this.metadataJson = metadataJson;
    }

    public String getStatus() {
        return status;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getConversationName() {
        return conversationName;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getMetadataJson() {
        return metadataJson;
    }
}
