package com.agentteams.salesagent.web;

import com.agentteams.salesagent.agent.ChatResponse;
import com.agentteams.salesagent.progress.ExecutionProgressListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 管理前端一次调试请求对应的 run、事件历史与 SSE 订阅者。
 *
 * <p>当前默认以内存态承接实时订阅；若注入持久化 store，则同时支持历史回放与刷新后恢复查看。
 */
public final class ChatRunManager {

    private final ConcurrentMap<String, ChatRunState> runs = new ConcurrentHashMap<>();
    private final ChatRunStore store;

    public ChatRunManager() {
        this(ChatRunStore.noop());
    }

    public ChatRunManager(ChatRunStore store) {
        this.store = store == null ? ChatRunStore.noop() : store;
    }

    public ChatRunState createRun() {
        return createRun(
                new ChatRunStore.RunCreate(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""));
    }

    public ChatRunState createRun(ChatRunStore.RunCreate runCreate) {
        ChatRunStore.RunCreate normalized = normalizeCreate(runCreate);
        ChatRunState state = new ChatRunState(normalized.runId(), normalized.createdAt(), normalized, store);
        runs.put(state.runId(), state);
        store.createRun(normalized);
        state.append(
                "orchestration",
                "run.created",
                "start",
                "创建运行",
                null,
                Map.of("createdAt", state.createdAt().toString()),
                false);
        return state;
    }

