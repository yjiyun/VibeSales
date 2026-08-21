package com.vibesales.salesagent.rule.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.RuleResult;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;
import org.junit.jupiter.api.Test;

class ProfileCompletenessRuleTest {

    private final ProfileCompletenessRule rule = new ProfileCompletenessRule();

    @Test
    void shouldAllowRecommend_whenBothIntentAndContextSignalsPresent() {
        CustomerProfileSnapshot profile =
                new CustomerProfileSnapshot("c1", "s", "v1", true, false, false, true, false, false);

        RuleResult<ProfileCompletenessRule.Output> result =
                rule.evaluate(new ProfileCompletenessRule.Input(profile, 0));

        assertTrue(result.output().canRecommend());
        assertTrue(result.output().missingFields().isEmpty());
    }

    @Test
    void shouldRejectRecommend_whenBothSignalGroupsMissing() {
        CustomerProfileSnapshot profile =
                CustomerProfileSnapshot.placeholder("c1", "s", "v1");

        RuleResult<ProfileCompletenessRule.Output> result =
                rule.evaluate(new ProfileCompletenessRule.Input(profile, 0));

        assertFalse(result.output().canRecommend());
        assertTrue(result.output().missingFields().contains("concern_or_benefit_or_need"));
        assertTrue(result.output().missingFields().contains("skinType_or_budget_or_category"));
    }

    @Test
    void shouldForceAllow_whenRoundLimitReachedAndSkinTypeKnown_evenIfOtherFieldsMissing() {
        CustomerProfileSnapshot profile =
                new CustomerProfileSnapshot("c1", "s", "v1", false, false, false, true, false, false);

        RuleResult<ProfileCompletenessRule.Output> result =
                rule.evaluate(new ProfileCompletenessRule.Input(profile, 3));

        assertTrue(result.output().canRecommend());
    }

    @Test
    void shouldNotForceAllow_whenRoundLimitReachedButSkinTypeUnknown() {
        CustomerProfileSnapshot profile =
                new CustomerProfileSnapshot("c1", "s", "v1", true, false, false, false, false, false);

        RuleResult<ProfileCompletenessRule.Output> result =
                rule.evaluate(new ProfileCompletenessRule.Input(profile, 3));

        assertFalse(result.output().canRecommend());
    }

    @Test
    void ageFieldShouldNeverInfluenceResult_regardlessOfOtherSignals() {
        // 只有肤质+关注点，年龄字段不存在于 CustomerProfileSnapshot 中，验证判断结果只依赖六个信号
        CustomerProfileSnapshot onlyAgeAbsentButOthersSufficient =
                new CustomerProfileSnapshot("c1", "s", "v1", true, false, false, true, false, false);
        CustomerProfileSnapshot onlyAgeAbsentAndOthersMissing =
                CustomerProfileSnapshot.placeholder("c1", "s", "v1");

        assertTrue(
                rule.evaluate(new ProfileCompletenessRule.Input(onlyAgeAbsentButOthersSufficient, 0))
                        .output()
                        .canRecommend());
        assertFalse(
                rule.evaluate(new ProfileCompletenessRule.Input(onlyAgeAbsentAndOthersMissing, 0))
                        .output()
                        .canRecommend());
    }
}
