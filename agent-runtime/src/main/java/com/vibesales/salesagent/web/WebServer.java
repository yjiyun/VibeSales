package com.vibesales.salesagent.web;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.vibesales.salesagent.agent.ChatResponse;
import com.vibesales.salesagent.agent.CustomerServiceOrchestratorAgent;
import com.vibesales.salesagent.blueprint.AgentBlueprint;
import com.vibesales.salesagent.blueprint.BlueprintCatalogItem;
import com.vibesales.salesagent.blueprint.BlueprintSelection;
import com.vibesales.salesagent.blueprint.JdbcPublishedBlueprintSource;
import com.vibesales.salesagent.config.AppConfig;
import com.vibesales.salesagent.conversation.ConversationCreateResult;
import com.vibesales.salesagent.conversation.ConversationService;
import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.knowledge.BailianKnowledgeHealthService;
import com.vibesales.salesagent.knowledge.KnowledgeHealthResult;
import com.vibesales.salesagent.progress.ExecutionProgressListener;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 最小本地 HTTP 服务。
 *
 * <p>它提供三个能力：
 * 1. 返回验证页 `index.html`
 * 2. 提供健康检查接口
 * 3. 提供最小聊天接口 `/api/chat`
 *
 * <p>当前阶段只为 P1 最小闭环服务，不追求完整 Web 架构。
 */
public final class WebServer {
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CustomerServiceOrchestratorAgent agent;
    private final BailianKnowledgeHealthService knowledgeHealthService =
            new BailianKnowledgeHealthService();
    private final ConversationService conversationService;
    private final AppConfig config;
    private final ChatRunManager chatRunManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final int port;

    public WebServer(int port, AppConfig config) {
        this.port = port;
        this.config = config;
        this.agent = new CustomerServiceOrchestratorAgent(config);
        this.conversationService = new ConversationService(config);
        this.chatRunManager =
                new ChatRunManager(
                        (config.chatRunJdbcConfigured() || config.mysqlConfigured())
                                ? new MysqlChatRunStore(config)
                                : ChatRunStore.noop());
    }

