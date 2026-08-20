package com.agentteams.salesagent.blueprint;

import io.agentscope.core.skill.util.SkillUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Blueprint 装配前校验。
 *
 * <p>原则是：<b>装配阶段直接报错，不留到运行时静默忽略</b>。缺字段、Skill 缺 frontmatter、
 * 声明了本工程不存在的 Tool，这些都应该在 resolve 阶段就暴露出来。
 *
 * <p>errors 与 warnings 的分界：
 * <ul>
 *   <li>errors = 拿这份 Blueprint <b>装不出一个能跑的 Agent</b>，或者<b>配了却不可能生效</b>
 *       （缺 clientCode/prompt、inline skill 解析不了、声明了完全不存在的 Tool/Rule 名、
 *       Rule 参数键拼错、JSON 里的 clientCode/cluster 与索引项不一致）
 *   <li>warnings = 能装出 Agent，但和上游契约或本工程能力面有落差（比如 allow 里的
 *       {@code read_file} 被本工程的 {@code disableFilesystemTools()} 关掉了）
 * </ul>
 */
public final class AgentBlueprintValidator {

    /** 对齐上游自检第1项的 blueprintId 形状约束。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_-]+$");

    /** 本次模拟真实消费的字段清单，用于向上游明确"支持子集"。 */
    private static final List<String> CONSUMED_FIELDS =
            List.of(
                    "blueprintId",
                    "version",
                    "clientCode",
                    "cluster",
                    "runtimeAgentId",
                    "meta.scenarios",
                    "prompt.agentsMd",
                    "prompt.soulMd",
                    "skills[].name",
                    "skills[].source",
                    "skills[].ref",
                    "skills[].skillMd",
                    "rules[].ruleCode",
                    "rules[].enabled",
                    "rules[].params",
                    "runtimeMode",
                    "stages[].stageKey",
                    "stages[].stageType",
                    "stages[].displayName",
                    "stages[].promptRef",
                    "stages[].prompt",
                    "stages[].skills",
                    "stages[].rules",
                    "stages[].toolsAllow",
                    "stages[].enabledWhen",
                    "stages[].outputContract",
                    "stages[].next",
                    "stages[].onResult",
                    "tools.allow",
                    "tools.deny",
                    "runtime.isolationScope");

    /** 声明存在但本工程当前不消费的字段，同样要明确说出来。 */
    private static final List<String> IGNORED_FIELDS =
            List.of(
                    "prompt.knowledgeMd（本工程走百炼知识库检索，不走文件）",
                    "tools.mcpServers（本工程是 Java 直连 runtime API，未走 MCP）",
                    "runtime.model（首轮不允许 Blueprint 覆盖模型，避免验证失焦）",
                    "runtime.maxContextTokens（未消费）",
                    "runtime.compaction（未消费）",
                    "guidance（Java 侧无表达位）");

    public BlueprintValidationReport validate(
            AgentBlueprint blueprint, String expectedClientCode, String expectedCluster) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (blueprint == null) {
            errors.add("blueprint is null");
            return new BlueprintValidationReport(errors, warnings, CONSUMED_FIELDS, IGNORED_FIELDS);
        }

        validateIdentity(blueprint, expectedClientCode, expectedCluster, errors, warnings);
        validatePrompt(blueprint, errors);
        validateSkills(blueprint, errors, warnings);
        validateRules(blueprint, errors, warnings);
        validateTools(blueprint, errors, warnings);
        validateRuntimeMode(blueprint, errors, warnings);
        validateRuntime(blueprint, errors);

