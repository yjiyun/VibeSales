package com.vibesales.salesagent.tool.rulecontext;

import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.RuntimeApiResponse;
import com.vibesales.salesagent.mapping.RuleContextMapper;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则上下文读取 Tool，场景卡片1推荐链的前置上下文装配。
 *
 * <p>调用后端 {@code GET /api/agent/runtime/rule-context}（{@code responseMode=llm}），
 * 拿到候选分层、真实商品清单（含 {@code productId}/价格/链接）和 {@code agentOutputContract}
 * 输出约束。
 *
 * <p>后端不可用时返回 {@link RuleContextSnapshot#unavailable} —— {@code fromBackend()} 为
 * {@code false}，调用方据此判断"没有可用商品数据，不要进入推荐决策"。这比返回一份编造的
 * 占位商品清单安全得多：宁可不推荐，也不能让模型基于假数据给客户推荐不存在的商品。
 */
public final class GetRuleContextTool {

    private static final Logger log = LoggerFactory.getLogger(GetRuleContextTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端。 */
    public GetRuleContextTool() {
        this(RuntimeToolScope.disabled());
    }

    public GetRuleContextTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public RuleContextSnapshot load(CustomerContext customerContext) {
        String sceneCode = scope.resolveSceneCode(customerContext);

        if (!scope.available()) {
            return RuleContextSnapshot.unavailable(sceneCode);
        }

        RuntimeApiResponse response =
                scope.apiClient()
                        .getRuleContext(
                                scope.resolveClientCode(customerContext),
                                scope.resolveCluster(customerContext),
                                sceneCode,
                                customerContext.normalizedChatUser());

        if (response.success()) {
            return RuleContextMapper.fromResponse(response.data(), sceneCode);
        }

        // 这个接口的失败信封缺 errorCode/retryable，所以只记 error 文本
        log.warn(
                "rule context call failed, recommendation must not proceed. sceneCode={}, error={}",
                sceneCode,
                response.error());
        return RuleContextSnapshot.unavailable(sceneCode);
    }
}
