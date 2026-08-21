package com.vibesales.salesagent.tool.telemetry;

import com.vibesales.salesagent.observability.RuntimeTelemetry;
import com.vibesales.salesagent.progress.ExecutionProgressListener;
import com.vibesales.salesagent.progress.ExecutionProgressUpdate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Agent 内部自定义 Tool 的统一埋点入口。
 *
 * <p>AgentScope 默认的 {@code ToolCallStartEvent} 不暴露完整原始入参；项目自建 Tool 如果希望在 SSE
 * 时间线里看到完整结构化输入/输出，必须主动经过这里上报。
 */
public final class ToolTelemetry {

    private static final InheritableThreadLocal<State> CURRENT = new InheritableThreadLocal<>();

    private ToolTelemetry() {}

    public static Scope install(ExecutionProgressListener progressListener) {
        State previous = CURRENT.get();
        CURRENT.set(new State(progressListener == null ? ExecutionProgressListener.noop() : progressListener));
        return new Scope(previous);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static <T> T withParentNode(
            String nodeId, String nodeType, String nodeName, Supplier<T> invocation) {
        return withParentNode(nodeId, nodeType, nodeName, "node", "", invocation);
    }

    public static <T> T withParentNode(
            String nodeId,
            String nodeType,
            String nodeName,
            String nodeLayer,
            String stageKey,
            Supplier<T> invocation) {
        State state = CURRENT.get();
        if (state == null) {
            return invocation.get();
        }
        NodeContext parent = state.stack.peekLast();
        String inheritedStageKey =
                !safeStageKey(stageKey).isEmpty()
                        ? safeStageKey(stageKey)
                        : parent == null ? "" : parent.stageKey();
        NodeContext current =
                new NodeContext(
                        safeNodeId(nodeId),
                        safeNodeType(nodeType),
                        safeNodeName(nodeName),
                        parent == null ? "" : parent.nodeId(),
                        state.stack.size(),
                        safeNodeLayer(nodeLayer),
                        inheritedStageKey);
        state.stack.addLast(current);
        try {
            return invocation.get();
        } finally {
            state.stack.removeLast();
        }
    }

    public static <T> T trace(
            String toolName,
            Map<String, Object> input,
            Supplier<T> invocation,
            Function<T, Map<String, Object>> outputMapper) {
        return traceNode("tool", toolName, "工具 " + safeToolName(toolName), input, invocation, outputMapper);
    }

    public static <T> T traceApi(
            String apiName,
            String method,
            String path,
            Map<String, Object> input,
            Supplier<T> invocation,
            Function<T, Map<String, Object>> outputMapper) {
        String displayName = safeToolName(apiName);
        String label = safeToolName(method) + " " + safeNodeName(path);
        Map<String, Object> normalizedInput = new LinkedHashMap<>();
        normalizedInput.put("apiName", displayName);
        normalizedInput.put("method", safeToolName(method));
        normalizedInput.put("path", safeNodeName(path));
        if (input != null && !input.isEmpty()) {
            normalizedInput.putAll(input);
        }
        return traceNode("api_call", displayName, label, normalizedInput, invocation, outputMapper);
    }

    public static String currentNodeId() {
        State state = CURRENT.get();
        if (state == null || state.stack.isEmpty()) {
            return "";
        }
        return state.stack.peekLast().nodeId();
    }

    public static String currentStageKey() {
        State state = CURRENT.get();
        if (state == null || state.stack.isEmpty()) {
            return "";
        }
        return state.stack.peekLast().stageKey();
    }

    private static <T> T traceNode(
            String nodeType,
            String nodeName,
            String label,
            Map<String, Object> input,
            Supplier<T> invocation,
            Function<T, Map<String, Object>> outputMapper) {
        State state = CURRENT.get();
        if (state == null) {
            return invocation.get();
        }

        String displayName = safeNodeName(nodeName);
        String normalizedName = normalizeToolName(displayName);
        long sequence = state.sequence.incrementAndGet();
        String traceId = safeNodeType(nodeType) + "-" + sequence;
        long startedAt = System.currentTimeMillis();
        NodeContext parent = state.stack.peekLast();
        NodeContext current =
                new NodeContext(
                        traceId,
                        safeNodeType(nodeType),
                        displayName,
                        parent == null ? "" : parent.nodeId(),
                        state.stack.size(),
                        "child_call",
                        parent == null ? "" : parent.stageKey());

        state.stack.addLast(current);
        RuntimeTelemetry.ToolScope telemetryScope =
                RuntimeTelemetry.startToolCall(displayName, immutable(input));
        state.progressListener.onUpdate(
                new ExecutionProgressUpdate(
                        "agent",
                        "agent.tool." + normalizedName + "#" + traceId,
                        "start",
                        label,
                        null,
                        startDetail(current, immutable(input))));

        try {
            T result = invocation.get();
            telemetryScope.success(outputMapper.apply(result));
            state.progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "agent",
                            "agent.tool." + normalizedName + ".result#" + traceId,
                            "end",
                            label,
                            System.currentTimeMillis() - startedAt,
                            endDetail(current, immutable(outputMapper.apply(result)))));
            return result;
        } catch (RuntimeException exception) {
            telemetryScope.failure(
                    exception,
                    Map.of(
                            "error",
                            Map.of(
                                    "type", exception.getClass().getSimpleName(),
                                    "message", safeMessage(exception))));
            state.progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "agent",
                            "agent.tool." + normalizedName + ".result#" + traceId,
                            "error",
                            label,
                            System.currentTimeMillis() - startedAt,
                            errorDetail(current, immutable(input), exception)));
            throw exception;
        } finally {
            telemetryScope.close();
            state.stack.removeLast();
        }
    }

    private static Map<String, Object> startDetail(NodeContext context, Map<String, Object> input) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolName", context.nodeName());
        detail.put("toolTraceId", context.nodeId());
        detail.put("telemetrySource", "custom_tool");
        detail.put("payloadMode", "full_json");
        detail.put("nodeId", context.nodeId());
        detail.put("parentNodeId", context.parentNodeId());
        detail.put("nodeType", context.nodeType());
        detail.put("nodeName", context.nodeName());
        detail.put("treeDepth", context.treeDepth());
        detail.put("nodeLayer", context.nodeLayer());
        detail.put("stageKey", context.stageKey());
        detail.put("input", input);
        return java.util.Collections.unmodifiableMap(detail);
    }

    private static Map<String, Object> endDetail(NodeContext context, Map<String, Object> output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolName", context.nodeName());
        detail.put("toolTraceId", context.nodeId());
        detail.put("telemetrySource", "custom_tool");
        detail.put("payloadMode", "full_json");
        detail.put("nodeId", context.nodeId());
        detail.put("parentNodeId", context.parentNodeId());
        detail.put("nodeType", context.nodeType());
        detail.put("nodeName", context.nodeName());
        detail.put("treeDepth", context.treeDepth());
        detail.put("nodeLayer", context.nodeLayer());
        detail.put("stageKey", context.stageKey());
        detail.put("output", output);
        return java.util.Collections.unmodifiableMap(detail);
    }

    private static Map<String, Object> errorDetail(
            NodeContext context, Map<String, Object> input, RuntimeException exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolName", context.nodeName());
        detail.put("toolTraceId", context.nodeId());
        detail.put("telemetrySource", "custom_tool");
        detail.put("payloadMode", "full_json");
        detail.put("nodeId", context.nodeId());
        detail.put("parentNodeId", context.parentNodeId());
        detail.put("nodeType", context.nodeType());
        detail.put("nodeName", context.nodeName());
        detail.put("treeDepth", context.treeDepth());
        detail.put("nodeLayer", context.nodeLayer());
        detail.put("stageKey", context.stageKey());
        detail.put("input", input);
        detail.put(
                "error",
                Map.of(
                        "type", exception.getClass().getSimpleName(),
                        "message", safeMessage(exception)));
        return java.util.Collections.unmodifiableMap(detail);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String safeToolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "custom_tool" : toolName.trim();
    }

    private static String safeNodeType(String nodeType) {
        return nodeType == null || nodeType.isBlank() ? "event" : nodeType.trim();
    }

    private static String safeNodeName(String nodeName) {
        return nodeName == null || nodeName.isBlank() ? "unnamed" : nodeName.trim();
    }

    private static String safeNodeId(String nodeId) {
        return nodeId == null || nodeId.isBlank() ? "node-" + System.nanoTime() : nodeId.trim();
    }

    private static String safeNodeLayer(String nodeLayer) {
        return nodeLayer == null || nodeLayer.isBlank() ? "node" : nodeLayer.trim();
    }

    private static String safeStageKey(String stageKey) {
        return stageKey == null ? "" : stageKey.trim();
    }

    private static String normalizeToolName(String toolName) {
        StringBuilder builder = new StringBuilder();
        for (char ch : toolName.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '-'
                    || ch == '.') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception == null ? "unknown_error" : exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
                return;
            }
            CURRENT.set(previous);
        }
    }

    private record State(
            ExecutionProgressListener progressListener,
            AtomicLong sequence,
            Deque<NodeContext> stack) {
        private State(ExecutionProgressListener progressListener) {
            this(progressListener, new AtomicLong(), new ArrayDeque<>());
        }
    }

    private record NodeContext(
            String nodeId,
            String nodeType,
            String nodeName,
            String parentNodeId,
            int treeDepth,
            String nodeLayer,
            String stageKey) {}
}
