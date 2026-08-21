package com.vibesales.salesagent.tool.rulecontext;

import java.util.List;

/**
 * {@code getRuleContext} Tool 的返回快照，承载场景卡片1推荐链的前置上下文。
 *
 * <p><b>为什么保留 {@code allowedProductIds} 和 {@code recommendationConstraints}</b>：
 * 场景卡片1有一条硬性约束——"不能编造商品/链接/价格"。后端 {@code agentOutputContract} 已经把
 * 这条约束给成了机器可读形式（{@code mustReturnFields} + {@code recommendationConstraints}），
 * 配合带真实 {@code productId} 的商品清单，就能让推荐决策有据可循，而不是靠提示词里写一句
 * "请不要编造"去指望模型自觉。
 *
 * <p>{@code promptText} 是给模型看的紧凑文本形式——把分层、商品、约束压成一段结构化文本注入
 * 提示词，比把整个 JSON 塞进去更省 token 也更易读。
 */
public final class RuleContextSnapshot {

    private final String sceneCode;
    private final String ruleVersion;
    private final String taskGoal;
    private final List<String> decisionRules;
    private final List<String> mustReturnFields;
    private final List<String> recommendationConstraints;
    private final List<String> allowedProductIds;
    private final String promptText;
    private final boolean fromBackend;

    public RuleContextSnapshot(
            String sceneCode,
            String ruleVersion,
            String taskGoal,
            List<String> decisionRules,
            List<String> mustReturnFields,
            List<String> recommendationConstraints,
            List<String> allowedProductIds,
            String promptText,
            boolean fromBackend) {
        this.sceneCode = sceneCode;
        this.ruleVersion = ruleVersion;
        this.taskGoal = taskGoal;
        this.decisionRules = List.copyOf(decisionRules);
        this.mustReturnFields = List.copyOf(mustReturnFields);
        this.recommendationConstraints = List.copyOf(recommendationConstraints);
        this.allowedProductIds = List.copyOf(allowedProductIds);
        this.promptText = promptText;
        this.fromBackend = fromBackend;
    }

    /** 后端不可用时的空上下文：{@code fromBackend=false}，调用方据此判断不要进入推荐决策。 */
    public static RuleContextSnapshot unavailable(String sceneCode) {
        return new RuleContextSnapshot(
                sceneCode,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "规则上下文接口当前不可用，没有可用的分层与商品数据。",
                false);
    }

    public String sceneCode() {
        return sceneCode;
    }

    public String ruleVersion() {
        return ruleVersion;
    }

    public String taskGoal() {
        return taskGoal;
    }

    public List<String> decisionRules() {
        return decisionRules;
    }

    public List<String> mustReturnFields() {
        return mustReturnFields;
    }

    public List<String> recommendationConstraints() {
        return recommendationConstraints;
    }

    /**
     * 允许被推荐的真实 {@code productId} 白名单。
     *
     * <p>推荐决策产出的商品必须落在这个集合内——这是"不能编造商品"从提示词约束变成
     * 可校验约束的关键。
     */
    public List<String> allowedProductIds() {
        return allowedProductIds;
    }

    /** 注入提示词的紧凑文本形式。 */
    public String promptText() {
        return promptText;
    }

    /** 是否来自后端真实数据；{@code false} 表示走了降级路径。 */
    public boolean fromBackend() {
        return fromBackend;
    }
}
