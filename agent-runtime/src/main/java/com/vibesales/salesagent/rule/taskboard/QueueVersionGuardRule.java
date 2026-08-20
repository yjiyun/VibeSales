package com.agentteams.salesagent.rule.taskboard;

import com.agentteams.salesagent.rule.Rule;
import com.agentteams.salesagent.rule.RuleResult;

/**
 * {@code syncIntentQueue} 调用前后的 {@code queueVersion} 乐观锁校验。
 *
 * <p>这是11号文档风险登记表明确的强制约束（"{@code syncIntentQueue} 必须处理 {@code queueVersion}
 * 乐观锁是强制约束，不是可选项——防止场景卡片8并发覆盖挂起任务状态"）。这条 Rule 只做版本号比较，
 * 不做写入动作——如果检测到冲突，调用方应该重新拉取最新任务板状态，不应该强行覆盖写入。
 */
public final class QueueVersionGuardRule
        implements Rule<QueueVersionGuardRule.Input, QueueVersionGuardRule.Output> {

    public record Input(String localQueueVersion, String remoteQueueVersion) {}

    public record Output(boolean isConflict) {}

    @Override
    public String ruleCode() {
        return "queue-version-guard";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        String local = input.localQueueVersion();
        String remote = input.remoteQueueVersion();
        boolean isConflict = local != null && remote != null && !local.equals(remote);
        return RuleResult.pass(new Output(isConflict));
    }
}
