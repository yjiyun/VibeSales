package com.agentteams.salesagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CustomerServiceOrchestratorAgentTest {

    @Test
    void compactQuestionFocusShouldKeepShortInput() {
        assertEquals("油皮提亮精华", CustomerServiceOrchestratorAgent.compactQuestionFocus("油皮提亮精华"));
    }

    @Test
    void compactQuestionFocusShouldTrimLongInputToDatabaseSafeLength() {
        String compacted =
                CustomerServiceOrchestratorAgent.compactQuestionFocus(
                        "这次我想先看清爽一点的提亮精华，有没有适合油皮、预算 200 左右的推荐？");

        assertEquals("这次我想先看清爽一点的提亮精华，有没有适合油皮、预算 200 左", compacted);
        assertTrue(compacted.length() <= 32);
    }
}
