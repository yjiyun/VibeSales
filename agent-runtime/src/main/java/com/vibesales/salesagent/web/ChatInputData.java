package com.agentteams.salesagent.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 接入层透传给正式项目的 `input_data` 对象。
 *
 * <p>它用于承接 `agent-connector` 或其他上游系统传来的稳定上下文，
 * 包括企业微信原始会话标识、发送者信息、消息追踪字段和项目内部会话 ID。
 */
public final class ChatInputData {
    public String conversationName;
    public String sendTime;
    public String runtimeAgentId;
    public String version;

    @JsonProperty("user_name")
    public String userName;

    public String messageId;
    public String robotKey;

    @JsonProperty("user_id")
    public String userId;

    public String conversationId;
    public String addMsgCount;
    public String robotConversationId;
    public String chatUser;
}
