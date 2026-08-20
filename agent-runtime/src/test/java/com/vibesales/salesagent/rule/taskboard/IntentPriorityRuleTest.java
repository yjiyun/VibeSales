package com.agentteams.salesagent.rule.taskboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.rule.RuleResult;
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
}
