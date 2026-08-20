package com.agentteams.salesagent.integration.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.config.AppConfig;
import com.agentteams.salesagent.tool.telemetry.ToolTelemetry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对接 {@code marketing-agent-service} 的 agent runtime 接口客户端。
 *
 * <p>类名带上后端系统名（不叫笼统的 {@code RuntimeApiClient}）是刻意的——按
 * {@code skills/asset-oriented-development/SKILL.md} 检查项2的要求，这一层的命名要能看出
 * "对接的是哪个具体后端"，将来接入用别的后端的客户时，能一眼看出这个类不能直接复用。
 *
 * <p>这一层<b>只负责 HTTP 调用细节</b>：URL 拼接、超时、响应信封解析、错误码透传。
 * 不做 DTO 到业务快照对象的转换（那是 {@code mapping/} 包的职责），也不做任何业务判断。
 *
 * <p>已核实的接口事实（2026-08-14，基于真实调用 binding {@code 028186e7-...} 探测）：
 * <ul>
 *   <li>路由前缀是 {@code /api/agent/runtime}（三段带斜杠）
 *   <li>{@code clientCode + cluster} 是当前接入层的标准作用域入参，用于映射后端统一 scope
 *   <li><b>POST 接口只从 request body 读参数</b>，query string 里的作用域参数会被忽略
 *   <li>后端对这些接口<b>没有任何认证</b>，{@code AGENT_RUNTIME_API_TOKEN} 因此是可选项
 * </ul>
 */
public final class MarketingAgentRuntimeApiClient {

    private static final Logger log = LoggerFactory.getLogger(MarketingAgentRuntimeApiClient.class);
    private static final String ROUTE_PREFIX = "/api/agent/runtime";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiToken;
    private final Duration requestTimeout;

    public MarketingAgentRuntimeApiClient(AppConfig config) {
        this(config.runtimeApiBaseUrl(), config.runtimeApiToken(), Duration.ofSeconds(10));
    }

    public MarketingAgentRuntimeApiClient(String baseUrl, String apiToken, Duration requestTimeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.requestTimeout = requestTimeout;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** {@code GET /customer-profile}。新客户无画像时后端返回 404，属于正常业务状态。 */
    public RuntimeApiResponse getCustomerProfile(
            String clientCode, String cluster, String sceneCode, String chatUser) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("clientCode", clientCode);
        query.put("cluster", cluster);
        query.put("sceneCode", sceneCode);
        query.put("chatUser", chatUser);
        return get("/customer-profile", query);
    }

