package com.vibesales.salesagent.rule.closure;

import com.vibesales.salesagent.rule.Rule;
import com.vibesales.salesagent.rule.RuleResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 收口写回前的必填项校验（Post-LLM，模型产出结果后、写回前的最后一道检查）。
 *
 * <p>对应场景卡片8的统一收口环节——摘要/任务板增量对象在写入 {@code saveHistorySummary}/
 * {@code syncIntentQueue} 之前，必须先确认关键字段完整，避免因为模型输出不完整导致下一轮读取
 * 摘要时解析出错（见06号文档 {@code conversation-closure} 的 Skill 说明）。
 */
public final class ClosureWritebackRequiredFieldsRule
        implements Rule<ClosureWritebackRequiredFieldsRule.Input, ClosureWritebackRequiredFieldsRule.Output> {

    public record Input(
            String summaryText, String intentCode, String taskStatus, String queueVersion) {}

    public record Output(boolean isComplete, List<String> missingFields) {}

    @Override
    public String ruleCode() {
        return "closure-writeback-required-fields";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        List<String> missingFields = new ArrayList<>();
        if (isBlank(input.summaryText())) missingFields.add("summaryText");
        if (isBlank(input.intentCode())) missingFields.add("intentCode");
        if (isBlank(input.taskStatus())) missingFields.add("taskStatus");
        if (isBlank(input.queueVersion())) missingFields.add("queueVersion");

        return RuleResult.pass(new Output(missingFields.isEmpty(), missingFields));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
