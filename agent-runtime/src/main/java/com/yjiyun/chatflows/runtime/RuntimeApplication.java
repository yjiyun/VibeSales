package com.yjiyun.chatflows.runtime;

import com.vibesales.salesagent.app.SalesCustomerAgentApplication;
import com.vibesales.salesagent.observability.RuntimeTelemetry;

/**
 * `agent-runtime` 的兼容启动壳。
 *
 * <p>当前目录已经切换到 `sales-customer-agent` 主体实现，但为了尽量不打断旧脚本、旧构建坐标和旧主类
 * 引用，这里继续保留 `com.yjiyun.chatflows.runtime.RuntimeApplication` 作为稳定入口。
 */
public final class RuntimeApplication {

    private RuntimeApplication() {}

    public static void main(String[] args) throws Exception {
        mirrorEnv("RUNTIME_PORT", "AGENT_WEB_PORT");
        mirrorEnv("RUNTIME_AUTH_TOKEN", "AGENT_COMPAT_V1_CHAT_AUTH_TOKEN");
        ifMissingSet("AGENT_COMPAT_V1_CHAT_AUTH_ENABLED", legacyAuthEnabled());
        ifMissingSet("AGENT_APP_NAME", "agent-runtime");
        RuntimeTelemetry.install(System.getenv());
        SalesCustomerAgentApplication.main(args);
    }

    private static void mirrorEnv(String sourceKey, String targetKey) {
        String source = read(sourceKey);
        if (source.isBlank()) {
            return;
        }
        if (read(targetKey).isBlank()) {
            System.setProperty(targetKey, source);
        }
    }

    private static void ifMissingSet(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (read(key).isBlank()) {
            System.setProperty(key, value);
        }
    }

    private static String legacyAuthEnabled() {
        return read("RUNTIME_AUTH_TOKEN").isBlank() ? "false" : "true";
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
