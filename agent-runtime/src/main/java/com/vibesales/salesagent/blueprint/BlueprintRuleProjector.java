package com.vibesales.salesagent.blueprint;

import com.vibesales.salesagent.rule.profile.FollowUpRoundLimitRule;
import com.vibesales.salesagent.rule.profile.ProfileCompletenessRule;
import com.vibesales.salesagent.rule.recovery.RecoveryDetectionRule;
import com.vibesales.salesagent.rule.taskboard.IntentPriorityRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 Blueprint 的 {@code rules[]} 投影成该租户实际生效的规则配置。
 *
 * <p>只投影<b>已接入编排链</b>的规则，当前是 {@code recovery-detection}、{@code intent-priority}、
 * 画像双闸 {@code profile-completeness} / {@code follow-up-round-limit}、
 * {@code human-handoff-trigger}、{@code queue-version-guard} 与
 * {@code closure-writeback-required-fields}。
 * {@code recovery-detection} 的 {@code continuationKeywords} 原先是 {@code RecoveryHandlingService} 里的
 * 类常量，所有租户共用一份中文续接词。这恰好是 Rule 需要租户化的典型例子：女装租户的"接着上次那件"
 * 和美妆租户的"继续之前的护肤方案"用词习惯不同，硬编码一份就必然有一方判不准。
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
        IntentPriorityRule intentPriorityRule = null;
        ProfileCompletenessRule profileCompletenessRule = null;
        FollowUpRoundLimitRule followUpRoundLimitRule = null;

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
                    switch (code) {
                        case "recovery-detection" -> recoveryRule = buildRecoveryRule(spec);
                        case "intent-priority" -> intentPriorityRule = buildIntentPriorityRule(spec);
                        case "profile-completeness" ->
                                profileCompletenessRule = buildProfileCompletenessRule(spec);
                        case "follow-up-round-limit" ->
                                followUpRoundLimitRule = buildFollowUpRoundLimitRule(spec);
                        case "human-handoff-trigger" -> {
                            // 无可覆盖参数：四个触发条件都是业务硬纪律（明确要人工、severe 过敏、
                            // 高敏感医疗、情绪激烈/超范围），任一条可关就等于允许租户把安全阀拆掉。
                            // 所以只记"本轮生效"，编排层用共享的那个默认实例。
                        }
                        case "queue-version-guard" -> {
                            // 同样无可覆盖参数：这条规则只做 queueVersion 的相等比较，
                            // 没有阈值也没有词表可配。乐观锁是 11 号文档登记的强制约束，
                            // 开关留着是为了让不接任务板的租户能关掉，参数则没有开放的意义。
                        }
                        case "closure-writeback-required-fields" -> {
                            // 第三条"无参可配"的规则：四个必填字段（摘要/意图/任务动作/queueVersion）
                            // 是下一轮读摘要能不能解析出来的前提，不是租户偏好。开放成可配等于允许
                            // 租户把"摘要可以为空"写进蓝图，下一轮就会读到一份解析不出来的摘要。
                        }
                        default -> {
                            // wired 但这里没有构造分支：说明规则接了调用点却忘了投影，
                            // 蓝图参数会静默失效，比不接更难查
                            throw new IllegalStateException(
                                    "rule " + code + " is marked wired but has no projection branch");
                        }
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
                Optional.ofNullable(intentPriorityRule),
                Optional.ofNullable(profileCompletenessRule),
                Optional.ofNullable(followUpRoundLimitRule),
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

    /**
     * 三张词表全缺省时返回 {@code null}，让编排层用 {@link IntentPriorityRule} 的 Java 默认词表。
     *
     * <p>与 {@link #buildRecoveryRule} 一样是"没配"而不是"配空了"的语义。这里三张表只要有一张被
     * 覆盖就构造实例，未覆盖的那几张由规则构造器自己回落到默认——蓝图只想换过敏词表、不想重抄
     * 六类意图关键词是很正常的配置意图。
     */
    private IntentPriorityRule buildIntentPriorityRule(AgentBlueprint.RuleSpec spec) {
        Map<String, List<String>> intentKeywords = readStringListMap(spec.params().get("intentKeywords"));
        List<String> symptomKeywords = readStringList(spec.params().get("allergySymptomKeywords"));
        List<String> usageMarkers = readStringList(spec.params().get("allergyUsageMarkers"));
        if (intentKeywords.isEmpty() && symptomKeywords.isEmpty() && usageMarkers.isEmpty()) {
            return null;
        }
        return new IntentPriorityRule(intentKeywords, symptomKeywords, usageMarkers);
    }

    /**
     * 阈值缺省（键没写、类型不对、或配了非正数）时返回 {@code null}，让编排层复用默认实例。
     *
     * <p>阈值和词表不同，没有"配空了"这一说，但"配了 0"同样是配置事故——规则构造器会把它挡回默认值，
     * 这里则连实例都不新建，好让时间线上的 {@code thresholdSource} 如实显示 {@code java-default}。
     */
    private ProfileCompletenessRule buildProfileCompletenessRule(AgentBlueprint.RuleSpec spec) {
        int threshold = readPositiveInt(spec.params().get("forcedRoundThreshold"));
        return threshold <= 0 ? null : new ProfileCompletenessRule(threshold);
    }

    /** 语义同 {@link #buildProfileCompletenessRule}，阈值键是 {@code maxFollowUpRounds}。 */
    private FollowUpRoundLimitRule buildFollowUpRoundLimitRule(AgentBlueprint.RuleSpec spec) {
        int rounds = readPositiveInt(spec.params().get("maxFollowUpRounds"));
        return rounds <= 0 ? null : new FollowUpRoundLimitRule(rounds);
    }

    /**
     * 读正整数阈值；读不出来返回 {@code 0} 表示"没配"。
     *
     * <p>字符串也认：蓝图是 JSON，但经 JDBC/远端源转一手后数字可能是字符串形态，
     * 因为形态差异让租户阈值静默失效是最难查的一类问题。
     */
    private static int readPositiveInt(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** {@code {"intentCode": ["词1","词2"]}} 形状的覆盖表；非法项跳过而不是整表作废。 */
    private static Map<String, List<String>> readStringListMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                continue;
            }
            List<String> keywords = readStringList(entry.getValue());
            if (!keywords.isEmpty()) {
                values.put(key.trim(), keywords);
            }
        }
        return Map.copyOf(values);
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
     * @param intentPriorityRule 蓝图覆盖出的意图优先级规则；{@code empty} 表示用 Java 侧默认词表
     * @param profileCompletenessRule 蓝图覆盖出的画像充分度规则；{@code empty} 表示用 Java 侧默认阈值
     * @param followUpRoundLimitRule 蓝图覆盖出的追问轮次上限规则；{@code empty} 表示用 Java 侧默认阈值
     * @param effectiveRuleCodes 本轮真实生效的规则
     * @param unwiredRuleCodes 蓝图启用了、但编排链还没有调用点的规则
     * @param disabledRuleCodes 蓝图显式 {@code enabled=false} 的规则
     * @param classification 每条声明规则的分类，用于时间线与 debug 输出
     */
    public record Projection(
            Optional<RecoveryDetectionRule> recoveryDetectionRule,
            Optional<IntentPriorityRule> intentPriorityRule,
            Optional<ProfileCompletenessRule> profileCompletenessRule,
            Optional<FollowUpRoundLimitRule> followUpRoundLimitRule,
            List<String> effectiveRuleCodes,
            List<String> unwiredRuleCodes,
            List<String> disabledRuleCodes,
            Map<String, String> classification) {

        public static Projection empty() {
            return new Projection(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
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

        /** 本轮意图关键词表的来源，语义同 {@link #recoveryKeywordSource()}。 */
        public String intentKeywordSource() {
            return intentPriorityRule.isPresent() ? "blueprint" : "java-default";
        }

        /** 本轮画像双闸阈值的来源；两条阈值分别可配，任一条被覆盖就算 {@code blueprint}。 */
        public String profileThresholdSource() {
            return profileCompletenessRule.isPresent() || followUpRoundLimitRule.isPresent()
                    ? "blueprint"
                    : "java-default";
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
