package com.vibesales.salesagent.blueprint;

import com.vibesales.salesagent.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 直接从 PostgreSQL 读取已发布 Blueprint。
 *
 * <p>这条来源是用户明确选择的“路线 B”：下游直接连 PG，而不是通过 HTTP 拉已发布快照。
 * 为了降低对上游 schema 的耦合，这里只依赖最小字段：
 *
 * <ul>
 *   <li>{@code agent_blueprint.client_code}
 *   <li>{@code agent_blueprint.status}
 *   <li>{@code agent_blueprint.payload}
 *   <li>{@code agent_blueprint.blueprint_id}
 *   <li>{@code agent_blueprint.version}
 * </ul>
 *
 * <p>路由逻辑仍保持本工程口径：按 {@code clientCode + cluster} 选蓝图，{@code sceneCode} 只做可选增强筛选。
 */
public final class JdbcPublishedBlueprintSource implements BlueprintSource {

    public static final String SOURCE_TYPE = "jdbc_published";

    private static final String SQL_SELECT_PUBLISHED =
            "select blueprint_id, version, payload::text "
                    + "from agent_blueprint "
                    + "where client_code = ? and status = 'PUBLISHED' "
                    + "order by version desc";

    private static final String SQL_LIST_PUBLISHED =
            "select blueprint_id, version, payload::text "
                    + "from agent_blueprint "
                    + "where status = 'PUBLISHED' "
                    + "order by client_code asc, version desc";

    private final AppConfig config;
    private final AgentBlueprintLoader loader;
    private final RowProvider rowProvider;

    public JdbcPublishedBlueprintSource(AppConfig config) {
        this(config, new AgentBlueprintLoader(), new PostgresRowProvider(config));
    }

    JdbcPublishedBlueprintSource(AppConfig config, AgentBlueprintLoader loader, RowProvider rowProvider) {
        this.config = config;
        this.loader = loader;
        this.rowProvider = rowProvider;
    }

    @Override
    public boolean hasAnyBlueprint() {
        if (!jdbcEnabled()) {
            return false;
        }
        return !listScopes().isEmpty();
    }

    @Override
    public List<Map<String, String>> listScopes() {
        if (!jdbcEnabled()) {
            return List.of();
        }
        List<StoredBlueprintRow> rows = rowProvider.loadAllPublished();
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        List<Map<String, String>> scopes = new ArrayList<>();
        for (StoredBlueprintRow row : rows) {
            AgentBlueprint blueprint = loader.parse(row.payloadJson(), "jdbc:" + row.blueprintId());
            String clientCode = safe(blueprint.clientCode());
            String cluster = safe(blueprint.clusterOrEmpty());
            String runtimeAgentId = safe(blueprint.runtimeAgentId());
            String dedupKey = clientCode + "||" + cluster + "||" + runtimeAgentId;
            if (!dedup.add(dedupKey)) {
                continue;
            }
            Map<String, String> scope = new LinkedHashMap<>();
            scope.put("clientCode", clientCode);
            scope.put("cluster", cluster);
            scope.put("runtimeAgentId", runtimeAgentId);
            scope.put("source", "jdbc_published_pg");
            scope.put("blueprintId", safe(blueprint.blueprintId()));
            scope.put("version", String.valueOf(blueprint.version()));
            scopes.add(Map.copyOf(scope));
        }
        return List.copyOf(scopes);
    }