    public HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new IndexHandler());
        server.createContext("/api/conversations", new ConversationCreateHandler());
        server.createContext("/api/chat/runs", new ChatRunCreateHandler());
        server.createContext("/api/chat/runs/history", new ChatRunHistoryHandler());
        server.createContext("/api/chat/runs/", new ChatRunEventsHandler());
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/v1/chat", new LegacyV1ChatHandler());
        server.createContext("/api/v1/dryrun", new DryRunHandler());
        server.createContext("/api/v1/ingest", new IngestHandler());
        server.createContext("/api/health", exchange -> writeJson(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/health/knowledge", new KnowledgeHealthHandler());
        server.createContext("/api/debug/agent-blueprint", new AgentBlueprintDebugHandler());
        server.createContext("/api/debug/blueprint-catalog", new BlueprintCatalogHandler());
        server.createContext("/api/debug/blueprint-retire", new BlueprintRetireHandler());
        server.createContext("/api/debug/runtime-binding-summary", new RuntimeBindingSummaryHandler());
        server.createContext("/api/debug/runtime-agent-templates", new RuntimeAgentTemplateHandler());
        server.setExecutor(executor);
        server.start();
        return server;
    }

    private final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try (InputStream inputStream =
                    WebServer.class.getResourceAsStream("/web/index.html")) {
                if (inputStream == null) {
                    writeText(exchange, 404, "index.html not found", "text/plain; charset=utf-8");
                    return;
                }
                String html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                writeText(exchange, 200, html, "text/html; charset=utf-8");
            }
        }
    }

    private final class ConversationCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                ConversationCreateRequest request =
                        requestBytes.length == 0
                                ? new ConversationCreateRequest()
                                : objectMapper.readValue(requestBytes, ConversationCreateRequest.class);
                ConversationCreateResult result =
                        conversationService.createConversation(request.conversationName, request.metadata);
                ConversationCreateResponse response =
                        new ConversationCreateResponse(
                                "created",
                                result.conversationId(),
                                result.conversationName(),
                                result.createdAt().toString(),
                                result.metadataJson());
                writeJson(exchange, 200, objectMapper.writeValueAsString(response));
            } catch (Exception exception) {
                String body =
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "error", "conversation_create_failed",
                                        "message", exception.getMessage()));
                writeJson(exchange, 500, body);
            }
        }
    }

    private final class LegacyV1ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(
                        exchange,
                        405,
                        objectMapper.writeValueAsString(java.util.Map.of("error", "POST required")));
                return;
            }
            if (!"/api/v1/chat".equals(exchange.getRequestURI().getPath())) {
                writeText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            try {
                requireLegacyV1ChatAuth(exchange);
                ChatCommand command =
                        toLegacyV1ChatCommand(
                                exchange.getRequestURI(),
                                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    AtomicBoolean deltaSent = new AtomicBoolean(false);
                    try {
                        ChatResponse response =
                                agent.handle(
                                        command.customerContext(),
                                        command.message(),
                                        command.selection(),
                                        ExecutionProgressListener.noop(),
                                        event ->
                                                forwardLegacyV1ChatEvent(
                                                        outputStream, event, deltaSent));
                        if (!deltaSent.get()) {
                            writeLegacyV1SseEvent(
                                    outputStream,
                                    "message",
                                    java.util.Map.of("delta", response.reply(), "eventId", "final"));
                        }
                        writeLegacyV1SseEvent(outputStream, "done", java.util.Map.of());
                    } catch (Exception exception) {
                        writeLegacyV1SseEvent(
                                outputStream,
                                "error",
                                java.util.Map.of("message", safeMessage(exception)));
                    }
                }
            } catch (Exception exception) {
                writeLegacyV1ChatError(exchange, exception);
            }
        }
    }

    /**
     * P4 dry-run 冒烟：body 是完整 Blueprint 原文（可能还没落库），query 带
     * {@code clientCode/userId/runtimeAgentId} 三个作用域字段用于构造 {@link CustomerContext}。
     * 鉴权复用 {@code /api/v1/chat} 同一枚 {@code RUNTIME_AUTH_TOKEN}（{@link AppConfig#compatV1ChatAuthToken()}），
     * 因为它们同属"持有 Runtime 网关令牌就能触发一次真实对话"这一权限级别，不是 admin 操作。
     */
    private final class DryRunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(
                        exchange, 405, objectMapper.writeValueAsString(java.util.Map.of("error", "POST required")));
                return;
            }
            if (!"/api/v1/dryrun".equals(exchange.getRequestURI().getPath())) {
                writeText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            try {
                requireRuntimeBearerAuth(exchange);
                URI uri = exchange.getRequestURI();
                String clientCode = requireQueryParam(uri, "clientCode");
                String userId = requireQueryParam(uri, "userId");
                String runtimeAgentId = requireQueryParam(uri, "runtimeAgentId");
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                AgentBlueprint blueprint = objectMapper.readValue(requestBytes, AgentBlueprint.class);
                CustomerContext customerContext =
                        new CustomerContext(
                                clientCode,
                                blueprint.clusterOrEmpty(),
                                "",
                                runtimeAgentId,
                                String.valueOf(blueprint.version()),
                                "",
                                "",
                                userId,
                                "",
                                userId,
                                userId,
                                "",
                                "",
                                "",
                                "");
                ChatResponse response =
                        agent.handleWithBlueprint(
                                blueprint,
                                customerContext,
                                "ping",
                                ExecutionProgressListener.noop(),
                                null);
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                java.util.Map.of("ok", true, "response", safe(response.reply()))));
            } catch (Exception exception) {
                writeDryRunError(exchange, exception);
            }
        }
    }

    /**
     * {@code /api/v1/ingest} 显式声明"本部署没有 ingest"，恒定返回 404。
     *
     * <p>Blueprint 落库不属于 agent-runtime：{@code agent_blueprint} 表由 agent-core 的
     * {@code ArtifactStoreService} 按 {@code worker_p3c}(insert DRAFT) →
     * {@code worker_p4}(DRAFT→STAGED) → {@code blueprint_admin}(STAGED→PUBLISHED) 的角色状态机写入，
     * 由 PG 触发器 {@code enforce_blueprint_role_write()} 强制；agent-runtime 持只读连接，
     * 既没有也不该有插入或流转该表状态的权限。P4 调用 ingest 时，agent-core 已经把这份 Blueprint
     * 写成 STAGED 了，agent-runtime 靠 {@code JdbcPublishedBlueprintSource} 读同一张表即可。
     *
     * <p>之所以仍要注册这条路由：不注册时请求会落到根 {@code "/"} 的 {@link IndexHandler}，
     * 它只收 GET，于是 POST 得到 405。而 P4 客户端（{@code agent-runtime.client.ts#ingest}）
     * 只对 404 静默跳过，405 会被当成真失败抛出去，把整条 run 拖进 RUN_BLOCKED。
     */
    private final class IngestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(
                    exchange,
                    404,
                    objectMapper.writeValueAsString(
                            java.util.Map.of(
                                    "error", "ingest_not_deployed",
                                    "message",
                                            "agent-runtime does not ingest blueprints; "
                                                    + "agent-core writes agent_blueprint directly")));
        }
    }

    private final class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            if (!"/api/chat".equals(exchange.getRequestURI().getPath())) {
                writeText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            try {
                ChatRequest request = readChatRequest(exchange);
                ChatCommand command = toChatCommand(request);
                ChatResponse response =
                        agent.handle(
                                command.customerContext(),
                                command.message(),
                                command.selection(),
                                ExecutionProgressListener.noop(),
                                null);
                String json = objectMapper.writeValueAsString(response);
                writeJson(exchange, 200, json);
            } catch (Exception exception) {
                String body =
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "error", "chat_handler_failed",
                                        "message", exception.getMessage()));
                writeJson(exchange, 500, body);
            }
        }
    }

    private final class ChatRunCreateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            if (!"/api/chat/runs".equals(exchange.getRequestURI().getPath())) {
                writeText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            try {
                ChatRequest request = readChatRequest(exchange);
                ChatCommand command = toChatCommand(request);
                ChatRunManager.ChatRunState runState =
                        chatRunManager.createRun(buildRunCreate(request, command));
                executor.execute(() -> executeRun(runState.runId(), command));
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                new ChatRunCreatedResponse(
                                        "created",
                                        runState.runId(),
                                        runState.createdAt().toString())));
            } catch (Exception exception) {
                String body =
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "error", "chat_run_create_failed",
                                        "message", exception.getMessage()));
                writeJson(exchange, 500, body);
            }
        }
    }

    private final class ChatRunHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try {
                int limit = parseLimit(extractQueryParam(exchange.getRequestURI(), "limit"));
                List<ChatRunStore.RunHistoryItem> items = chatRunManager.listRecent(limit);
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "ok",
                                        "persistenceEnabled", chatRunManager.persistenceEnabled(),
                                        "items", items)));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "chat_run_history_failed",
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class ChatRunEventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }

            String runId = extractRunId(exchange.getRequestURI());
            ChatRunManager.ChatRunState runState = chatRunManager.find(runId).orElse(null);
            if (runState == null) {
                writeJson(
                        exchange,
                        404,
                        objectMapper.writeValueAsString(
                                java.util.Map.of("error", "run_not_found", "runId", runId)));
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream outputStream = exchange.getResponseBody()) {
                LinkedBlockingQueue<ChatRunEvent> queue = new LinkedBlockingQueue<>();
                Consumer<ChatRunEvent> subscriber = queue::offer;
                runState.subscribe(subscriber);
                try {
                    List<ChatRunEvent> history = runState.snapshotEvents();
                    long replayUntil = history.isEmpty() ? 0 : history.get(history.size() - 1).seq();
                    for (ChatRunEvent event : history) {
                        writeSseEvent(outputStream, event);
                    }
                    if (runState.completed()) {
                        return;
                    }

                    while (true) {
                        ChatRunEvent event = queue.poll(15, TimeUnit.SECONDS);
                        if (event == null) {
                            writeSseKeepalive(outputStream);
                            continue;
                        }
                        if (event.seq() <= replayUntil) {
                            continue;
                        }
                        writeSseEvent(outputStream, event);
                        if (event.terminal()) {
                            return;
                        }
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                } finally {
                    runState.unsubscribe(subscriber);
                }
            }
        }
    }

    /**
     * 蓝图自查接口。
     *
     * <p>不带 {@code clientCode} 时列出索引里全部租户作用域；带上时返回该作用域的解析结果。
     * 校验失败时刻意返回 {@code 422} + 完整 errors 清单（走 {@code inspect} 而不是 {@code resolve}，
     * 后者会直接抛异常），这样上游改 JSON 时能一次看清所有问题，而不是每次只看到第一条。
     */
    private final class AgentBlueprintDebugHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            String clientCode = extractQueryParam(exchange.getRequestURI(), "clientCode");
            String cluster = extractQueryParam(exchange.getRequestURI(), "cluster");
            String sceneCode = extractQueryParam(exchange.getRequestURI(), "sceneCode");
            String runtimeAgentId = extractQueryParam(exchange.getRequestURI(), "runtimeAgentId");
            String version = extractQueryParam(exchange.getRequestURI(), "version");
            BlueprintSelection selection = selectionFromQuery(exchange.getRequestURI());
            try {
                if (clientCode.isBlank() && !selection.isPinned()) {
                    writeJson(
                            exchange,
                            200,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "ok",
                                            "scopes", agent.listBlueprintScopes())));
                    return;
                }
                var inspection =
                        agent.inspectBlueprint(
                                clientCode, cluster, sceneCode, runtimeAgentId, version, selection);
                if (inspection.isEmpty()) {
                    writeJson(
                            exchange,
                            404,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "blueprint_not_found",
                                            "clientCode", clientCode,
                                            "cluster", cluster,
                                            "runtimeAgentId", runtimeAgentId,
                                            "version", version,
                                            "sceneCode", sceneCode,
                                            "scopes", agent.listBlueprintScopes())));
                    return;
                }
                var report = inspection.get().validation();
                if (!report.valid()) {
                    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("status", "blueprint_invalid");
                    payload.put("clientCode", clientCode);
                    payload.put("cluster", cluster);
                    payload.put("runtimeAgentId", runtimeAgentId);
                    payload.put("version", version);
                    payload.put("sceneCode", sceneCode);
                    payload.put("matchLevel", inspection.get().matchLevel());
                    payload.put("sourceType", inspection.get().sourceType());
                    payload.put("selectionId", inspection.get().selectionId());
                    payload.put("sourcePath", inspection.get().sourcePath());
                    payload.put("errors", report.errors());
                    payload.put("warnings", report.warnings());
                    payload.put("consumedFields", report.consumedFields());
                    payload.put("ignoredFields", report.ignoredFields());
                    writeJson(
                            exchange,
                            422,
                            objectMapper.writeValueAsString(payload));
                    return;
                }
                var resolved =
                        agent.resolveBlueprint(
                                        clientCode,
                                        cluster,
                                        sceneCode,
                                        runtimeAgentId,
                                        version,
                                        selection)
                                .orElseThrow();
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("status", "ok");
                payload.put("matchLevel", resolved.matchLevel());
                payload.put("sourceType", resolved.sourceType());
                payload.put("selectionId", resolved.selectionId());
                payload.put("runtimeAgentId", resolved.runtimeAgentId());
                payload.put("requestedVersion", version);
                payload.put("sceneCode", sceneCode);
                payload.put("blueprint", resolved.toTimelineDetail());
                payload.put("systemPrompt", resolved.systemPrompt());
                payload.put("blueprintIdentity", resolved.blueprintIdentity());
                payload.put(
                        "recoveryKeywordSource",
                        resolved.rules().recoveryDetectionRule().isPresent()
                                ? "blueprint"
                                : "java-default");
                payload.put("intentKeywordSource", resolved.rules().intentKeywordSource());
                payload.put("profileThresholdSource", resolved.rules().profileThresholdSource());
                payload.put("consumedFields", report.consumedFields());
                payload.put("warnings", report.warnings());
                payload.put("ignoredFields", report.ignoredFields());
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(payload));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "blueprint_debug_failed",
                                        "clientCode", clientCode,
                                        "cluster", cluster,
                                        "runtimeAgentId", runtimeAgentId,
                                        "version", version,
                                        "sceneCode", sceneCode,
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class BlueprintCatalogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            String clientCode = extractQueryParam(exchange.getRequestURI(), "clientCode");
            String cluster = extractQueryParam(exchange.getRequestURI(), "cluster");
            String sceneCode = extractQueryParam(exchange.getRequestURI(), "sceneCode");
            String runtimeAgentId = extractQueryParam(exchange.getRequestURI(), "runtimeAgentId");
            try {
                List<BlueprintCatalogItem> items =
                        agent.listBlueprintCatalog(clientCode, cluster, sceneCode, runtimeAgentId);
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "ok",
                                        "items", items)));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "blueprint_catalog_failed",
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class BlueprintRetireHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> body =
                        requestBytes.length == 0
                                ? java.util.Map.of()
                                : objectMapper.readValue(requestBytes, java.util.Map.class);
                String sourceType = safe(stringValue(body.get("sourceType")));
                String blueprintId = safe(stringValue(body.get("blueprintId")));
                String clientCode = safe(stringValue(body.get("clientCode")));
                String runtimeAgentId = safe(stringValue(body.get("runtimeAgentId")));
                String version = safe(stringValue(body.get("version")));
                if (!JdbcPublishedBlueprintSource.SOURCE_TYPE.equals(sourceType)) {
                    writeJson(
                            exchange,
                            400,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "blueprint_retire_invalid_source",
                                            "message", "当前只支持删除 jdbc_published 来源的 Blueprint 资产")));
                    return;
                }
                if (!config.blueprintJdbcConfigured()) {
                    writeJson(
                            exchange,
                            500,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "blueprint_retire_jdbc_unconfigured",
                                            "message", "未配置 Blueprint JDBC 连接，无法执行数据库软删除")));
                    return;
                }
                if (blueprintId.isBlank() || clientCode.isBlank() || runtimeAgentId.isBlank() || version.isBlank()) {
                    writeJson(
                            exchange,
                            400,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "blueprint_retire_bad_request",
                                            "message", "blueprintId、clientCode、runtimeAgentId、version 不能为空")));
                    return;
                }
                int affected = retireJdbcPublishedBlueprint(blueprintId, clientCode, runtimeAgentId, version);
                if (affected <= 0) {
                    writeJson(
                            exchange,
                            404,
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "status", "blueprint_retire_not_found",
                                            "blueprintId", blueprintId,
                                            "clientCode", clientCode,
                                            "runtimeAgentId", runtimeAgentId,
                                            "version", version,
                                            "message", "未找到可软删除的已发布 Blueprint 记录")));
                    return;
                }
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "ok",
                                        "action", "retired",
                                        "affectedRows", affected,
                                        "blueprintId", blueprintId,
                                        "clientCode", clientCode,
                                        "runtimeAgentId", runtimeAgentId,
                                        "version", version)));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "blueprint_retire_failed",
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class RuntimeAgentTemplateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try {
                writeJson(
                        exchange,
                        200,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "ok",
                                        "templates", agent.listRuntimeAgentTemplates())));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "runtime_agent_templates_failed",
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class RuntimeBindingSummaryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            String clientCode = extractQueryParam(exchange.getRequestURI(), "clientCode");
            String cluster = extractQueryParam(exchange.getRequestURI(), "cluster");
            String sceneCode = extractQueryParam(exchange.getRequestURI(), "sceneCode");
            String runtimeAgentId = extractQueryParam(exchange.getRequestURI(), "runtimeAgentId");
            String version = extractQueryParam(exchange.getRequestURI(), "version");
            BlueprintSelection selection = selectionFromQuery(exchange.getRequestURI());
            if (runtimeAgentId.isBlank() && !sceneCode.isBlank()) {
                runtimeAgentId = sceneCode;
            }
            try {
                var summary =
                        agent.summarizeRuntimeBinding(
                                clientCode, cluster, sceneCode, runtimeAgentId, version, selection);
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("status", "ok");
                payload.put("current", summary.current());
                payload.put("runtimeAgents", summary.runtimeAgents());
                writeJson(exchange, 200, objectMapper.writeValueAsString(payload));
            } catch (Exception exception) {
                writeJson(
                        exchange,
                        500,
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "runtime_binding_summary_failed",
                                        "clientCode", clientCode,
                                        "cluster", cluster,
                                        "sceneCode", sceneCode,
                                        "runtimeAgentId", runtimeAgentId,
                                        "version", version,
                                        "message", String.valueOf(exception.getMessage()))));
            }
        }
    }

    private final class KnowledgeHealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
                return;
            }
            try {
                String query = extractQueryParam(exchange.getRequestURI(), "q");
                KnowledgeHealthResult result = knowledgeHealthService.check(query);
                int statusCode = "ok".equals(result.status()) ? 200 : 500;
                writeJson(exchange, statusCode, objectMapper.writeValueAsString(result));
            } catch (Exception exception) {
                String body =
                        objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "status", "knowledge_health_failed",
                                        "message", exception.getMessage()));
                writeJson(exchange, 500, body);
            }
        }
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String body)
            throws IOException {
        writeText(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private static String pick(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private static String extractQueryParam(URI uri, String key) {
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return "";
        }
        String[] pairs = uri.getQuery().split("&");
        for (String pair : pairs) {
            String[] entry = pair.split("=", 2);
            if (entry.length == 2 && key.equals(entry[0])) {
                return java.net.URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void writeText(
            HttpExchange exchange, int statusCode, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private ChatRequest readChatRequest(HttpExchange exchange) throws IOException {
        return objectMapper.readValue(exchange.getRequestBody().readAllBytes(), ChatRequest.class);
    }

    private ChatCommand toLegacyV1ChatCommand(URI requestUri, String message) {
        String clientCode = requireQueryParam(requestUri, "clientCode");
        String userId = requireQueryParam(requestUri, "userId");
        String sessionId = requireQueryParam(requestUri, "sessionId");
        String runtimeAgentId = requireQueryParam(requestUri, "runtimeAgentId");
        String cluster = extractQueryParam(requestUri, "cluster");
        String version = extractQueryParam(requestUri, "version");
        String sceneCode = extractQueryParam(requestUri, "sceneCode");

        ChatInputData inputData = new ChatInputData();
        inputData.userId = userId;
        inputData.userName = userId;
        inputData.chatUser = userId;
        inputData.conversationId = sessionId;
        inputData.runtimeAgentId = runtimeAgentId;
        inputData.version = version;

        ChatRequest request = new ChatRequest();
        request.message = message == null ? "" : message;
        request.clientCode = clientCode;
        request.cluster = cluster;
        // sceneCode 与 runtimeAgentId 是两个不同维度：runtimeAgentId 形如 <场景>-<租户>（P4
        // bindProject 生成），sceneCode 要对上 Blueprint 的 meta.scenarios 元素（如 beauty_wecom_cs）。
        // 这里曾经写 sceneCode = runtimeAgentId，等于凭空造出一个永远匹配不上 meta.scenarios 的场景
        // 过滤条件，JdbcPublishedBlueprintSource.sceneRank 把候选全判掉，蓝图明明已发布也会静默
        // 落回 fallback 提示词。试聊入口不传 sceneCode，就该留空表示"不限定场景"，交给
        // runtimeAgentId 单独定位。
        request.sceneCode = sceneCode;
        request.runtimeAgentId = runtimeAgentId;
        request.version = version;
        request.conversationId = sessionId;
        request.chatUser = userId;
        request.inputData = inputData;
        return toChatCommand(request);
    }

    private ChatCommand toChatCommand(ChatRequest request) {
        ChatInputData inputData = request.inputData;
        CustomerContext customerContext =
                new CustomerContext(
                        pick(request.clientCode, null),
                        pick(request.cluster, null),
                        pick(request.sceneCode, null),
                        pick(
                                inputData == null ? null : inputData.runtimeAgentId,
                                request.runtimeAgentId),
                        pick(inputData == null ? null : inputData.version, request.version),
                        pick(inputData == null ? null : inputData.conversationId, request.conversationId),
                        pick(inputData == null ? null : inputData.robotConversationId, null),
                        pick(inputData == null ? null : inputData.chatUser, request.chatUser),
                        pick(inputData == null ? null : inputData.robotKey, null),
                        pick(inputData == null ? null : inputData.userId, null),
                        pick(inputData == null ? null : inputData.userName, null),
                        pick(inputData == null ? null : inputData.conversationName, null),
                        pick(inputData == null ? null : inputData.messageId, null),
                        pick(inputData == null ? null : inputData.sendTime, null),
                        pick(inputData == null ? null : inputData.addMsgCount, null));
        BlueprintSelection selection =
                request.blueprintSelector == null
                        ? BlueprintSelection.scoped()
                        : request.blueprintSelector;
        return new ChatCommand(customerContext, request.message == null ? "" : request.message, selection);
    }

    private ChatRunStore.RunCreate buildRunCreate(ChatRequest request, ChatCommand command)
            throws IOException {
        CustomerContext context = command.customerContext();
        BlueprintSelection selection = command.selection();
        return new ChatRunStore.RunCreate(
                java.util.UUID.randomUUID().toString(),
                Instant.now(),
                safe(context.clientCode()),
                safe(context.cluster()),
                safe(context.sceneCode()),
                safe(context.runtimeAgentId()),
                safe(context.conversationId()),
                safe(context.chatUser()),
                previewText(command.message(), 500),
                selection == null ? "" : safe(selection.selectorMode()),
                selection == null ? "" : safe(selection.selectionId()),
                objectMapper.writeValueAsString(request));
    }

    private void executeRun(String runId, ChatCommand command) {
        try {
            ChatResponse response =
                    agent.handle(
                            command.customerContext(),
                            command.message(),
                            command.selection(),
                            chatRunManager.listenerFor(runId),
                            null);
            chatRunManager.completeRun(runId, response);
        } catch (Exception exception) {
            chatRunManager.failRun(runId, exception);
        }
    }

    private static BlueprintSelection selectionFromQuery(URI requestUri) {
        String selectorMode = extractQueryParam(requestUri, "selectorMode");
        String selectionId = extractQueryParam(requestUri, "selectionId");
        if (!selectionId.isBlank()
                && (selectorMode.isBlank()
                        || BlueprintSelection.MODE_PINNED.equalsIgnoreCase(selectorMode))) {
            return BlueprintSelection.pinned(selectionId);
        }
        return BlueprintSelection.scoped();
    }

    private String extractRunId(URI requestUri) {
        String path = requestUri == null ? "" : requestUri.getPath();
        String prefix = "/api/chat/runs/";
        if (path == null || !path.startsWith(prefix) || !path.endsWith("/events")) {
            return "";
        }
        return path.substring(prefix.length(), path.length() - "/events".length());
    }

    private static int parseLimit(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 20;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(rawValue.trim()), 100));
        } catch (NumberFormatException exception) {
            return 20;
        }
    }

    private int retireJdbcPublishedBlueprint(
            String blueprintId, String clientCode, String runtimeAgentId, String version) {
        String sql =
                "update agent_blueprint "
                        + "set status = 'RETIRED', updated_at = CURRENT_TIMESTAMP "
                        + "where blueprint_id = ? and client_code = ? and runtime_agent_id = ? "
                        + "and version = ? and status = 'PUBLISHED'";
        try (Connection connection =
                        DriverManager.getConnection(
                                config.blueprintJdbcUrl(),
                                config.blueprintJdbcUsername(),
                                config.blueprintJdbcPassword());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            statement.setString(1, blueprintId);
            statement.setString(2, clientCode);
            statement.setString(3, runtimeAgentId);
            statement.setInt(4, Integer.parseInt(version));
            int affected = statement.executeUpdate();
            connection.commit();
            return affected;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "retire jdbc blueprint failed: " + exception.getMessage(), exception);
        }
    }

    private void writeSseEvent(OutputStream outputStream, ChatRunEvent event) throws IOException {
        outputStream.write(("id: " + event.seq() + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write("event: progress\n".getBytes(StandardCharsets.UTF_8));
        outputStream.write(
                ("data: " + objectMapper.writeValueAsString(event) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static void writeSseKeepalive(OutputStream outputStream) throws IOException {
        outputStream.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void requireLegacyV1ChatAuth(HttpExchange exchange) {
        if (!config.compatV1ChatAuthEnabled()) {
            return;
        }
        String expected = config.compatV1ChatAuthToken();
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("compat v1 chat auth enabled but token missing");
        }
        String actual = bearerToken(exchange);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("unauthorized");
        }
    }

    /** {@code /api/v1/dryrun} 鉴权：与 {@code /api/v1/chat} 同一枚 {@code RUNTIME_AUTH_TOKEN}，始终强制校验。 */
    private void requireRuntimeBearerAuth(HttpExchange exchange) {
        String expected = config.compatV1ChatAuthToken();
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException("RUNTIME_AUTH_TOKEN missing; dry-run requires it");
        }
        String actual = bearerToken(exchange);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("unauthorized");
        }
    }

    private void writeDryRunError(HttpExchange exchange, Exception exception) throws IOException {
        int statusCode;
        String error;
        if (exception instanceof SecurityException) {
            statusCode = 401;
            error = "unauthorized";
        } else if (exception instanceof IllegalArgumentException) {
            statusCode = 400;
            error = "bad_request";
        } else {
            statusCode = 500;
            error = "dryrun_failed";
        }
        writeJson(
                exchange,
                statusCode,
                objectMapper.writeValueAsString(
                        java.util.Map.of("error", error, "message", safeMessage(exception))));
    }

    private void forwardLegacyV1ChatEvent(
            OutputStream outputStream, AgentEvent event, AtomicBoolean deltaSent) {
        try {
            if (event instanceof TextBlockDeltaEvent textBlockDeltaEvent) {
                deltaSent.set(true);
                writeLegacyV1SseEvent(
                        outputStream,
                        "message",
                        java.util.Map.of(
                                "delta",
                                safe(textBlockDeltaEvent.getDelta()),
                                "eventId",
                                legacyEventId(event)));
                return;
            }
            if (event instanceof AgentResultEvent agentResultEvent && !deltaSent.get()) {
                writeLegacyV1SseEvent(
                        outputStream,
                        "message",
                        java.util.Map.of(
                                "delta",
                                safe(agentResultEvent.getResult().getTextContent()),
                                "eventId",
                                legacyEventId(event)));
                return;
            }
            if (event instanceof RequireUserConfirmEvent) {
                writeLegacyV1SseEvent(
                        outputStream,
                        "approval_required",
                        java.util.Map.of("eventId", legacyEventId(event)));
            }
        } catch (IOException ioException) {
            throw new RuntimeException(ioException);
        }
    }

    private static void writeLegacyV1SseEvent(
            OutputStream outputStream, String eventName, java.util.Map<String, Object> payload)
            throws IOException {
        outputStream.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(
                ("data: " + new ObjectMapper().writeValueAsString(payload) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private static String requireQueryParam(URI uri, String key) {
        String value = extractQueryParam(uri, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " required");
        }
        return value;
    }

    private static String bearerToken(HttpExchange exchange) {
        if (exchange == null) {
            return "";
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "";
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private void writeLegacyV1ChatError(HttpExchange exchange, Exception exception) throws IOException {
        int statusCode;
        String error;
        if (exception instanceof SecurityException) {
            statusCode = 401;
            error = "unauthorized";
        } else if (exception instanceof IllegalArgumentException) {
            statusCode = 400;
            error = "bad_request";
        } else {
            statusCode = 500;
            error = "legacy_chat_handler_failed";
        }
        writeJson(
                exchange,
                statusCode,
                objectMapper.writeValueAsString(
                        java.util.Map.of("error", error, "message", safeMessage(exception))));
    }

    private static String legacyEventId(AgentEvent event) {
        return event == null || event.getId() == null ? "" : event.getId();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String previewText(String value, int maxLength) {
        String normalized = safe(value).trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return throwable == null ? "unknown_error" : throwable.getClass().getSimpleName();
        }
        return cause.getMessage();
    }

    private record ChatCommand(
            CustomerContext customerContext, String message, BlueprintSelection selection) {}
}
