package com.agentteams.salesagent.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已校验、已投影、可直接用于装配 Agent 的 Blueprint。
 *
 * <p>它是 {@code blueprint} 包对外的唯一交付物：编排层和 debug 接口都只依赖这个对象，不直接碰
 * 原始 JSON 或投影中间态。
 */
public record ResolvedBlueprint(
        AgentBlueprint blueprint,
        String requestedClientCode,
        String requestedCluster,
        String requestedSceneCode,
        String requestedRuntimeAgentId,
        String requestedVersion,
        String selectionId,
        String matchLevel,
        String sourceType,
        String sourcePath,
        String agentsMd,
        String soulMd,
        String systemPrompt,
        BlueprintSkillProjector.Projection skills,
        BlueprintRuleProjector.Projection rules,
        BlueprintValidationReport validation) {

    /** Agent 名称与状态分桶键，直接取 {@code runtimeAgentId}。 */
    public String runtimeAgentId() {
        return blueprint.runtimeAgentId();
    }

    public String blueprintId() {
        return blueprint.blueprintId();
    }

    public int version() {
        return blueprint.version();
    }

    public String clientCode() {
        return blueprint.clientCode();
    }

    /** 蓝图自己声明的 cluster；与索引项一致（不一致会在校验阶段报 error）。 */
    public String cluster() {
        return blueprint.clusterOrEmpty();
    }

    /**
     * 本轮生效的蓝图身份串，形如 {@code yjiyuncom/test/yjiyun-service-agent@v3}。
     *
     * <p>用<b>命中的蓝图</b>而不是请求参数拼串：两个不同 {@code cluster} 的请求若都降级到同一份默认
     * 蓝图，它们本轮生效的其实是同一份配置，身份串就该相同。含 {@code version}，便于一眼看出改版
     * 是否真的生效。
     *
     * <p>历史上这个方法叫 {@code cacheKey()}，用来给"每租户一个 Agent"的注册表分桶。现在只有一个共享
     * Agent（租户差异靠每请求投影 workspace 文件），它不再是缓存键，只是一个可读的身份标识。
     */
    public String blueprintIdentity() {
        return clientCode()
                + "/"
                + (cluster().isEmpty() ? "-" : cluster())
                + "/"
                + blueprint.runtimeAgentId()
                + "@v"
                + blueprint.version();
    }

    /**
     * 时间线与 debug 接口共用的摘要输出。
     *
     * <p><b>键顺序有意义，不要随手调整</b>。工作台时间线的"补充明细"只渲染前若干个键
     * （实测 marketing-agent-service 的 shell 页取 {@code slice(0, 8)}），所以最能回答
     * "这轮到底装配了谁、命中方式是什么"的键必须排在最前面。同理这里返回
     * {@link LinkedHashMap} 而不是 {@code Map.copyOf}——后者是无序的 immutable map，
     * 会把这个顺序彻底打乱。
     */
    public Map<String, Object> toTimelineDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        // 前 8 个：工作台"补充明细"里一定看得见的位置，留给路由结论与本轮生效配置
        detail.put("matchLevel", matchLevel);
        detail.put("blueprintId", blueprintId());
        detail.put("clientCode", clientCode());
        detail.put("cluster", cluster());
        detail.put("requestedCluster", requestedCluster);
        detail.put("requestedSceneCode", requestedSceneCode);
        detail.put("requestedRuntimeAgentId", requestedRuntimeAgentId);
        detail.put("requestedVersion", requestedVersion);
        detail.put("selectionId", selectionId);
        detail.put("runtimeAgentId", runtimeAgentId());
        detail.put("runtimeMode", blueprint.runtimeModeOrDefault());
        detail.put("stageCount", blueprint.stages().size());
        detail.put("sourceType", sourceType);
        detail.put("rules", rules.toTimelineDetail());
        detail.put("recoveryKeywordSource", rules.recoveryKeywordSource());
        detail.put("intentKeywordSource", rules.intentKeywordSource());
        detail.put("profileThresholdSource", rules.profileThresholdSource());
        // 以下是补充信息，看不全也不影响判断"配的东西有没有生效"
        detail.put("version", version());
        detail.put("requestedClientCode", requestedClientCode);
        detail.put("sourcePath", sourcePath);
        detail.put("skills", skills.skillNames());
        detail.put("skillSources", skills.sourceByName());
        detail.put("scenarios", blueprint.metaOrEmpty().scenarios());
        detail.put("systemPromptLength", systemPrompt.length());
        detail.put(
                "toolsAllow", ToolCapabilityCatalog.classifyAll(blueprint.toolsOrEmpty().allow()));
        detail.put("toolsDeny", blueprint.toolsOrEmpty().deny());
        detail.put("validationWarnings", validation.warnings());
        return java.util.Collections.unmodifiableMap(detail);
    }
}
