package com.vibesales.salesagent.blueprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 当前默认的 Blueprint 来源：classpath 静态资源。 */
public final class ClasspathBlueprintSource implements BlueprintSource {

    public static final String SOURCE_TYPE = "classpath";

    private static final String DEFAULT_INDEX_PATH = "blueprints/index.json";

    private final AgentBlueprintLoader loader;
    private final BlueprintIndex index;

    public ClasspathBlueprintSource() {
        this(DEFAULT_INDEX_PATH);
    }

    public ClasspathBlueprintSource(String indexPath) {
        this.loader = new AgentBlueprintLoader();
        this.index = loader.loadIndex(indexPath);
    }

    @Override
    public boolean hasAnyBlueprint() {
        return !index.entries().isEmpty();
    }

    @Override
    public List<Map<String, String>> listScopes() {
        return index.entries().stream()
                .map(
                        entry -> {
                            AgentBlueprint blueprint = loader.loadFromClasspath(safe(entry.path()));
                            Map<String, String> scope = new LinkedHashMap<>();
                            scope.put("clientCode", safe(entry.clientCode()));
                            scope.put("cluster", safe(entry.cluster()));
                            scope.put("path", safe(entry.path()));
                            scope.put(
                                    "runtimeAgentId",
                                    blueprint == null ? "" : safe(blueprint.runtimeAgentId()));
                            scope.put("blueprintId", blueprint == null ? "" : safe(blueprint.blueprintId()));
                            scope.put(
                                    "version",
                                    blueprint == null ? "" : String.valueOf(blueprint.version()));
                            return Map.copyOf(scope);
                        })
                .toList();
    }

    @Override
    public Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        String normalizedVersion = safe(version);
        if (normalizedClientCode.isEmpty()) {
            return Optional.empty();
        }
        Optional<Match> match =
                findEntry(
                        normalizedClientCode,
                        normalizedCluster,
                        normalizedRuntimeAgentId,
                        normalizedVersion);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        String path = safe(match.get().entry().path());
        AgentBlueprint blueprint = loader.loadFromClasspath(path);
        if (blueprint == null) {
            throw new IllegalStateException(
                    "blueprint index points to a missing resource: "
                            + path
                            + " (clientCode="
                            + normalizedClientCode
                            + ", cluster="
                            + normalizedCluster
                            + ")");
        }
        if (!normalizedRuntimeAgentId.isEmpty()
                && !normalizedRuntimeAgentId.equals(safe(blueprint.runtimeAgentId()))) {
            return Optional.empty();
        }
        if (!normalizedVersion.isEmpty()
                && !normalizedVersion.equals(String.valueOf(blueprint.version()))) {
            return Optional.empty();
        }
        return Optional.of(
                new BlueprintHandle(
                        blueprint,
                        path,
                        SOURCE_TYPE,
                        match.get().matchLevel(),
                        safe(match.get().entry().cluster())));
    }

    private Optional<Match> findEntry(
            String clientCode, String cluster, String runtimeAgentId, String version) {
        if (!cluster.isEmpty()) {
            Optional<Match> exact =
                    selectEntry(
                            index.entries().stream()
                                    .filter(entry -> clientCode.equals(safe(entry.clientCode())))
                                    .filter(entry -> cluster.equals(safe(entry.cluster())))
                                    .toList(),
                            runtimeAgentId,
                            version,
                            AgentBlueprintRepository.MATCH_EXACT);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return selectEntry(
                index.entries().stream()
                        .filter(entry -> clientCode.equals(safe(entry.clientCode())))
                        .filter(entry -> safe(entry.cluster()).isEmpty())
                        .toList(),
                runtimeAgentId,
                version,
                cluster.isEmpty()
                        ? AgentBlueprintRepository.MATCH_DEFAULT
                        : AgentBlueprintRepository.MATCH_FALLBACK);
    }

    private Optional<Match> selectEntry(
            List<BlueprintIndex.Entry> candidates,
            String runtimeAgentId,
            String version,
            String matchLevel) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (runtimeAgentId.isEmpty() && version.isEmpty()) {
            return Optional.of(new Match(candidates.get(0), matchLevel));
        }
        for (BlueprintIndex.Entry entry : candidates) {
            AgentBlueprint blueprint = loader.loadFromClasspath(safe(entry.path()));
            if (blueprint == null) {
                continue;
            }
            if (!runtimeAgentId.isEmpty() && !runtimeAgentId.equals(safe(blueprint.runtimeAgentId()))) {
                continue;
            }
            if (!version.isEmpty() && !version.equals(String.valueOf(blueprint.version()))) {
                continue;
            }
            return Optional.of(new Match(entry, matchLevel));
        }
        return Optional.empty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record Match(BlueprintIndex.Entry entry, String matchLevel) {}
}
