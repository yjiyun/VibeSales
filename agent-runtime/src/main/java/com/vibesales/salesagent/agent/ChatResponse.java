package com.vibesales.salesagent.agent;

/**
 * 网页接口返回给前端的统一响应对象。
 *
 * <p>它把最小闭环阶段关心的几个核心信息一起返回给 HTML 页面：
 * 智能体回复、恢复模式、目标意图、摘要占位、画像占位、任务板版本，以及关键会话上下文字段。
 */
public final class ChatResponse {
    private final String reply;
    private final String conversationId;
    private final String robotConversationId;
    private final String chatUser;
    private final String robotKey;
    private final String conversationName;
    private final String messageId;
    private final String recoveryMode;
    private final String targetIntent;
    private final String historySummary;
    private final String profileSummary;
    private final String queueVersion;
    private final ResolvedBlueprintSummary resolvedBlueprint;

    public ChatResponse(
            String reply,
            String conversationId,
            String robotConversationId,
            String chatUser,
            String robotKey,
            String conversationName,
            String messageId,
            String recoveryMode,
            String targetIntent,
            String historySummary,
            String profileSummary,
            String queueVersion,
            ResolvedBlueprintSummary resolvedBlueprint) {
        this.reply = reply;
        this.conversationId = conversationId;
        this.robotConversationId = robotConversationId;
        this.chatUser = chatUser;
        this.robotKey = robotKey;
        this.conversationName = conversationName;
        this.messageId = messageId;
        this.recoveryMode = recoveryMode;
        this.targetIntent = targetIntent;
        this.historySummary = historySummary;
        this.profileSummary = profileSummary;
        this.queueVersion = queueVersion;
        this.resolvedBlueprint = resolvedBlueprint;
    }

    public String reply() {
        return reply;
    }

    public String getReply() {
        return reply;
    }

    public String conversationId() {
        return conversationId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String chatUser() {
        return chatUser;
    }

    public String getChatUser() {
        return chatUser;
    }

    public String robotConversationId() {
        return robotConversationId;
    }

    public String getRobotConversationId() {
        return robotConversationId;
    }

    public String robotKey() {
        return robotKey;
    }

    public String getRobotKey() {
        return robotKey;
    }

    public String conversationName() {
        return conversationName;
    }

    public String getConversationName() {
        return conversationName;
    }

    public String messageId() {
        return messageId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String recoveryMode() {
        return recoveryMode;
    }

    public String getRecoveryMode() {
        return recoveryMode;
    }

    public String targetIntent() {
        return targetIntent;
    }

    public String getTargetIntent() {
        return targetIntent;
    }

    public String historySummary() {
        return historySummary;
    }

    public String getHistorySummary() {
        return historySummary;
    }

    public String profileSummary() {
        return profileSummary;
    }

    public String getProfileSummary() {
        return profileSummary;
    }

    public String queueVersion() {
        return queueVersion;
    }

    public String getQueueVersion() {
        return queueVersion;
    }

    public ResolvedBlueprintSummary resolvedBlueprint() {
        return resolvedBlueprint;
    }

    public ResolvedBlueprintSummary getResolvedBlueprint() {
        return resolvedBlueprint;
    }

    public record ResolvedBlueprintSummary(
            String selectorMode,
            String selectionId,
            String blueprintId,
            String runtimeMode,
            int stageCount,
            String sourceType,
            String matchLevel) {}
}
