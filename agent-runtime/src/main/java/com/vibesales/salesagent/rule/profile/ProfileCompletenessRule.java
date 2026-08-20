package com.agentteams.salesagent.rule.profile;

import com.agentteams.salesagent.rule.Rule;
import com.agentteams.salesagent.rule.RuleResult;
import com.agentteams.salesagent.tool.profile.CustomerProfileSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景卡片1画像充分度公式：{@code canRecommend = (关注点或目标功效或核心需求) AND (肤质或预算或品类偏好)}。
 *
 * <p>年龄字段不参与判断——这条约束体现为代码里根本没有出现任何 age 字段的引用，不需要额外写
 * "排除年龄"的负向判断（见07号文档3.2节）。
 */
public final class ProfileCompletenessRule
        implements Rule<ProfileCompletenessRule.Input, ProfileCompletenessRule.Output> {

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

        // 追问满3轮后，只要有肤质+其他任一信号，强制放行——对应场景卡片1的"防止无限拉话"规则
        boolean forcedByRoundLimit = input.followUpRoundCount() >= 3 && profile.hasSkinType();

        boolean canRecommend = (hasIntentSignal && hasContextSignal) || forcedByRoundLimit;

        List<String> missingFields = new ArrayList<>();
        if (!hasIntentSignal) missingFields.add("concern_or_benefit_or_need");
        if (!hasContextSignal) missingFields.add("skinType_or_budget_or_category");

        return RuleResult.pass(new Output(canRecommend, missingFields));
    }
}
