package com.agentteams.salesagent.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.agentteams.salesagent.blueprint.BlueprintSelection;

/**
 * 前端聊天请求体。
 *
 * <p>当前阶段同时兼容两种入参风格：
 * 1. 早期最小闭环使用的扁平字段
 * 2. 面向 `agent-connector` 接入的 `input_data` 结构化上下文
 */
public final class ChatRequest {
    public String message;
    public String conversationId;
    public String chatUser;
    public String clientCode;
    public String cluster;
    public String sceneCode;
    public String runtimeAgentId;
    public String version;
    public BlueprintSelection blueprintSelector;

    @JsonProperty("input_data")
    public ChatInputData inputData;
}
