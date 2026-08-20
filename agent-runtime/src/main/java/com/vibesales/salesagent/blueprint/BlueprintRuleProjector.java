package com.agentteams.salesagent.blueprint;

import com.agentteams.salesagent.rule.recovery.RecoveryDetectionRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 Blueprint 的 {@code rules[]} 投影成该租户实际生效的规则配置。
 *
 * <p>只投影<b>已接入编排链</b>的规则，当前是 {@code recovery-detection}——它的
 * {@code continuationKeywords} 原先是 {@code RecoveryHandlingService} 里的类常量，所有租户共用一份
 * 中文续接词。这恰好是 Rule 需要租户化的典型例子：女装租户的"接着上次那件"和美妆租户的
 * "继续之前的护肤方案"用词习惯不同，硬编码一份就必然有一方判不准。
 *
 * <p>未接线的规则只做留痕（{@link #disabledRules()} / {@link #unwiredRules()}），不构造实例——
 * 构造了也没有调用点，反而会让 debug 输出看起来"已生效"。
 */
public final class BlueprintRuleProjector {

    public Projection project(AgentBlueprint blueprint) {
        Map<String, String> classification = RuleCapabilityCatalog.classifyAll(blueprint.rules());
        List<String> effective = new ArrayList<>();
        List<String> unwired = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        RecoveryDetectionRule recoveryRule = null;

        for (AgentBlueprint.RuleSpec spec : blueprint.rules()) {
            if (spec == null || spec.ruleCode() == null || spec.ruleCode().isBlank()) {
                continue;
            }
            String code = spec.ruleCode().trim();
            if (!spec.enabledOrDefault()) {
                disabled.add(code);
                continue;
            }
            switch (RuleCapabilityCatalog.classify(code)) {
                case "wired" -> {
                    if ("recovery-detection".equals(code)) {
                        recoveryRule = buildRecoveryRule(spec);
                    }
                    effective.add(code);
                }
                case "implemented_not_wired" -> unwired.add(code);
                default -> {
                    // 校验阶段已按 error 拦下，这里不该出现；真出现也不静默吞掉
                    throw new IllegalStateException(
                            "blueprint "
                                    + blueprint.blueprintId()
                                    + " declares unsupported rule: "
                                    + code);
                }
            }
        }

        return new Projection(
                Optional.ofNullable(recoveryRule),
                List.copyOf(effective),
                List.copyOf(unwired),
                List.copyOf(disabled),
                classification);
    }

    /**
     * {@code continuationKeywords} 缺省或为空时返回 {@code null}，让编排层继续用
     * {@code RecoveryHandlingService} 的默认词表——蓝图"启用但不覆盖参数"是合法配置，
     * 不该被当成"覆盖成空列表"。
     */
    private RecoveryDetectionRule buildRecoveryRule(AgentBlueprint.RuleSpec spec) {
        List<String> keywords = readStringList(spec.params().get("continuationKeywords"));
        if (keywords.isEmpty()) {
            return null;
        }
        return new RecoveryDetectionRule(keywords);
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return List.copyOf(values);
    }

    /**
     * @param recoveryDetectionRule 蓝图覆盖出的恢复判断规则；{@code empty} 表示用 Java 侧默认词表
     * @param effectiveRuleCodes 本轮真实生效的规则
     * @param unwiredRuleCodes 蓝图启用了、但编排链还没有调用点的规则
     * @param disabledRuleCodes 蓝图显式 {@code enabled=false} 的规则
     * @param classification 每条声明规则的分类，用于时间线与 debug 输出
     */
    public record Projection(
            Optional<RecoveryDetectionRule> recoveryDetectionRule,
            List<String> effectiveRuleCodes,
            List<String> unwiredRuleCodes,
            List<String> disabledRuleCodes,
            Map<String, String> classification) {

        public static Projection empty() {
            return new Projection(Optional.empty(), List.of(), List.of(), List.of(), Map.of());
        }

        public Projection {
            effectiveRuleCodes = effectiveRuleCodes == null ? List.of() : List.copyOf(effectiveRuleCodes);
            unwiredRuleCodes = unwiredRuleCodes == null ? List.of() : List.copyOf(unwiredRuleCodes);
            disabledRuleCodes = disabledRuleCodes == null ? List.of() : List.copyOf(disabledRuleCodes);
            classification = classification == null ? Map.of() : Map.copyOf(classification);
        }

        /** 本轮续接词表的来源；时间线上要能一眼看出是租户配的还是 Java 默认的。 */
        public String recoveryKeywordSource() {
            return recoveryDetectionRule.isPresent() ? "blueprint" : "java-default";
        }

        /** 键顺序同 {@link ResolvedBlueprint#toTimelineDetail()}：靠前的键才一定被工作台渲染。 */
        public Map<String, Object> toTimelineDetail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("effective", effectiveRuleCodes);
            detail.put("unwired", unwiredRuleCodes);
            detail.put("disabled", disabledRuleCodes);
            detail.put("classification", classification);
            return java.util.Collections.unmodifiableMap(detail);
        }
    }
}
