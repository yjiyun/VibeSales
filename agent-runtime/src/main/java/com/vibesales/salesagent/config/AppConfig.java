package com.agentteams.salesagent.config;

/**
 * 当前正式项目的最小配置装配对象。
 *
 * <p>本轮重点承接知识库相关配置，把 `.env` / 环境变量里的值整理成一个统一对象，
 * 供 Web 层、健康检查和后续知识检索服务复用。
 */
public record AppConfig(
        String modelName,
        String modelBaseUrl,
        String modelApiKey,
        String knowledgeProvider,
        String accessKeyId,
        String accessKeySecret,
        String workspaceId,
        String knowledgeBaseId,
        String mysqlHost,
        String mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        String mysqlJdbcUrl,
        String conversationTableName,
        String chatRunJdbcUrl,
        String chatRunJdbcUsername,
        String chatRunJdbcPassword,
        String chatRunTableName,
        String chatRunEventTableName,
        String runtimeApiBaseUrl,
        String runtimeApiToken,
        String blueprintSource,
        boolean blueprintRemoteEnabled,
        String blueprintRemoteBaseUrl,
        String blueprintRemoteApiToken,
        String blueprintRemotePath,
        int blueprintRemoteConnectTimeoutMs,
        int blueprintRemoteReadTimeoutMs,
        int blueprintRemoteCacheTtlMs,
        String blueprintJdbcUrl,
        String blueprintJdbcUsername,
        String blueprintJdbcPassword,
        int blueprintJdbcQueryTimeoutSeconds,
        boolean blueprintFailOnRemoteError,
        boolean blueprintFallbackToClasspath,
        String defaultClientCode,
        String defaultCluster,
        String defaultSceneCode,
        String workspaceRoot,
        String appName,
        boolean compatV1ChatAuthEnabled,
        String compatV1ChatAuthToken,
        boolean otelEnabled,
        String otelEndpoint,
        String otelLicenseKey,
        String otelArmsProject,
        String otelCmsWorkspace) {

    public static AppConfig load() {
        String compatV1ChatAuthToken =
                ConfigValueResolver.get("AGENT_COMPAT_V1_CHAT_AUTH_TOKEN", "RUNTIME_AUTH_TOKEN");
        boolean compatV1ChatAuthEnabled =
                ConfigValueResolver.getBooleanOrDefault(
                        !compatV1ChatAuthToken.isBlank(), "AGENT_COMPAT_V1_CHAT_AUTH_ENABLED");
        return new AppConfig(
                ConfigValueResolver.getOrDefault("deepseek-v4-flash-0731", "AGENT_MODEL_NAME"),
                ConfigValueResolver.getOrDefault(
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                        "AGENT_MODEL_BASE_URL"),
                ConfigValueResolver.get(
                        "AGENT_MODEL_API_KEY", "OPENAI_API_KEY", "DASHSCOPE_API_KEY"),
                ConfigValueResolver.getOrDefault("bailian", "AGENT_KNOWLEDGE_PROVIDER"),
                ConfigValueResolver.get("ALIBABA_CLOUD_ACCESS_KEY_ID"),
                ConfigValueResolver.get("ALIBABA_CLOUD_ACCESS_KEY_SECRET"),
                ConfigValueResolver.get("AGENT_BAILIAN_WORKSPACE_ID", "WORKSPACE_ID"),
                ConfigValueResolver.get("AGENT_BAILIAN_KNOWLEDGE_BASE_ID"),
                ConfigValueResolver.getOrDefault("127.0.0.1", "AGENT_MYSQL_HOST"),
                ConfigValueResolver.getOrDefault("3306", "AGENT_MYSQL_PORT"),
                ConfigValueResolver.get("AGENT_MYSQL_DATABASE"),
                ConfigValueResolver.get("AGENT_MYSQL_USERNAME"),
                ConfigValueResolver.get("AGENT_MYSQL_PASSWORD"),
                ConfigValueResolver.get("AGENT_MYSQL_JDBC_URL"),
                ConfigValueResolver.getOrDefault("agent_conversations", "AGENT_CONVERSATION_TABLE"),
                ConfigValueResolver.get("AGENT_CHAT_RUN_JDBC_URL"),
                ConfigValueResolver.get("AGENT_CHAT_RUN_JDBC_USERNAME"),
                ConfigValueResolver.get("AGENT_CHAT_RUN_JDBC_PASSWORD"),
                ConfigValueResolver.getOrDefault("agent_runtime_chat_runs", "AGENT_CHAT_RUN_TABLE"),
                ConfigValueResolver.getOrDefault(
                        "agent_runtime_chat_run_events", "AGENT_CHAT_RUN_EVENT_TABLE"),
                ConfigValueResolver.getOrDefault(
                        "http://localhost:3002", "AGENT_RUNTIME_API_BASE_URL"),
                ConfigValueResolver.get("AGENT_RUNTIME_API_TOKEN"),
                ConfigValueResolver.getOrDefault("classpath", "AGENT_BLUEPRINT_SOURCE"),
                ConfigValueResolver.getBooleanOrDefault(false, "AGENT_BLUEPRINT_REMOTE_ENABLED"),
                ConfigValueResolver.get("AGENT_BLUEPRINT_REMOTE_BASE_URL"),
                ConfigValueResolver.get("AGENT_BLUEPRINT_REMOTE_API_TOKEN"),
                ConfigValueResolver.getOrDefault(
                        "/api/v1/blueprints/published", "AGENT_BLUEPRINT_REMOTE_PATH"),
                ConfigValueResolver.getIntOrDefault(
                        3000, "AGENT_BLUEPRINT_REMOTE_CONNECT_TIMEOUT_MS"),
                ConfigValueResolver.getIntOrDefault(
                        5000, "AGENT_BLUEPRINT_REMOTE_READ_TIMEOUT_MS"),
                ConfigValueResolver.getIntOrDefault(
                        5000, "AGENT_BLUEPRINT_REMOTE_CACHE_TTL_MS"),
                ConfigValueResolver.get("AGENT_BLUEPRINT_JDBC_URL"),
                ConfigValueResolver.get("AGENT_BLUEPRINT_JDBC_USERNAME"),
                ConfigValueResolver.get("AGENT_BLUEPRINT_JDBC_PASSWORD"),
                ConfigValueResolver.getIntOrDefault(
                        5, "AGENT_BLUEPRINT_JDBC_QUERY_TIMEOUT_SECONDS"),
                ConfigValueResolver.getBooleanOrDefault(
                        false, "AGENT_BLUEPRINT_FAIL_ON_REMOTE_ERROR"),
                ConfigValueResolver.getBooleanOrDefault(
                        true, "AGENT_BLUEPRINT_FALLBACK_TO_CLASSPATH"),
                ConfigValueResolver.get("AGENT_DEFAULT_CLIENT_CODE"),
                ConfigValueResolver.get("AGENT_DEFAULT_CLUSTER"),
                ConfigValueResolver.getOrDefault("BEAUTY_SKINCARE", "AGENT_DEFAULT_SCENE_CODE"),
                ConfigValueResolver.getOrDefault(
                        ".agentscope/workspace", "AGENT_WORKSPACE_ROOT"),
                ConfigValueResolver.getOrDefault("sales-customer-agent", "AGENT_APP_NAME"),
                compatV1ChatAuthEnabled,
                compatV1ChatAuthToken,
                "true".equalsIgnoreCase(ConfigValueResolver.getOrDefault("false", "AGENT_OTEL_ENABLED")),
                ConfigValueResolver.get("AGENT_OTEL_ENDPOINT"),
                ConfigValueResolver.get("AGENT_OTEL_LICENSE_KEY"),
                ConfigValueResolver.get("AGENT_OTEL_ARMS_PROJECT"),
                ConfigValueResolver.get("AGENT_OTEL_CMS_WORKSPACE"));
    }

    /**
     * 租户提示词/Skill 的投影根目录。
     *
     * <p>默认值与 {@code HarnessAgent.resolveDefaultWorkspace()} 的落点一致
     * （{@code user.dir/.agentscope/workspace}），所以本地不配也能对上既有目录。
     * 相对路径按进程工作目录解析。
     */
    public java.nio.file.Path resolvedWorkspaceRoot() {
        String configured = workspaceRoot == null || workspaceRoot.isBlank()
                ? ".agentscope/workspace"
                : workspaceRoot.trim();
        return java.nio.file.Path.of(configured).toAbsolutePath().normalize();
    }

    public boolean modelConfigured() {
        return !modelName.isBlank() && !modelBaseUrl.isBlank() && !modelApiKey.isBlank();
    }

    public void validateModelConfig() {
        StringBuilder missing = new StringBuilder();
        appendMissing(missing, modelName, "AGENT_MODEL_NAME");
        appendMissing(missing, modelBaseUrl, "AGENT_MODEL_BASE_URL");
        appendMissing(missing, modelApiKey, "AGENT_MODEL_API_KEY/OPENAI_API_KEY/DASHSCOPE_API_KEY");
        if (missing.length() > 0) {
            throw new IllegalStateException("Missing model config: " + missing);
        }
    }

    public boolean knowledgeConfigured() {
        return !workspaceId.isBlank()
                && !knowledgeBaseId.isBlank()
                && !accessKeyId.isBlank()
                && !accessKeySecret.isBlank();
    }

    /**
     * 是否已具备调用 {@code marketing-agent-service} agent runtime 接口的条件。
     *
     * <p>当前主链路的正式口径是由请求显式传入 {@code clientCode + cluster + sceneCode}，
     * 配置里的默认作用域只用于本地调试兜底，不应该成为"能否启用 runtime Tool"的前置条件。
     * 否则工作台已经把真实 scope 传进来时，这里仍会误判为"后端未配置"，整条链路直接回退到占位快照。
     *
     * <p>因此这里只要求存在可访问的 {@code runtimeApiBaseUrl}。后续具体请求若既没有请求内 scope、
     * 也没有默认 scope，再由各 Tool/后端接口按实际参数校验失败，而不是在装配阶段提前整体禁用。
     *
     * <p>后端当前对这些接口没有做认证（无 JWT、无 API key），所以 {@link #runtimeApiToken()} 不是必填项。
     * 该字段仅为后端将来补认证预留：配置为空时客户端不发认证 header，配置了就带上。
     */
    public boolean runtimeApiConfigured() {
        return !runtimeApiBaseUrl.isBlank();
    }

    public boolean blueprintJdbcConfigured() {
        return !blueprintJdbcUrl.isBlank() && !blueprintJdbcUsername.isBlank();
    }

    public boolean mysqlConfigured() {
        return !mysqlDatabase.isBlank()
                && !mysqlUsername.isBlank()
                && (!mysqlJdbcUrl.isBlank() || (!mysqlHost.isBlank() && !mysqlPort.isBlank()));
    }

    public boolean chatRunJdbcConfigured() {
        return !chatRunJdbcUrl.isBlank() && !chatRunJdbcUsername.isBlank();
    }

    /**
     * 是否已具备接入 AgentLoop "Agent 通用接入"（javaagent 探针）协议的条件。
     *
     * <p>探针协议只需要 LicenseKey + workspace，不需要 {@code otelEndpoint}/{@code otelArmsProject}
     * ——那两个字段是给"OpenTelemetry"协议（标准 OTLP 上报）保留的，探针协议下留空即可，见
     * {@code 14-AgentLoop.md} 第2节的协议对比。
     */
    public boolean otelConfigured() {
        return otelEnabled && !otelLicenseKey.isBlank() && !otelCmsWorkspace.isBlank();
    }

    public String resolvedMysqlJdbcUrl() {
        if (!mysqlJdbcUrl.isBlank()) {
            return mysqlJdbcUrl;
        }
        return "jdbc:mysql://"
                + mysqlHost
                + ":"
                + mysqlPort
                + "/"
                + mysqlDatabase
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static void appendMissing(StringBuilder builder, String value, String key) {
        if (!value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(key);
    }
}
