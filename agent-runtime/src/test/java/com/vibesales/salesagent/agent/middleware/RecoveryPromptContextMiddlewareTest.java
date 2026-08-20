package com.agentteams.salesagent.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.skill.RecoveryDecision;
import com.agentteams.salesagent.tool.history.HistorySummarySnapshot;
import com.agentteams.salesagent.tool.profile.CustomerProfileSnapshot;
import com.agentteams.salesagent.tool.taskboard.IntentTaskSnapshot;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

class RecoveryPromptContextMiddlewareTest {

    private final RecoveryPromptContextMiddleware middleware = new RecoveryPromptContextMiddleware();

    @Test
    void shouldAppendRecoveryPromptContextIntoSystemPrompt() {
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("conv-1").userId("user-1").build();
        runtimeContext.put(
                RecoveryPromptContext.class,
                new RecoveryPromptContext(
                        new RecoveryDecision(
                                true,
                                "resume-existing-intent",
                                "skin-care",
                                "优先按恢复态处理，继续上一轮链路。"),
                        new HistorySummarySnapshot("客户上一轮在咨询补水方案。", true, "skin-care"),
                        new IntentTaskSnapshot(3, 1, 1, "queue-v1"),
                        CustomerProfileSnapshot.placeholder(
                                "user-1", "客户偏好清爽型补水产品。", "profile-v1")));

        String prompt = middleware.onSystemPrompt(null, runtimeContext, "基础系统提示").block();

        assertTrue(prompt.contains("基础系统提示"));
        assertTrue(prompt.contains("轮次类型：续接上一轮未完成话题"));
        assertTrue(prompt.contains("目标意图：skin-care"));
        assertTrue(prompt.contains("历史摘要：客户上一轮在咨询补水方案。"));
        assertTrue(prompt.contains("客户画像摘要：客户偏好清爽型补水产品。"));
    }

    @Test
    void shouldKeepOriginalPromptWhenNoRecoveryContextExists() {
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("conv-1").userId("user-1").build();

        String prompt = middleware.onSystemPrompt(null, runtimeContext, "基础系统提示").block();

        assertEquals("基础系统提示", prompt);
    }
}
