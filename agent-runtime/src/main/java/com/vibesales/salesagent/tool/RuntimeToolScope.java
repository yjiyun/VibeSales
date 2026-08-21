package com.vibesales.salesagent.tool;

import com.vibesales.salesagent.config.AppConfig;
import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.MarketingAgentRuntimeApiClient;

/**
 * 只读 Tool 共享的后端调用作用域。
 *
 * <p>三个只读 Tool（画像/历史摘要/任务板）都需要同一套东西：后端客户端、{@code clientCode} /
 * {@code cluster} / {@code sceneCode} 的"优先取请求上下文、回落到配置默认值"解析逻辑、以及
 * "后端是否已配置"的开关。
 * 收口到这个对象里，避免在每个 Tool 里重复三遍相同的字段和解析方法。
 *
 * <p>{@code clientCode}/{@code cluster} 的解析顺序是刻意的：请求上下文优先于配置默认值——正式接入
 * {@code agent-connector} 后，每个请求都会带上真实的客户作用域，配置里的默认值只是本地联调用的兜底
 * （见02号文档3.1节，{@code clientCode}/{@code cluster}/{@code sceneCode} 由接入层显式传入）。
 */
public final class RuntimeToolScope {

    private final MarketingAgentRuntimeApiClient apiClient;
    private final String defaultClientCode;
    private final String defaultCluster;
    private final String defaultSceneCode;
    private final boolean configured;

    /** 未接后端的占位作用域，供单测和"后端未配置"场景使用。 */
    public static RuntimeToolScope disabled() {
        return new RuntimeToolScope(null, "", "", "", false);
    }

    public static RuntimeToolScope from(AppConfig config) {
        if (!config.runtimeApiConfigured()) {
            return disabled();
        }
        return new RuntimeToolScope(
                new MarketingAgentRuntimeApiClient(config),
                config.defaultClientCode(),
                config.defaultCluster(),
                config.defaultSceneCode(),
                true);
    }

    public RuntimeToolScope(
            MarketingAgentRuntimeApiClient apiClient,
            String defaultClientCode,
            String defaultCluster,
            String defaultSceneCode,
            boolean configured) {
        this.apiClient = apiClient;
        this.defaultClientCode = defaultClientCode == null ? "" : defaultClientCode;
        this.defaultCluster = defaultCluster == null ? "" : defaultCluster;
        this.defaultSceneCode = defaultSceneCode == null ? "" : defaultSceneCode;
        this.configured = configured;
    }

    /** 后端是否可用；为 {@code false} 时 Tool 应直接走占位降级路径，不发起调用。 */
    public boolean available() {
        return configured && apiClient != null;
    }

    public MarketingAgentRuntimeApiClient apiClient() {
        return apiClient;
    }

    public String resolveClientCode(CustomerContext customerContext) {
        return firstNonBlank(customerContext.clientCode(), defaultClientCode);
    }

    public String resolveCluster(CustomerContext customerContext) {
        return firstNonBlank(customerContext.cluster(), defaultCluster);
    }

    public String resolveSceneCode(CustomerContext customerContext) {
        return firstNonBlank(customerContext.sceneCode(), defaultSceneCode);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }
}
