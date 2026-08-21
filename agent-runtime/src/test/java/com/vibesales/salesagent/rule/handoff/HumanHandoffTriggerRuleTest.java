package com.vibesales.salesagent.rule.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.RuleResult;
import org.junit.jupiter.api.Test;

class HumanHandoffTriggerRuleTest {

    private final HumanHandoffTriggerRule rule = new HumanHandoffTriggerRule();

    @Test
    void shouldNotHandoff_whenNoTriggerSignalPresent() {
        RuleResult<HumanHandoffTriggerRule.Output> result =
                rule.evaluate(new HumanHandoffTriggerRule.Input(false, false, false, false));

        assertFalse(result.output().shouldHandoff());
        assertNull(result.output().triggerReason());
    }

    @Test
    void shouldHandoff_whenExplicitHumanRequest() {
        RuleResult<HumanHandoffTriggerRule.Output> result =
                rule.evaluate(new HumanHandoffTriggerRule.Input(true, false, false, false));

        assertTrue(result.output().shouldHandoff());
        assertEquals("explicit_human_request", result.output().triggerReason());
    }

    @Test
    void shouldHandoff_whenSevereAllergyOnly() {
        RuleResult<HumanHandoffTriggerRule.Output> result =
                rule.evaluate(new HumanHandoffTriggerRule.Input(false, true, false, false));

        assertTrue(result.output().shouldHandoff());
        assertEquals("severe_allergy", result.output().triggerReason());
    }

    @Test
    void explicitHumanRequestShouldTakePriority_whenMultipleSignalsPresent() {
        RuleResult<HumanHandoffTriggerRule.Output> result =
                rule.evaluate(new HumanHandoffTriggerRule.Input(true, true, true, true));

        assertEquals("explicit_human_request", result.output().triggerReason());
    }

    @Test
    void shouldHandoff_whenOnlyEmotionalOrOutOfScopeSignalPresent() {
        RuleResult<HumanHandoffTriggerRule.Output> result =
                rule.evaluate(new HumanHandoffTriggerRule.Input(false, false, false, true));

        assertTrue(result.output().shouldHandoff());
        assertEquals("emotional_or_out_of_scope", result.output().triggerReason());
    }
}
