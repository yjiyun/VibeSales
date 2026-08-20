package com.agentteams.salesagent.context;

/**
 * 客服链路的业务上下文对象。
 *
 * <p>它承接网页或接口层传入的客户账号、集群、会话、用户等业务字段，属于正式项目里的“业务作用域”。
 * 当前阶段先做最小字段收口和默认值兜底，后续会继续补更多运行时字段。
 */
public final class CustomerContext {
    private final String clientCode;
    private final String cluster;
    private final String sceneCode;
    private final String runtimeAgentId;
    private final String version;
    private final String conversationId;
    private final String robotConversationId;
    private final String chatUser;
    private final String robotKey;
    private final String userId;
    private final String userName;
    private final String conversationName;
    private final String messageId;
    private final String sendTime;
    private final String addMsgCount;

    public CustomerContext(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            String conversationId,
            String robotConversationId,
            String chatUser,
            String robotKey,
            String userId,
            String userName,
            String conversationName,
            String messageId,
            String sendTime,
            String addMsgCount) {
        this.clientCode = clientCode;
        this.cluster = cluster;
        this.sceneCode = sceneCode;
        this.runtimeAgentId = runtimeAgentId;
        this.version = version;
        this.conversationId = conversationId;
        this.robotConversationId = robotConversationId;
        this.chatUser = chatUser;
        this.robotKey = robotKey;
        this.userId = userId;
        this.userName = userName;
        this.conversationName = conversationName;
        this.messageId = messageId;
        this.sendTime = sendTime;
        this.addMsgCount = addMsgCount;
    }

    public String clientCode() {
        return clientCode;
    }

    public String cluster() {
        return cluster;
    }

    public String sceneCode() {
        return sceneCode;
    }

    public String runtimeAgentId() {
        return runtimeAgentId;
    }

    public String version() {
        return version;
    }

    public String conversationId() {
        return conversationId;
    }

    public String chatUser() {
        return chatUser;
    }

    public String robotConversationId() {
        return robotConversationId;
    }

    public String robotKey() {
        return robotKey;
    }

    public String userId() {
        return userId;
    }

    public String userName() {
        return userName;
    }

    public String conversationName() {
        return conversationName;
    }

    public String messageId() {
        return messageId;
    }

    public String sendTime() {
        return sendTime;
    }

    public String addMsgCount() {
        return addMsgCount;
    }

    public String normalizedConversationId() {
        return isBlank(conversationId) ? "demo-conversation" : conversationId.trim();
    }

    public String normalizedChatUser() {
        return isBlank(chatUser) ? "guest-user" : chatUser.trim();
    }

    public String normalizedRobotConversationId() {
        return isBlank(robotConversationId) ? "robot-conversation-pending" : robotConversationId.trim();
    }

    public String normalizedRobotKey() {
        return isBlank(robotKey) ? "robot-key-pending" : robotKey.trim();
    }

    public String normalizedUserId() {
        return isBlank(userId) ? normalizedChatUser() : userId.trim();
    }

    public String normalizedUserName() {
        return isBlank(userName) ? normalizedChatUser() : userName.trim();
    }

    public String normalizedConversationName() {
        return isBlank(conversationName) ? normalizedChatUser() : conversationName.trim();
    }

    public String normalizedMessageId() {
        return isBlank(messageId) ? "demo-message-id" : messageId.trim();
    }

    public String normalizedSendTime() {
        return isBlank(sendTime) ? "pending-send-time" : sendTime.trim();
    }

    public String normalizedAddMsgCount() {
        return isBlank(addMsgCount) ? "0" : addMsgCount.trim();
    }

    public String normalizedSceneCode() {
        return isBlank(sceneCode) ? "sales-service" : sceneCode.trim();
    }

    public String normalizedClientCode() {
        return isBlank(clientCode) ? "pending-client-code" : clientCode.trim();
    }

    public String normalizedCluster() {
        return isBlank(cluster) ? "pending-cluster" : cluster.trim();
    }

    public String normalizedRuntimeAgentId() {
        return isBlank(runtimeAgentId) ? "" : runtimeAgentId.trim();
    }

    public String normalizedBlueprintVersion() {
        return isBlank(version) ? "" : version.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
