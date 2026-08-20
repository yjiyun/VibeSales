package com.agentteams.salesagent.rule.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.rule.RuleResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryDetectionRuleTest {

    private final RecoveryDetectionRule rule =
            new RecoveryDetectionRule(List.of("继续", "继续说", "刚才", "接着", "上次", "前面"));

    @Test
    void shouldDetectContinuation_whenMessageStartsWithKnownKeyword() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("继续帮我推荐", false));

        assertTrue(result.passed());
        assertTrue(result.output().looksLikeContinuation());
        assertEquals("继续", result.output().matchedKeyword());
    }

    @Test
    void shouldNotDetectContinuation_whenMessageIsNewTopicAndNotPending() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("你们的护肤品适合油性皮肤吗", false));

        assertTrue(result.passed());
        assertFalse(result.output().looksLikeContinuation());
    }

    @Test
    void shouldDetectContinuation_whenHistoryMarksRecoveryPending_regardlessOfKeyword() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("随便说点别的", true));

        assertTrue(result.output().looksLikeContinuation());
    }

    @Test
    void shouldNotMatchKeyword_whenKeywordAppearsInMiddleNotAtStart() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("你好，我刚才没说清楚是要问价格", false));

        assertTrue(result.output().looksLikeContinuation());
        assertEquals("刚才", result.output().matchedKeyword());
    }

    @Test
    void shouldDetectContinuation_whenKeywordAppearsAfterIntentVerb() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("那个，我想继续看适合油皮的推荐", false));

        assertTrue(result.output().looksLikeContinuation());
        assertEquals("继续", result.output().matchedKeyword());
    }

    @Test
    void shouldNotTreatGenericAcknowledgementAsContinuationSignal() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input("好的，我还有一个新问题", false));

        assertFalse(result.output().looksLikeContinuation());
    }

    @Test
    void shouldTreatNullMessageAsEmpty_withoutThrowing() {
        RuleResult<RecoveryDetectionRule.Output> result =
                rule.evaluate(new RecoveryDetectionRule.Input(null, false));

        assertFalse(result.output().looksLikeContinuation());
    }

    @Test
    void shouldUseInjectedKeywords_notHardcodedOnes() {
        RecoveryDetectionRule customRule = new RecoveryDetectionRule(List.of("接着"));

        RuleResult<RecoveryDetectionRule.Output> matched =
                customRule.evaluate(new RecoveryDetectionRule.Input("我想接着上次说的继续看", false));
        RuleResult<RecoveryDetectionRule.Output> notMatched =
                customRule.evaluate(new RecoveryDetectionRule.Input("继续上次说的", false));

        assertTrue(matched.output().looksLikeContinuation());
        assertFalse(notMatched.output().looksLikeContinuation());
    }
}
