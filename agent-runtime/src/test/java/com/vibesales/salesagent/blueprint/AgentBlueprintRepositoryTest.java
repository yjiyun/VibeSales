package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void shouldResolvePromptAssetsFromRefs() {
        ResolvedBlueprint guyu =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", "yjiyuncom_guyu_agent", "")
                        .orElseThrow();

        assertTrue(guyu.soulMd().contains("你是谷雨的美肤顾问「小雨滴」"));
        assertTrue(guyu.agentsMd().contains("每个阶段的输出形状由该阶段的 outputContract 决定"));
        assertTrue(guyu.systemPrompt().contains("你是谷雨的美肤顾问「小雨滴」"));
        assertTrue(guyu.systemPrompt().contains("转人工纪律"));
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
        // profile-completeness 也已接线（orchestration:rule.profile.gate），阈值被覆盖所以投影出实例
        assertEquals(
                java.util.List.of("recovery-detection", "profile-completeness"),
                rules.effectiveRuleCodes());
        assertTrue(rules.profileCompletenessRule().isPresent());
        assertEquals(3, rules.profileCompletenessRule().orElseThrow().forcedRoundThreshold());
        // 这份蓝图声明的三条规则里已经没有"实现了但没接线"的了
        assertEquals(java.util.List.of(), rules.unwiredRuleCodes());
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
        assertEquals(6, repository.listScopes().size());
        // 同一 client/cluster 下允许多份 blueprint 并存，靠 runtimeAgentId 区分：
        // 例如 yjiyuncom/test 里既有单阶段，也有多阶段与 guyu 的两种模式变体。
        assertTrue(
                repository.listScopes().stream()
                        .anyMatch(
                                scope ->
                                        TEST_CLIENT.equals(scope.get("clientCode"))
                                                && TEST_CLUSTER.equals(scope.get("cluster"))
                                                && "BEAUTY_SKINCARE".equals(scope.get("runtimeAgentId"))));
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
        // 该 runtimeAgentId 在任何蓝图里都不存在，两级路由都不该兜住它
        assertTrue(repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", "no_such_agent", "").isEmpty());
        assertTrue(repository.resolve(TEST_CLIENT, "", "", "no_such_agent", "").isEmpty());
    }

    @Test
    void clusterFallbackShouldStillHonourRuntimeAgentId() {
        // 请求 cluster=test 但带租户默认蓝图的 runtimeAgentId：test 集群下没有它，
        // 于是降级到 cluster 为空的默认蓝图并命中——降级不等于放宽 runtimeAgentId 校验
        ResolvedBlueprint fallback =
                repository.resolve(TEST_CLIENT, TEST_CLUSTER, "", DEFAULT_RUNTIME_AGENT_ID, "").orElseThrow();

        assertEquals("yjiyuncom_default_v1", fallback.blueprintId());
        assertEquals(DEFAULT_RUNTIME_AGENT_ID, fallback.runtimeAgentId());
        assertEquals(AgentBlueprintRepository.MATCH_FALLBACK, fallback.matchLevel());
    }

    @Test
    void resolveAdHocShouldValidateAndProjectDirectlyFromPayload() {
        // dry-run 场景：Blueprint 原文可能还没（或刚)落库，不能也不需要按作用域反查
        AgentBlueprint adHoc =
                new AgentBlueprint(
                        "bp_ad_hoc_test",
                        1,
                        "ad_hoc_client",
                        "",
                        "ad_hoc_agent",
                        new AgentBlueprint.Meta("beauty", java.util.List.of("BEAUTY_SKINCARE"), "test", "run-1"),
                        new AgentBlueprint.Prompt("# 工作准则\n测试准则", null, "# 身份\n测试身份", null, ""),
                        java.util.List.of(),
                        java.util.List.of(),
                        new AgentBlueprint.Tools(java.util.List.of(), java.util.List.of(), java.util.List.of()),
                        new AgentBlueprint.RuntimeSpec("deepseek-v4-flash", "USER", 8000),
                        null,
                        java.util.List.of());

        ResolvedBlueprint resolved = repository.resolveAdHoc(adHoc);

        assertEquals("bp_ad_hoc_test", resolved.blueprintId());
        assertEquals("ad_hoc_client", resolved.requestedClientCode());
        assertEquals("ad_hoc_agent", resolved.runtimeAgentId());
        assertEquals(AgentBlueprintRepository.MATCH_EXACT, resolved.matchLevel());
        assertTrue(resolved.systemPrompt().contains("测试身份"));
        assertTrue(resolved.systemPrompt().contains("测试准则"));
    }

    @Test
    void resolveAdHocShouldThrowWhenValidationFails() {
        // clientCode 缺失即校验失败——ad-hoc 路径必须保持与 resolve() 一致的失败即抛出口径
        AgentBlueprint invalid =
                new AgentBlueprint(
                        "bp_invalid",
                        1,
                        "",
                        "",
                        "ad_hoc_agent",
                        null,
                        new AgentBlueprint.Prompt("agents", null, "soul", null, ""),
                        java.util.List.of(),
                        java.util.List.of(),
                        null,
                        null,
                        null,
                        java.util.List.of());

        assertThrows(IllegalStateException.class, () -> repository.resolveAdHoc(invalid));
    }
}
