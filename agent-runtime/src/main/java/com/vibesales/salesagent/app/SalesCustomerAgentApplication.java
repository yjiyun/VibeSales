package com.agentteams.salesagent.app;

import com.sun.net.httpserver.HttpServer;
import com.agentteams.salesagent.config.AppConfig;
import com.agentteams.salesagent.config.EnvFileLoader;
import com.agentteams.salesagent.web.WebServer;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * 正式项目当前的启动入口。
 *
 * <p>P1 最小闭环阶段，它只负责启动一个本地 HTTP 服务，把“网页输入 -> 后端入口 -> 主链路 ->
 * 网页输出”先跑通，后续再逐步接入真实 Tool、Skill 和模型能力。
 */
public final class SalesCustomerAgentApplication {

    private SalesCustomerAgentApplication() {
    }

    public static void main(String[] args) throws IOException {
        EnvFileLoader.loadIfPresent(Paths.get(".env"));
        AppConfig config = AppConfig.load();
        config.validateModelConfig();
        int port = resolvePort();
        HttpServer server = new WebServer(port, config).start();

        System.out.println("sales-customer-agent bootstrap ready");
        System.out.println("phase: P1-minimal-loop");
        System.out.println("package: com.agentteams.salesagent");
        System.out.println("strategy: Skill-first + Rule-in-Code + Tool-as-Boundary");
        System.out.println("model name: " + config.modelName());
        System.out.println("model base url: " + fallback(config.modelBaseUrl()));
        System.out.println("knowledge: Bailian knowledge base");
        System.out.println("status: html -> entry -> runtimeContext -> model-call -> output is ready");
        System.out.println("knowledge provider: " + config.knowledgeProvider());
        System.out.println("knowledge workspace: " + fallback(config.workspaceId()));
        System.out.println("knowledge base: " + fallback(config.knowledgeBaseId()));
        System.out.println("conversation mysql database: " + fallback(config.mysqlDatabase()));
        System.out.println("conversation table: " + fallback(config.conversationTableName()));
        System.out.println("app name (for AgentLoop probe): " + config.appName());
        System.out.println("local url: http://localhost:" + port + "/");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    private static int resolvePort() {
        String value = com.agentteams.salesagent.config.ConfigValueResolver.get("AGENT_WEB_PORT");
        if (value == null || value.isBlank()) {
            return 18080;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 18080;
        }
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "(empty)" : value;
    }
}
