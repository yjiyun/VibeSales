package com.vibesales.salesagent.agent.middleware;

import com.vibesales.salesagent.skill.RecoveryDecision;
import com.vibesales.salesagent.tool.history.HistorySummarySnapshot;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;
import com.vibesales.salesagent.tool.taskboard.IntentTaskSnapshot;

/**
 * 恢复链路写入 RuntimeContext 的提示词上下文。
 *
 * <p>当前保持"编排层预取 + Middleware 注入系统提示词"模式：Tool 和 Rule 先在 Java 侧产出结构化快照，
 * 再由 Middleware 统一转成模型可消费的系统上下文，避免把内部判断结果伪装成客户原话写进 USER 历史。
 */
public record RecoveryPromptContext(
        RecoveryDecision recoveryDecision,
        HistorySummarySnapshot historySummary,
        IntentTaskSnapshot intentTaskSnapshot,
        CustomerProfileSnapshot customerProfile) {}
