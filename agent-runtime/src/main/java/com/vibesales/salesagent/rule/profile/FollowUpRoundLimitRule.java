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
 *
 * <p>两条规则的阈值也<b>分别可配</b>（{@code maxFollowUpRounds} 与 {@code forcedRoundThreshold}）。
 * 看着冗余，但合并成一个参数就等于宣布"停止追问"和"强制放行"必须同轮发生：想先停止追问、
 * 再多给一轮机会补肤质的运营策略就配不出来了。
 */
public final class FollowUpRoundLimitRule
        implements Rule<FollowUpRoundLimitRule.Input, FollowUpRoundLimitRule.Output> {

    public static final int DEFAULT_MAX_FOLLOW_UP_ROUNDS = 3;

    private final int maxFollowUpRounds;

    public FollowUpRoundLimitRule() {
        this(DEFAULT_MAX_FOLLOW_UP_ROUNDS);
    }

    /**
     * @param maxFollowUpRounds 追问轮次上限；{@code <= 0} 回落到默认值——"配了 0"意味着一轮都不许追问，
     *     那是配置事故而不是配置意图
     */
    public FollowUpRoundLimitRule(int maxFollowUpRounds) {
        this.maxFollowUpRounds =
                maxFollowUpRounds <= 0 ? DEFAULT_MAX_FOLLOW_UP_ROUNDS : maxFollowUpRounds;
    }

    /** 本实例实际生效的上限，供时间线留痕与断言使用。 */
    public int maxFollowUpRounds() {
        return maxFollowUpRounds;
    }

    public record Input(int followUpRoundCount, CustomerProfileSnapshot collectedProfile) {}

    public record Output(boolean shouldStopAsking) {}

    @Override
    public String ruleCode() {
        return "follow-up-round-limit";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        boolean roundLimitReached = input.followUpRoundCount() >= maxFollowUpRounds;

        CustomerProfileSnapshot profile = input.collectedProfile();
        boolean hasIntentSignal = profile.hasConcern() || profile.hasTargetBenefit() || profile.hasCoreNeed();
        boolean hasContextSignal =
                profile.hasSkinType() || profile.hasBudget() || profile.hasCategoryPreference();
        boolean alreadySufficient = hasIntentSignal && hasContextSignal;

        return RuleResult.pass(new Output(roundLimitReached || alreadySufficient));
    }
}
