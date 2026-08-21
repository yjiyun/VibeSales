package com.vibesales.salesagent.tool.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

class RetrieveKnowledgeBaseToolRegistrationTest {

    @Test
    void shouldRegisterKnowledgeToolIntoToolkit() {
        Toolkit toolkit = new Toolkit();

        toolkit.registerTool(new RetrieveKnowledgeBaseTool(null));

        assertTrue(toolkit.getToolNames().contains(RetrieveKnowledgeBaseTool.TOOL_NAME));
    }
}
