package com.agentteams.salesagent.blueprint;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 主来源失败或未命中时，按策略回退到兜底来源。 */
public final class CompositeBlueprintSource implements BlueprintSource {

    private final BlueprintSource primary;
    private final BlueprintSource fallback;
    private final boolean failOnPrimaryError;

    public CompositeBlueprintSource(
            BlueprintSource primary, BlueprintSource fallback, boolean failOnPrimaryError) {
        this.primary = primary;
        this.fallback = fallback;
        this.failOnPrimaryError = failOnPrimaryError;
    }

    @Override
    public boolean hasAnyBlueprint() {
        return primary.hasAnyBlueprint() || fallback.hasAnyBlueprint();
    }

    @Override
    public List<Map<String, String>> listScopes() {
        List<Map<String, String>> scopes = primary.listScopes();
        return scopes.isEmpty() ? fallback.listScopes() : scopes;
    }

    @Override
    public Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        try {
            Optional<BlueprintHandle> resolved =
                    primary.resolve(clientCode, cluster, sceneCode, runtimeAgentId, version);
            if (resolved.isPresent()) {
                return resolved;
            }
        } catch (RuntimeException exception) {
            if (failOnPrimaryError) {
                throw exception;
            }
        }
        return fallback.resolve(clientCode, cluster, sceneCode, runtimeAgentId, version);
    }

    BlueprintSource primary() {
        return primary;
    }

    BlueprintSource fallback() {
        return fallback;
    }
}
