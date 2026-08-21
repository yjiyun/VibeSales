package com.vibesales.salesagent.blueprint;

import java.util.List;

/**
 * Blueprint 校验结果。
 *
 * <p>刻意把结果拆成 {@code errors} / {@code warnings} / {@code consumedFields} / {@code ignoredFields}
 * 四份清单，而不是只返回一个 boolean：本次模拟对上游最有价值的产出之一，就是一份"本工程到底消费了
 * 哪些字段、忽略了哪些字段"的明确说明——上游可以直接拿它核对生成的 JSON。
 */
public record BlueprintValidationReport(
        List<String> errors,
        List<String> warnings,
        List<String> consumedFields,
        List<String> ignoredFields) {

    public BlueprintValidationReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        consumedFields = consumedFields == null ? List.of() : List.copyOf(consumedFields);
        ignoredFields = ignoredFields == null ? List.of() : List.copyOf(ignoredFields);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public String errorSummary() {
        return String.join("; ", errors);
    }
}
