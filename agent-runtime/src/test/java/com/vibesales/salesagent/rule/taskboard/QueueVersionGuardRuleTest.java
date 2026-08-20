package com.agentteams.salesagent.rule.taskboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.rule.RuleResult;
import org.junit.jupiter.api.Test;

class QueueVersionGuardRuleTest {

    private final QueueVersionGuardRule rule = new QueueVersionGuardRule();

    @Test
    void shouldNotConflict_whenVersionsMatch() {
        RuleResult<QueueVersionGuardRule.Output> result =
                rule.evaluate(new QueueVersionGuardRule.Input("v1", "v1"));

        assertFalse(result.output().isConflict());
    }

    @Test
    void shouldConflict_whenVersionsDiffer() {
        RuleResult<QueueVersionGuardRule.Output> result =
                rule.evaluate(new QueueVersionGuardRule.Input("v1", "v2"));

        assertTrue(result.output().isConflict());
    }

    @Test
    void shouldNotConflict_whenEitherVersionIsNull() {
        RuleResult<QueueVersionGuardRule.Output> resultLocalNull =
                rule.evaluate(new QueueVersionGuardRule.Input(null, "v1"));
        RuleResult<QueueVersionGuardRule.Output> resultRemoteNull =
                rule.evaluate(new QueueVersionGuardRule.Input("v1", null));

        assertFalse(resultLocalNull.output().isConflict());
        assertFalse(resultRemoteNull.output().isConflict());
    }
}