    @Override
    public Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return resolve(clientCode, cluster, sceneCode, runtimeAgentId, version, "");
    }

    @Override
    public Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            String userId) {
        if (!jdbcEnabled()) {
            return Optional.empty();
        }
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        String normalizedSceneCode = safe(sceneCode);
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        String normalizedUserId = safe(userId);
        Integer requestedVersion = parseRequestedVersion(version);
        if (normalizedClientCode.isEmpty()) {
            return Optional.empty();
        }
        if (!normalizedRuntimeAgentId.isEmpty() && !normalizedUserId.isEmpty()) {
            Optional<BlueprintHandle> bound =
                    resolveByTenantBinding(
                            normalizedClientCode,
                            normalizedCluster,
                            normalizedSceneCode,
                            normalizedRuntimeAgentId,
                            normalizedUserId,
                            requestedVersion);
            if (bound.isPresent()) {
                return bound;
            }
        }
        List<StoredBlueprintRow> rows = rowProvider.loadPublishedByClientCode(normalizedClientCode);
        Candidate best = null;
        for (StoredBlueprintRow row : rows) {
            AgentBlueprint blueprint = loader.parse(row.payloadJson(), "jdbc:" + row.blueprintId());
            Candidate candidate =
                    classifyCandidate(
                            row,
                            blueprint,
                            normalizedClientCode,
                            normalizedCluster,
                            normalizedSceneCode,
                            normalizedRuntimeAgentId,
                            requestedVersion);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.sortKey() < best.sortKey()) {
                best = candidate;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(
                new BlueprintHandle(
                        best.blueprint(),
                        "jdbc:" + best.row().blueprintId() + "@v" + best.row().version(),
                        SOURCE_TYPE,
                        best.matchLevel(),
                        best.blueprint().clusterOrEmpty()));
    }

    private Optional<BlueprintHandle> resolveByTenantBinding(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String userId,
            Integer requestedVersion) {
        Optional<BoundBlueprintRow> bound =
                rowProvider.loadTenantBoundPublished(
                        clientCode, cluster, runtimeAgentId, userId, requestedVersion);
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        BoundBlueprintRow row = bound.get();
        AgentBlueprint blueprint = loader.parse(row.payloadJson(), "jdbc:" + row.blueprintId());
        Candidate candidate =
                classifyCandidate(
                        new StoredBlueprintRow(row.blueprintId(), row.version(), row.payloadJson()),
                        blueprint,
                        clientCode,
                        row.matchedCluster(),
                        sceneCode,
                        runtimeAgentId,
                        requestedVersion);
        if (candidate == null) {
            return Optional.empty();
        }
        return Optional.of(
                new BlueprintHandle(
                        candidate.blueprint(),
                        "jdbc:" + row.blueprintId() + "@v" + row.version(),
                        SOURCE_TYPE,
                        row.matchLevel(),
                        row.matchedCluster()));
    }

    private Candidate classifyCandidate(
            StoredBlueprintRow row,
            AgentBlueprint blueprint,
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            Integer requestedVersion) {
        if (!clientCode.equals(safe(blueprint.clientCode()))) {
            return null;
        }
        if (!runtimeAgentId.isEmpty() && !runtimeAgentId.equals(safe(blueprint.runtimeAgentId()))) {
            return null;
        }
        if (requestedVersion != null && requestedVersion.intValue() != blueprint.version()) {
            return null;
        }
        String blueprintCluster = safe(blueprint.clusterOrEmpty());
        String matchLevel;
        int clusterRank;
        if (!cluster.isEmpty() && cluster.equals(blueprintCluster)) {
            matchLevel = AgentBlueprintRepository.MATCH_EXACT;
            clusterRank = 0;
        } else if (blueprintCluster.isEmpty()) {
            matchLevel =
                    cluster.isEmpty()
                            ? AgentBlueprintRepository.MATCH_DEFAULT
                            : AgentBlueprintRepository.MATCH_FALLBACK;
            clusterRank = cluster.isEmpty() ? 1 : 2;
        } else {
            return null;
        }
        int sceneRank = sceneRank(blueprint, sceneCode);
        if (sceneRank == Integer.MAX_VALUE) {
            return null;
        }
        int versionRank = 10_000 - Math.min(blueprint.version(), 10_000);
        int sortKey = clusterRank * 100_000 + sceneRank * 10_000 + versionRank;
        return new Candidate(row, blueprint, matchLevel, sortKey);
    }

    private int sceneRank(AgentBlueprint blueprint, String sceneCode) {
        List<String> scenarios = blueprint.metaOrEmpty().scenarios();
        if (sceneCode.isEmpty()) {
            return scenarios.isEmpty() ? 1 : 0;
        }
        if (scenarios.isEmpty()) {
            return 1;
        }
        for (String scenario : scenarios) {
            if (sceneCode.equals(safe(scenario))) {
                return 0;
            }
        }
        return Integer.MAX_VALUE;
    }

    private boolean jdbcEnabled() {
        return "jdbc".equalsIgnoreCase(config.blueprintSource()) || config.blueprintJdbcConfigured();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static Integer parseRequestedVersion(String value) {
        String normalized = safe(value);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("blueprint version must be an integer: " + normalized);
        }
    }

    record StoredBlueprintRow(String blueprintId, int version, String payloadJson) {}

    record BoundBlueprintRow(
            String blueprintId,
            int version,
            String payloadJson,
            String matchedCluster,
            String matchLevel) {}

    private record Candidate(
            StoredBlueprintRow row, AgentBlueprint blueprint, String matchLevel, int sortKey) {}

    interface RowProvider {
        List<StoredBlueprintRow> loadPublishedByClientCode(String clientCode);

        List<StoredBlueprintRow> loadAllPublished();

        default Optional<BoundBlueprintRow> loadTenantBoundPublished(
                String clientCode,
                String cluster,
                String runtimeAgentId,
                String tenantUserId,
                Integer requestedVersion) {
            return Optional.empty();
        }
    }

    static final class PostgresRowProvider implements RowProvider {
        private static final String SQL_SELECT_BOUND_WITH_CLUSTER =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and a.cluster = ? and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_SELECT_BOUND_WITH_CLUSTER_AND_VERSION =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and a.cluster = ? and a.projected_version = ? and b.version = ? "
                        + "and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_SELECT_BOUND_WITHOUT_CLUSTER =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and coalesce(a.cluster, '') = '' and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_SELECT_BOUND_WITHOUT_CLUSTER_AND_VERSION =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and coalesce(a.cluster, '') = '' and a.projected_version = ? and b.version = ? "
                        + "and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_SELECT_BOUND_LEGACY =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_SELECT_BOUND_LEGACY_AND_VERSION =
                "select b.blueprint_id, b.version, b.payload::text "
                        + "from agent_binding a "
                        + "join agent_blueprint b on b.blueprint_id = a.blueprint_id "
                        + "where a.client_code = ? and a.user_id = ? and a.runtime_agent_id = ? "
                        + "and a.projected_version = ? and b.version = ? and b.status = 'PUBLISHED' "
                        + "order by b.version desc limit 1";
        private static final String SQL_CHECK_CLUSTER_COLUMN =
                "select exists ("
                        + "select 1 from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'agent_binding' and column_name = 'cluster'"
                        + ")";

        private final AppConfig config;
        private volatile Boolean clusterColumnSupported;

        PostgresRowProvider(AppConfig config) {
            this.config = config;
        }

        @Override
        public List<StoredBlueprintRow> loadPublishedByClientCode(String clientCode) {
            return query(SQL_SELECT_PUBLISHED, clientCode, statement -> statement.setString(1, clientCode));
        }

        @Override
        public List<StoredBlueprintRow> loadAllPublished() {
            return query(SQL_LIST_PUBLISHED, "", statement -> {});
        }

        @Override
        public Optional<BoundBlueprintRow> loadTenantBoundPublished(
                String clientCode,
                String cluster,
                String runtimeAgentId,
                String tenantUserId,
                Integer requestedVersion) {
            String normalizedClientCode = safe(clientCode);
            String normalizedCluster = safe(cluster);
            String normalizedRuntimeAgentId = safe(runtimeAgentId);
            String normalizedTenantUserId = safe(tenantUserId);
            if (normalizedClientCode.isEmpty()
                    || normalizedRuntimeAgentId.isEmpty()
                    || normalizedTenantUserId.isEmpty()) {
                return Optional.empty();
            }
            if (clusterColumnSupported()) {
                if (!normalizedCluster.isEmpty()) {
                    Optional<StoredBlueprintRow> exact =
                            queryFirst(
                                    requestedVersion == null
                                            ? SQL_SELECT_BOUND_WITH_CLUSTER
                                            : SQL_SELECT_BOUND_WITH_CLUSTER_AND_VERSION,
                                    normalizedClientCode,
                                    statement -> {
                                        statement.setString(1, normalizedClientCode);
                                        statement.setString(2, normalizedTenantUserId);
                                        statement.setString(3, normalizedRuntimeAgentId);
                                        statement.setString(4, normalizedCluster);
                                        if (requestedVersion != null) {
                                            statement.setInt(5, requestedVersion);
                                            statement.setInt(6, requestedVersion);
                                        }
                                    });
                    if (exact.isPresent()) {
                        StoredBlueprintRow row = exact.get();
                        return Optional.of(
                                new BoundBlueprintRow(
                                        row.blueprintId(),
                                        row.version(),
                                        row.payloadJson(),
                                        normalizedCluster,
                                        AgentBlueprintRepository.MATCH_EXACT));
                    }
                }
                Optional<StoredBlueprintRow> tenantDefault =
                        queryFirst(
                                requestedVersion == null
                                        ? SQL_SELECT_BOUND_WITHOUT_CLUSTER
                                        : SQL_SELECT_BOUND_WITHOUT_CLUSTER_AND_VERSION,
                                normalizedClientCode,
                                statement -> {
                                    statement.setString(1, normalizedClientCode);
                                    statement.setString(2, normalizedTenantUserId);
                                    statement.setString(3, normalizedRuntimeAgentId);
                                    if (requestedVersion != null) {
                                        statement.setInt(4, requestedVersion);
                                        statement.setInt(5, requestedVersion);
                                    }
                                });
                if (tenantDefault.isEmpty()) {
                    return Optional.empty();
                }
                StoredBlueprintRow row = tenantDefault.get();
                return Optional.of(
                        new BoundBlueprintRow(
                                row.blueprintId(),
                                row.version(),
                                row.payloadJson(),
                                "",
                                normalizedCluster.isEmpty()
                                        ? AgentBlueprintRepository.MATCH_DEFAULT
                                        : AgentBlueprintRepository.MATCH_FALLBACK));
            }
            Optional<StoredBlueprintRow> legacy =
                    queryFirst(
                            requestedVersion == null
                                    ? SQL_SELECT_BOUND_LEGACY
                                    : SQL_SELECT_BOUND_LEGACY_AND_VERSION,
                            normalizedClientCode,
                            statement -> {
                                statement.setString(1, normalizedClientCode);
                                statement.setString(2, normalizedTenantUserId);
                                statement.setString(3, normalizedRuntimeAgentId);
                                if (requestedVersion != null) {
                                    statement.setInt(4, requestedVersion);
                                    statement.setInt(5, requestedVersion);
                                }
                            });
            if (legacy.isEmpty()) {
                return Optional.empty();
            }
            StoredBlueprintRow row = legacy.get();
            return Optional.of(
                    new BoundBlueprintRow(
                            row.blueprintId(),
                            row.version(),
                            row.payloadJson(),
                            "",
                            normalizedCluster.isEmpty()
                                    ? AgentBlueprintRepository.MATCH_DEFAULT
                                    : AgentBlueprintRepository.MATCH_FALLBACK));
        }

        private List<StoredBlueprintRow> query(String sql, String clientCode, StatementBinder binder) {
            Objects.requireNonNull(sql, "sql");
            try (Connection connection =
                            DriverManager.getConnection(
                                    config.blueprintJdbcUrl(),
                                    config.blueprintJdbcUsername(),
                                    config.blueprintJdbcPassword());
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                installTenantGuc(connection, clientCode);
                statement.setQueryTimeout(Math.max(1, config.blueprintJdbcQueryTimeoutSeconds()));
                binder.bind(statement);
                try (ResultSet rs = statement.executeQuery()) {
                    List<StoredBlueprintRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new StoredBlueprintRow(rs.getString(1), rs.getInt(2), rs.getString(3)));
                    }
                    connection.commit();
                    return List.copyOf(rows);
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "JDBC published blueprint query failed: " + exception.getMessage(), exception);
            }
        }

        private Optional<StoredBlueprintRow> queryFirst(
                String sql, String clientCode, StatementBinder binder) {
            List<StoredBlueprintRow> rows = query(sql, clientCode, binder);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        private boolean clusterColumnSupported() {
            Boolean cached = clusterColumnSupported;
            if (cached != null) {
                return cached;
            }
            synchronized (this) {
                if (clusterColumnSupported != null) {
                    return clusterColumnSupported;
                }
                clusterColumnSupported = queryBoolean(SQL_CHECK_CLUSTER_COLUMN, statement -> {});
                return clusterColumnSupported;
            }
        }

        /**
         * 设置本连接的租户 GUC，供业务表的 {@code client_code = current_setting('app.client_code', true)}
         * RLS 策略使用。
         *
         * <p>{@code clientCode} 为空时（{@code loadAllPublished}/schema 探测这类跨租户或非业务表查询）
         * 沿用一个不对应任何真实租户的占位符——RLS 策略下这会让业务表查询返回 0 行而不是报错，
         * 是刻意的 fail-closed，不是"随便填一个值"。业务表的单租户查询必须传真实 {@code clientCode}，
         * 否则同样会被 RLS 挡成空结果，且不会有任何异常提示（之前这里一直传死的占位符，
         * 表现为"蓝图已发布，试聊却查不到"）。
         */
        private void installTenantGuc(Connection connection, String clientCode) {
            String value = clientCode == null || clientCode.isBlank() ? "jdbc-blueprint-reader" : clientCode.trim();
            try (PreparedStatement set =
                    connection.prepareStatement("select set_config('app.client_code', ?, true)")) {
                set.setString(1, value);
                set.execute();
            } catch (Exception ignored) {
                // 某些库没有 RLS/GUC 约束；这里是兼容性 best-effort，不作为失败条件。
            }
        }

        private boolean queryBoolean(String sql, StatementBinder binder) {
            Objects.requireNonNull(sql, "sql");
            try (Connection connection =
                            DriverManager.getConnection(
                                    config.blueprintJdbcUrl(),
                                    config.blueprintJdbcUsername(),
                                    config.blueprintJdbcPassword());
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                installTenantGuc(connection, "");
                statement.setQueryTimeout(Math.max(1, config.blueprintJdbcQueryTimeoutSeconds()));
                binder.bind(statement);
                try (ResultSet rs = statement.executeQuery()) {
                    boolean result = rs.next() && rs.getBoolean(1);
                    connection.commit();
                    return result;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "JDBC schema probe failed: " + exception.getMessage(), exception);
            }
        }
    }

    @FunctionalInterface
    interface StatementBinder {
        void bind(PreparedStatement statement) throws Exception;
    }
}
