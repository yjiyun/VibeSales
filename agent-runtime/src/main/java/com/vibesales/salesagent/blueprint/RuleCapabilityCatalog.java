package com.agentteams.salesagent.blueprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本工程当前实现的确定性 Rule 清单，是 {@code Blueprint.rules[]} 校验与投影的唯一依据。
 *
 * <p>为什么 Rule 只声明不下发逻辑：{@code Rule#evaluate} 的契约是<b>无副作用纯判断</b>，每条规则的
 * 正确性都由单测锁定（{@code src/test/java/.../rule/}）。如果允许蓝图下发表达式或脚本，等于把已被
 * 测试覆盖的判断替换成运行期未验证的字符串，规则资产化反而失去了确定性这个唯一优势。因此蓝图里的
 * {@code rules[]} 只能做两件事：<b>开关</b>和<b>参数覆盖</b>。
 *
 * <p>分两类，语义差别必须让上游看见：
 *
 * <ol>
 *   <li>{@link #WIRED_RULES}——已接入编排主链、蓝图声明后<b>当轮真实生效</b>。当前只有
 *       {@code recovery-detection}（{@code orchestration:rule.recovery.evaluate} 节点）。
 *   <li>{@link #IMPLEMENTED_NOT_WIRED_RULES}——实现与单测都在，但编排链还没有调用点。蓝图声明它们
 *       只会留痕，不改变本轮行为，校验时出 warning。这不是实现缺陷，是编排链尚未推进到对应场景
 *       （画像充分度、转人工、任务板排序等节点还没接）。
 * </ol>
 *
 * <p>{@link #PARAM_KEYS} 单独列出每条规则认哪些参数键：参数写错名字（比如把
 * {@code continuationKeywords} 写成 {@code keywords}）如果静默忽略，上游会以为覆盖生效了而实际没有，
 * 这类"看起来配了但没生效"的问题最难排查，所以按 error 处理。
 */
public final class RuleCapabilityCatalog {

    /** 已接入编排主链，蓝图声明即当轮生效。 */
    public static final Set<String> WIRED_RULES = Set.of("recovery-detection");

    /** 已实现且有单测，但编排链尚无调用点。 */
    public static final Set<String> IMPLEMENTED_NOT_WIRED_RULES =
            Set.of(
                    "profile-completeness",
                    "follow-up-round-limit",
                    "intent-priority",
                    "human-handoff-trigger",
                    "queue-version-guard",
                    "closure-writeback-required-fields");

    /** 每条规则接受的参数键；未列出的规则表示当前不接受任何参数覆盖。 */
    public static final Map<String, Set<String>> PARAM_KEYS =
            Map.of(
                    "recovery-detection", Set.of("continuationKeywords"),
                    "follow-up-round-limit", Set.of("maxFollowUpRounds"),
                    "profile-completeness", Set.of("forcedRoundThreshold"));

    private RuleCapabilityCatalog() {}

    /** 全部已实现的 ruleCode（含未接线的）。 */
    public static Set<String> allImplemented() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(WIRED_RULES);
        all.addAll(IMPLEMENTED_NOT_WIRED_RULES);
        return Set.copyOf(all);
    }

    /**
     * @return {@code wired} / {@code implemented_not_wired} / {@code unsupported}
     */
    public static String classify(String ruleCode) {
        String code = ruleCode == null ? "" : ruleCode.trim();
        if (WIRED_RULES.contains(code)) {
            return "wired";
        }
        if (IMPLEMENTED_NOT_WIRED_RULES.contains(code)) {
            return "implemented_not_wired";
        }
        return "unsupported";
    }

    /** 该规则是否接受这个参数键。 */
    public static boolean acceptsParam(String ruleCode, String paramKey) {
        Set<String> accepted = PARAM_KEYS.get(ruleCode == null ? "" : ruleCode.trim());
        return accepted != null && accepted.contains(paramKey);
    }

    /** 把整份 {@code rules[]} 投影成"每条规则分别属于哪一类"的可观测输出。 */
    public static Map<String, String> classifyAll(List<AgentBlueprint.RuleSpec> rules) {
        if (rules == null) {
            return Map.of();
        }
        Map<String, String> classified = new LinkedHashMap<>();
        for (AgentBlueprint.RuleSpec rule : rules) {
            if (rule == null || rule.ruleCode() == null || rule.ruleCode().isBlank()) {
                continue;
            }
            String code = rule.ruleCode().trim();
            classified.put(code, rule.enabledOrDefault() ? classify(code) : "disabled_by_blueprint");
        }
        return Map.copyOf(classified);
    }
}
