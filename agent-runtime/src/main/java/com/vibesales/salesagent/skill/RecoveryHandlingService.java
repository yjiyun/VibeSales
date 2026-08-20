package com.agentteams.salesagent.skill;

import com.agentteams.salesagent.rule.RuleResult;
import com.agentteams.salesagent.rule.recovery.RecoveryDetectionRule;
import com.agentteams.salesagent.tool.history.HistorySummarySnapshot;
import com.agentteams.salesagent.tool.taskboard.IntentTaskSnapshot;
import java.util.List;

/**
 * 恢复判断链路的编排代码。
 *
 * <p>判断本身已经下沉到 {@link RecoveryDetectionRule}（见07号文档3.3节的改进方向），这里只做
 * "调用 Rule、把判断结果转成 {@link RecoveryDecision}"这一件编排工作，不再自己维护判断逻辑。
 */
public final class RecoveryHandlingService {

    private static final List<String> DEFAULT_CONTINUATION_KEYWORDS =
            List.of("继续", "继续说", "刚才", "接着", "上次", "前面");

    private final RecoveryDetectionRule recoveryDetectionRule;

    public RecoveryHandlingService() {
        this(new RecoveryDetectionRule(DEFAULT_CONTINUATION_KEYWORDS));
    }

    public RecoveryHandlingService(RecoveryDetectionRule recoveryDetectionRule) {
        this.recoveryDetectionRule = recoveryDetectionRule;
    }

    /** 本实例实际比对的续接词表，供时间线输出——只看"来源=蓝图"看不出词是否配错。 */
    public List<String> continuationKeywords() {
        return recoveryDetectionRule.continuationKeywords();
    }

    public RecoveryDecision evaluate(
            String userMessage, HistorySummarySnapshot history, IntentTaskSnapshot queue) {
        RuleResult<RecoveryDetectionRule.Output> result =
                recoveryDetectionRule.evaluate(
                        new RecoveryDetectionRule.Input(userMessage, history.recoveryPending()));

        if (result.output().looksLikeContinuation()) {
            return new RecoveryDecision(
                    true,
                    "resume-existing-intent",
                    history.activeIntentCode(),
                    "优先按恢复态处理，结合历史摘要和任务板继续上一轮链路。");
        }

        return new RecoveryDecision(
                false,
                "start-new-round",
                "bootstrap",
                "当前按最小闭环演示处理，先完成入口、恢复判断和输出链路验证。");
    }
}
