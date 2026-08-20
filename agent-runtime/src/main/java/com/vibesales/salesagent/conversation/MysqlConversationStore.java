package com.agentteams.salesagent.conversation;

import com.agentteams.salesagent.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 基于 MySQL 的平台会话存储实现。
 *
 * <p>首版只提供“创建会话记录”能力，并在初始化时自动建表，
 * 方便本地开发先快速验证创建会话接口。
 */
public final class MysqlConversationStore implements ConversationStore {
    private final AppConfig config;
    private final String tableName;

    public MysqlConversationStore(AppConfig config) {
        this.config = config;
        this.tableName = validateTableName(config.conversationTableName());
        initializeSchema();
    }

    @Override
    public ConversationRecord create(ConversationRecord record) {
        String sql =
                "INSERT INTO "
                        + tableName
                        + " (conversation_id, conversation_name, metadata_json, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.conversationId());
            statement.setString(2, emptyToNull(record.conversationName()));
            statement.setString(3, emptyToNull(record.metadataJson()));
            statement.setTimestamp(4, java.sql.Timestamp.from(record.createdAt()));
            statement.setTimestamp(5, java.sql.Timestamp.from(record.createdAt()));
            statement.executeUpdate();
            return record;
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL 会话记录写入失败: " + exception.getMessage(), exception);
        }
    }

    private void initializeSchema() {
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "conversation_id VARCHAR(64) NOT NULL,"
                        + "conversation_name VARCHAR(255) NULL,"
                        + "metadata_json LONGTEXT NULL,"
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "UNIQUE KEY uk_conversation_id (conversation_id),"
                        + "KEY idx_created_at (created_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.execute();
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL 会话表初始化失败: " + exception.getMessage(), exception);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                config.resolvedMysqlJdbcUrl(), config.mysqlUsername(), config.mysqlPassword());
    }

    private static String validateTableName(String rawTableName) {
        String value = (rawTableName == null || rawTableName.isBlank())
                ? "agent_conversations"
                : rawTableName.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("AGENT_CONVERSATION_TABLE 只允许字母、数字和下划线。");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
