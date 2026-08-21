package com.vibesales.salesagent.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;

/** 账号级知识库绑定配置，按 {@code clientCode + cluster} 生效。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountKnowledgeBinding(
        String clientCode,
        String cluster,
        String provider,
        String defaultKnowledgeBaseCode,
        List<KnowledgeBaseConfig> knowledgeBases) {

    public AccountKnowledgeBinding {
        knowledgeBases = knowledgeBases == null ? List.of() : List.copyOf(knowledgeBases);
    }

    public String clientCodeOrEmpty() {
        return safe(clientCode);
    }

    public String clusterOrEmpty() {
        return safe(cluster);
    }

    public String providerOrDefault() {
        return safe(provider).isEmpty() ? "bailian" : safe(provider);
    }

    public boolean available() {
        return knowledgeBases.stream().anyMatch(KnowledgeBaseConfig::enabledOrDefault);
    }

    public List<String> knowledgeBaseCodes() {
        return knowledgeBases.stream()
                .filter(KnowledgeBaseConfig::enabledOrDefault)
                .map(KnowledgeBaseConfig::knowledgeBaseCodeOrEmpty)
                .filter(code -> !code.isEmpty())
                .toList();
    }

    public Optional<KnowledgeBaseConfig> defaultKnowledgeBase() {
        String requested = safe(defaultKnowledgeBaseCode);
        if (!requested.isEmpty()) {
            Optional<KnowledgeBaseConfig> exact = findKnowledgeBase(requested);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return knowledgeBases.stream().filter(KnowledgeBaseConfig::enabledOrDefault).findFirst();
    }

    public Optional<KnowledgeBaseConfig> findKnowledgeBase(String knowledgeBaseCode) {
        String requested = safe(knowledgeBaseCode);
        if (requested.isEmpty()) {
            return defaultKnowledgeBase();
        }
        return knowledgeBases.stream()
                .filter(KnowledgeBaseConfig::enabledOrDefault)
                .filter(base -> requested.equals(base.knowledgeBaseCodeOrEmpty()))
                .findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeBaseConfig(
            String knowledgeBaseCode,
            String displayName,
            String workspaceId,
            String indexId,
            String description,
            Boolean enabled) {

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public String knowledgeBaseCodeOrEmpty() {
            return safe(knowledgeBaseCode);
        }

        public String displayNameOrCode() {
            return safe(displayName).isEmpty() ? knowledgeBaseCodeOrEmpty() : safe(displayName);
        }

        public String workspaceIdOrEmpty() {
            return safe(workspaceId);
        }

        public String indexIdOrEmpty() {
            return safe(indexId);
        }

        public String descriptionOrEmpty() {
            return safe(description);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
