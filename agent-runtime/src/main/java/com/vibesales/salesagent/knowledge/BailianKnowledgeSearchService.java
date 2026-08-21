package com.vibesales.salesagent.knowledge;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.RetrieveRequest;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibesales.salesagent.config.AppConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 百炼知识库检索服务。 */
public final class BailianKnowledgeSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Client client;

    public BailianKnowledgeSearchService(AppConfig appConfig) {
        if (appConfig == null
                || isBlank(appConfig.accessKeyId())
                || isBlank(appConfig.accessKeySecret())) {
            this.client = null;
            return;
        }
        try {
            Config config =
                    new Config()
                            .setAccessKeyId(appConfig.accessKeyId())
                            .setAccessKeySecret(appConfig.accessKeySecret())
                            .setEndpoint("bailian.cn-beijing.aliyuncs.com");
            this.client = new Client(config);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize bailian knowledge client", exception);
        }
    }

    public boolean available() {
        return client != null;
    }

    public List<Map<String, Object>> retrieve(
            AccountKnowledgeBinding binding,
            AccountKnowledgeBinding.KnowledgeBaseConfig knowledgeBase,
            String query,
            int limit,
            Double scoreThreshold)
            throws Exception {
        if (client == null) {
            throw new IllegalStateException("knowledge access key is not configured");
        }
        if (!"bailian".equalsIgnoreCase(binding.providerOrDefault())) {
            throw new IllegalStateException("unsupported knowledge provider: " + binding.providerOrDefault());
        }

        RetrieveRequest request =
                new RetrieveRequest()
                        .setQuery(query)
                        .setIndexId(knowledgeBase.indexIdOrEmpty())
                        .setDenseSimilarityTopK(Math.max(limit * 2, 6))
                        .setSparseSimilarityTopK(Math.max(limit * 2, 6))
                        .setEnableReranking(true)
                        .setRerankTopN(limit);
        RetrieveResponse response = client.retrieve(knowledgeBase.workspaceIdOrEmpty(), request);
        JsonNode responseNode = OBJECT_MAPPER.valueToTree(response);
        JsonNode dataNode = responseNode.path("body").path("data");
        if (!dataNode.isObject()) {
            return List.of();
        }
        JsonNode nodes = dataNode.path("nodes");
        if (!nodes.isArray()) {
            return List.of();
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        for (JsonNode node : nodes) {
            Double score = extractScore(node);
            if (scoreThreshold != null && score != null && score < scoreThreshold) {
                continue;
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("title", extractFirstText(node, "title", "docTitle", "documentName", "fileName"));
            document.put(
                    "snippet",
                    trimLength(
                            extractFirstText(
                                    node,
                                    "chunkText",
                                    "text",
                                    "content",
                                    "snippet",
                                    "segmentText",
                                    "nodeText"),
                            600));
            document.put(
                    "source",
                    extractFirstText(node, "source", "sourceName", "fileName", "documentName", "docName"));
            document.put("score", score);
            documents.add(Map.copyOf(document));
            if (documents.size() >= limit) {
                break;
            }
        }
        return List.copyOf(documents);
    }

    private static Double extractScore(JsonNode node) {
        for (String key : List.of("rerankScore", "score", "relevanceScore", "denseScore", "sparseScore")) {
            JsonNode candidate = node.get(key);
            if (candidate != null && candidate.isNumber()) {
                return candidate.doubleValue();
            }
        }
        return null;
    }

    private static String extractFirstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && candidate.isValueNode()) {
                String value = candidate.asText("");
                if (!value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static String trimLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
