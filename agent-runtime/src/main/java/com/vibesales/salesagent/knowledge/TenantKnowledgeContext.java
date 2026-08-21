package com.vibesales.salesagent.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前轮次注入到 RuntimeContext 的租户知识库上下文。 */
public record TenantKnowledgeContext(
        String clientCode,
        String cluster,
        String runtimeAgentId,
        String matchLevel,
        AccountKnowledgeBinding binding) {

    public boolean retrievalAvailable() {
        return binding != null && binding.available();
    }

    public String provider() {
        return binding == null ? "" : binding.providerOrDefault();
    }

    public String defaultKnowledgeBaseCode() {
        return binding == null
                ? ""
                : binding.defaultKnowledgeBase()
                        .map(AccountKnowledgeBinding.KnowledgeBaseConfig::knowledgeBaseCodeOrEmpty)
                        .orElse("");
    }

    public List<String> availableKnowledgeBaseCodes() {
        return binding == null ? List.of() : binding.knowledgeBaseCodes();
    }

    public Map<String, Object> toTimelineDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("clientCode", safe(clientCode));
        detail.put("cluster", safe(cluster));
        detail.put("runtimeAgentId", safe(runtimeAgentId));
        detail.put("matchLevel", safe(matchLevel));
        detail.put("provider", provider());
        detail.put("retrievalAvailable", retrievalAvailable());
        detail.put("defaultKnowledgeBaseCode", defaultKnowledgeBaseCode());
        detail.put("knowledgeBaseCodes", availableKnowledgeBaseCodes());
        return java.util.Collections.unmodifiableMap(detail);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
