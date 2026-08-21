package com.vibesales.salesagent.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibesales.salesagent.config.AppConfig;
import java.time.Instant;

/**
 * 平台创建会话服务。
 *
 * <p>它只负责生成新的平台 `conversationId` 并写入正式项目自己的会话表，
 * 不承担外部接入层会话映射逻辑。
 */
public final class ConversationService {
    private final AppConfig config;
    private final ConversationIdGenerator idGenerator = new ConversationIdGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStore store;

    public ConversationService(AppConfig config) {
        this.config = config;
        this.store = config.mysqlConfigured() ? new MysqlConversationStore(config) : null;
    }

    public ConversationCreateResult createConversation(String conversationName, JsonNode metadata) {
        if (!config.mysqlConfigured()) {
            throw new IllegalStateException(
                    "MySQL 会话存储未配置，请检查 AGENT_MYSQL_DATABASE / AGENT_MYSQL_USERNAME "
                            + "/ AGENT_MYSQL_PASSWORD（可为空）/ AGENT_MYSQL_HOST / AGENT_MYSQL_PORT "
                            + "或直接提供 AGENT_MYSQL_JDBC_URL。");
        }
        String conversationId = idGenerator.nextId();
        Instant createdAt = Instant.now();
        ConversationRecord record =
                new ConversationRecord(
                        conversationId,
                        normalizeName(conversationName),
                        serializeMetadata(metadata),
                        createdAt);
        ConversationRecord saved = store.create(record);
        return new ConversationCreateResult(
                saved.conversationId(), saved.conversationName(), saved.metadataJson(), saved.createdAt());
    }

    private String normalizeName(String conversationName) {
        if (conversationName == null || conversationName.isBlank()) {
            return "";
        }
        return conversationName.trim();
    }

    private String serializeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull() || metadata.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata 不是可序列化的 JSON 对象。", exception);
        }
    }
}
