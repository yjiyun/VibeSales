package com.agentteams.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 覆盖"按 clientCode + cluster 取不同蓝图"这条主链路，直接跑真实的 classpath 资源。 */
class AgentBlueprintRepositoryTest {

    private static final String TEST_CLIENT = "yjiyuncom";
    private static final String TEST_CLUSTER = "test";
    private static final String TEST_RUNTIME_AGENT_ID = "yjiyuncom_test_agent";
    private static final String DEFAULT_RUNTIME_AGENT_ID = "yjiyuncom_default_agent";

    private final AgentBlueprintRepository repository = new AgentBlueprintRepository();

    @Test
    void shouldResolveDifferentBlueprintPerClientCode() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();
        ResolvedBlueprint apparel =
                repository.resolve("hanmei_apparel", "", "", "hanmei_apparel_service_agent", "")
                        .orElseThrow();

        assertEquals("yjiyuncom_test_v1", beauty.blueprintId());
        assertEquals("hanmei_apparel_v1", apparel.blueprintId());
        // 两个租户的身份串与提示词都必须分开，否则时间线上分不清本轮生效的是谁
        assertNotEquals(beauty.runtimeAgentId(), apparel.runtimeAgentId());
        assertNotEquals(beauty.blueprintIdentity(), apparel.blueprintIdentity());
        assertNotEquals(beauty.systemPrompt(), apparel.systemPrompt());
    }

    @Test
    void shouldPreferExactClusterMatchOverTenantDefault() {
        ResolvedBlueprint exact =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();

        assertEquals(AgentBlueprintRepository.MATCH_EXACT, exact.matchLevel());
        assertEquals("yjiyuncom_test_v1", exact.blueprintId());
        assertEquals("yjiyuncom_test_agent", exact.runtimeAgentId());
        assertEquals(TEST_CLUSTER, exact.cluster());
    }

    @Test
    void shouldDegradeToTenantDefaultWhenClusterHasNoBlueprint() {
        ResolvedBlueprint degraded =
                repository.resolve(TEST_CLIENT, "no-such-cluster", "", DEFAULT_RUNTIME_AGENT_ID, "")
                        .orElseThrow();

        // 降级和"没指定 cluster"是两种情况，matchLevel 必须能区分，否则上游配错 cluster 看不出来
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, degraded.matchLevel());
        assertEquals("yjiyuncom_default_v1", degraded.blueprintId());
        assertEquals("no-such-cluster", degraded.requestedCluster());
        assertEquals("", degraded.cluster());
    }

    @Test
    void shouldUseTenantDefaultWhenClusterIsAbsent() {
        ResolvedBlueprint noCluster =
                repository.resolve(TEST_CLIENT, "", "", DEFAULT_RUNTIME_AGENT_ID, "").orElseThrow();

        assertEquals(AgentBlueprintRepository.MATCH_DEFAULT, noCluster.matchLevel());
        assertEquals("yjiyuncom_default_v1", noCluster.blueprintId());
    }

    @Test
    void degradedRequestsShouldShareOneBlueprintIdentity() {
        // 身份串取"命中的蓝图"而不是请求参数：两个不同 cluster 都降级到同一份默认蓝图时，
        // 本轮生效的是同一份配置，串就该相同——否则看不出"这两次其实读的是同一份蓝图"
        ResolvedBlueprint first =
                repository.resolve(TEST_CLIENT, "cluster-a", "", DEFAULT_RUNTIME_AGENT_ID, "")
                        .orElseThrow();
        ResolvedBlueprint second =
                repository.resolve(TEST_CLIENT, "cluster-b", "", DEFAULT_RUNTIME_AGENT_ID, "")
                        .orElseThrow();

        assertEquals(first.blueprintIdentity(), second.blueprintIdentity());
        assertNotEquals(
                first.blueprintIdentity(),
                repository
                        .resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "")
                        .orElseThrow()
                        .blueprintIdentity());
    }

    @Test
    void shouldResolveExactRequestedVersionWhenProvided() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "1").orElseThrow();

        assertEquals("yjiyuncom_test_v1", beauty.blueprintId());
        assertEquals("1", beauty.requestedVersion());
    }

    @Test
    void shouldReturnEmptyWhenRequestedVersionDoesNotMatch() {
        assertTrue(repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "99").isEmpty());
    }

    @Test
    void shouldReturnEmptyForUnregisteredClientCode() {
        assertTrue(
                repository.resolve("no-such-tenant", TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "")
                        .isEmpty());
        // UI 的历史默认值，刻意确认它现在走兜底而不是误命中某个租户
        assertTrue(repository.resolve("demo-client", "main", "", TEST_RUNTIME_AGENT_ID, "").isEmpty());
    }

    @Test
    void shouldProjectSoulBeforeAgentsInSystemPrompt() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();

        int soulIndex = beauty.systemPrompt().indexOf("# 身份");
        int agentsIndex = beauty.systemPrompt().indexOf("# 工作准则");
        assertTrue(soulIndex >= 0, "soulMd should be projected");
        assertTrue(agentsIndex > soulIndex, "agentsMd should follow soulMd");
        // knowledgeMd 明确不参与投影
        assertFalse(beauty.systemPrompt().contains("百炼知识库检索"));
    }

    @Test
    void shouldProjectInlineAndLibrarySkillsWithSourceTrace() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();

        // 产出是解析好的 AgentSkill，不是构造期仓库——共享 Agent 上挂仓库会串租户
        assertEquals(2, beauty.skills().skills().size());
        assertEquals(
                java.util.List.of("recovery-handling", "skincare-consult"),
                beauty.skills().skillNames());
        assertEquals("library", beauty.skills().sourceByName().get("recovery-handling"));
        assertEquals("inline", beauty.skills().sourceByName().get("skincare-consult"));
    }

    @Test
    void shouldProjectWiredRuleAndOnlyRecordUnwiredOnes() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();
        BlueprintRuleProjector.Projection rules = beauty.rules();

        // recovery-detection 已接线：投影出实例，租户词表真实生效
        assertTrue(rules.recoveryDetectionRule().isPresent());
        assertEquals(java.util.List.of("recovery-detection"), rules.effectiveRuleCodes());
        // profile-completeness 已实现但编排链无调用点：只留痕
        assertEquals(java.util.List.of("profile-completeness"), rules.unwiredRuleCodes());
        // human-handoff-trigger 蓝图里显式 enabled=false
        assertEquals(java.util.List.of("human-handoff-trigger"), rules.disabledRuleCodes());
        assertEquals("disabled_by_blueprint", rules.classification().get("human-handoff-trigger"));
    }

    @Test
    void enabledRuleWithoutParamsShouldKeepJavaDefaults() {
        // "启用但不覆盖参数"是合法配置，不能被当成"覆盖成空词表"
        ResolvedBlueprint tenantDefault =
                repository.resolve(TEST_CLIENT, "", "", DEFAULT_RUNTIME_AGENT_ID, "").orElseThrow();

        assertEquals(java.util.List.of("recovery-detection"), tenantDefault.rules().effectiveRuleCodes());
        assertTrue(tenantDefault.rules().recoveryDetectionRule().isEmpty());
    }

    @Test
    void differentTenantsShouldGetDifferentContinuationKeywords() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();
        ResolvedBlueprint apparel =
                repository.resolve("hanmei_apparel", "", "", "hanmei_apparel_service_agent", "")
                        .orElseThrow();

        assertTrue(beauty.rules().recoveryDetectionRule().isPresent());
        assertTrue(apparel.rules().recoveryDetectionRule().isPresent());
        assertNotEquals(
                beauty.rules().recoveryDetectionRule().orElseThrow().continuationKeywords(),
                apparel.rules().recoveryDetectionRule().orElseThrow().continuationKeywords());
    }

    @Test
    void shouldListAllRegisteredScopes() {
        assertTrue(repository.hasAnyBlueprint());
        assertEquals(4, repository.listScopes().size());
        assertTrue(
                repository.listScopes().stream()
                        .anyMatch(
                                scope ->
                                        TEST_CLIENT.equals(scope.get("clientCode"))
                                                && "multistage".equals(scope.get("cluster"))));
    }

    @Test
    void shouldReportToolClassificationInTimelineDetail() {
        ResolvedBlueprint beauty =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", TEST_RUNTIME_AGENT_ID, "").orElseThrow();

        @SuppressWarnings("unchecked")
        var toolsAllow =
                (java.util.Map<String, String>) beauty.toTimelineDetail().get("toolsAllow");
        assertEquals("orchestration_fixed", toolsAllow.get("getHistorySummary"));
        assertEquals("agent_builtin_dynamic", toolsAllow.get("memory_search"));
    }

    @Test
    void timelineDetailShouldCarryMatchLevelAndRules() {
        java.util.Map<String, Object> detail =
                repository
                        .resolve(TEST_CLIENT, "no-such-cluster", "", DEFAULT_RUNTIME_AGENT_ID, "")
                        .orElseThrow()
                        .toTimelineDetail();
 
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, detail.get("matchLevel"));
        assertEquals("no-such-cluster", detail.get("requestedCluster"));
        assertEquals(DEFAULT_RUNTIME_AGENT_ID, detail.get("requestedRuntimeAgentId"));
        assertEquals("", detail.get("requestedVersion"));
        assertEquals("", detail.get("cluster"));
        @SuppressWarnings("unchecked")
        var rules = (java.util.Map<String, Object>) detail.get("rules");
        assertEquals(java.util.List.of("recovery-detection"), rules.get("effective"));
    }

    @Test
    void inspectShouldReturnValidationReportWithoutProjecting() {
        Optional<AgentBlueprintRepository.Inspection> inspection =
                repository.inspect("hanmei_apparel", "", "", "hanmei_apparel_service_agent", "");

        assertTrue(inspection.isPresent());
        assertTrue(inspection.get().validation().valid(), inspection.get().validation().errorSummary());
        assertEquals(AgentBlueprintRepository.MATCH_DEFAULT, inspection.get().matchLevel());
    }

    @Test
    void allBundledBlueprintsShouldPassValidation() {
        for (var scope : repository.listScopes()) {
            String clientCode = scope.get("clientCode");
            String cluster = scope.get("cluster");
            String runtimeAgentId = scope.getOrDefault("runtimeAgentId", "");
            var inspection = repository.inspect(clientCode, cluster, "", runtimeAgentId, "").orElseThrow();
            assertTrue(
                    inspection.validation().valid(),
                    clientCode + "/" + cluster + " => " + inspection.validation().errorSummary());
        }
    }

    @Test
    void shouldReturnEmptyWhenRuntimeAgentIdDoesNotMatchBlueprint() {
        assertTrue(repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", DEFAULT_RUNTIME_AGENT_ID, "").isEmpty());
    }
}
