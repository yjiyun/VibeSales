package com.vibesales.salesagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccountKnowledgeBindingRepositoryTest {

    private final AccountKnowledgeBindingRepository repository =
            new AccountKnowledgeBindingRepository();

    @Test
    void shouldResolveExactTenantKnowledgeBinding() {
        AccountKnowledgeBindingRepository.ResolvedBinding resolved =
                repository.resolve("yjiyuncom", "test").orElseThrow();

        assertEquals(AccountKnowledgeBindingRepository.MATCH_EXACT, resolved.matchLevel());
        assertEquals("yjiyuncom", resolved.binding().clientCodeOrEmpty());
        assertEquals("test", resolved.binding().clusterOrEmpty());
        assertEquals("guyu-default", resolved.binding().defaultKnowledgeBase().orElseThrow().knowledgeBaseCodeOrEmpty());
        assertTrue(resolved.binding().available());
    }

    @Test
    void shouldReturnEmptyWhenTenantHasNoKnowledgeBinding() {
        assertTrue(repository.resolve("no-such-tenant", "test").isEmpty());
    }
}
