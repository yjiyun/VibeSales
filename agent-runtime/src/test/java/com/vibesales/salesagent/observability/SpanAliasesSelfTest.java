package com.vibesales.salesagent.observability;

import java.util.List;

public final class SpanAliasesSelfTest {

    public static void main(String[] args) {
        List<String> names =
                List.of(
                        SpanAliases.runSpanName(),
                        SpanAliases.llmSpanName(),
                        SpanAliases.toolSpanName("marketing_agent_runtime", "/history-summary"),
                        SpanAliases.toolSpanName("marketing_agent_runtime", "/intent-queue/sync"),
                        SpanAliases.toolSpanName("unknown_tool", "/custom"));
        for (String name : names) {
            if (!containsCjk(name)) {
                throw new AssertionError("span name is not Chinese: " + name);
            }
        }
        if (SpanAliases.toolSpanName("marketing_agent_runtime", "/customer-profile")
                .contains("marketing_agent_runtime")) {
            throw new AssertionError("tool alias leaked english tool name");
        }
        System.out.println("[PASS] runtime span aliases stay Chinese");
    }

    private static boolean containsCjk(String value) {
        return value != null && value.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }
}
