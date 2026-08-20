package com.agentteams.salesagent.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blueprint 读取来源抽象。
 *
 * <p>这一层只回答"Blueprint 从哪来"，不负责后续校验、Prompt/Skill/Rule 投影，也不关心 workspace
 * 写盘。这样本地 classpath 模式和后续远端发布态模式就能共用同一套消费主链。
 */
public interface BlueprintSource {

    boolean hasAnyBlueprint();

    List<Map<String, String>> listScopes();

    Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version);

    record BlueprintHandle(
            AgentBlueprint blueprint,
            String sourceId,
            String sourceType,
            String matchLevel,
            String matchedCluster) {}
}
