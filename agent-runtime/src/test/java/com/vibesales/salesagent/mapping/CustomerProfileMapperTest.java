package com.agentteams.salesagent.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.rule.profile.ProfileCompletenessRule;
import com.agentteams.salesagent.tool.profile.CustomerProfileSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 画像 DTO 转换测试，重点覆盖六个布尔信号的推导。
 *
 * <p>这六个信号是 {@code ProfileCompletenessRule} 的唯一输入，推导错了会直接导致画像充分度
 * 判断失效，所以除了单独验证每个信号，还联动验证了规则的最终输出。
 */
class CustomerProfileMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CustomerProfileSnapshot map(String json) throws Exception {
        return CustomerProfileMapper.fromResponse(objectMapper.readTree(json), "fallback-user");
    }

    @Test
    @DisplayName("新客户的真实响应：顶层字段全为 null/空，六个信号应全部为 false")
    void newCustomerHasNoSignals() throws Exception {
        // 这份 JSON 取自真实探测结果（POST /sessions 建档后立刻查询的返回）
        String json =
                """
                {
                  "customerId": "probe-user-001",
                  "sceneCode": "BEAUTY_SKINCARE",
                  "nickname": "probe-user-001",
                  "ageRange": null,
                  "skinType": null,
                  "concerns": [],
                  "sensitivityLevel": null,
                  "budgetMin": null,
                  "budgetMax": null,
                  "profileSummary": "agentteams-java-agent 首次识别客户，已建立最小资料",
                  "profileVersion": 0,
                  "domainProfiles": {},
                  "sharedProfileSnapshot": {"commonProfile": {}, "domainProfiles": {}}
                }
                """;
        CustomerProfileSnapshot snapshot = map(json);

        assertEquals("probe-user-001", snapshot.customerId());
        assertFalse(snapshot.hasConcern());
        assertFalse(snapshot.hasTargetBenefit());
        assertFalse(snapshot.hasCoreNeed());
        assertFalse(snapshot.hasSkinType());
        assertFalse(snapshot.hasBudget());
        assertFalse(snapshot.hasCategoryPreference());
    }

    @Test
    @DisplayName("concerns 非空数组 → hasConcern 为 true")
    void concernsArrayDrivesHasConcern() throws Exception {
        CustomerProfileSnapshot snapshot = map("{\"concerns\":[\"暗黄\",\"色斑\"]}");
        assertTrue(snapshot.hasConcern());
    }

    @Test
    @DisplayName("concerns 为空数组 → hasConcern 为 false（不能把空数组当有值）")
    void emptyConcernsArrayIsNotSignal() throws Exception {
        assertFalse(map("{\"concerns\":[]}").hasConcern());
    }

    @Test
    @DisplayName("skinType 非空字符串 → hasSkinType 为 true")
    void skinTypeDrivesHasSkinType() throws Exception {
        assertTrue(map("{\"skinType\":\"油性\"}").hasSkinType());
    }

    @Test
    @DisplayName("skinType 为空白字符串 → hasSkinType 为 false")
    void blankSkinTypeIsNotSignal() throws Exception {
        assertFalse(map("{\"skinType\":\"   \"}").hasSkinType());
    }

    @Test
    @DisplayName("budgetMin 或 budgetMax 任一为正数 → hasBudget 为 true")
    void eitherBudgetBoundDrivesHasBudget() throws Exception {
        assertTrue(map("{\"budgetMin\":150}").hasBudget());
        assertTrue(map("{\"budgetMax\":320}").hasBudget());
        assertTrue(map("{\"budgetMin\":150,\"budgetMax\":320}").hasBudget());
    }

    @Test
    @DisplayName("预算为 0 不算有效预算信号")
    void zeroBudgetIsNotSignal() throws Exception {
        assertFalse(map("{\"budgetMin\":0,\"budgetMax\":0}").hasBudget());
    }

    @Test
    @DisplayName("目标功效/核心需求/品类偏好落在 domainProfiles 里也应被识别")
    void domainProfilesNestedSignalsAreFound() throws Exception {
        String json =
                """
                {
                  "domainProfiles": {
                    "targetBenefit": "提亮",
                    "coreNeed": "改善暗沉",
                    "categoryPreference": ["精华"]
                  }
                }
                """;
        CustomerProfileSnapshot snapshot = map(json);
        assertTrue(snapshot.hasTargetBenefit());
        assertTrue(snapshot.hasCoreNeed());
        assertTrue(snapshot.hasCategoryPreference());
    }

    @Test
    @DisplayName("信号也可能落在 sharedProfileSnapshot.domainProfiles 这一层")
    void sharedProfileSnapshotNestedSignalsAreFound() throws Exception {
        String json =
                """
                {
                  "sharedProfileSnapshot": {
                    "domainProfiles": {"targetBenefits": ["美白"]}
                  }
                }
                """;
        assertTrue(map(json).hasTargetBenefit());
    }

    @Test
    @DisplayName("空画像（后端404）：六个信号全 false，且 ProfileCompletenessRule 判定不能推荐")
    void emptyProfileLeadsRuleToRejectRecommendation() {
        CustomerProfileSnapshot snapshot = CustomerProfileMapper.emptyProfile("new-user");

        ProfileCompletenessRule.Output output =
                new ProfileCompletenessRule()
                        .evaluate(new ProfileCompletenessRule.Input(snapshot, 0))
                        .output();

        assertFalse(output.canRecommend());
        assertEquals(2, output.missingFields().size());
    }

    @Test
    @DisplayName("真实画像满足充分度公式时，ProfileCompletenessRule 应放行推荐")
    void realProfileSatisfyingFormulaLetsRulePass() throws Exception {
        // 关注点（意图侧）+ 肤质（上下文侧）都命中 → canRecommend 为 true
        CustomerProfileSnapshot snapshot =
                map("{\"concerns\":[\"暗黄\"],\"skinType\":\"混合\"}");

        assertTrue(snapshot.hasConcern());
        assertTrue(snapshot.hasSkinType());

        ProfileCompletenessRule.Output output =
                new ProfileCompletenessRule()
                        .evaluate(new ProfileCompletenessRule.Input(snapshot, 0))
                        .output();

        assertTrue(output.canRecommend());
        assertTrue(output.missingFields().isEmpty());
    }

    @Test
    @DisplayName("只有意图侧信号、缺上下文侧信号时，规则应判定不能推荐")
    void intentSignalAloneIsNotEnough() throws Exception {
        CustomerProfileSnapshot snapshot = map("{\"concerns\":[\"暗黄\"]}");

        ProfileCompletenessRule.Output output =
                new ProfileCompletenessRule()
                        .evaluate(new ProfileCompletenessRule.Input(snapshot, 0))
                        .output();

        assertFalse(output.canRecommend());
        assertTrue(output.missingFields().contains("skinType_or_budget_or_category"));
    }

    @Test
    @DisplayName("data 节点为 null 时回落到空画像，不抛异常")
    void nullDataFallsBackToEmptyProfile() {
        CustomerProfileSnapshot snapshot = CustomerProfileMapper.fromResponse(null, "fallback-user");
        assertEquals("fallback-user", snapshot.customerId());
        assertFalse(snapshot.hasConcern());
    }
}
