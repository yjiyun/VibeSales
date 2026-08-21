package com.vibesales.salesagent.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** 从 classpath 读取账号级知识库绑定。 */
public final class AccountKnowledgeBindingRepository {

    public static final String MATCH_EXACT = "exact";
    public static final String MATCH_FALLBACK = "fallback";
    private static final String RESOURCE_PATH = "knowledge/account-bindings.json";

    private final List<AccountKnowledgeBinding> bindings;

    public AccountKnowledgeBindingRepository() {
        this(new ObjectMapper());
    }

    AccountKnowledgeBindingRepository(ObjectMapper objectMapper) {
        this.bindings = loadBindings(objectMapper);
    }

    public Optional<ResolvedBinding> resolve(String clientCode, String cluster) {
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        if (normalizedClientCode.isEmpty()) {
            return Optional.empty();
        }

        Optional<AccountKnowledgeBinding> exact =
                bindings.stream()
                        .filter(binding -> normalizedClientCode.equals(binding.clientCodeOrEmpty()))
                        .filter(binding -> normalizedCluster.equals(binding.clusterOrEmpty()))
                        .findFirst();
        if (exact.isPresent()) {
            return exact.map(binding -> new ResolvedBinding(binding, MATCH_EXACT));
        }

        return bindings.stream()
                .filter(binding -> normalizedClientCode.equals(binding.clientCodeOrEmpty()))
                .filter(binding -> binding.clusterOrEmpty().isEmpty())
                .findFirst()
                .map(binding -> new ResolvedBinding(binding, MATCH_FALLBACK));
    }

    List<AccountKnowledgeBinding> bindings() {
        return bindings;
    }

    private static List<AccountKnowledgeBinding> loadBindings(ObjectMapper objectMapper) {
        try (InputStream inputStream =
                AccountKnowledgeBindingRepository.class
                        .getClassLoader()
                        .getResourceAsStream(RESOURCE_PATH)) {
            if (inputStream == null) {
                return List.of();
            }
            BindingDocument document = objectMapper.readValue(inputStream, BindingDocument.class);
            return document.bindings == null ? List.of() : List.copyOf(document.bindings);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "failed to load account knowledge bindings from " + RESOURCE_PATH, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class BindingDocument {
        public List<AccountKnowledgeBinding> bindings;
    }

    public record ResolvedBinding(AccountKnowledgeBinding binding, String matchLevel) {}

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
