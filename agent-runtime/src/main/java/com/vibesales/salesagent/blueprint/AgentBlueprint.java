package com.agentteams.salesagent.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 上游生成引擎产出的 Agent 装配中间物。
 *
 * <p>字段形状基线是上游 {@code agent-runtime} 的
 * {@code com.yjiyun.chatflows.runtime.blueprint.AgentBlueprint}（实测该 record 只有
 * {@code blueprintId/version/clientCode/runtimeAgentId/meta/prompt/skills/tools/runtime} 九个字段）。
 * 本类在此基线上<b>主动多出两个字段</b>，都是本工程认定为必需、需要反推给上游契约补齐的：
 *
 * <ol>
 *   <li>{@code cluster}——租户作用域的第二个维度。运行期从调用参数里同时取 {@code clientCode} 与
 *       {@code cluster} 定位蓝图，命中不到才降级为只按 {@code clientCode} 查。写进 JSON 而不是只放在
 *       本地索引里，是为了让蓝图<b>自描述</b>：一份 JSON 单独拿出来也能说清自己属于哪个作用域，
 *       并且能和索引交叉校验，防止蓝图放错位置导致串租户。
 *   <li>{@code rules}——确定性规则的声明位。本工程有 7 条 {@code Rule} 实现，原上游契约无处安放，
 *       只能硬编码在 Java 里，租户之间无法差异化。详见 {@link RuleCapabilityCatalog}。
 * </ol>
 *
 * <p>两个字段都做成<b>可缺省</b>：上游尚未补齐契约时下发的九字段 JSON 依然能正常加载，
 * {@code cluster} 缺省即该租户默认蓝图，{@code rules} 缺省即全部走 Java 侧默认参数。
 *
 * <p>用 {@code ignoreUnknown = true}：上游示例 JSON 里带了大量 {@code _说明}/{@code _注} 注释键，
 * 而且 Blueprint schema 还在演进（{@code guidance}、{@code runtime.compaction} 等本工程当前不消费），
 * 遇到未识别字段应该忽略而不是拒绝加载——本工程支持哪些字段由
 * {@link AgentBlueprintValidator} 显式给出，不靠反序列化失败来表达。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentBlueprint(
        String blueprintId,
        int version,
        String clientCode,
        String cluster,
        String runtimeAgentId,
        Meta meta,
        Prompt prompt,
        List<Skill> skills,
        List<RuleSpec> rules,
        Tools tools,
        RuntimeSpec runtime,
        String runtimeMode,
        List<StageSpec> stages) {

    public AgentBlueprint {
        skills = skills == null ? List.of() : List.copyOf(skills);
        rules = rules == null ? List.of() : List.copyOf(rules);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** 上游 Java record 会硬拒绝非 {@code USER} 的隔离范围，这里保持同一口径。 */
    public static final String IMMUTABLE_ISOLATION_SCOPE = "USER";
    public static final String RUNTIME_MODE_SINGLE_AGENT = "single_agent";
    public static final String RUNTIME_MODE_MULTI_STAGE = "multi_stage";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String industry, List<String> scenarios, String generatedBy, String runId) {
        public Meta {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prompt(String agentsMd, String soulMd, String knowledgeMd) {}

    /**
     * 单个 Skill 声明。
     *
     * <p>{@code source = inline} 时内容直接在 {@code skillMd} 里；{@code source = library} 时由
     * {@code ref} 指向预置 Skill 目录。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Skill(String name, String source, String ref, String skillMd) {
        public static final String SOURCE_INLINE = "inline";
        public static final String SOURCE_LIBRARY = "library";

        public boolean isInline() {
            return SOURCE_INLINE.equalsIgnoreCase(source);
        }

        public boolean isLibrary() {
            return SOURCE_LIBRARY.equalsIgnoreCase(source);
        }
    }

    /**
     * 单条确定性规则的租户级声明。
     *
     * <p>刻意<b>不</b>让蓝图携带规则逻辑（不下发表达式、不下发脚本）：规则实现始终留在 Java 侧，
     * 蓝图只声明"启用哪条规则、用什么参数"。理由是 {@code Rule} 的契约是无副作用纯判断，
     * 其正确性由单测锁定；如果允许上游下发逻辑，等于把已被测试覆盖的判断换成运行期未验证的字符串。
     *
     * @param ruleCode 对应 {@code Rule#ruleCode()}，必须在 {@link RuleCapabilityCatalog} 里存在
     * @param enabled 是否启用；缺省视为启用
     * @param params 规则参数覆盖，形状由各 Rule 自己定义（例如
     *     {@code recovery-detection} 认 {@code continuationKeywords}，
     *     {@code follow-up-round-limit} 认 {@code maxFollowUpRounds}）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleSpec(String ruleCode, Boolean enabled, Map<String, Object> params) {
        public RuleSpec {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tools(List<String> allow, List<String> deny, List<McpServer> mcpServers) {
        public Tools {
            allow = allow == null ? List.of() : List.copyOf(allow);
            deny = deny == null ? List.of() : List.copyOf(deny);
            mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpServer(String name, String url, String transport) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuntimeSpec(String model, String isolationScope, int maxContextTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StageSpec(
            String stageKey,
            String displayName,
            String stageType,
            String description,
            String promptRef,
            String prompt,
            List<String> skills,
            List<String> rules,
            List<String> toolsAllow,
            String enabledWhen,
            String inputContract,
            String outputContract,
            String next,
            Map<String, String> onResult) {
        public StageSpec {
            skills = skills == null ? List.of() : List.copyOf(skills);
            rules = rules == null ? List.of() : List.copyOf(rules);
            toolsAllow = toolsAllow == null ? List.of() : List.copyOf(toolsAllow);
            onResult = onResult == null ? Map.of() : Map.copyOf(onResult);
        }
    }

    public Prompt promptOrEmpty() {
        return prompt == null ? new Prompt("", "", "") : prompt;
    }

    public Tools toolsOrEmpty() {
        return tools == null ? new Tools(List.of(), List.of(), List.of()) : tools;
    }

    public Meta metaOrEmpty() {
        return meta == null ? new Meta("", List.of(), "", "") : meta;
    }

    public RuntimeSpec runtimeOrEmpty() {
        return runtime == null ? new RuntimeSpec("", IMMUTABLE_ISOLATION_SCOPE, 0) : runtime;
    }

    public String runtimeModeOrDefault() {
        if (runtimeMode == null || runtimeMode.isBlank()) {
            return RUNTIME_MODE_SINGLE_AGENT;
        }
        return runtimeMode.trim();
    }

    public boolean isMultiStage() {
        return RUNTIME_MODE_MULTI_STAGE.equalsIgnoreCase(runtimeModeOrDefault());
    }

    /** {@code cluster} 缺省即该 {@code clientCode} 的默认蓝图，用空串表示，不用 null 往下传。 */
    public String clusterOrEmpty() {
        return cluster == null ? "" : cluster.trim();
    }
}
