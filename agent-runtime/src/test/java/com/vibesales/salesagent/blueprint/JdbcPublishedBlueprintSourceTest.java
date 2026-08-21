package com.agentteams.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcPublishedBlueprintSourceTest {
    private static final String TEST_RUNTIME_AGENT_ID = "yjiyuncom_test_agent";
    private static final String DEFAULT_RUNTIME_AGENT_ID = "yjiyuncom_default_agent";

    @Test
    void shouldPreferExactClusterMatch() throws Exception {
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProvider(
                                rows(
                                        "blueprints/yjiyuncom.test.json",
                                        "blueprints/yjiyuncom.default.json")));

        BlueprintSource.BlueprintHandle handle =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", TEST_RUNTIME_AGENT_ID, "")
                        .orElseThrow();

        assertEquals("yjiyuncom_test_v1", handle.blueprint().blueprintId());
        assertEquals(AgentBlueprintRepository.MATCH_EXACT, handle.matchLevel());
        assertEquals("test", handle.matchedCluster());
    }

    @Test
    void shouldFallbackToTenantDefaultWhenClusterMissing() throws Exception {
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProvider(rows("blueprints/yjiyuncom.default.json")));

        BlueprintSource.BlueprintHandle handle =
                source.resolve(
                                "yjiyuncom",
                                "unknown-cluster",
                                "BEAUTY_SKINCARE",
                                DEFAULT_RUNTIME_AGENT_ID,
                                "")
                        .orElseThrow();

        assertEquals("yjiyuncom_default_v1", handle.blueprint().blueprintId());
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, handle.matchLevel());
        assertEquals("", handle.matchedCluster());
    }

    @Test
    void shouldFilterOutScenarioMismatchWhenSpecificSceneRequested() throws Exception {
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProvider(rows("blueprints/hanmei_apparel.default.json")));

        assertTrue(
                source.resolve(
                                "hanmei_apparel",
                                "",
                                "BEAUTY_SKINCARE",
                                "hanmei_apparel_service_agent",
                                "")
                        .isEmpty());
    }

    @Test
    void shouldListDistinctScopes() throws Exception {
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProvider(
                                rows(
                                        "blueprints/yjiyuncom.test.json",
                                        "blueprints/yjiyuncom.default.json",
                                        "blueprints/yjiyuncom.test.json")));

        List<Map<String, String>> scopes = source.listScopes();

        assertEquals(2, scopes.size());
        assertEquals("yjiyuncom", scopes.get(0).get("clientCode"));
        assertEquals("yjiyuncom_test_agent", scopes.get(0).get("runtimeAgentId"));
        assertEquals("test", scopes.get(0).get("cluster"));
    }

    @Test
    void shouldUseTenantBindingBeforeLegacyClusterRouting() throws Exception {
        List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows =
                rows("blueprints/yjiyuncom.test.json", "blueprints/yjiyuncom.default.json");
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProviderWithBinding(
                                rows,
                                new JdbcPublishedBlueprintSource.BoundBlueprintRow(
                                        "yjiyuncom_default_v1",
                                        1,
                                        rows.get(1).payloadJson(),
                                        "",
                                        AgentBlueprintRepository.MATCH_FALLBACK)));

        BlueprintSource.BlueprintHandle handle =
                source.resolve(
                                "yjiyuncom",
                                "test",
                                "BEAUTY_SKINCARE",
                                DEFAULT_RUNTIME_AGENT_ID,
                                "",
                                "admin_matrix-local_agentteams_io_18080")
                        .orElseThrow();

        assertEquals("yjiyuncom_default_v1", handle.blueprint().blueprintId());
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, handle.matchLevel());
    }

    @Test
    void shouldSkipTenantBindingLookupWhenUserIdMissing() throws Exception {
        List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows =
                rows("blueprints/yjiyuncom.test.json", "blueprints/yjiyuncom.default.json");
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(
                        config(),
                        new AgentBlueprintLoader(),
                        rowProviderFailingOnTenantBindingLookup(rows));

        BlueprintSource.BlueprintHandle handle =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", DEFAULT_RUNTIME_AGENT_ID, "")
                        .orElseThrow();

        assertEquals("yjiyuncom_default_v1", handle.blueprint().blueprintId());
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, handle.matchLevel());
    }

    @Test
    void shouldResolveRequestedVersionWhenMultiplePublishedVersionsExist() throws Exception {
        List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows =
                List.of(
                        rowWithVersion("blueprints/yjiyuncom.test.json", "yjiyuncom_test_v1", 1),
                        rowWithVersion("blueprints/yjiyuncom.test.json", "yjiyuncom_test_v2", 2));
        JdbcPublishedBlueprintSource source =
                new JdbcPublishedBlueprintSource(config(), new AgentBlueprintLoader(), rowProvider(rows));

        BlueprintSource.BlueprintHandle latest =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", TEST_RUNTIME_AGENT_ID, "")
                        .orElseThrow();
        BlueprintSource.BlueprintHandle requested =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", TEST_RUNTIME_AGENT_ID, "1")
                        .orElseThrow();

        assertEquals("yjiyuncom_test_v2", latest.blueprint().blueprintId());
        assertEquals(2, latest.blueprint().version());
        assertEquals("yjiyuncom_test_v1", requested.blueprint().blueprintId());
        assertEquals(1, requested.blueprint().version());
    }

    private static List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows(String... resources)
            throws Exception {
        AgentBlueprintLoader loader = new AgentBlueprintLoader();
        java.util.ArrayList<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows =
                new java.util.ArrayList<>();
        for (String resource : resources) {
            String json = readBlueprintResource(resource);
            AgentBlueprint blueprint = loader.parse(json, resource);
            rows.add(
                    new JdbcPublishedBlueprintSource.StoredBlueprintRow(
                            blueprint.blueprintId(), blueprint.version(), json));
        }
        return List.copyOf(rows);
    }

    private static JdbcPublishedBlueprintSource.StoredBlueprintRow rowWithVersion(
            String resource, String blueprintId, int version) throws Exception {
        String json =
                readBlueprintResource(resource)
                        .replace("\"blueprintId\": \"yjiyuncom_test_v1\"", "\"blueprintId\": \"" + blueprintId + "\"")
                        .replace("\"version\": 1", "\"version\": " + version);
        return new JdbcPublishedBlueprintSource.StoredBlueprintRow(blueprintId, version, json);
    }

    /**
     * 从 classpath 读蓝图原文。
     *
     * <p>刻意不走文件系统绝对路径：这些 JSON 就在 {@code src/main/resources/blueprints/} 下、
     * 已随 classpath 打包，{@code AgentBlueprintLoader} 也是按 classpath 读的。本测试需要
     * <b>原始 JSON 字符串</b>（要塞进 {@code StoredBlueprintRow.payloadJson} 模拟 PG 的
     * {@code payload::text}，还要做字符串替换造多版本），所以自己读一次流而不是复用
     * {@code loadFromClasspath}（那个只返回解析后的对象）。
     */
    private static String readBlueprintResource(String resource) throws Exception {
        try (java.io.InputStream inputStream =
                JdbcPublishedBlueprintSourceTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("blueprint resource not on classpath: " + resource);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JdbcPublishedBlueprintSource.RowProvider rowProvider(
            List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows) {
        return new JdbcPublishedBlueprintSource.RowProvider() {
            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadPublishedByClientCode(
                    String clientCode) {
                return rows.stream()
                        .filter(row -> row.payloadJson().contains("\"clientCode\": \"" + clientCode + "\""))
                        .toList();
            }

            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadAllPublished() {
                return rows;
            }
        };
    }

    private static JdbcPublishedBlueprintSource.RowProvider rowProviderWithBinding(
            List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows,
            JdbcPublishedBlueprintSource.BoundBlueprintRow boundRow) {
        return new JdbcPublishedBlueprintSource.RowProvider() {
            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadPublishedByClientCode(
                    String clientCode) {
                return rowProvider(rows).loadPublishedByClientCode(clientCode);
            }

            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadAllPublished() {
                return rows;
            }

            @Override
            public java.util.Optional<JdbcPublishedBlueprintSource.BoundBlueprintRow>
                    loadTenantBoundPublished(
                            String clientCode,
                            String cluster,
                            String runtimeAgentId,
                            String tenantUserId,
                            Integer requestedVersion) {
                return java.util.Optional.of(boundRow);
            }
        };
    }

    /** {@code loadTenantBoundPublished} 一旦被调用就失败，用来断言"没传 userId 时该查找被跳过"。 */
    private static JdbcPublishedBlueprintSource.RowProvider rowProviderFailingOnTenantBindingLookup(
            List<JdbcPublishedBlueprintSource.StoredBlueprintRow> rows) {
        return new JdbcPublishedBlueprintSource.RowProvider() {
            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadPublishedByClientCode(
                    String clientCode) {
                return rowProvider(rows).loadPublishedByClientCode(clientCode);
            }

            @Override
            public List<JdbcPublishedBlueprintSource.StoredBlueprintRow> loadAllPublished() {
                return rows;
            }

            @Override
            public java.util.Optional<JdbcPublishedBlueprintSource.BoundBlueprintRow>
                    loadTenantBoundPublished(
                            String clientCode,
                            String cluster,
                            String runtimeAgentId,
                            String tenantUserId,
                            Integer requestedVersion) {
                throw new AssertionError(
                        "tenant binding lookup must not run when userId is missing");
            }
        };
    }

    private static AppConfig config() {
        return new AppConfig(
                "model",
                "http://model",
                "secret",
                "bailian",
                "",
                "",
                "",
                "",
                "127.0.0.1",
                "3306",
                "",
                "",
                "",
                "",
                "agent_conversations",
                // chatRunJdbcUrl / Username / Password：留空表示不启用运行留痕落库
                "",
                "",
                "",
                "agent_chat_runs",
                "agent_chat_run_events",
                "http://localhost:3002",
                "",
                "jdbc",
                false,
                "",
                "",
                "/api/v1/blueprints/published",
                3000,
                5000,
                5000,
                "jdbc:postgresql://127.0.0.1:5432/postgres",
                "postgres",
                "secret",
                5,
                false,
                true,
                "",
                "",
                "BEAUTY_SKINCARE",
                ".agentscope/workspace",
                "sales-customer-agent",
                false,
                "",
                "",
                false,
                "",
                "",
                "",
                "");
    }
}
