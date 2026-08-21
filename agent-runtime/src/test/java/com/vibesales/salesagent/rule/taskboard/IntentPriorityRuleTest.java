package com.vibesales.salesagent.rule.taskboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.RuleResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentPriorityRuleTest {

    private final IntentPriorityRule rule = new IntentPriorityRule();

    @Test
    void shouldRankTransferToHumanFirst_evenIfListedLast() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(
                        new IntentPriorityRule.Input(
                                List.of("product_recommend", "daily_chat", "transfer_to_human")));

        assertEquals("transfer_to_human", result.output().topPriorityIntentCode());
        assertEquals(
                List.of("transfer_to_human", "product_recommend", "daily_chat"),
                result.output().sortedIntentCodes());
    }

    @Test
    void shouldPreserveFullPriorityOrder_forAllNineIntents() {
        List<String> shuffled =
                List.of(
                        "out_of_scope",
                        "daily_chat",
                        "product_recommend",
                        "package_card",
                        "membership_benefit",
                        "product_usage",
                        "return_exchange",
                        "allergy_quality",
                        "transfer_to_human");

        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(new IntentPriorityRule.Input(shuffled));

        assertEquals(
                List.of(
                        "transfer_to_human",
                        "allergy_quality",
                        "return_exchange",
                        "product_usage",
                        "membership_benefit",
                        "package_card",
                        "product_recommend",
                        "daily_chat",
                        "out_of_scope"),
                result.output().sortedIntentCodes());
    }

    @Test
    void shouldReturnNullTopPriority_whenCandidateListEmpty() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(new IntentPriorityRule.Input(List.of()));

        assertTrue(result.output().sortedIntentCodes().isEmpty());
        assertNull(result.output().topPriorityIntentCode());
    }

    @Test
    void unknownIntentCode_shouldSortToTheEnd() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(
                        new IntentPriorityRule.Input(List.of("unknown_intent", "transfer_to_human")));

        assertEquals(
                List.of("transfer_to_human", "unknown_intent"), result.output().sortedIntentCodes());
    }

    /**
     * 本规则存在的理由那个 case：客户把过敏事故和选购需求说在同一句里，模型只看后半句判成
     * {@code product_recommend}，必须被关键词推导盖回 {@code allergy_quality}。
     *
     * <p>句子用的是口语「脸红了」而不是书面语「泛红」——词表只配书面词时这条会静默漏判，所以断言
     * 里连命中的那个词一起锁住。
     */
    @Test
    void allergyComplaintDisguisedAsRecommendation_shouldBeCorrected() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(
                        new IntentPriorityRule.Input(
                                List.of("product_recommend"),
                                "product_recommend",
                                "high",
                                "用了你们家的水乳脸红了，还有别的推荐吗"));

        IntentPriorityRule.Output output = result.output();
        assertEquals("allergy_quality", output.topPriorityIntentCode());
        assertEquals(
                List.of("allergy_quality", "product_recommend"), output.sortedIntentCodes());
        assertTrue(output.corrected(), output.violationType());
        // priority=2 → 高风险不看模型自评
        assertEquals("high", output.priorityLabel());
        assertTrue(output.evidence().contains("脸红"), String.valueOf(output.evidence()));
    }

    /**
     * 双条件的反面：只问「会不会过敏」没有使用痕迹，是咨询而不是质量事故。放宽这条会把选购咨询
     * 直接升级成售后工单。
     */
    @Test
    void allergyKeywordWithoutUsageMarker_shouldNotEscalate() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(
                        new IntentPriorityRule.Input(
                                List.of("product_recommend"),
                                "product_recommend",
                                "medium",
                                "你们家产品会不会过敏啊"));

        IntentPriorityRule.Output output = result.output();
        assertEquals("product_recommend", output.topPriorityIntentCode());
        assertFalse(output.corrected(), output.violationType());
        assertFalse(
                output.sortedIntentCodes().contains("allergy_quality"),
                String.valueOf(output.sortedIntentCodes()));
    }

    /** 声明值不在九码表里说明蓝图用的不是本规则的词表，原样放回并留痕，而不是越权改写。 */
    @Test
    void declaredIntentOutsidePriorityTable_shouldBeLeftAlone() {
        RuleResult<IntentPriorityRule.Output> result =
                rule.evaluate(
                        new IntentPriorityRule.Input(
                                List.of(),
                                "recommendation_consulting",
                                "high",
                                "用了你们家的水乳脸红了"));

        IntentPriorityRule.Output output = result.output();
        assertEquals("recommendation_consulting", output.topPriorityIntentCode());
        assertEquals(
                IntentPriorityRule.VIOLATION_DECLARED_NOT_IN_TABLE, output.violationType());
    }

    /** 租户只覆盖过敏词表时，未覆盖的六类意图关键词必须还在。 */
    @Test
    void tenantOverridingOnlyAllergyWords_shouldKeepOtherTables() {
        IntentPriorityRule tenantRule =
                new IntentPriorityRule(null, List.of("刺挠"), List.of("抹了"));

        RuleResult<IntentPriorityRule.Output> overridden =
                tenantRule.evaluate(
                        new IntentPriorityRule.Input(List.of(), "", "", "抹了之后有点刺挠"));
        assertEquals("allergy_quality", overridden.output().topPriorityIntentCode());

        RuleResult<IntentPriorityRule.Output> untouched =
                tenantRule.evaluate(new IntentPriorityRule.Input(List.of(), "", "", "想转人工"));
        assertEquals("transfer_to_human", untouched.output().topPriorityIntentCode());
    }
}
