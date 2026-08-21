package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibesales.salesagent.config.AppConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemotePublishedBlueprintSourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TEST_RUNTIME_AGENT_ID = "yjiyuncom_test_agent";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void shouldResolveRemoteBlueprintAndReuseCacheWithinTtl() throws Exception {
        AgentBlueprint blueprint =
                new AgentBlueprintLoader().loadFromClasspath("blueprints/yjiyuncom.test.json");
        AtomicInteger requestCount = new AtomicInteger();
        CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();
        server =
                startServer(
                        exchange -> {
                            requestCount.incrementAndGet();
                            queries.add(exchange.getRequestURI().getRawQuery());
                            writeJson(
                                    exchange,
                                    200,
                                    Map.of(
                                            "success",
                                            true,
                                            "data",
                                            Map.of(
                                                    "matchLevel",
                                                    AgentBlueprintRepository.MATCH_EXACT,
                                                    "matchedCluster",
                                                    "test",
                                                    "sourceId",
                                                    "remote:test-endpoint",
                                                    "blueprint",
                                                    blueprint)));
                        });
        RemotePublishedBlueprintSource source =
                new RemotePublishedBlueprintSource(config(baseUrl(), 10_000));

        BlueprintSource.BlueprintHandle first =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", TEST_RUNTIME_AGENT_ID, "")
                        .orElseThrow();
        BlueprintSource.BlueprintHandle second =
                source.resolve("yjiyuncom", "test", "BEAUTY_SKINCARE", TEST_RUNTIME_AGENT_ID, "")
                        .orElseThrow();

        assertEquals("yjiyuncom_test_v1", first.blueprint().blueprintId());
        assertEquals("remote:test-endpoint", first.sourceId());
        assertEquals(AgentBlueprintRepository.MATCH_EXACT, first.matchLevel());
        assertEquals("test", first.matchedCluster());
        assertEquals("yjiyuncom_test_v1", second.blueprint().blueprintId());
        assertEquals(1, requestCount.get());
        assertTrue(queries.get(0).contains("clientCode=yjiyuncom"));
        assertTrue(queries.get(0).contains("cluster=test"));
        assertTrue(queries.get(0).contains("sceneCode=BEAUTY_SKINCARE"));
        assertTrue(queries.get(0).contains("runtimeAgentId=" + TEST_RUNTIME_AGENT_ID));
    }

    @Test
    void shouldCacheRemoteNotFoundWithinTtl() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server =
                startServer(
                        exchange -> {
                            requestCount.incrementAndGet();
                            writeJson(
                                    exchange,
                                    404,
                                    Map.of("error", "blueprint_not_found", "success", false));
                        });
        RemotePublishedBlueprintSource source =
                new RemotePublishedBlueprintSource(config(baseUrl(), 10_000));

        Optional<BlueprintSource.BlueprintHandle> first =
                source.resolve("missing-client", "main", "BEAUTY_SKINCARE", "missing_agent", "");
        Optional<BlueprintSource.BlueprintHandle> second =
                source.resolve("missing-client", "main", "BEAUTY_SKINCARE", "missing_agent", "");

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertEquals(1, requestCount.get());
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer localServer = HttpServer.create(new InetSocketAddress(0), 0);
        localServer.createContext(
                "/api/v1/blueprints/published",
                exchange -> {
                    try {
                        handler.handle(exchange);
                    } finally {
                        exchange.close();
                    }
                });
        localServer.setExecutor(Executors.newCachedThreadPool());
        localServer.start();
        return localServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, Object body)
            throws IOException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static AppConfig config(String baseUrl, int cacheTtlMs) {
        return new AppConfig(
                "model",
                "http://model",
                "secret",
                "bailian",
                "",
                "",
                "",
                "",
                "127.0.0.1",
                "3306",
                "",
                "",
                "",
                "",
                "agent_conversations",
                // chatRunJdbcUrl / Username / Password：留空表示不启用运行留痕落库
                "",
                "",
                "",
                "agent_chat_runs",
                "agent_chat_run_events",
                "http://localhost:3002",
                "",
                "remote",
                true,
                baseUrl,
                "",
                "/api/v1/blueprints/published",
                3000,
                5000,
                cacheTtlMs,
                "",
                "",
                "",
                5,
                false,
                true,
                "",
                "",
                "BEAUTY_SKINCARE",
                ".agentscope/workspace",
                "sales-customer-agent",
                false,
                "",
                "",
                false,
                "",
                "",
                "",
                "");
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
