package com.vibesales.salesagent.observability;

/**
 * 运行时侧 span 中文别名表。
 */
public final class SpanAliases {

    private SpanAliases() {}

    public static String runSpanName() {
        return "智能体接待·整体运行";
    }

    public static String llmSpanName() {
        return "模型调用·对话生成";
    }

    public static String toolSpanName(String toolName, Object path) {
        String normalizedTool = safe(toolName);
        String normalizedPath = safe(path);
        if ("marketing_agent_runtime".equals(normalizedTool)) {
            if (normalizedPath.endsWith("/session/bootstrap")) {
                return "运行时接口·准备业务会话";
            }
            if (normalizedPath.endsWith("/history-summary")) {
                return "运行时接口·读写历史摘要";
            }
            if (normalizedPath.endsWith("/intent-queue")) {
                return "运行时接口·读取任务板";
            }
            if (normalizedPath.endsWith("/intent-queue/sync")) {
                return "运行时接口·同步任务板";
            }
            if (normalizedPath.endsWith("/customer-profile")) {
                return "运行时接口·读取客户画像";
            }
            if (normalizedPath.endsWith("/rule-context")) {
                return "运行时接口·读取规则上下文";
            }
            return "运行时接口·调用营销中台";
        }
        return "工具调用·执行工具";
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
