package com.agentteams.salesagent.conversation;

/**
 * 平台会话存储抽象。
 *
 * <p>当前先只暴露“创建会话记录”这一条最小能力，
 * 后续如需要补会话查询、详情、生命周期管理，再继续扩展。
 */
public interface ConversationStore {

    ConversationRecord create(ConversationRecord record);
}
