package com.agentteams.salesagent.rule.taskboard;

import com.agentteams.salesagent.rule.Rule;
import com.agentteams.salesagent.rule.RuleResult;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 场景卡片8的固定优先级排序：转人工(1) &gt; 过敏(2) &gt; 退换货(3) &gt; 产品使用(4) &gt; 会员(5)
 * &gt; 包裹卡(6) &gt; 推荐(7) &gt; 日常(8) &gt; 越界(9)。数字越小优先级越高。
 *
 * <p>这条规则只做排序，不做意图识别——候选意图列表应该已经由意图识别节点产出，
 * 这里只负责按固定表格排出处理顺序，这个表格本身是确定性的业务规则，不需要每次都让 LLM 重新推理。
 */
public final class IntentPriorityRule
        implements Rule<IntentPriorityRule.Input, IntentPriorityRule.Output> {

    /** 意图码 → 优先级数字（越小越高），对应场景卡片8的9级固定优先级表。 */
    public static final Map<String, Integer> PRIORITY_TABLE =
            Map.ofEntries(
                    Map.entry("transfer_to_human", 1),
                    Map.entry("allergy_quality", 2),
                    Map.entry("return_exchange", 3),
                    Map.entry("product_usage", 4),
                    Map.entry("membership_benefit", 5),
                    Map.entry("package_card", 6),
                    Map.entry("product_recommend", 7),
                    Map.entry("daily_chat", 8),
                    Map.entry("out_of_scope", 9));

    public record Input(List<String> candidateIntentCodes) {}

    public record Output(List<String> sortedIntentCodes, String topPriorityIntentCode) {}

    @Override
    public String ruleCode() {
        return "intent-priority";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        List<String> sorted =
                input.candidateIntentCodes().stream()
                        .sorted(Comparator.comparingInt(IntentPriorityRule::priorityOf))
                        .toList();

        String topPriority = sorted.isEmpty() ? null : sorted.get(0);
        return RuleResult.pass(new Output(sorted, topPriority));
    }

    private static int priorityOf(String intentCode) {
        return PRIORITY_TABLE.getOrDefault(intentCode, Integer.MAX_VALUE);
    }
}