    public Optional<ChatRunState> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        ChatRunState state = runs.get(runId);
        if (state != null) {
            return Optional.of(state);
        }
        Optional<ChatRunStore.RunSnapshot> snapshot = store.loadRun(runId.trim());
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        ChatRunState restored = ChatRunState.restore(snapshot.get());
        ChatRunState existing = runs.putIfAbsent(restored.runId(), restored);
        return Optional.of(existing == null ? restored : existing);
    }

    public ExecutionProgressListener listenerFor(String runId) {
        return update ->
                find(runId)
                        .ifPresent(
                                state ->
                                        state.append(
                                                update.phase(),
                                                update.step(),
                                                update.status(),
                                                update.label(),
                                                update.elapsedMs(),
                                                update.detail(),
                                                false));
    }

    public boolean persistenceEnabled() {
        return store.enabled();
    }

    public List<ChatRunStore.RunHistoryItem> listRecent(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        Map<String, ChatRunStore.RunHistoryItem> merged = new LinkedHashMap<>();
        for (ChatRunStore.RunHistoryItem item : store.listRecent(normalizedLimit * 2)) {
            merged.put(item.runId(), item);
        }
        for (ChatRunState state : runs.values()) {
            merged.put(state.runId(), state.toHistoryItem(store.enabled()));
        }
        return merged.values().stream()
                .sorted(
                        Comparator.comparing(ChatRunStore.RunHistoryItem::createdAt).reversed()
                                .thenComparing(ChatRunStore.RunHistoryItem::runId))
                .limit(normalizedLimit)
                .toList();
    }

    public void completeRun(String runId, ChatResponse response) {
        find(runId)
                .ifPresent(
                        state -> {
                            state.response = response;
                            state.completed = true;
                            state.status = "completed";
                            state.completedAt = Instant.now();
                            state.append(
                                    "orchestration",
                                    "run.complete",
                                    "end",
                                    "本轮执行完成",
                                    System.currentTimeMillis() - state.createdAt().toEpochMilli(),
                                    responseDetail(response),
                                    true);
                            store.markCompleted(
                                    new ChatRunStore.RunCompletion(
                                            state.runId(),
                                            state.completedAt,
                                            state.status,
                                            state.failureMessage,
                                            response,
                                            state.eventCount()));
                        });
    }

    public void failRun(String runId, Throwable exception) {
        find(runId)
                .ifPresent(
                        state -> {
                            state.failureMessage = exception == null ? "unknown_error" : safeMessage(exception);
                            state.completed = true;
                            state.status = "failed";
                            state.completedAt = Instant.now();
                            state.append(
                                    "orchestration",
                                    "run.complete",
                                    "error",
                                    "本轮执行失败",
                                    System.currentTimeMillis() - state.createdAt().toEpochMilli(),
                                    Map.of(
                                            "error", "chat_handler_failed",
                                            "message", state.failureMessage),
                                    true);
                            store.markCompleted(
                                    new ChatRunStore.RunCompletion(
                                            state.runId(),
                                            state.completedAt,
                                            state.status,
                                            state.failureMessage,
                                            null,
                                            state.eventCount()));
                        });
    }

    private static Map<String, Object> responseDetail(ChatResponse response) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reply", response.reply());
        detail.put("conversationId", response.conversationId());
        detail.put("robotConversationId", response.robotConversationId());
        detail.put("chatUser", response.chatUser());
        detail.put("robotKey", response.robotKey());
        detail.put("conversationName", response.conversationName());
        detail.put("messageId", response.messageId());
        detail.put("recoveryMode", response.recoveryMode());
        detail.put("targetIntent", response.targetIntent());
        detail.put("historySummary", response.historySummary());
        detail.put("profileSummary", response.profileSummary());
        detail.put("queueVersion", response.queueVersion());
        detail.put("resolvedBlueprint", response.resolvedBlueprint());
        return detail;
    }

    private static Map<String, Object> enrichDetail(
            String phase, String step, String label, Map<String, Object> detail) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (detail != null) {
            enriched.putAll(detail);
        }
        String nodeType = inferNodeType(phase, step);
        String nodeName = inferNodeName(step, label, enriched);
        enriched.putIfAbsent("nodeType", nodeType);
        if (!nodeName.isBlank()) {
            enriched.putIfAbsent("nodeName", nodeName);
        }
        // 用 unmodifiableMap 而不是 Map.copyOf：后者无序，会打乱各节点刻意安排的键顺序，
        // 而消费端（工作台时间线"补充明细"）只渲染前若干个键，顺序丢了等于关键字段看不见
        return java.util.Collections.unmodifiableMap(enriched);
    }

    private static String inferNodeType(String phase, String step) {
        String normalizedPhase = phase == null ? "" : phase;
        String normalizedStep = step == null ? "" : step;
        if ("orchestration".equals(normalizedPhase)) {
            if (normalizedStep.startsWith("blueprint.")) {
                return "blueprint";
            }
            if (normalizedStep.startsWith("tool.")) {
                return "orchestration_tool";
            }
            if (normalizedStep.startsWith("rule.")) {
                return "rule";
            }
            if (normalizedStep.startsWith("prompt.")) {
                return "prompt";
            }
            if (normalizedStep.startsWith("run.")) {
                return "run";
            }
            return "orchestration";
        }
        if ("stage".equals(normalizedPhase)) {
            if (normalizedStep.endsWith(".project") || normalizedStep.endsWith(".prompt_compose")) {
                return "prompt";
            }
            if (normalizedStep.startsWith("preload_context.")
                    || normalizedStep.startsWith("result_close.")) {
                return "orchestration_tool";
            }
            return "stage";
        }
        if ("agent".equals(normalizedPhase)) {
            if (normalizedStep.startsWith("model.call")) {
                return "llm";
            }
            if (normalizedStep.startsWith("agent.tool.")) {
                return normalizedStep.contains(".result#") ? "tool_result" : "tool";
            }
            if (normalizedStep.startsWith("agent.abnormal.")) {
                return "abnormal";
            }
            if (normalizedStep.startsWith("agent.run")
                    || (normalizedStep.startsWith("stage.") && normalizedStep.endsWith(".run"))) {
                return "agent";
            }
        }
        return "event";
    }

    private static String inferNodeName(String step, String label, Map<String, Object> detail) {
        Object explicitToolName = detail.get("toolName");
        if (explicitToolName instanceof String text && !text.isBlank()) {
            return text;
        }
        if (step != null && step.startsWith("agent.tool.")) {
            String suffix = step.substring("agent.tool.".length());
            int resultIndex = suffix.indexOf(".result#");
            if (resultIndex >= 0) {
                return suffix.substring(0, resultIndex);
            }
            int index = suffix.indexOf('#');
            if (index >= 0) {
                return suffix.substring(0, index);
            }
            return suffix;
        }
        if (step != null && step.startsWith("tool.")) {
            return switch (step) {
                case "tool.session.ensure" -> "session_scope";
                case "tool.history.load" -> "history_summary";
                case "tool.history.save" -> "history_summary_write";
                case "tool.queue.load" -> "intent_queue";
                case "tool.queue.sync" -> "intent_queue_sync";
                case "tool.profile.load" -> "customer_profile";
                default -> step;
            };
        }
        if (step != null && step.startsWith("preload_context.")) {
            return switch (step) {
                case "preload_context.session" -> "session_scope";
                case "preload_context.history" -> "history_summary";
                case "preload_context.queue" -> "intent_queue";
                case "preload_context.profile" -> "customer_profile";
                case "preload_context.rule_context" -> "rule_context";
                case "preload_context.recovery_signal" -> "recovery_signal";
                default -> step;
            };
        }
        if (step != null && step.startsWith("result_close.")) {
            return switch (step) {
                case "result_close.history" -> "history_summary_write";
                case "result_close.queue" -> "intent_queue_sync";
                default -> step;
            };
        }
        if (step != null && step.startsWith("model.call")) {
            return "llm";
        }
        return label == null ? "" : label;
    }

    private static String safeMessage(Throwable exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception == null ? "unknown_error" : exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private static ChatRunStore.RunCreate normalizeCreate(ChatRunStore.RunCreate runCreate) {
        Instant createdAt = runCreate == null || runCreate.createdAt() == null ? Instant.now() : runCreate.createdAt();
        return new ChatRunStore.RunCreate(
                runCreate == null || safe(runCreate.runId()).isBlank()
                        ? UUID.randomUUID().toString()
                        : safe(runCreate.runId()),
                createdAt,
                runCreate == null ? "" : safe(runCreate.clientCode()),
                runCreate == null ? "" : safe(runCreate.cluster()),
                runCreate == null ? "" : safe(runCreate.sceneCode()),
                runCreate == null ? "" : safe(runCreate.runtimeAgentId()),
                runCreate == null ? "" : safe(runCreate.conversationId()),
                runCreate == null ? "" : safe(runCreate.chatUser()),
                runCreate == null ? "" : safe(runCreate.messagePreview()),
                runCreate == null ? "" : safe(runCreate.selectorMode()),
                runCreate == null ? "" : safe(runCreate.selectionId()),
                runCreate == null ? "" : safe(runCreate.requestJson()));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ChatRunState {
        private final String runId;
        private final Instant createdAt;
        private final ChatRunStore.RunCreate runCreate;
        private final ChatRunStore store;
        private final AtomicLong sequence = new AtomicLong();
        private final List<ChatRunEvent> events = new ArrayList<>();
        private final CopyOnWriteArrayList<Consumer<ChatRunEvent>> subscribers = new CopyOnWriteArrayList<>();
        private volatile boolean completed;
        private volatile Instant completedAt;
        private volatile String status;
        private volatile ChatResponse response;
        private volatile String failureMessage;

        private ChatRunState(
                String runId,
                Instant createdAt,
                ChatRunStore.RunCreate runCreate,
                ChatRunStore store) {
            this.runId = runId;
            this.createdAt = createdAt;
            this.runCreate = runCreate;
            this.store = store;
            this.status = "running";
        }

        private ChatRunState(
                String runId,
                Instant createdAt,
                ChatRunStore.RunCreate runCreate,
                ChatRunStore store,
                long initialSequence,
                List<ChatRunEvent> initialEvents,
                boolean completed,
                Instant completedAt,
                String status,
                ChatResponse response,
                String failureMessage) {
            this.runId = runId;
            this.createdAt = createdAt;
            this.runCreate = runCreate;
            this.store = store;
            this.sequence.set(initialSequence);
            this.events.addAll(initialEvents);
            this.completed = completed;
            this.completedAt = completedAt;
            this.status = safe(status).isBlank() ? (completed ? "completed" : "running") : safe(status);
            this.response = response;
            this.failureMessage = safe(failureMessage);
        }

        private static ChatRunState restore(ChatRunStore.RunSnapshot snapshot) {
            List<ChatRunEvent> initialEvents = snapshot.events() == null ? List.of() : snapshot.events();
            long lastSequence = initialEvents.isEmpty() ? 0 : initialEvents.get(initialEvents.size() - 1).seq();
            boolean completed = !"running".equalsIgnoreCase(safe(snapshot.status()));
            return new ChatRunState(
                    snapshot.run().runId(),
                    snapshot.run().createdAt(),
                    snapshot.run(),
                    ChatRunStore.noop(),
                    lastSequence,
                    initialEvents,
                    completed,
                    snapshot.completedAt(),
                    snapshot.status(),
                    snapshot.response(),
                    snapshot.failureMessage());
        }

        public String runId() {
            return runId;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public synchronized List<ChatRunEvent> snapshotEvents() {
            return List.copyOf(events);
        }

        public boolean completed() {
            return completed;
        }

        public ChatResponse response() {
            return response;
        }

        public String failureMessage() {
            return failureMessage;
        }

        public String status() {
            return status;
        }

        public Instant completedAt() {
            return completedAt;
        }

        public void subscribe(Consumer<ChatRunEvent> subscriber) {
            subscribers.add(subscriber);
        }

        public void unsubscribe(Consumer<ChatRunEvent> subscriber) {
            subscribers.remove(subscriber);
        }

        private synchronized void append(
                String phase,
                String step,
                String status,
                String label,
                Long elapsedMs,
                Map<String, Object> detail,
                boolean terminal) {
            Map<String, Object> enrichedDetail = enrichDetail(phase, step, label, detail);
            ChatRunEvent event =
                    new ChatRunEvent(
                            runId,
                            sequence.incrementAndGet(),
                            phase,
                            step,
                            status,
                            label,
                            String.valueOf(enrichedDetail.get("nodeType")),
                            String.valueOf(enrichedDetail.getOrDefault("nodeName", "")),
                            elapsedMs,
                            enrichedDetail,
                            System.currentTimeMillis(),
                            terminal);
            events.add(event);
            store.appendEvent(event);
            for (Consumer<ChatRunEvent> subscriber : subscribers) {
                subscriber.accept(event);
            }
        }

        private synchronized int eventCount() {
            return events.size();
        }

        private synchronized ChatRunStore.RunHistoryItem toHistoryItem(boolean persisted) {
            return new ChatRunStore.RunHistoryItem(
                    runId,
                    createdAt,
                    completedAt,
                    safe(status).isBlank() ? (completed ? "completed" : "running") : status,
                    runCreate == null ? "" : safe(runCreate.clientCode()),
                    runCreate == null ? "" : safe(runCreate.cluster()),
                    runCreate == null ? "" : safe(runCreate.sceneCode()),
                    runCreate == null ? "" : safe(runCreate.runtimeAgentId()),
                    runCreate == null ? "" : safe(runCreate.conversationId()),
                    runCreate == null ? "" : safe(runCreate.chatUser()),
                    runCreate == null ? "" : safe(runCreate.messagePreview()),
                    runCreate == null ? "" : safe(runCreate.selectorMode()),
                    runCreate == null ? "" : safe(runCreate.selectionId()),
                    response != null && response.resolvedBlueprint() != null
                            ? safe(response.resolvedBlueprint().blueprintId())
                            : "",
                    response == null ? "" : previewText(response.reply(), 120),
                    safe(failureMessage),
                    eventCount(),
                    persisted);
        }

        private static String previewText(String value, int maxLength) {
            String normalized = safe(value);
            if (normalized.length() <= maxLength) {
                return normalized;
            }
            return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
        }
    }
}
