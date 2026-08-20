package com.agentteams.salesagent.blueprint;

import java.util.List;

/**
 * 前端测试工作台消费的 Blueprint 目录项。
 */
public record BlueprintCatalogItem(
        String selectionId,
        String sourceType,
        String sourceLabel,
        String clientCode,
        String cluster,
        String sceneCode,
        String runtimeAgentId,
        String version,
        String blueprintId,
        String runtimeMode,
        int stageCount,
        String displayName,
        List<String> matchHints,
        boolean selectable,
        String sourceId) {

    public BlueprintCatalogItem {
        matchHints = matchHints == null ? List.of() : List.copyOf(matchHints);
    }
}
