package com.vibesales.salesagent.rule;

/**
 * Rule 的统一判断结果（见 07-Rule资产设计与接口规范.md 第2节）。
 *
 * <p>用 {@code passed} 而不是抛异常表达"规则未通过"——规则判断"不满足"是业务上的正常路径
 * （比如"画像还不够充分，还不能推荐"），不是错误，不应该用异常控制流。
 */
public record RuleResult<O>(boolean passed, O output, String reasonCode, String reasonDetail) {

    public static <O> RuleResult<O> pass(O output) {
        return new RuleResult<>(true, output, null, null);
    }

    public static <O> RuleResult<O> reject(String reasonCode, String reasonDetail) {
        return new RuleResult<>(false, null, reasonCode, reasonDetail);
    }
}
