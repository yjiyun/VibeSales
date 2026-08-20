package com.agentteams.salesagent.rule.profile;

import com.agentteams.salesagent.rule.Rule;
import com.agentteams.salesagent.rule.RuleResult;
import com.agentteams.salesagent.tool.profile.CustomerProfileSnapshot;

/**
 * 场景卡片1的追问轮次上限规则："同一推荐主题连续追问不超过3条"，追问满3轮后强制停止，
 * 防止无限拉话导致客户流失；已收集字段足够充分时，即使未到3轮也应该停止追问。
 *
 * <p>这条规则和 {@link ProfileCompletenessRule} 都依赖"追问满3轮"这个阈值，但语义不同：
 * {@code ProfileCompletenessRule} 回答"能不能推荐"，这条规则单独回答"该不该继续追问"——
 * 两者职责分开、各自可单独测试，即使当前判断条件有重叠，也不应该合并成一个方法。
 */
public final class FollowUpRoundLimitRule
        implements Rule<FollowUpRoundLimitRule.Input, FollowUpRoundLimitRule.Output> {

    private static final int MAX_FOLLOW_UP_ROUNDS = 3;

    public record Input(int followUpRoundCount, CustomerProfileSnapshot collectedProfile) {}

    public record Output(boolean shouldStopAsking) {}

    @Override
    public String ruleCode() {
        return "follow-up-round-limit";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        boolean roundLimitReached = input.followUpRoundCount() >= MAX_FOLLOW_UP_ROUNDS;

        CustomerProfileSnapshot profile = input.collectedProfile();
        boolean hasIntentSignal = profile.hasConcern() || profile.hasTargetBenefit() || profile.hasCoreNeed();
        boolean hasContextSignal =
                profile.hasSkinType() || profile.hasBudget() || profile.hasCategoryPreference();
        boolean alreadySufficient = hasIntentSignal && hasContextSignal;

        return RuleResult.pass(new Output(roundLimitReached || alreadySufficient));
    }
}
