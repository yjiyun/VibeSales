package com.vibesales.salesagent.rule.profile;

import com.vibesales.salesagent.rule.Rule;
import com.vibesales.salesagent.rule.RuleResult;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景卡片1画像充分度公式：{@code canRecommend = (关注点或目标功效或核心需求) AND (肤质或预算或品类偏好)}。
 *
 * <p>年龄字段不参与判断——这条约束体现为代码里根本没有出现任何 age 字段的引用，不需要额外写
 * "排除年龄"的负向判断（见07号文档3.2节）。
 *
 * <p><b>公式本身不可配置，只有轮次强制阈值可配。</b>两个信号组的构成是业务定义（"知道她想解决什么"
 * ＋"知道她的肤况或预算"），改动它等于换一套画像模型；而"追问几轮之后认输放行"是运营节奏，不同
 * 租户的客户耐心不同，所以走蓝图参数 {@code forcedRoundThreshold}。
 */
public final class ProfileCompletenessRule
        implements Rule<ProfileCompletenessRule.Input, ProfileCompletenessRule.Output> {

    /** 追问满几轮后强制放行，对应场景卡片1的"防止无限拉话"。 */
    public static final int DEFAULT_FORCED_ROUND_THRESHOLD = 3;

    private final int forcedRoundThreshold;

    public ProfileCompletenessRule() {
        this(DEFAULT_FORCED_ROUND_THRESHOLD);
    }

    /**
     * @param forcedRoundThreshold 追问轮次强制放行阈值；{@code <= 0} 回落到默认值——"配了 0"会让
     *     第一轮就强制放行，那是配置事故而不是配置意图
     */
    public ProfileCompletenessRule(int forcedRoundThreshold) {
        this.forcedRoundThreshold =
                forcedRoundThreshold <= 0 ? DEFAULT_FORCED_ROUND_THRESHOLD : forcedRoundThreshold;
    }

    /** 本实例实际生效的阈值，供时间线留痕与断言使用。 */
    public int forcedRoundThreshold() {
        return forcedRoundThreshold;
    }

    public record Input(CustomerProfileSnapshot profile, int followUpRoundCount) {}

    public record Output(boolean canRecommend, List<String> missingFields) {}

    @Override
    public String ruleCode() {
        return "profile-completeness";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        CustomerProfileSnapshot profile = input.profile();
        boolean hasIntentSignal = profile.hasConcern() || profile.hasTargetBenefit() || profile.hasCoreNeed();
        boolean hasContextSignal =
                profile.hasSkinType() || profile.hasBudget() || profile.hasCategoryPreference();

        // 追问满阈值轮后，只要有肤质+其他任一信号，强制放行——对应场景卡片1的"防止无限拉话"规则
        boolean forcedByRoundLimit =
                input.followUpRoundCount() >= forcedRoundThreshold && profile.hasSkinType();

        boolean canRecommend = (hasIntentSignal && hasContextSignal) || forcedByRoundLimit;

        List<String> missingFields = new ArrayList<>();
        if (!hasIntentSignal) missingFields.add("concern_or_benefit_or_need");
        if (!hasContextSignal) missingFields.add("skinType_or_budget_or_category");

        return RuleResult.pass(new Output(canRecommend, missingFields));
    }
}
