package com.vibesales.salesagent.rule.handoff;

import com.vibesales.salesagent.rule.Rule;
import com.vibesales.salesagent.rule.RuleResult;

/**
 * 场景卡片2的转人工硬触发条件，四类分散在各分支里的触发信号统一收口成一条 Pre-Flow 判断：
 *
 * <ol>
 *   <li>客户明确要求人工/投诉
 *   <li>过敏严重度=severe（全脸大面积红肿/水疱渗液/眼部肿胀影响视线/呼吸困难）
 *   <li>孕期哺乳期用药等高敏感医疗问题
 *   <li>客户反复表示没听懂、情绪激烈、超出智能体能力边界
 * </ol>
 *
 * <p>这四类信号本身的识别（比如"这句话算不算情绪激烈"）需要语义理解，不适合这条 Rule 自己判断——
 * 应该由上游的结构化输出 Tool 先识别出这四个布尔信号，这条 Rule 只做最后的"任一命中即触发"的
 * 确定性合并判断，这正是 Rule 该做的事：合并判断，不做语义识别。
 */
public final class HumanHandoffTriggerRule
        implements Rule<HumanHandoffTriggerRule.Input, HumanHandoffTriggerRule.Output> {

    public record Input(
            boolean explicitHumanRequest,
            boolean severeAllergy,
            boolean sensitiveMedicalContext,
            boolean emotionalOrOutOfScope) {}

    public record Output(boolean shouldHandoff, String triggerReason) {}

    @Override
    public String ruleCode() {
        return "human-handoff-trigger";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        if (input.explicitHumanRequest()) {
            return RuleResult.pass(new Output(true, "explicit_human_request"));
        }
        if (input.severeAllergy()) {
            return RuleResult.pass(new Output(true, "severe_allergy"));
        }
        if (input.sensitiveMedicalContext()) {
            return RuleResult.pass(new Output(true, "sensitive_medical_context"));
        }
        if (input.emotionalOrOutOfScope()) {
            return RuleResult.pass(new Output(true, "emotional_or_out_of_scope"));
        }
        return RuleResult.pass(new Output(false, null));
    }
}
