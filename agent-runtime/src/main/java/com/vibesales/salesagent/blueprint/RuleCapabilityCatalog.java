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
 *   <li>{@link #WIRED_RULES}——已接入编排主链、蓝图声明后<b>当轮真实生效</b>。当前是
 *       {@code recovery-detection}（{@code orchestration:rule.recovery.evaluate} 节点）、
 *       {@code intent-priority}（{@code orchestration:rule.intent.priority} 节点）、画像双闸
 *       {@code profile-completeness} / {@code follow-up-round-limit}
 *       （{@code orchestration:rule.profile.gate} 节点，两条一起算、结论一起进业务处理提示词）
 *       与 {@code human-handoff-trigger}（{@code orchestration:rule.handoff.trigger} 节点）
 *       与 {@code queue-version-guard}（{@code orchestration:rule.queue.version} 节点，
 *       在 {@code result_close.queue} 写回之前比对读到的与将要回传的 queueVersion）
 *       与 {@code closure-writeback-required-fields}（{@code orchestration:rule.closure.required_fields}
 *       节点，在两次写回之前检查摘要/意图/任务动作/版本号四个字段齐不齐）。
 *   <li>{@link #IMPLEMENTED_NOT_WIRED_RULES}——实现与单测都在，但编排链还没有调用点。蓝图声明它们
 *       只会留痕，不改变本轮行为，校验时出 warning。<b>当前这一类是空的</b>：六条规则已全部接线。
 *       这个集合与 {@link AgentBlueprintValidator} 里对应的 warning 分支都保留着——规则通常先落地
 *       实现与单测、后接调用点，下一条规则进来时还会经过这个状态。
 * </ol>
 *
 * <p>{@link #PARAM_KEYS} 单独列出每条规则认哪些参数键：参数写错名字（比如把
 * {@code continuationKeywords} 写成 {@code keywords}）如果静默忽略，上游会以为覆盖生效了而实际没有，
 * 这类"看起来配了但没生效"的问题最难排查，所以按 error 处理。
 */
public final class RuleCapabilityCatalog {

    /** 已接入编排主链，蓝图声明即当轮生效。 */
    public static final Set<String> WIRED_RULES =
            Set.of(
                    "recovery-detection",
                    "intent-priority",
                    "profile-completeness",
                    "follow-up-round-limit",
                    "human-handoff-trigger",
                    "queue-version-guard",
                    "closure-writeback-required-fields");

    /**
     * 已实现且有单测，但编排链尚无调用点。
     *
     * <p>当前为空——六条规则已全部接线。留着空集合而不是删掉这一类：新规则通常先落地实现与单测、
     * 后接调用点，删掉的话下一条规则进来时只能在 {@code WIRED_RULES} 与 {@code unsupported} 之间选，
     * 前者会让 {@link BlueprintRuleProjector} 的 {@code default} 分支抛异常，后者会让校验直接报 error。
     */
    public static final Set<String> IMPLEMENTED_NOT_WIRED_RULES = Set.of();

    /**
     * 每条规则接受的参数键；未列出的规则表示当前不接受任何参数覆盖。
     *
     * <p>{@code intent-priority} 只开放三张<b>词表</b>，不开放优先级表：九级顺序（高风险永远压过推荐）
     * 是业务纪律而不是租户偏好，能被蓝图改掉的话这条规则就失去了存在意义。词表可改是因为
     * "运营换一个关键词不该改 Java 代码"。
     *
     * <p>同理画像双闸只开放<b>轮次阈值</b>：追问几轮认输是运营节奏，而"哪些字段算画像充分"是业务
     * 定义的两个信号组，可配等于换一套画像模型。
     */
    public static final Map<String, Set<String>> PARAM_KEYS =
            Map.of(
                    "recovery-detection", Set.of("continuationKeywords"),
                    "follow-up-round-limit", Set.of("maxFollowUpRounds"),
                    "profile-completeness", Set.of("forcedRoundThreshold"),
                    "intent-priority",
                            Set.of("intentKeywords", "allergySymptomKeywords", "allergyUsageMarkers"));

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
