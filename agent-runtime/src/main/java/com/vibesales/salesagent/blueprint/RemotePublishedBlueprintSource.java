package com.agentteams.salesagent.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.config.AppConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通过 HTTP 读取上游已发布 Blueprint。
 *
 * <p>这一层只负责把"远端发布态快照"取回来并做最小缓存，不负责后续校验与投影。缓存语义刻意保持轻量：
 * 只做 TTL，避免把下游变成第二个发布状态机。
 */
public final class RemotePublishedBlueprintSource implements BlueprintSource {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String SOURCE_TYPE = "remote";

    private final AppConfig config;
    private final HttpClient httpClient;
    private final Clock clock;
    private final AgentBlueprintLoader loader;
    private final ConcurrentMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public RemotePublishedBlueprintSource(AppConfig config) {
        this(
                config,
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofMillis(
                                        Math.max(1, config.blueprintRemoteConnectTimeoutMs())))
                        .build(),
                Clock.systemUTC(),
                new AgentBlueprintLoader());
    }

    RemotePublishedBlueprintSource(
            AppConfig config, HttpClient httpClient, Clock clock, AgentBlueprintLoader loader) {
        this.config = config;
        this.httpClient = httpClient;
        this.clock = clock;
        this.loader = loader;
    }

    @Override
    public boolean hasAnyBlueprint() {
        return remoteEnabled() && !safe(config.blueprintRemoteBaseUrl()).isEmpty();
    }

    @Override
    public List<Map<String, String>> listScopes() {
        if (!remoteEnabled()) {
            return List.of();
        }
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("source", "remote_published_http");
        scope.put("baseUrl", safe(config.blueprintRemoteBaseUrl()));
        scope.put("path", normalizedPath());
        scope.put("scopeMode", "clientCode+cluster+runtimeAgentId(+sceneCode,+version)");
        return List.of(Map.copyOf(scope));
    }

    @Override
    public Optional<BlueprintHandle> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        if (!remoteEnabled()) {
            return Optional.empty();
        }
        ensureConfigured();
        CacheKey key =
                new CacheKey(
                        safe(clientCode),
                        safe(cluster),
                        safe(sceneCode),
                        safe(runtimeAgentId),
                        safe(version));
        if (key.clientCode().isEmpty()) {
            return Optional.empty();
        }
        Optional<BlueprintHandle> cached = cached(key);
        if (cached != null) {
            return cached;
        }
        Optional<BlueprintHandle> fetched = fetchRemote(key);
        cache.put(key, new CacheEntry(fetched, clock.millis() + ttlMs()));
        return fetched;
    }

    private Optional<BlueprintHandle> fetchRemote(CacheKey key) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(buildUrl(key)))
                        .timeout(Duration.ofMillis(Math.max(1, config.blueprintRemoteReadTimeoutMs())))
                        .header("Accept", "application/json")
                        .GET();
        if (!safe(config.blueprintRemoteApiToken()).isEmpty()) {
            builder.header("Authorization", "Bearer " + config.blueprintRemoteApiToken().trim());
        }
        try {
            HttpResponse<String> response =
                    httpClient.send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "remote published blueprint request failed: status="
                                + response.statusCode()
                                + ", body="
                                + trimBody(response.body()));
            }
            return parseResponse(response.body(), key);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("remote published blueprint request interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "remote published blueprint request failed for " + key.describe(), exception);
        }
    }

    private Optional<BlueprintHandle> parseResponse(String rawBody, CacheKey key) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                String errorCode = safe(root.path("errorCode").asText(""));
                if ("blueprint_not_found".equalsIgnoreCase(errorCode)
                        || "not_found".equalsIgnoreCase(errorCode)) {
                    return Optional.empty();
                }
                throw new IllegalStateException(
                        "remote published blueprint responded with failure: "
                                + safe(root.path("error").asText("unknown_error")));
            }
            JsonNode payload = root.has("data") ? root.path("data") : root;
            AgentBlueprint blueprint = extractBlueprint(payload);
            if (blueprint == null) {
                return Optional.empty();
            }
            if (!key.runtimeAgentId().isEmpty()
                    && !key.runtimeAgentId().equals(safe(blueprint.runtimeAgentId()))) {
                return Optional.empty();
            }
            if (!key.version().isEmpty()
                    && !key.version().equals(String.valueOf(blueprint.version()))) {
                return Optional.empty();
            }
            String matchedCluster =
                    firstNonBlank(
                            text(payload, "matchedCluster"),
                            text(payload, "cluster"),
                            blueprint.clusterOrEmpty());
            String matchLevel =
                    firstNonBlank(
                            text(payload, "matchLevel"),
                            inferMatchLevel(key.cluster(), matchedCluster));
            String sourceId =
                    firstNonBlank(
                            text(payload, "sourceId"),
                            text(payload, "sourcePath"),
                            "remote:"
                                    + key.clientCode()
                                    + "/"
                                    + (matchedCluster.isEmpty() ? "-" : matchedCluster)
                                    + (key.sceneCode().isEmpty()
                                            ? ""
                                            : "?sceneCode=" + key.sceneCode()));
            return Optional.of(
                    new BlueprintHandle(blueprint, sourceId, SOURCE_TYPE, matchLevel, matchedCluster));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "remote published blueprint response parse failed for " + key.describe(),
                    exception);
        }
    }

    private AgentBlueprint extractBlueprint(JsonNode payload) throws Exception {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode blueprintNode = payload;
        if (payload.has("blueprint")) {
            blueprintNode = payload.path("blueprint");
        } else if (payload.has("payload")) {
            blueprintNode = payload.path("payload");
        }
        if (blueprintNode.isMissingNode() || blueprintNode.isNull()) {
            return null;
        }
        if (blueprintNode.isTextual()) {
            return loader.parse(blueprintNode.asText(), "remote published blueprint response");
        }
        return OBJECT_MAPPER.treeToValue(blueprintNode, AgentBlueprint.class);
    }

    private Optional<BlueprintHandle> cached(CacheKey key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtEpochMs() < clock.millis()) {
            cache.remove(key, entry);
            return null;
        }
        return entry.handle();
    }

    private String buildUrl(CacheKey key) {
        StringBuilder url = new StringBuilder(stripTrailingSlash(config.blueprintRemoteBaseUrl()));
        url.append(normalizedPath());
        String query = toQueryString(key);
        if (!query.isEmpty()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    private String normalizedPath() {
        String path = safe(config.blueprintRemotePath());
        if (path.isEmpty()) {
            return "/api/v1/blueprints/published";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private boolean remoteEnabled() {
        return config.blueprintRemoteEnabled() || "remote".equalsIgnoreCase(config.blueprintSource());
    }

    private void ensureConfigured() {
        if (safe(config.blueprintRemoteBaseUrl()).isEmpty()) {
            throw new IllegalStateException(
                    "AGENT_BLUEPRINT_REMOTE_BASE_URL is required when remote blueprint source is enabled");
        }
    }

    private long ttlMs() {
        return Math.max(0, config.blueprintRemoteCacheTtlMs());
    }

    private static String trimBody(String body) {
        String safeBody = safe(body);
        return safeBody.length() <= 400 ? safeBody : safeBody.substring(0, 400);
    }

    private static String inferMatchLevel(String requestedCluster, String matchedCluster) {
        if (safe(requestedCluster).isEmpty()) {
            return AgentBlueprintRepository.MATCH_DEFAULT;
        }
        return safe(requestedCluster).equals(safe(matchedCluster))
                ? AgentBlueprintRepository.MATCH_EXACT
                : AgentBlueprintRepository.MATCH_FALLBACK;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : safe(node.path(field).asText(""));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!safe(value).isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String toQueryString(CacheKey key) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("clientCode", key.clientCode());
        query.put("cluster", key.cluster());
        query.put("sceneCode", key.sceneCode());
        query.put("runtimeAgentId", key.runtimeAgentId());
        query.put("version", key.version());
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (safe(entry.getValue()).isEmpty()) {
                continue;
            }
            if (!first) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return builder.toString();
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = safe(value);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record CacheKey(
            String clientCode, String cluster, String sceneCode, String runtimeAgentId, String version) {
        private String describe() {
            return "clientCode="
                    + clientCode
                    + ", cluster="
                    + cluster
                    + ", sceneCode="
                    + sceneCode
                    + ", runtimeAgentId="
                    + runtimeAgentId
                    + ", version="
                    + version;
        }
    }

    private record CacheEntry(Optional<BlueprintHandle> handle, long expiresAtEpochMs) {}
}