        return new BlueprintValidationReport(errors, warnings, CONSUMED_FIELDS, IGNORED_FIELDS);
    }

    private void validateIdentity(
            AgentBlueprint blueprint,
            String expectedClientCode,
            String expectedCluster,
            List<String> errors,
            List<String> warnings) {
        if (isBlank(blueprint.blueprintId())) {
            errors.add("blueprintId is required");
        } else if (!IDENTIFIER.matcher(blueprint.blueprintId().trim()).matches()) {
            errors.add(
                    "blueprintId must match ^[a-zA-Z0-9_-]+$ but was: " + blueprint.blueprintId());
        }
        if (blueprint.version() <= 0) {
            errors.add("version must be a positive integer but was: " + blueprint.version());
        }
        if (isBlank(blueprint.clientCode())) {
            errors.add("clientCode is required");
        } else if (!isBlank(expectedClientCode)
                && !blueprint.clientCode().trim().equals(expectedClientCode.trim())) {
            // 索引里的 clientCode 与 JSON 里的不一致，说明蓝图放错了目录——按错误处理，
            // 否则会出现"请求 A 租户却装配了 B 租户 Agent"这类最难排查的串租户问题
            errors.add(
                    "clientCode mismatch: index says '"
                            + expectedClientCode
                            + "' but blueprint says '"
                            + blueprint.clientCode()
                            + "'");
        }
        // cluster 的一致性同理：索引项决定路由，JSON 里的 cluster 是蓝图自描述。两处不一致意味着
        // 蓝图被放进了别的 cluster 的槽位，会出现"请求 test 集群却装配了 prod 集群 Agent"
        if (!safe(expectedCluster).equals(blueprint.clusterOrEmpty())) {
            errors.add(
                    "cluster mismatch: index says '"
                            + safe(expectedCluster)
                            + "' but blueprint says '"
                            + blueprint.clusterOrEmpty()
                            + "'");
        }
        if (isBlank(blueprint.runtimeAgentId())) {
            errors.add("runtimeAgentId is required (used as agent name and state bucket key)");
        } else if (!IDENTIFIER.matcher(blueprint.runtimeAgentId().trim()).matches()) {
            errors.add(
                    "runtimeAgentId must match ^[a-zA-Z0-9_-]+$ but was: "
                            + blueprint.runtimeAgentId());
        }
        if (blueprint.metaOrEmpty().scenarios().isEmpty()) {
            warnings.add("meta.scenarios is empty; scene matching cannot be verified this round");
        }
    }

    private void validatePrompt(AgentBlueprint blueprint, List<String> errors) {
        if (isBlank(blueprint.promptOrEmpty().agentsMd())) {
            errors.add(
                    "prompt.agentsMd is required: it replaces the hardcoded system prompt, "
                            + "without it the tenant assembly has no effect");
        }
    }

    private void validateSkills(
            AgentBlueprint blueprint, List<String> errors, List<String> warnings) {
        List<String> seen = new ArrayList<>();
        for (AgentBlueprint.Skill skill : blueprint.skills()) {
            if (skill == null || isBlank(skill.name())) {
                errors.add("skills[] contains an entry without a name");
                continue;
            }
            String name = skill.name().trim();
            if (seen.contains(name)) {
                errors.add("duplicate skill name: " + name);
                continue;
            }
            seen.add(name);
            if (skill.isInline()) {
                validateInlineSkill(skill, name, errors);
            } else if (skill.isLibrary()) {
                if (isBlank(skill.ref())) {
                    warnings.add(
                            "library skill '" + name + "' has no ref; will fall back to name lookup");
                }
            } else {
                errors.add(
                        "skill '" + name + "' has unsupported source: " + skill.source()
                                + " (expected inline or library)");
            }
        }
    }

    private void validateInlineSkill(
            AgentBlueprint.Skill skill, String name, List<String> errors) {
        if (isBlank(skill.skillMd())) {
            errors.add("inline skill '" + name + "' has empty skillMd");
            return;
        }
        try {
            // 直接用框架自己的解析器校验 frontmatter，而不是自己写正则：这样"能通过校验"
            // 就等价于"框架真的能加载"，不会出现自研校验放过、框架运行时才炸的情况
            SkillUtil.createFrom(skill.skillMd(), null, "blueprint-inline");
        } catch (RuntimeException exception) {
            errors.add(
                    "inline skill '"
                            + name
                            + "' is not loadable (SKILL.md needs YAML frontmatter with name +"
                            + " description): "
                            + exception.getMessage());
        }
    }

    /**
     * 校验 {@code rules[]}。
     *
     * <p>三条判断的严格程度不同，理由都是"上游能不能看出自己配错了"：
     *
     * <ul>
     *   <li>未实现的 {@code ruleCode} = error。Rule 是确定性判断，本工程只认已实现的那 7 条，
     *       声明一个不存在的规则名不可能生效。
     *   <li>参数键不认识 = error。这是最隐蔽的一类：把 {@code continuationKeywords} 写成
     *       {@code keywords}，静默忽略的话上游会以为租户词表覆盖生效了，而实际用的还是默认词表。
     *   <li>已实现但未接线 = warning。蓝图能装出 Agent，只是这条规则本轮不会被调用。
     * </ul>
     */
    private void validateRules(
            AgentBlueprint blueprint, List<String> errors, List<String> warnings) {
        List<String> seen = new ArrayList<>();
        for (AgentBlueprint.RuleSpec rule : blueprint.rules()) {
            if (rule == null || isBlank(rule.ruleCode())) {
                errors.add("rules[] contains an entry without a ruleCode");
                continue;
            }
            String code = rule.ruleCode().trim();
            if (seen.contains(code)) {
                errors.add("duplicate ruleCode: " + code);
                continue;
            }
            seen.add(code);

            String classification = RuleCapabilityCatalog.classify(code);
            if ("unsupported".equals(classification)) {
                errors.add(
                        "rules[] declares '"
                                + code
                                + "' which this project does not implement; implemented rules are "
                                + RuleCapabilityCatalog.allImplemented());
                continue;
            }
            for (String paramKey : rule.params().keySet()) {
                if (!RuleCapabilityCatalog.acceptsParam(code, paramKey)) {
                    errors.add(
                            "rule '"
                                    + code
                                    + "' does not accept param '"
                                    + paramKey
                                    + "'; accepted keys are "
                                    + RuleCapabilityCatalog.PARAM_KEYS.getOrDefault(
                                            code, java.util.Set.of()));
                }
            }
            if (!rule.enabledOrDefault()) {
                continue;
            }
            if ("implemented_not_wired".equals(classification)) {
                warnings.add(
                        "rules[] enables '"
                                + code
                                + "' which is implemented and unit-tested but has no call site in"
                                + " the orchestration chain yet; it will be recorded, not enforced");
            }
        }
    }

    private void validateTools(
            AgentBlueprint blueprint, List<String> errors, List<String> warnings) {
        AgentBlueprint.Tools tools = blueprint.toolsOrEmpty();
        for (String toolName : tools.allow()) {
            if (isBlank(toolName)) {
                continue;
            }
            String name = toolName.trim();
            switch (ToolCapabilityCatalog.classify(name)) {
                case "unsupported" ->
                        errors.add(
                                "tools.allow declares '"
                                        + name
                                        + "' which this project does not implement at all");
                case "implemented_not_wired" ->
                        warnings.add(
                                "tools.allow declares '"
                                        + name
                                        + "' which is implemented but not wired into the"
                                        + " orchestration chain yet");
                case "disabled_by_factory" ->
                        warnings.add(
                                "tools.allow declares '"
                                        + name
                                        + "' which SalesAgentFactory currently disables"
                                        + " (disableFilesystemTools/disableShellTool)");
                default -> {
                    // supported, nothing to report
                }
            }
        }
        for (String toolName : tools.deny()) {
            if (isBlank(toolName)) {
                continue;
            }
            if (tools.allow().stream().anyMatch(allowed -> toolName.trim().equals(allowed))) {
                errors.add("tool '" + toolName.trim() + "' appears in both allow and deny");
            }
        }
        if (!tools.mcpServers().isEmpty()) {
            warnings.add(
                    "tools.mcpServers declared ("
                            + tools.mcpServers().size()
                            + ") but not consumed: this project calls the runtime API directly");
        }
    }

    private void validateRuntimeMode(
            AgentBlueprint blueprint, List<String> errors, List<String> warnings) {
        String runtimeMode = safe(blueprint.runtimeModeOrDefault());
        if (!AgentBlueprint.RUNTIME_MODE_SINGLE_AGENT.equals(runtimeMode)
                && !AgentBlueprint.RUNTIME_MODE_MULTI_STAGE.equals(runtimeMode)) {
            errors.add(
                    "runtimeMode must be single_agent or multi_stage but was: " + runtimeMode);
            return;
        }
        if (!blueprint.isMultiStage()) {
            return;
        }
        if (blueprint.stages().isEmpty()) {
            errors.add("multi_stage blueprint must declare stages[]");
            return;
        }
        List<String> seen = new ArrayList<>();
        for (AgentBlueprint.StageSpec stage : blueprint.stages()) {
            if (stage == null) {
                errors.add("stages[] contains null entry");
                continue;
            }
            String stageKey = safe(stage.stageKey());
            if (stageKey.isEmpty()) {
                errors.add("stages[] contains an entry without stageKey");
                continue;
            }
            if (seen.contains(stageKey)) {
                errors.add("duplicate stageKey: " + stageKey);
                continue;
            }
            seen.add(stageKey);
            if (safe(stage.displayName()).isEmpty()) {
                errors.add("stage '" + stageKey + "' is missing displayName");
            }
            String stageType = safe(stage.stageType());
            if (!List.of("llm", "tool", "router", "writeback").contains(stageType)) {
                errors.add(
                        "stage '"
                                + stageKey
                                + "' has unsupported stageType: "
                                + stageType
                                + " (expected llm/tool/router/writeback)");
                continue;
            }
            if ("llm".equals(stageType)
                    && safe(stage.promptRef()).isEmpty()
                    && safe(stage.prompt()).isEmpty()) {
                errors.add("llm stage '" + stageKey + "' requires promptRef or prompt");
            }
            if ("router".equals(stageType) && stage.onResult().isEmpty() && safe(stage.next()).isEmpty()) {
                errors.add("router stage '" + stageKey + "' requires onResult or next");
            }
            for (String ruleCode : stage.rules()) {
                if (safe(ruleCode).isEmpty()) {
                    continue;
                }
                String classification = RuleCapabilityCatalog.classify(ruleCode);
                if ("unsupported".equals(classification)) {
                    errors.add(
                            "stage '"
                                    + stageKey
                                    + "' declares unsupported rule '"
                                    + ruleCode
                                    + "'");
                } else if ("implemented_not_wired".equals(classification)) {
                    warnings.add(
                            "stage '"
                                    + stageKey
                                    + "' references rule '"
                                    + ruleCode
                                    + "' which exists but is not wired in the current orchestration chain yet");
                }
            }
            for (String toolName : stage.toolsAllow()) {
                if (safe(toolName).isEmpty()) {
                    continue;
                }
                String classification = ToolCapabilityCatalog.classify(toolName);
                if ("unsupported".equals(classification)) {
                    errors.add(
                            "stage '"
                                    + stageKey
                                    + "' declares unsupported tool '"
                                    + toolName
                                    + "'");
                } else if ("disabled_by_factory".equals(classification)) {
                    warnings.add(
                            "stage '"
                                    + stageKey
                                    + "' allows tool '"
                                    + toolName
                                    + "' which is disabled by SalesAgentFactory");
                } else if ("implemented_not_wired".equals(classification)) {
                    warnings.add(
                            "stage '"
                                    + stageKey
                                    + "' allows tool '"
                                    + toolName
                                    + "' which exists but is not wired in the current orchestration chain yet");
                }
            }
        }
    }

    private void validateRuntime(AgentBlueprint blueprint, List<String> errors) {
        String isolationScope = blueprint.runtimeOrEmpty().isolationScope();
        if (isBlank(isolationScope)) {
            return;
        }
        if (!AgentBlueprint.IMMUTABLE_ISOLATION_SCOPE.equals(isolationScope.trim())) {
            errors.add(
                    "runtime.isolationScope is immutable "
                            + AgentBlueprint.IMMUTABLE_ISOLATION_SCOPE
                            + " but was: "
                            + isolationScope);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
