package com.vibesales.salesagent.rule.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.RuleResult;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;
import org.junit.jupiter.api.Test;

class FollowUpRoundLimitRuleTest {

    private final FollowUpRoundLimitRule rule = new FollowUpRoundLimitRule();

    @Test
    void shouldContinueAsking_whenRoundsBelowLimitAndProfileInsufficient() {
        CustomerProfileSnapshot profile = CustomerProfileSnapshot.placeholder("c1", "s", "v1");

        RuleResult<FollowUpRoundLimitRule.Output> result =
                rule.evaluate(new FollowUpRoundLimitRule.Input(1, profile));

        assertFalse(result.output().shouldStopAsking());
    }

    @Test
    void shouldStopAsking_whenRoundLimitReached_evenIfProfileStillInsufficient() {
        CustomerProfileSnapshot profile = CustomerProfileSnapshot.placeholder("c1", "s", "v1");

        RuleResult<FollowUpRoundLimitRule.Output> result =
                rule.evaluate(new FollowUpRoundLimitRule.Input(3, profile));

        assertTrue(result.output().shouldStopAsking());
    }

    @Test
    void shouldStopAsking_whenProfileAlreadySufficient_evenBeforeRoundLimit() {
        CustomerProfileSnapshot profile =
                new CustomerProfileSnapshot("c1", "s", "v1", true, false, false, true, false, false);

        RuleResult<FollowUpRoundLimitRule.Output> result =
                rule.evaluate(new FollowUpRoundLimitRule.Input(0, profile));

        assertTrue(result.output().shouldStopAsking());
    }

    @Test
    void boundaryRoundCountExactlyAtLimit_shouldStop() {
        CustomerProfileSnapshot profile = CustomerProfileSnapshot.placeholder("c1", "s", "v1");

        RuleResult<FollowUpRoundLimitRule.Output> resultAtLimit =
                rule.evaluate(new FollowUpRoundLimitRule.Input(3, profile));
        RuleResult<FollowUpRoundLimitRule.Output> resultBelowLimit =
                rule.evaluate(new FollowUpRoundLimitRule.Input(2, profile));

        assertTrue(resultAtLimit.output().shouldStopAsking());
        assertFalse(resultBelowLimit.output().shouldStopAsking());
    }
}
