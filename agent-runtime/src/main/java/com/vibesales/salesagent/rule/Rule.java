package com.agentteams.salesagent.rule;

/**
 * 所有 Rule 类的统一接口契约（见 07-Rule资产设计与接口规范.md 第2节）。
 *
 * <p>{@code evaluate} 不允许有副作用——规则只做判断，不做读写。如果判断需要先查询外部数据，
 * 查询动作应该在调用 Rule 之前由 Tool 完成，Rule 只接收已经查好的数据做判断。
 */
public interface Rule<I, O> {

    /** 规则的唯一标识，用于日志、审计和后续资产注册。 */
    String ruleCode();

    /** 执行判断，不应有副作用（不读写外部系统），纯函数式判断。 */
    RuleResult<O> evaluate(I input);
}
