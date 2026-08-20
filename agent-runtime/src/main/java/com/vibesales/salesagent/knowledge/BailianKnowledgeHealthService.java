package com.agentteams.salesagent.knowledge;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.ListIndexFileDetailsRequest;
import com.aliyun.bailian20231229.models.ListIndexFileDetailsResponse;
import com.aliyun.bailian20231229.models.RetrieveRequest;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.config.AppConfig;

/**
 * 百炼知识库最小健康检查服务。
 *
 * <p>当前先不把检索结果接入主 Agent，而是先用真实 SDK 完成两步校验：
 * 1. 查询知识库文件列表，确认 workspace 和 indexId 可访问
 * 2. 发起一次检索，确认知识库接口实际可调用
 */
public final class BailianKnowledgeHealthService {
    private static final String DEFAULT_QUERY = "补水方案";
    private static final String BAILIAN_ENDPOINT = "bailian.cn-beijing.aliyuncs.com";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeHealthResult check(String sampleQuery) {
        AppConfig config = AppConfig.load();
        String provider = config.knowledgeProvider();
        String query = normalizeQuery(sampleQuery);

        if (!"bailian".equalsIgnoreCase(provider)) {
            return new KnowledgeHealthResult(
                    "unsupported_provider",
                    provider,
                    false,
                    false,
                    false,
                    config.workspaceId(),
                    config.knowledgeBaseId(),
                    query,
                    0,
                    0,
                    "当前知识层 provider 不是 bailian，未执行知识库校验。");
        }

        if (!config.knowledgeConfigured()) {
            return new KnowledgeHealthResult(
                    "config_missing",
                    provider,
                    false,
                    false,
                    false,
                    config.workspaceId(),
                    config.knowledgeBaseId(),
                    query,
                    0,
                    0,
                    "缺少知识库必填配置，请检查 ALIBABA_CLOUD_ACCESS_KEY_ID / "
                            + "ALIBABA_CLOUD_ACCESS_KEY_SECRET / AGENT_BAILIAN_WORKSPACE_ID(或 WORKSPACE_ID) / "
                            + "AGENT_BAILIAN_KNOWLEDGE_BASE_ID。");
        }

        try {
            Client client = createClient(config);

            ListIndexFileDetailsResponse listResponse =
                    client.listIndexFileDetails(
                            config.workspaceId(),
                            new ListIndexFileDetailsRequest()
                                    .setIndexId(config.knowledgeBaseId())
                                    .setPageNumber(1)
                                    .setPageSize(10));
            JsonNode listBody = objectMapper.valueToTree(listResponse.getBody());
            if (!listBody.path("success").asBoolean(false)) {
                return failure(config, query, "file_list_failed", listBody.path("message").asText("知识库文件列表调用失败"));
            }

            int documentCount = extractDocumentCount(listBody);

            RetrieveResponse retrieveResponse =
                    client.retrieve(
                            config.workspaceId(),
                            new RetrieveRequest()
                                    .setIndexId(config.knowledgeBaseId())
                                    .setQuery(query)
                                    .setDenseSimilarityTopK(5)
                                    .setSparseSimilarityTopK(5)
                                    .setEnableReranking(Boolean.TRUE)
                                    .setRerankTopN(3));
            JsonNode retrieveBody = objectMapper.valueToTree(retrieveResponse.getBody());
            if (!retrieveBody.path("success").asBoolean(false)) {
                return new KnowledgeHealthResult(
                        "retrieve_failed",
                        provider,
                        true,
                        true,
                        false,
                        config.workspaceId(),
                        config.knowledgeBaseId(),
                        query,
                        documentCount,
                        0,
                        retrieveBody.path("message").asText("知识库检索调用失败"));
            }

            int retrievedNodeCount = retrieveBody.path("data").path("nodes").size();
            return new KnowledgeHealthResult(
                    "ok",
                    provider,
                    true,
                    true,
                    true,
                    config.workspaceId(),
                    config.knowledgeBaseId(),
                    query,
                    documentCount,
                    retrievedNodeCount,
                    "百炼知识库配置已加载，文件列表与检索接口都可访问。");
        } catch (Exception exception) {
            return new KnowledgeHealthResult(
                    "exception",
                    provider,
                    true,
                    false,
                    false,
                    config.workspaceId(),
                    config.knowledgeBaseId(),
                    query,
                    0,
                    0,
                    trimMessage(exception));
        }
    }

    private static Client createClient(AppConfig config) throws Exception {
        Config openApiConfig = new Config();
        openApiConfig.setAccessKeyId(config.accessKeyId());
        openApiConfig.setAccessKeySecret(config.accessKeySecret());
        openApiConfig.setEndpoint(BAILIAN_ENDPOINT);
        return new Client(openApiConfig);
    }

    private static String normalizeQuery(String sampleQuery) {
        if (sampleQuery == null || sampleQuery.isBlank()) {
            return DEFAULT_QUERY;
        }
        return sampleQuery.trim();
    }

    private static int extractDocumentCount(JsonNode listBody) {
        JsonNode dataNode = listBody.path("data");
        if (dataNode.path("totalCount").canConvertToInt()) {
            return dataNode.path("totalCount").asInt();
        }
        if (dataNode.path("documents").isArray()) {
            return dataNode.path("documents").size();
        }
        return 0;
    }

    private static KnowledgeHealthResult failure(
            AppConfig config, String query, String status, String message) {
        return new KnowledgeHealthResult(
                status,
                config.knowledgeProvider(),
                true,
                false,
                false,
                config.workspaceId(),
                config.knowledgeBaseId(),
                query,
                0,
                0,
                message);
    }

    private static String trimMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
