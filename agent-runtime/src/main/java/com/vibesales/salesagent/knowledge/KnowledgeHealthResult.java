package com.vibesales.salesagent.knowledge;

/**
 * 百炼知识库健康检查结果。
 *
 * <p>用于把“配置已加载”“文件列表可访问”“检索接口可调用”等状态统一返回给
 * `/api/health/knowledge` 和前端验证页。
 */
public record KnowledgeHealthResult(
        String status,
        String provider,
        boolean configLoaded,
        boolean fileListReachable,
        boolean retrieveReachable,
        String workspaceId,
        String knowledgeBaseId,
        String sampleQuery,
        int documentCount,
        int retrievedNodeCount,
        String message) {
}
