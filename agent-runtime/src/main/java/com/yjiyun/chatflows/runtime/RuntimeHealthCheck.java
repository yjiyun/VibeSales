package com.yjiyun.chatflows.runtime;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * 保留旧镜像脚本所依赖的健康检查入口，实际探测新的 `/api/health`。
 */
public final class RuntimeHealthCheck {

    private RuntimeHealthCheck() {}

    public static void main(String[] args) throws IOException {
        int port = resolvePort();
        URL url = URI.create("http://127.0.0.1:" + port + "/api/health").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("health check failed with status " + status);
        }
    }

    private static int resolvePort() {
        String runtimePort = read("RUNTIME_PORT");
        if (!runtimePort.isBlank()) {
            return parsePort(runtimePort, 8088);
        }
        String agentWebPort = read("AGENT_WEB_PORT");
        if (!agentWebPort.isBlank()) {
            return parsePort(agentWebPort, 18080);
        }
        return 8088;
    }

    private static int parsePort(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String read(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String property = System.getProperty(key);
        return property == null ? "" : property.trim();
    }
}
