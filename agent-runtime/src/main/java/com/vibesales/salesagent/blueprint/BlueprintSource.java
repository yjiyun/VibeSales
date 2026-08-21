package com.vibesales.salesagent.blueprint;

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

    /**
     * 带调用方 {@code userId} 的解析入口。
     *
     * <p>默认实现忽略 {@code userId}、委托给不带用户维度的 {@link #resolve(String, String, String,
     * String, String)}——大多数来源（classpath 示例、远端发布快照）本来就不区分具体登录用户。只有需要按
     * {@code agent_binding} 精确匹配"谁批准发布、谁在试聊"的来源（见 {@link
     * JdbcPublishedBlueprintSource}）才需要覆盖这个方法。
     */
    default Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            String userId) {
        return resolve(clientCode, cluster, sceneCode, runtimeAgentId, version);
    }

    record BlueprintHandle(
            AgentBlueprint blueprint,
            String sourceId,
            String sourceType,
            String matchLevel,
            String matchedCluster) {}
}
