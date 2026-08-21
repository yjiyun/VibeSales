package com.vibesales.salesagent.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibesales.salesagent.context.CustomerContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime 侧最小 OTel/GenAI 上报。
 */
public final class RuntimeTelemetry {

    public static final String DEFAULT_SERVICE_NAME = "vibe-sales-runtime";
    private static final int MAX_TEXT_LENGTH = 8000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final boolean CAPTURE_CONTENT = captureContent(System.getenv());
    private static final InheritableThreadLocal<Deque<SpanFrame>> SPAN_STACK =
            new InheritableThreadLocal<>() {
                @Override
                protected Deque<SpanFrame> initialValue() {
                    return new ArrayDeque<>();
                }
            };
    private static final InheritableThreadLocal<RunMeta> RUN_META = new InheritableThreadLocal<>();
    private static volatile boolean otlpMode = false;

    private RuntimeTelemetry() {}

    public static boolean install(Map<String, String> env) {
        String mode = env.getOrDefault("AGENTLOOP_EXPORTER", "off").trim().toLowerCase();
        if (!"on".equals(mode)) {
            return false;
        }
        String protocol = env.getOrDefault("AGENTLOOP_PROTOCOL", "otlp").trim().toLowerCase();
        if (!"otlp".equals(protocol)) {
            return false;
        }
        String endpoint = trim(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
        if (endpoint == null) {
            throw new IllegalStateException(
                    "AGENTLOOP_PROTOCOL=otlp + EXPORTER=on requires OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
        }
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
        for (Map.Entry<String, String> header : parseHeaders(env).entrySet()) {
            exporter.addHeader(header.getKey(), header.getValue());
        }
        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter.build()).build())
                        .setResource(Resource.getDefault().merge(resource(env)))
                        .build();
        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () ->
                                        sdk.getSdkTracerProvider()
                                                .shutdown()
                                                .join(10, java.util.concurrent.TimeUnit.SECONDS)));
        otlpMode = true;
        System.err.println(
                "[runtime-telemetry] OTLP on: service="
                        + serviceName(env)
                        + " endpoint="
                        + safeHost(endpoint));
        return true;
    }

    public static boolean otlpMode() {
        return otlpMode;
    }

    public static RunScope startAgentRun(
            String defaultAgentName, CustomerContext customerContext, String userMessage) {
        String agentName = trimToEmpty(defaultAgentName);
        String sessionId =
                customerContext == null ? "" : trimToEmpty(customerContext.normalizedConversationId());
        Span span =
                tracer()
                        .spanBuilder(SpanAliases.runSpanName())
                        .setParent(parentContext())
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("gen_ai.span.kind", "AGENT")
                        .setAttribute("gen_ai.operation.name", "invoke_agent")
                        .setAttribute("gen_ai.agent.name", agentName.isBlank() ? DEFAULT_SERVICE_NAME : agentName)
                        .setAttribute("gen_ai.session.id", sessionId)
                        .setAttribute(
                                "vibesales.run_id",
                                customerContext == null ? "" : customerContext.normalizedConversationId())
                        .setAttribute(
                                "vibesales.client_code",
                                customerContext == null ? "" : customerContext.normalizedClientCode())
                        .setAttribute(
                                "vibesales.runtime_agent_id",
                                customerContext == null ? "" : customerContext.normalizedRuntimeAgentId())
                        .startSpan();
        if (CAPTURE_CONTENT && userMessage != null && !userMessage.isBlank()) {
            span.setAttribute("gen_ai.input.messages", messagesJson(List.of(message("user", userMessage))));
        }
        Scope scope = Context.current().with(span).makeCurrent();
        SPAN_STACK.get().addLast(new SpanFrame(span, scope));
        RUN_META.set(
                new RunMeta(
                        sessionId,
                        customerContext == null ? "" : customerContext.normalizedClientCode(),
                        agentName));
        return new RunScope(span, scope);
    }

    public static LlmScope startLlmCall(String replyId, String modelLabel) {
        Span span =
                tracer()
                        .spanBuilder(SpanAliases.llmSpanName())
                        .setParent(parentContext())
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("gen_ai.span.kind", "LLM")
                        .setAttribute("gen_ai.operation.name", "chat")
                        .startSpan();
        attachRunMeta(span);
        if (!trimToEmpty(modelLabel).isBlank()) {
            span.setAttribute("vibesales.model.label", trimToEmpty(modelLabel));
        }
        if (!trimToEmpty(replyId).isBlank()) {
            span.setAttribute("vibesales.reply_id", trimToEmpty(replyId));
        }
        Scope scope = Context.current().with(span).makeCurrent();
        SPAN_STACK.get().addLast(new SpanFrame(span, scope));
        return new LlmScope(span, scope);
    }

    public static ToolScope startToolCall(String toolName, Map<String, Object> input) {
        String serializedInput = toJsonString(sanitizeValue(input));
        String displayName =
                SpanAliases.toolSpanName(toolName, input == null ? null : input.get("path"));
        Span span =
                tracer()
                        .spanBuilder(displayName)
                        .setParent(parentContext())
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("gen_ai.span.kind", "TOOL")
                        .setAttribute("gen_ai.operation.name", "execute_tool")
                        .setAttribute("gen_ai.tool.name", trimToEmpty(toolName))
                        .startSpan();
        attachRunMeta(span);
        if (CAPTURE_CONTENT && !serializedInput.isBlank()) {
            span.setAttribute("gen_ai.tool.call.arguments", serializedInput);
        }
        Scope scope = Context.current().with(span).makeCurrent();
        SPAN_STACK.get().addLast(new SpanFrame(span, scope));
        return new ToolScope(span, scope);
    }

    static boolean captureContent(Map<String, String> env) {
        String value =
                env.getOrDefault("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "span_and_event")
                        .trim()
                        .toLowerCase();
        return !(value.equals("false") || value.equals("none") || value.isEmpty());
    }

    static Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = trimToEmpty(entry.getKey());
                if (shouldDropKey(key)) {
                    continue;
                }
                sanitized.put(key, sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeValue(java.lang.reflect.Array.get(value, index)));
            }
            return sanitized;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString();
            return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
        }
        return value;
    }

    static String messagesJson(List<Map<String, Object>> messages) {
        try {
            return JSON.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize messages failed", e);
        }
    }

    static List<Map<String, Object>> extractInputMessages(Map<String, Object> detail) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.addAll(readMessages(detail == null ? null : detail.get("prompt")));
        messages.addAll(readMessages(detail == null ? null : detail.get("input")));
        return messages;
    }

    static List<Map<String, Object>> extractOutputMessages(Map<String, Object> detail) {
        List<Map<String, Object>> messages = readMessages(detail == null ? null : detail.get("output"));
        if (!messages.isEmpty()) {
            return messages;
        }
        String outputText = extractText(detail == null ? null : detail.get("output"));
        if (outputText.isBlank()) {
            return List.of();
        }
        return List.of(message("assistant", outputText));
    }

    static int usageValue(Object usage, String... keys) {
        if (!(usage instanceof Map<?, ?> map)) {
            return 0;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    // Ignore malformed values and keep searching other aliases.
                }
            }
        }
        return 0;
    }

    private static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("com.vibesales.salesagent.runtime", "1.0.0");
    }

    private static void attachRunMeta(Span span) {
        RunMeta meta = RUN_META.get();
        if (meta == null) {
            return;
        }
        if (!meta.sessionId().isBlank()) {
            span.setAttribute("gen_ai.session.id", meta.sessionId());
            span.setAttribute("vibesales.run_id", meta.sessionId());
        }
        if (!meta.clientCode().isBlank()) {
            span.setAttribute("vibesales.client_code", meta.clientCode());
        }
        if (!meta.agentName().isBlank()) {
            span.setAttribute("gen_ai.agent.name", meta.agentName());
        }
    }

    private static Context parentContext() {
        Deque<SpanFrame> stack = SPAN_STACK.get();
        SpanFrame frame = stack == null ? null : stack.peekLast();
        return frame == null ? Context.current() : Context.current().with(frame.span());
    }

    private static Resource resource(Map<String, String> env) {
        AttributesBuilder attrs = Attributes.builder();
        attrs.put("service.name", serviceName(env));
        String raw = trim(env.get("OTEL_RESOURCE_ATTRIBUTES"));
        if (raw != null) {
            for (String pair : raw.split(",")) {
                int idx = pair.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = pair.substring(0, idx).trim();
                String value = pair.substring(idx + 1).trim();
                if (!key.isEmpty() && !"service.name".equals(key)) {
                    attrs.put(key, value);
                }
            }
        }
        return Resource.create(attrs.build());
    }

    private static Map<String, String> parseHeaders(Map<String, String> env) {
        Map<String, String> headers = new LinkedHashMap<>();
        String raw = trim(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
        if (raw != null) {
            for (String pair : raw.split(",")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    headers.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            }
            return headers;
        }
        String licenseKey = trim(env.get("ARMS_LICENSE_KEY"));
        if (licenseKey != null) {
            headers.put("x-arms-license-key", licenseKey);
        }
        return headers;
    }

    private static String serviceName(Map<String, String> env) {
        String name = trim(env.get("OTEL_SERVICE_NAME"));
        return name != null ? name : DEFAULT_SERVICE_NAME;
    }

    private static boolean shouldDropKey(String key) {
        String normalized = trimToEmpty(key).toLowerCase();
        return normalized.equals("_ctx")
                || normalized.equals("authorization")
                || normalized.equals("approval.proof")
                || normalized.endsWith(".token")
                || normalized.contains("token")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.equals("traceparent");
    }

    private static List<Map<String, Object>> readMessages(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object messages = map.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = toMap(item);
            String role = trimToEmpty(raw.get("role"));
            String content = extractText(raw);
            if (content.isBlank()) {
                continue;
            }
            normalized.add(message(role.isBlank() ? "user" : role, content));
        }
        return normalized;
    }

    private static Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(trimToEmpty(entry.getKey()), entry.getValue());
            }
            return converted;
        }
        return JSON.convertValue(value, MAP_TYPE);
    }

    private static String extractText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            String direct = firstNonBlank(map.get("text"), map.get("content"));
            if (!direct.isBlank()) {
                return direct;
            }
            Object messages = map.get("messages");
            if (messages instanceof List<?> list && !list.isEmpty()) {
                return extractText(list.get(0));
            }
            return "";
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                String text = extractText(item);
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return trimToEmpty(value);
    }

    private static Map<String, Object> message(String role, String content) {
        return Map.of(
                "role", trimToEmpty(role).isBlank() ? "user" : trimToEmpty(role),
                "parts", List.of(Map.of("type", "text", "content", truncate(content))));
    }

    private static String toJsonString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return truncate(text);
        }
        try {
            return truncate(JSON.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return truncate(String.valueOf(value));
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = trimToEmpty(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safeHost(String endpoint) {
        try {
            return java.net.URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "(invalid)";
        }
    }

    public static final class RunScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private String agentName = "";

        private RunScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void setAgentName(String agentName) {
            this.agentName = trimToEmpty(agentName);
            if (!this.agentName.isBlank()) {
                span.setAttribute("gen_ai.agent.name", this.agentName);
                RunMeta current = RUN_META.get();
                if (current != null) {
                    RUN_META.set(new RunMeta(current.sessionId(), current.clientCode(), this.agentName));
                }
            }
        }

        public void success(String output) {
            if (CAPTURE_CONTENT && output != null && !output.isBlank()) {
                span.setAttribute(
                        "gen_ai.output.messages",
                        messagesJson(List.of(message("assistant", output))));
            }
            span.setStatus(StatusCode.OK);
        }

        public void failure(Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error == null ? "RuntimeError" : error.getClass().getSimpleName());
        }

        @Override
        public void close() {
            pop(span, scope);
            RUN_META.remove();
        }
    }

    public static final class ToolScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private ToolScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void success(Object output) {
            if (CAPTURE_CONTENT) {
                String serialized = toJsonString(sanitizeValue(output));
                if (!serialized.isBlank()) {
                    span.setAttribute("gen_ai.tool.call.result", serialized);
                }
            }
            span.setStatus(StatusCode.OK);
        }

        public void failure(Throwable error, Object output) {
            if (CAPTURE_CONTENT && output != null) {
                String serialized = toJsonString(sanitizeValue(output));
                if (!serialized.isBlank()) {
                    span.setAttribute("gen_ai.tool.call.result", serialized);
                }
            }
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error == null ? "ToolError" : error.getClass().getSimpleName());
        }

        @Override
        public void close() {
            pop(span, scope);
        }
    }

    public static final class LlmScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private boolean finished;

        private LlmScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void success(Map<String, Object> detail, Object usageValue, String fallbackOutput) {
            if (finished) {
                return;
            }
            finished = true;
            if (CAPTURE_CONTENT) {
                List<Map<String, Object>> inputMessages = extractInputMessages(detail);
                List<Map<String, Object>> outputMessages = extractOutputMessages(detail);
                if (!inputMessages.isEmpty()) {
                    span.setAttribute("gen_ai.input.messages", messagesJson(inputMessages));
                }
                if (!outputMessages.isEmpty()) {
                    span.setAttribute("gen_ai.output.messages", messagesJson(outputMessages));
                } else if (!trimToEmpty(fallbackOutput).isBlank()) {
                    span.setAttribute(
                            "gen_ai.output.messages",
                            messagesJson(List.of(message("assistant", fallbackOutput))));
                }
            }
            String model = trimToEmpty(detail == null ? null : detail.get("model"));
            if (!model.isBlank()) {
                span.setAttribute("gen_ai.request.model", model);
            }
            int inputTokens =
                    usageValue(
                            usageValue,
                            "inputTokens",
                            "input_tokens",
                            "promptTokens",
                            "prompt_tokens");
            int outputTokens =
                    usageValue(
                            usageValue,
                            "outputTokens",
                            "output_tokens",
                            "completionTokens",
                            "completion_tokens");
            if (inputTokens > 0) {
                span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
            }
            if (outputTokens > 0) {
                span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
            }
            span.setStatus(StatusCode.OK);
        }

        public void failure(Throwable error) {
            if (finished) {
                return;
            }
            finished = true;
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error == null ? "LlmError" : error.getClass().getSimpleName());
        }

        @Override
        public void close() {
            pop(span, scope);
        }
    }

    private static void pop(Span span, Scope scope) {
        Deque<SpanFrame> stack = SPAN_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            stack.removeLast();
        }
        try {
            scope.close();
        } finally {
            span.end();
            if (stack != null && stack.isEmpty()) {
                SPAN_STACK.remove();
            }
        }
    }

    private record SpanFrame(Span span, Scope scope) {}

    private record RunMeta(String sessionId, String clientCode, String agentName) {}
}
