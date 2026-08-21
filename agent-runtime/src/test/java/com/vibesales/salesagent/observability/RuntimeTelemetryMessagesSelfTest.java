package com.vibesales.salesagent.observability;

import java.util.List;
import java.util.Map;

public final class RuntimeTelemetryMessagesSelfTest {

    public static void main(String[] args) {
        if (!RuntimeTelemetry.captureContent(Map.of())) {
            throw new AssertionError("default must capture");
        }
        if (RuntimeTelemetry.captureContent(
                Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "false"))) {
            throw new AssertionError("false must not capture");
        }

        Object sanitized =
                RuntimeTelemetry.sanitizeValue(
                        Map.of(
                                "_ctx", Map.of("traceparent", "00-abc"),
                                "approval.proof", "signature",
                                "token", "secret",
                                "Authorization", "Bearer private",
                                "body",
                                        Map.of(
                                                "query", "keep",
                                                "nestedToken", "drop",
                                                "visible", "yes")));
        String json = String.valueOf(sanitized);
        if (json.contains("_ctx")
                || json.contains("approval.proof")
                || json.contains("secret")
                || json.contains("Bearer private")
                || json.contains("nestedToken")) {
            throw new AssertionError("sensitive fields leaked: " + json);
        }
        if (!json.contains("visible") || !json.contains("keep")) {
            throw new AssertionError("non-sensitive fields disappeared: " + json);
        }

        Map<String, Object> detail =
                Map.of(
                        "prompt", Map.of("messages", List.of(Map.of("role", "system", "text", "你是导购"))),
                        "input", Map.of("messages", List.of(Map.of("role", "user", "text", "我要精华"))),
                        "output", Map.of("messages", List.of(Map.of("role", "assistant", "text", "推荐这款"))));
        String inputJson = RuntimeTelemetry.messagesJson(RuntimeTelemetry.extractInputMessages(detail));
        String outputJson = RuntimeTelemetry.messagesJson(RuntimeTelemetry.extractOutputMessages(detail));
        if (!inputJson.contains("\"role\":\"system\"")
                || !inputJson.contains("\"role\":\"user\"")
                || !inputJson.contains("我要精华")) {
            throw new AssertionError("input messages shape mismatch: " + inputJson);
        }
        if (!outputJson.contains("\"role\":\"assistant\"") || !outputJson.contains("推荐这款")) {
            throw new AssertionError("output messages shape mismatch: " + outputJson);
        }

        int inputTokens =
                RuntimeTelemetry.usageValue(
                        Map.of("promptTokens", 12, "completionTokens", 6),
                        "inputTokens",
                        "promptTokens");
        int outputTokens =
                RuntimeTelemetry.usageValue(
                        Map.of("promptTokens", 12, "completion_tokens", 6),
                        "outputTokens",
                        "completion_tokens");
        if (inputTokens != 12 || outputTokens != 6) {
            throw new AssertionError(
                    "usage aliases mismatch: input=" + inputTokens + " output=" + outputTokens);
        }

        System.out.println("[PASS] runtime telemetry sanitization + messages + usage aliases");
    }
}
