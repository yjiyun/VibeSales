package com.vibesales.salesagent.rule.taskboard;

import com.vibesales.salesagent.rule.Rule;
import com.vibesales.salesagent.rule.RuleResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图优先级补正（对应原 Coze 节点 {@code 115297 U3-02 结果自检}）。
 *
 * <p>固定九级优先级：转人工(1) &gt; 过敏(2) &gt; 退换货(3) &gt; 产品使用(4) &gt; 会员(5)
 * &gt; 包裹卡(6) &gt; 推荐(7) &gt; 日常(8) &gt; 越界(9)。数字越小优先级越高。
 *
 * <p>这条规则做两件确定性的事，都是<b>不需要每轮让 LLM 重新推理</b>的：
 *
 * <ol>
 *   <li><b>排序</b>——多个候选意图共现时按上表排出处理顺序。
 *   <li><b>补正</b>——按关键词表从客户原话里独立推导候选，与 LLM 声明的主意图比对。声明值优先级
 *       低于关键词推导值时，输出 {@link #VIOLATION_PRIORITY_CORRECTED}，用确定性结果盖掉模型判断。
 *       典型场景：「用了你们水乳脸红了，还有别的推荐吗」——模型容易判成 {@code product_recommend}，
 *       但必须走 {@code allergy_quality}。
 * </ol>
 *
 * <p><b>兜底不在这条规则里</b>。候选全空时 {@link Output#topPriorityIntentCode()} 返回
 * {@code null} 而不是 {@code out_of_scope}：规则的语义是"给我候选我来排"，"一个候选都没有时该
 * 落到哪个意图"是编排策略而不是排序判断。九码场景的 {@code out_of_scope} 兜底写在意图识别阶段
 * 提示词里，非九码词表的蓝图（如首轮 multi_stage 样例）由编排层的 heuristic 兜底——把兜底塞进规则
 * 会让这两类蓝图必须共用同一套意图词表。
 *
 * <p><b>不认识的声明意图不会被补正</b>。声明值不在 {@link #PRIORITY_TABLE} 里说明该蓝图用的不是
 * 九码词表，这条规则的表格管不到它，此时原样返回并标
 * {@link #VIOLATION_DECLARED_NOT_IN_TABLE}——越权改写别人的词表比不补正更糟。
 */
public final class IntentPriorityRule
        implements Rule<IntentPriorityRule.Input, IntentPriorityRule.Output> {

    /** 声明主意图被关键词推导结果盖掉。 */
    public static final String VIOLATION_PRIORITY_CORRECTED = "priority_corrected";

    /** 声明主意图不在九码表内，本规则不予补正。 */
    public static final String VIOLATION_DECLARED_NOT_IN_TABLE = "declared_intent_not_in_priority_table";

    /** 证据关键词最多留 3 个：再多也不改变判断，只会把时间线刷满。 */
    private static final int MAX_EVIDENCE = 3;

    /** 意图码 → 优先级数字（越小越高），九码取自 {@code guyu-intent-route.md} 的枚举表。 */
    public static final Map<String, Integer> PRIORITY_TABLE =
            Map.ofEntries(
                    Map.entry("transfer_to_human", 1),
                    Map.entry("allergy_quality", 2),
                    Map.entry("return_exchange", 3),
                    Map.entry("product_usage", 4),
                    Map.entry("membership", 5),
                    Map.entry("package_card", 6),
                    Map.entry("product_recommend", 7),
                    Map.entry("daily_response", 8),
                    Map.entry("out_of_scope", 9));

    /**
     * 历史写法 → 九码正名。
     *
     * <p>{@code membership_benefit} / {@code daily_chat} 是本规则早期单测里的拼法，蓝图与阶段提示词
     * 用的是 {@code membership} / {@code daily_response}。别名只用于<b>查优先级</b>，输出仍保留调用方
     * 传进来的原始拼法——规则不该悄悄改写调用方给的字符串。
     */
    private static final Map<String, String> INTENT_ALIASES =
            Map.of(
                    "membership_benefit", "membership",
                    "daily_chat", "daily_response");

    /**
     * 关键词补正表，按优先级顺序排列（证据顺序因此也是确定的）。
     *
     * <p>{@code allergy_quality} 不在这张表里，它是双条件，单独走
     * {@link #DEFAULT_ALLERGY_SYMPTOM_KEYWORDS} + {@link #DEFAULT_ALLERGY_USAGE_MARKERS}。
     */
    public static final Map<String, List<String>> DEFAULT_INTENT_KEYWORDS = defaultIntentKeywords();

    /**
     * 过敏不适词；单独出现<b>不</b>构成 {@code allergy_quality}。
     *
     * <p>{@code 脸红} 必须单列：客户实际说的是「用了你们水乳脸红了」，不会说「泛红」——只配书面词
     * 会让本规则最典型的那个 case 静默漏判。
     */
    public static final List<String> DEFAULT_ALLERGY_SYMPTOM_KEYWORDS =
            List.of(
                    "过敏", "刺痛", "红肿", "灼热", "发痒", "泛红", "脸红", "脱皮", "起皮", "闭口",
                    "爆痘", "不适", "烂脸");

    /**
     * 过敏链的第二个必需条件：使用痕迹。
     *
     * <p>只问「你们家会不会过敏」没有使用痕迹的，是咨询性提问而不是质量事故。这条双条件不能放宽，
     * 放宽会把咨询直接升级成售后工单。
     */
    public static final List<String> DEFAULT_ALLERGY_USAGE_MARKERS =
            List.of("用了", "用完", "使用后", "涂了", "擦了", "上脸", "买的", "这款");

    private final Map<String, List<String>> intentKeywords;
    private final List<String> allergySymptomKeywords;
    private final List<String> allergyUsageMarkers;

    /** 用 Java 侧默认词表。 */
    public IntentPriorityRule() {
        this(DEFAULT_INTENT_KEYWORDS, DEFAULT_ALLERGY_SYMPTOM_KEYWORDS, DEFAULT_ALLERGY_USAGE_MARKERS);
    }

    /**
     * 用租户词表。
     *
     * <p>词表可覆盖是刻意的：运营改一个关键词不该改 Java 代码。但<b>优先级表不可覆盖</b>——
     * 九级顺序是业务纪律（高风险永远压过推荐），不是租户偏好。
     */
    public IntentPriorityRule(
            Map<String, List<String>> intentKeywords,
            List<String> allergySymptomKeywords,
            List<String> allergyUsageMarkers) {
        this.intentKeywords = copyKeywordTable(intentKeywords, DEFAULT_INTENT_KEYWORDS);
        this.allergySymptomKeywords =
                copyKeywords(allergySymptomKeywords, DEFAULT_ALLERGY_SYMPTOM_KEYWORDS);
        this.allergyUsageMarkers = copyKeywords(allergyUsageMarkers, DEFAULT_ALLERGY_USAGE_MARKERS);
    }

    /** 本实例实际生效的关键词表，供时间线留痕与断言使用。 */
    public Map<String, List<String>> intentKeywords() {
        return intentKeywords;
    }

    /** 本实例实际生效的过敏使用痕迹词。 */
    public List<String> allergyUsageMarkers() {
        return allergyUsageMarkers;
    }

    /**
     * @param candidateIntentCodes 上游已经算出的候选意图（可为空）
     * @param declaredIntentCode LLM 本轮声明的主意图；空串表示模型没判出来
     * @param confidence LLM 自评置信度 {@code high|medium|low}，只用于算 {@code priorityLabel}
     * @param userMessage 客户原话，关键词补正的唯一输入；空串表示不做关键词推导
     */
    public record Input(
            List<String> candidateIntentCodes,
            String declaredIntentCode,
            String confidence,
            String userMessage) {

        /** 只排序、不补正的用法。 */
        public Input(List<String> candidateIntentCodes) {
            this(candidateIntentCodes, "", "", "");
        }

        public Input {
            candidateIntentCodes =
                    candidateIntentCodes == null ? List.of() : List.copyOf(candidateIntentCodes);
            declaredIntentCode = trim(declaredIntentCode);
            confidence = trim(confidence);
            userMessage = userMessage == null ? "" : userMessage;
        }
    }

    /**
     * @param sortedIntentCodes 全部候选按优先级升序；保留调用方传入的原始拼法
     * @param topPriorityIntentCode 主意图；候选全空时为 {@code null}（见类注释"兜底不在这条规则里"）
     * @param violationType 空串表示声明值与补正结果一致
     * @param priorityLabel {@code high|medium|low}，供下游阶段与任务板使用
     * @param evidence 命中的关键词，最多 {@value #MAX_EVIDENCE} 个
     */
    public record Output(
            List<String> sortedIntentCodes,
            String topPriorityIntentCode,
            String violationType,
            String priorityLabel,
            List<String> evidence) {

        public Output {
            sortedIntentCodes = sortedIntentCodes == null ? List.of() : List.copyOf(sortedIntentCodes);
            violationType = trim(violationType);
            priorityLabel = trim(priorityLabel);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        /** 声明主意图是否被确定性结果盖掉。 */
        public boolean corrected() {
            return VIOLATION_PRIORITY_CORRECTED.equals(violationType);
        }
    }

    @Override
    public String ruleCode() {
        return "intent-priority";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        List<String> evidence = new ArrayList<>();
        // canonical -> 调用方原始拼法：去重按正名，输出按原话
        Map<String, String> candidates = new LinkedHashMap<>();
        if (!input.declaredIntentCode().isEmpty()) {
            candidates.putIfAbsent(canonical(input.declaredIntentCode()), input.declaredIntentCode());
        }
        for (String derived : deriveFromKeywords(input.userMessage(), evidence)) {
            candidates.putIfAbsent(derived, derived);
        }
        for (String candidate : input.candidateIntentCodes()) {
            String normalized = trim(candidate);
            if (!normalized.isEmpty()) {
                candidates.putIfAbsent(canonical(normalized), normalized);
            }
        }

        List<String> trimmedEvidence =
                evidence.size() <= MAX_EVIDENCE ? List.copyOf(evidence) : List.copyOf(evidence.subList(0, MAX_EVIDENCE));

        // 声明值不在九码表里：这个蓝图用的不是本规则管的词表，原样放回，只排序不补正
        if (!input.declaredIntentCode().isEmpty()
                && !PRIORITY_TABLE.containsKey(canonical(input.declaredIntentCode()))) {
            return RuleResult.pass(
                    new Output(
                            List.of(input.declaredIntentCode()),
                            input.declaredIntentCode(),
                            VIOLATION_DECLARED_NOT_IN_TABLE,
                            "high".equals(input.confidence()) ? "high" : "medium",
                            trimmedEvidence));
        }

        List<String> sorted =
                candidates.values().stream()
                        .sorted(
                                Comparator.comparingInt(IntentPriorityRule::priorityOf)
                                        // 同优先级时声明值优先，再按码值稳定排序。文档里的
                                        // "confidence desc" 这一档在本实现里落不了地：入参只有
                                        // 声明主意图一个置信度，其余候选是关键词推导出来的，没有可比的分数
                                        .thenComparing(code -> code.equals(input.declaredIntentCode()) ? 0 : 1)
                                        .thenComparing(Comparator.naturalOrder()))
                        .toList();

        String top = sorted.isEmpty() ? null : sorted.get(0);
        String violation = "";
        if (top != null
                && !input.declaredIntentCode().isEmpty()
                && !canonical(top).equals(canonical(input.declaredIntentCode()))) {
            violation = VIOLATION_PRIORITY_CORRECTED;
        }
        return RuleResult.pass(
                new Output(sorted, top, violation, priorityLabel(top, input.confidence()), trimmedEvidence));
    }

    /**
     * 优先级分档，取自 {@code guyu-intent-route.md}：priority ≤3 恒为 high（高风险与售后不看模型
     * 自评）；≤7 时才采信 confidence；≥8 恒为 low。
     */
    private static String priorityLabel(String topIntentCode, String confidence) {
        if (topIntentCode == null) {
            return "";
        }
        int priority = priorityOf(topIntentCode);
        if (priority <= 3) {
            return "high";
        }
        if (priority <= 7) {
            return "high".equals(confidence) ? "high" : "medium";
        }
        return "low";
    }

    /** 从客户原话推导候选意图，顺带把命中的关键词写进 {@code evidence}。 */
    private List<String> deriveFromKeywords(String userMessage, List<String> evidence) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        List<String> derived = new ArrayList<>();
        String symptom = firstMatch(userMessage, allergySymptomKeywords);
        if (symptom != null) {
            String usage = firstMatch(userMessage, allergyUsageMarkers);
            if (usage != null) {
                derived.add("allergy_quality");
                evidence.add(symptom);
                evidence.add(usage);
            }
        }
        for (Map.Entry<String, List<String>> entry : intentKeywords.entrySet()) {
            String hit = firstMatch(userMessage, entry.getValue());
            if (hit != null) {
                derived.add(entry.getKey());
                evidence.add(hit);
            }
        }
        return derived;
    }

    private static String firstMatch(String userMessage, List<String> keywords) {
        for (String keyword : keywords) {
            if (userMessage.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private static int priorityOf(String intentCode) {
        return PRIORITY_TABLE.getOrDefault(canonical(intentCode), Integer.MAX_VALUE);
    }

    private static String canonical(String intentCode) {
        String normalized = trim(intentCode);
        return INTENT_ALIASES.getOrDefault(normalized, normalized);
    }

    private static Map<String, List<String>> defaultIntentKeywords() {
        Map<String, List<String>> table = new LinkedHashMap<>();
        table.put("transfer_to_human", List.of("转人工", "找人工", "叫客服", "真人", "人工客服"));
        table.put(
                "return_exchange",
                List.of(
                        "退款", "退货", "换货", "补发", "物流", "快递", "破损", "少件", "少发", "漏发",
                        "发错", "能退吗", "能换吗"));
        table.put(
                "product_usage",
                List.of(
                        "怎么用", "使用方法", "使用顺序", "先用哪个", "多久用一次", "频率", "搭配用",
                        "先乳后水", "先水后乳"));
        table.put("membership", List.of("会员", "积分", "等级", "权益", "生日礼", "会员日"));
        table.put("package_card", List.of("包裹卡", "卡片", "刮奖", "礼包卡", "二维码卡", "卡券", "卡密"));
        table.put(
                "product_recommend",
                List.of(
                        "推荐", "适合", "合适", "想买", "想看", "产品", "链接", "搭一套", "补水", "修护",
                        "美白", "控油", "发链接", "给我链接"));
        return java.util.Collections.unmodifiableMap(table);
    }

    /** 覆盖表里的空条目回落到默认——"配了个空列表"和"没配"不该有区别。 */
    private static Map<String, List<String>> copyKeywordTable(
            Map<String, List<String>> override, Map<String, List<String>> fallback) {
        if (override == null || override.isEmpty()) {
            return fallback;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fallback.entrySet()) {
            List<String> keywords = copyKeywords(override.get(entry.getKey()), entry.getValue());
            merged.put(entry.getKey(), keywords);
        }
        for (Map.Entry<String, List<String>> entry : override.entrySet()) {
            String intentCode = trim(entry.getKey());
            if (intentCode.isEmpty() || merged.containsKey(intentCode)) {
                continue;
            }
            List<String> keywords = copyKeywords(entry.getValue(), List.of());
            if (!keywords.isEmpty()) {
                merged.put(intentCode, keywords);
            }
        }
        return java.util.Collections.unmodifiableMap(merged);
    }

    private static List<String> copyKeywords(List<String> override, List<String> fallback) {
        if (override == null) {
            return fallback;
        }
        List<String> values =
                override.stream().map(IntentPriorityRule::trim).filter(text -> !text.isEmpty()).toList();
        return values.isEmpty() ? fallback : values;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
