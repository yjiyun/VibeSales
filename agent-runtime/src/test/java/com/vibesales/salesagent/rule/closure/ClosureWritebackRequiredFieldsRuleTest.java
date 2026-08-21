package com.vibesales.salesagent.rule.closure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.RuleResult;
import org.junit.jupiter.api.Test;

class ClosureWritebackRequiredFieldsRuleTest {

    private final ClosureWritebackRequiredFieldsRule rule = new ClosureWritebackRequiredFieldsRule();

    @Test
    void shouldBeComplete_whenAllFourFieldsPresent() {
        RuleResult<ClosureWritebackRequiredFieldsRule.Output> result =
                rule.evaluate(
                        new ClosureWritebackRequiredFieldsRule.Input(
                                "客户问了推荐问题", "product_recommend", "resolved", "v2"));

        assertTrue(result.output().isComplete());
        assertTrue(result.output().missingFields().isEmpty());
    }

    @Test
    void shouldBeIncomplete_whenAllFieldsMissing() {
        RuleResult<ClosureWritebackRequiredFieldsRule.Output> result =
                rule.evaluate(new ClosureWritebackRequiredFieldsRule.Input(null, null, null, null));

        assertFalse(result.output().isComplete());
        assertEquals(4, result.output().missingFields().size());
    }

    @Test
    void shouldReportExactlyOneMissingField_whenOnlyQueueVersionBlank() {
        RuleResult<ClosureWritebackRequiredFieldsRule.Output> result =
                rule.evaluate(
                        new ClosureWritebackRequiredFieldsRule.Input(
                                "摘要文本", "product_recommend", "resolved", "  "));

        assertFalse(result.output().isComplete());
        assertEquals(1, result.output().missingFields().size());
        assertEquals("queueVersion", result.output().missingFields().get(0));
    }
}
