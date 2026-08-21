package com.vibesales.salesagent.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 平台创建会话接口的请求体。
 *
 * <p>首版保持极简，只接收通用的会话名称和元信息，
 * 不把接入层特有字段耦合进正式项目的创建会话能力。
 */
public final class ConversationCreateRequest {
    public String conversationName;

    @JsonProperty("metadata")
    public JsonNode metadata;
}
