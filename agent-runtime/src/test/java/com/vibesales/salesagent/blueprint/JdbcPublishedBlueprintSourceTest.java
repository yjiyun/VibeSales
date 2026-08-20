package com.agentteams.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Path path =
                    Path.of("/Users/haoli/webproject/VibeSales/sales-customer-agent/src/main/resources")
                            .resolve(resource.replace("blueprints/", "blueprints/"));
            String json = Files.readString(path, StandardCharsets.UTF_8);
            AgentBlueprint blueprint = loader.parse(json, resource);
            rows.add(
                    new JdbcPublishedBlueprintSource.StoredBlueprintRow(
                            blueprint.blueprintId(), blueprint.version(), json));
        }
        return List.copyOf(rows);
    }

    private static JdbcPublishedBlueprintSource.StoredBlueprintRow rowWithVersion(
            String resource, String blueprintId, int version) throws Exception {
        Path path =
                Path.of("/Users/haoli/webproject/VibeSales/sales-customer-agent/src/main/resources")
                        .resolve(resource.replace("blueprints/", "blueprints/"));
        String json =
                Files.readString(path, StandardCharsets.UTF_8)
                        .replace("\"blueprintId\": \"yjiyuncom_test_v1\"", "\"blueprintId\": \"" + blueprintId + "\"")
                        .replace("\"version\": 1", "\"version\": " + version);
        return new JdbcPublishedBlueprintSource.StoredBlueprintRow(blueprintId, version, json);
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
                false,
                "",
                "",
                "",
                "");
    }
}
