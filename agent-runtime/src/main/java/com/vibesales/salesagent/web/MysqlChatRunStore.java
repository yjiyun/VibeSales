package com.agentteams.salesagent.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.agent.ChatResponse;
import com.agentteams.salesagent.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 MySQL 的 chat run 持久化实现。
 */
public final class MysqlChatRunStore implements ChatRunStore {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AppConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String runTableName;
    private final String eventTableName;
    private final boolean postgresDialect;

    public MysqlChatRunStore(AppConfig config) {
        this.config = config;
        this.runTableName = validateTableName(config.chatRunTableName(), "AGENT_CHAT_RUN_TABLE");
        this.eventTableName =
                validateTableName(config.chatRunEventTableName(), "AGENT_CHAT_RUN_EVENT_TABLE");
        this.postgresDialect = resolveJdbcUrl(config).startsWith("jdbc:postgresql:");
        initializeSchema();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void createRun(RunCreate run) {
        String sql =
                "INSERT INTO "
                        + runTableName
                        + " (run_id, status, client_code, cluster_code, scene_code, runtime_agent_id,"
                        + " conversation_id, chat_user, message_preview, selector_mode, selection_id,"
                        + " request_json, created_at, updated_at, event_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(run.runId()));
            statement.setString(2, "running");
            statement.setString(3, emptyToNull(run.clientCode()));
            statement.setString(4, emptyToNull(run.cluster()));
            statement.setString(5, emptyToNull(run.sceneCode()));
            statement.setString(6, emptyToNull(run.runtimeAgentId()));
            statement.setString(7, emptyToNull(run.conversationId()));
            statement.setString(8, emptyToNull(run.chatUser()));
            statement.setString(9, emptyToNull(trimPreview(run.messagePreview(), 500)));
            statement.setString(10, emptyToNull(run.selectorMode()));
            statement.setString(11, emptyToNull(run.selectionId()));
            statement.setString(12, emptyToNull(run.requestJson()));
            statement.setTimestamp(13, Timestamp.from(run.createdAt()));
            statement.setTimestamp(14, Timestamp.from(run.createdAt()));
            statement.setInt(15, 0);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL chat run 写入失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void appendEvent(ChatRunEvent event) {
        String sql =
                "INSERT INTO "
                        + eventTableName
                        + " (run_id, seq, phase, step, status, label, node_type, node_name, elapsed_ms,"
                        + " detail_json, ts, terminal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + duplicateIgnoreClause();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(event.runId()));
            statement.setLong(2, event.seq());
            statement.setString(3, emptyToNull(event.phase()));
            statement.setString(4, emptyToNull(event.step()));
            statement.setString(5, emptyToNull(event.status()));
            statement.setString(6, emptyToNull(event.label()));
            statement.setString(7, emptyToNull(event.nodeType()));
            statement.setString(8, emptyToNull(event.nodeName()));
            if (event.elapsedMs() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, event.elapsedMs());
            }
            statement.setString(10, emptyToNull(serializeDetail(event.detail())));
            statement.setLong(11, event.ts());
            statement.setBoolean(12, event.terminal());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL chat run 事件写入失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void markCompleted(RunCompletion completion) {
        String sql =
                "UPDATE "
                        + runTableName
                        + " SET status = ?, failure_message = ?, response_json = ?, completed_at = ?,"
                        + " event_count = ?, updated_at = ? WHERE run_id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(completion.status()));
            statement.setString(2, emptyToNull(completion.failureMessage()));
            statement.setString(3, emptyToNull(serializeResponse(completion.response())));
            statement.setTimestamp(4, Timestamp.from(completion.completedAt()));
            statement.setInt(5, Math.max(completion.eventCount(), 0));
            statement.setTimestamp(6, Timestamp.from(completion.completedAt()));
            statement.setString(7, safe(completion.runId()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL chat run 完成态更新失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<RunSnapshot> loadRun(String runId) {
        String runSql =
                "SELECT run_id, created_at, completed_at, status, failure_message, response_json,"
                        + " client_code, cluster_code, scene_code, runtime_agent_id, conversation_id,"
                        + " chat_user, message_preview, selector_mode, selection_id, request_json"
                        + " FROM "
                        + runTableName
                        + " WHERE run_id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(runSql)) {
            statement.setString(1, safe(runId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                RunCreate run =
                        new RunCreate(
                                resultSet.getString("run_id"),
                                requiredInstant(resultSet, "created_at"),
                                safe(resultSet.getString("client_code")),
                                safe(resultSet.getString("cluster_code")),
                                safe(resultSet.getString("scene_code")),
                                safe(resultSet.getString("runtime_agent_id")),
                                safe(resultSet.getString("conversation_id")),
                                safe(resultSet.getString("chat_user")),
                                safe(resultSet.getString("message_preview")),
                                safe(resultSet.getString("selector_mode")),
                                safe(resultSet.getString("selection_id")),
                                safe(resultSet.getString("request_json")));
                Instant completedAt = optionalInstant(resultSet, "completed_at");
                String status = safe(resultSet.getString("status"));
                String failureMessage = safe(resultSet.getString("failure_message"));
                ChatResponse response = deserializeResponse(resultSet.getString("response_json"));
                return Optional.of(
                        new RunSnapshot(
                                run,
                                completedAt,
                                status,
                                failureMessage,
                                response,
                                loadEvents(connection, runId)));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL chat run 读取失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<RunHistoryItem> listRecent(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        String sql =
                "SELECT run_id, created_at, completed_at, status, client_code, cluster_code, scene_code,"
                        + " runtime_agent_id, conversation_id, chat_user, message_preview, selector_mode,"
                        + " selection_id, failure_message, response_json, event_count"
                        + " FROM "
                        + runTableName
                        + " ORDER BY created_at DESC LIMIT ?";
        List<RunHistoryItem> items = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, normalizedLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ChatResponse response = deserializeResponse(resultSet.getString("response_json"));
                    items.add(
                            new RunHistoryItem(
                                    safe(resultSet.getString("run_id")),
                                    requiredInstant(resultSet, "created_at"),
                                    optionalInstant(resultSet, "completed_at"),
                                    safe(resultSet.getString("status")),
                                    safe(resultSet.getString("client_code")),
                                    safe(resultSet.getString("cluster_code")),
                                    safe(resultSet.getString("scene_code")),
                                    safe(resultSet.getString("runtime_agent_id")),
                                    safe(resultSet.getString("conversation_id")),
                                    safe(resultSet.getString("chat_user")),
                                    safe(resultSet.getString("message_preview")),
                                    safe(resultSet.getString("selector_mode")),
                                    safe(resultSet.getString("selection_id")),
                                    response != null && response.resolvedBlueprint() != null
                                            ? safe(response.resolvedBlueprint().blueprintId())
                                            : "",
                                    response == null ? "" : trimPreview(response.reply(), 120),
                                    safe(resultSet.getString("failure_message")),
                                    Math.max(resultSet.getInt("event_count"), 0),
                                    true));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new IllegalStateException("MySQL chat run 历史列表读取失败: " + exception.getMessage(), exception);
        }
    }

    private List<ChatRunEvent> loadEvents(Connection connection, String runId) throws SQLException {
        String sql =
                "SELECT run_id, seq, phase, step, status, label, node_type, node_name, elapsed_ms,"
                        + " detail_json, ts, terminal"
                        + " FROM "
                        + eventTableName
                        + " WHERE run_id = ? ORDER BY seq ASC";
        List<ChatRunEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(runId));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Long elapsedMs =
                            resultSet.getObject("elapsed_ms") == null
                                    ? null
                                    : resultSet.getLong("elapsed_ms");
                    events.add(
                            new ChatRunEvent(
                                    safe(resultSet.getString("run_id")),
                                    resultSet.getLong("seq"),
                                    safe(resultSet.getString("phase")),
                                    safe(resultSet.getString("step")),
                                    safe(resultSet.getString("status")),
                                    safe(resultSet.getString("label")),
                                    safe(resultSet.getString("node_type")),
                                    safe(resultSet.getString("node_name")),
                                    elapsedMs,
                                    deserializeDetail(resultSet.getString("detail_json")),
                                    resultSet.getLong("ts"),
                                    resultSet.getBoolean("terminal")));
                }
            }
        }
        return events;
    }

    private void initializeSchema() {
        String runDdl =
                postgresDialect ? postgresRunDdl() : mysqlRunDdl();
        String eventDdl =
                postgresDialect ? postgresEventDdl() : mysqlEventDdl();
        try (Connection connection = openConnection();
                PreparedStatement runStatement = connection.prepareStatement(runDdl);
                PreparedStatement eventStatement = connection.prepareStatement(eventDdl)) {
            runStatement.execute();
            eventStatement.execute();
            initializeIndexes(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("chat run 表初始化失败: " + exception.getMessage(), exception);
        }
    }

    private Connection openConnection() throws SQLException {
        String jdbcUrl = resolveJdbcUrl(config);
        String username = resolveUsername(config);
        String password = resolvePassword(config);
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void initializeIndexes(Connection connection) throws SQLException {
        for (String ddl : indexDdls()) {
            try (PreparedStatement statement = connection.prepareStatement(ddl)) {
                statement.execute();
            }
        }
    }

    private List<String> indexDdls() {
        if (postgresDialect) {
            return List.of(
                    "CREATE INDEX IF NOT EXISTS idx_" + runTableName + "_created_at ON " + runTableName + " (created_at)",
                    "CREATE INDEX IF NOT EXISTS idx_" + runTableName + "_scope ON "
                            + runTableName
                            + " (client_code, cluster_code, runtime_agent_id)",
                    "CREATE INDEX IF NOT EXISTS idx_" + eventTableName + "_run_seq ON "
                            + eventTableName
                            + " (run_id, seq)");
        }
        return List.of();
    }

    private String duplicateIgnoreClause() {
        return postgresDialect
                ? " ON CONFLICT (run_id, seq) DO NOTHING"
                : " ON DUPLICATE KEY UPDATE seq = seq";
    }

    private String mysqlRunDdl() {
        return "CREATE TABLE IF NOT EXISTS "
                + runTableName
                + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "client_code VARCHAR(64) NULL,"
                + "cluster_code VARCHAR(64) NULL,"
                + "scene_code VARCHAR(128) NULL,"
                + "runtime_agent_id VARCHAR(128) NULL,"
                + "conversation_id VARCHAR(128) NULL,"
                + "chat_user VARCHAR(128) NULL,"
                + "message_preview VARCHAR(500) NULL,"
                + "selector_mode VARCHAR(32) NULL,"
                + "selection_id VARCHAR(191) NULL,"
                + "request_json LONGTEXT NULL,"
                + "failure_message LONGTEXT NULL,"
                + "response_json LONGTEXT NULL,"
                + "event_count INT NOT NULL DEFAULT 0,"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "completed_at TIMESTAMP NULL DEFAULT NULL,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_run_id (run_id),"
                + "KEY idx_created_at (created_at),"
                + "KEY idx_scope (client_code, cluster_code, runtime_agent_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    private String mysqlEventDdl() {
        return "CREATE TABLE IF NOT EXISTS "
                + eventTableName
                + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,"
                + "seq BIGINT NOT NULL,"
                + "phase VARCHAR(64) NULL,"
                + "step VARCHAR(128) NULL,"
                + "status VARCHAR(32) NULL,"
                + "label VARCHAR(255) NULL,"
                + "node_type VARCHAR(64) NULL,"
                + "node_name VARCHAR(255) NULL,"
                + "elapsed_ms BIGINT NULL,"
                + "detail_json LONGTEXT NULL,"
                + "ts BIGINT NOT NULL,"
                + "terminal TINYINT(1) NOT NULL DEFAULT 0,"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_run_seq (run_id, seq),"
                + "KEY idx_run_seq (run_id, seq)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    private String postgresRunDdl() {
        return "CREATE TABLE IF NOT EXISTS "
                + runTableName
                + " ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL UNIQUE,"
                + "status VARCHAR(16) NOT NULL,"
                + "client_code VARCHAR(64) NULL,"
                + "cluster_code VARCHAR(64) NULL,"
                + "scene_code VARCHAR(128) NULL,"
                + "runtime_agent_id VARCHAR(128) NULL,"
                + "conversation_id VARCHAR(128) NULL,"
                + "chat_user VARCHAR(128) NULL,"
                + "message_preview VARCHAR(500) NULL,"
                + "selector_mode VARCHAR(32) NULL,"
                + "selection_id VARCHAR(191) NULL,"
                + "request_json TEXT NULL,"
                + "failure_message TEXT NULL,"
                + "response_json TEXT NULL,"
                + "event_count INT NOT NULL DEFAULT 0,"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "completed_at TIMESTAMP NULL DEFAULT NULL,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    private String postgresEventDdl() {
        return "CREATE TABLE IF NOT EXISTS "
                + eventTableName
                + " ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,"
                + "seq BIGINT NOT NULL,"
                + "phase VARCHAR(64) NULL,"
                + "step VARCHAR(128) NULL,"
                + "status VARCHAR(32) NULL,"
                + "label VARCHAR(255) NULL,"
                + "node_type VARCHAR(64) NULL,"
                + "node_name VARCHAR(255) NULL,"
                + "elapsed_ms BIGINT NULL,"
                + "detail_json TEXT NULL,"
                + "ts BIGINT NOT NULL,"
                + "terminal BOOLEAN NOT NULL DEFAULT FALSE,"
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE (run_id, seq)"
                + ")";
    }

    private static String resolveJdbcUrl(AppConfig config) {
        String jdbcUrl = config.chatRunJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return config.resolvedMysqlJdbcUrl();
        }
        return jdbcUrl;
    }

    private static String resolveUsername(AppConfig config) {
        String username = config.chatRunJdbcUsername();
        if (username == null || username.isBlank()) {
            return config.mysqlUsername();
        }
        return username;
    }

    private static String resolvePassword(AppConfig config) {
        String password = config.chatRunJdbcPassword();
        if (password == null) {
            return config.mysqlPassword();
        }
        return password;
    }

    private String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception exception) {
            throw new IllegalStateException("chat run detail JSON 序列化失败", exception);
        }
    }

    private Map<String, Object> deserializeDetail(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("chat run detail JSON 反序列化失败", exception);
        }
    }

    private String serializeResponse(ChatResponse response) {
        if (response == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException("chat run response JSON 序列化失败", exception);
        }
    }

    private ChatResponse deserializeResponse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode resolvedBlueprint = root.path("resolvedBlueprint");
            ChatResponse.ResolvedBlueprintSummary blueprintSummary =
                    resolvedBlueprint.isObject()
                            ? new ChatResponse.ResolvedBlueprintSummary(
                                    safe(resolvedBlueprint.path("selectorMode").asText("")),
                                    safe(resolvedBlueprint.path("selectionId").asText("")),
                                    safe(resolvedBlueprint.path("blueprintId").asText("")),
                                    safe(resolvedBlueprint.path("runtimeMode").asText("")),
                                    resolvedBlueprint.path("stageCount").asInt(0),
                                    safe(resolvedBlueprint.path("sourceType").asText("")),
                                    safe(resolvedBlueprint.path("matchLevel").asText("")))
                            : null;
            return new ChatResponse(
                    safe(root.path("reply").asText("")),
                    safe(root.path("conversationId").asText("")),
                    safe(root.path("robotConversationId").asText("")),
                    safe(root.path("chatUser").asText("")),
                    safe(root.path("robotKey").asText("")),
                    safe(root.path("conversationName").asText("")),
                    safe(root.path("messageId").asText("")),
                    safe(root.path("recoveryMode").asText("")),
                    safe(root.path("targetIntent").asText("")),
                    safe(root.path("historySummary").asText("")),
                    safe(root.path("profileSummary").asText("")),
                    safe(root.path("queueVersion").asText("")),
                    blueprintSummary);
        } catch (Exception exception) {
            throw new IllegalStateException("chat run response JSON 反序列化失败", exception);
        }
    }

    private static Instant requiredInstant(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        if (timestamp == null) {
            throw new IllegalStateException("缺少必填时间字段: " + columnName);
        }
        return timestamp.toInstant();
    }

    private static Instant optionalInstant(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String validateTableName(String rawTableName, String envKey) {
        String value = rawTableName == null ? "" : rawTableName.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(envKey + " 不能为空。");
        }
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(envKey + " 只允许字母、数字和下划线。");
        }
        return value;
    }

    private static String trimPreview(String value, int maxLength) {
        String normalized = safe(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