    /** {@code GET /history-summary}。 */
    public RuntimeApiResponse getHistorySummary(
            String clientCode, String cluster, String sceneCode, String conversationId, String chatUser) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("clientCode", clientCode);
        query.put("cluster", cluster);
        query.put("sceneCode", sceneCode);
        query.put("conversationId", conversationId);
        query.put("chatUser", chatUser);
        return get("/history-summary", query);
    }

    /** {@code GET /intent-queue}。 */
    public RuntimeApiResponse getIntentQueue(
            String clientCode, String cluster, String sceneCode, String conversationId, String chatUser) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("clientCode", clientCode);
        query.put("cluster", cluster);
        query.put("sceneCode", sceneCode);
        query.put("conversationId", conversationId);
        query.put("chatUser", chatUser);
        return get("/intent-queue", query);
    }

    /**
     * {@code GET /rule-context}，{@code responseMode=llm} 拿 LLM 友好结构。
     *
     * <p>注意这个接口的失败信封缺 {@code errorCode}/{@code retryable}/{@code traceId}，
     * 见 {@link RuntimeApiResponse} 类注释。
     */
    public RuntimeApiResponse getRuleContext(
            String clientCode, String cluster, String sceneCode, String chatUser) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("clientCode", clientCode);
        query.put("cluster", cluster);
        query.put("sceneCode", sceneCode);
        if (chatUser != null && !chatUser.isBlank()) {
            query.put("chatUser", chatUser);
        }
        query.put("responseMode", "llm");
        return get("/rule-context", query);
    }

    /**
     * {@code POST /sessions}，建立会话锚点。
     *
     * <p><b>调用顺序依赖（已实测）</b>：这个接口不只建会话，还会顺带创建客户记录——调用之前
     * {@code GET /customer-profile} 对新客户返回 404，调用之后就返回 200 带完整画像字段结构。
     * 所以新客户的第一轮对话必须先调这个接口，否则后续只读 Tool 会撞 404。
     */
    public RuntimeApiResponse createOrResumeSession(
            String clientCode,
            String cluster,
            String sceneCode,
            String source,
            String conversationId,
            String chatUser,
            String userId,
            String nickname) {
        Map<String, Object> messageContext = new LinkedHashMap<>();
        messageContext.put("conversationId", conversationId);
        messageContext.put("chatUser", chatUser);
        if (userId != null && !userId.isBlank()) {
            messageContext.put("userId", userId);
        }
        if (nickname != null && !nickname.isBlank()) {
            messageContext.put("nickname", nickname);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCode", clientCode);
        body.put("cluster", cluster);
        body.put("sceneCode", sceneCode);
        body.put("source", source);
        body.put("messageContext", messageContext);
        return post("/sessions", body);
    }

    /** {@code POST /history-summary}。 */
    public RuntimeApiResponse saveHistorySummary(Map<String, Object> body) {
        return post("/history-summary", body);
    }

    /** {@code POST /intent-queue/sync}。 */
    public RuntimeApiResponse syncIntentQueue(Map<String, Object> body) {
        return post("/intent-queue/sync", body);
    }

    private RuntimeApiResponse get(String path, Map<String, String> query) {
        String url = baseUrl + ROUTE_PREFIX + path + toQueryString(query);
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(requestTimeout)
                            .header("Accept", "application/json")
                            .GET();
            return ToolTelemetry.traceApi(
                    "marketing_agent_runtime",
                    "GET",
                    path,
                    Map.of(
                            "upstream", "marketing-agent-service",
                            "url", url,
                            "query", java.util.Collections.unmodifiableMap(new LinkedHashMap<>(query))),
                    () -> send(builder, path),
                    response -> apiOutputDetail(response));
        } catch (IllegalArgumentException e) {
            log.warn("runtime api url invalid, path={}, url={}", path, url, e);
            return failure("invalid_url", e.getMessage());
        }
    }

    private RuntimeApiResponse post(String path, Map<String, Object> body) {
        String url = baseUrl + ROUTE_PREFIX + path;
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(requestTimeout)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            return ToolTelemetry.traceApi(
                    "marketing_agent_runtime",
                    "POST",
                    path,
                    Map.of(
                            "upstream", "marketing-agent-service",
                            "url", url,
                            "body", java.util.Collections.unmodifiableMap(new LinkedHashMap<>(body))),
                    () -> send(builder, path),
                    response -> apiOutputDetail(response));
        } catch (Exception e) {
            log.warn("runtime api post failed to build request, path={}", path, e);
            return failure("request_build_failed", e.getMessage());
        }
    }

    private RuntimeApiResponse send(HttpRequest.Builder builder, String path) {
        if (!apiToken.isBlank()) {
            builder = builder.header("Authorization", "Bearer " + apiToken);
        }
        try {
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return parse(response.body(), response.statusCode(), path);
        } catch (java.io.InterruptedIOException e) {
            log.warn("runtime api call timed out, path={}", path);
            return failure("timeout", "request timed out: " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("runtime api call interrupted, path={}", path);
            return failure("interrupted", "request interrupted: " + path);
        } catch (Exception e) {
            log.warn("runtime api call failed, path={}", path, e);
            return failure("io_error", e.getMessage());
        }
    }

    private RuntimeApiResponse parse(String rawBody, int httpStatus, String path) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            boolean success = root.path("success").asBoolean(false);
            if (success) {
                return new RuntimeApiResponse(true, root.path("data"), "", "", false, httpStatus);
            }
            return new RuntimeApiResponse(
                    false,
                    null,
                    root.path("error").asText(""),
                    root.path("errorCode").asText(""),
                    root.path("retryable").asBoolean(false),
                    httpStatus);
        } catch (Exception e) {
            log.warn(
                    "runtime api response not parseable as json, path={}, httpStatus={}",
                    path,
                    httpStatus,
                    e);
            return new RuntimeApiResponse(
                    false, null, "response not parseable: " + e.getMessage(), "bad_response", false, httpStatus);
        }
    }

    private static RuntimeApiResponse failure(String errorCode, String error) {
        return new RuntimeApiResponse(false, null, error, errorCode, false, 0);
    }

    private Map<String, Object> apiOutputDetail(RuntimeApiResponse response) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", response.success());
        output.put("httpStatus", response.httpStatus());
        output.put("errorCode", response.errorCode());
        output.put("error", response.error());
        output.put("retryable", response.retryable());
        if (response.data() != null && !response.data().isMissingNode() && !response.data().isNull()) {
            output.put("data", objectMapper.convertValue(response.data(), Object.class));
        }
        return java.util.Collections.unmodifiableMap(output);
    }

    private static String toQueryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
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
        return builder.length() == 1 ? "" : builder.toString();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
