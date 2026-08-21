package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCapabilityCatalogTest {

    @Test
    void shouldClassifyProjectDynamicKnowledgeTool() {
        assertEquals("project_dynamic", ToolCapabilityCatalog.classify("retrieveKnowledgeBase"));
        assertTrue(ToolCapabilityCatalog.supported().contains("retrieveKnowledgeBase"));
    }
}
