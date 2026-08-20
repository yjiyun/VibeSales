package com.agentteams.salesagent.model.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 为 OpenAI 兼容模型补充项目级 LLM 埋点数据。
 *
 * <p>AgentScope 默认的 ModelCallStart/End 事件只暴露 replyId / usage，因此这里在 formatter 层缓存
 * 真正发送给模型的 messages 与返回内容，并交给上层时间线节点合并显示。
 */
public final class TelemetryOpenAIFormatter
        implements Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ConcurrentLinkedDeque<LlmTraceSnapshot> PENDING_SNAPSHOTS =
            new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<String> WAITING_REPLY_IDS =
            new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<LlmTraceSnapshot> UNBOUND_COMPLETED_SNAPSHOTS =
            new ConcurrentLinkedDeque<>();
    private static final ConcurrentHashMap<String, LlmTraceSnapshot> COMPLETED_SNAPSHOTS =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<LlmTraceSnapshot> ACTIVE_PARSE_SNAPSHOT = new ThreadLocal<>();

    private final Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> delegate;

    public TelemetryOpenAIFormatter(
            Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<OpenAIMessage> format(List<Msg> msgs) {
        List<OpenAIMessage> formatted = delegate.format(msgs);
        LlmTraceSnapshot snapshot = LlmTraceSnapshot.fromMessages(msgs, formatted);
        ACTIVE_PARSE_SNAPSHOT.set(snapshot);
        PENDING_SNAPSHOTS.addLast(snapshot);
        bindWaitingReplyId(snapshot);
        return formatted;
    }

    public static void bindPendingSnapshot(String replyId) {
        String normalizedReplyId = safe(replyId);
        if (normalizedReplyId.isBlank()) {
            return;
        }
        if (!bindReplyIdToFirstUnboundSnapshot(normalizedReplyId, PENDING_SNAPSHOTS)
                && !bindReplyIdToFirstUnboundSnapshot(normalizedReplyId, UNBOUND_COMPLETED_SNAPSHOTS)
                && !WAITING_REPLY_IDS.contains(normalizedReplyId)) {
            WAITING_REPLY_IDS.addLast(normalizedReplyId);
        }
    }

    private static boolean bindReplyIdToFirstUnboundSnapshot(
            String replyId, ConcurrentLinkedDeque<LlmTraceSnapshot> snapshots) {
        LlmTraceSnapshot snapshot = findFirstUnboundSnapshot(snapshots);
        if (snapshot == null) {
            return false;
        }
        snapshot.bindReplyId(replyId);
        if (UNBOUND_COMPLETED_SNAPSHOTS.removeFirstOccurrence(snapshot)) {
            COMPLETED_SNAPSHOTS.put(replyId, snapshot);
        }
        WAITING_REPLY_IDS.removeFirstOccurrence(replyId);
        return true;
    }

    private static void bindWaitingReplyId(LlmTraceSnapshot snapshot) {
        if (snapshot == null || !snapshot.replyId().isBlank()) {
            return;
        }
        while (!WAITING_REPLY_IDS.isEmpty()) {
            String replyId = safe(WAITING_REPLY_IDS.pollFirst());
            if (replyId.isBlank()) {
                continue;
            }
            snapshot.bindReplyId(replyId);
            return;
        }
    }

    @Override
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        ChatResponse parsed = delegate.parseResponse(response, startTime);
        LlmTraceSnapshot snapshot = ACTIVE_PARSE_SNAPSHOT.get();
        if (snapshot == null) {
            snapshot = PENDING_SNAPSHOTS.pollFirst();
            if (snapshot == null) {
                snapshot = new LlmTraceSnapshot();
            }
            ACTIVE_PARSE_SNAPSHOT.set(snapshot);
        }
        snapshot.recordResponse(response, parsed);
        if (snapshot.isTerminal(response, parsed)) {
            PENDING_SNAPSHOTS.removeFirstOccurrence(snapshot);
            if (!snapshot.replyId().isBlank()) {
                COMPLETED_SNAPSHOTS.put(snapshot.replyId(), snapshot);
            } else {
                UNBOUND_COMPLETED_SNAPSHOTS.addLast(snapshot);
            }
            ACTIVE_PARSE_SNAPSHOT.remove();
        }
        return parsed;
    }

    @Override
    public void applyOptions(
            OpenAIRequest paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions) {
        delegate.applyOptions(paramsBuilder, options, defaultOptions);
    }

    @Override
    public void applyTools(OpenAIRequest paramsBuilder, List<ToolSchema> tools) {
        delegate.applyTools(paramsBuilder, tools);
    }

    @Override
    public void applyTools(
            OpenAIRequest paramsBuilder, List<ToolSchema> tools, String baseUrl, String modelName) {
        delegate.applyTools(paramsBuilder, tools, baseUrl, modelName);
    }

    @Override
    public void applyToolChoice(OpenAIRequest paramsBuilder, ToolChoice toolChoice) {
        delegate.applyToolChoice(paramsBuilder, toolChoice);
    }

    @Override
    public void applyToolChoice(
            OpenAIRequest paramsBuilder,
            ToolChoice toolChoice,
            String baseUrl,
            String modelName) {
        delegate.applyToolChoice(paramsBuilder, toolChoice, baseUrl, modelName);
    }

    public static Map<String, Object> consumeTraceDetail(String replyId) {
        String normalizedReplyId = safe(replyId);
        if (normalizedReplyId.isBlank()) {
            return Map.of();
        }
        LlmTraceSnapshot snapshot = COMPLETED_SNAPSHOTS.remove(normalizedReplyId);
        if (snapshot == null) {
            return Map.of();
        }
        return snapshot.toDetail();
    }

    private static LlmTraceSnapshot findFirstUnboundSnapshot(
            ConcurrentLinkedDeque<LlmTraceSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        for (LlmTraceSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.replyId().isBlank()) {
                return snapshot;
            }
        }
        return null;
    }

    private static final class LlmTraceSnapshot {
        private List<Map<String, Object>> promptMessages = List.of();
        private List<Map<String, Object>> inputMessages = List.of();
        private final StringBuilder outputText = new StringBuilder();
        private List<Map<String, Object>> outputMessages = new ArrayList<>();
        private String model = "";
        private String finishReason = "";
        private Object usage = null;
        private Object metadata = null;
        private String replyId = "";

        private static LlmTraceSnapshot fromMessages(
                List<Msg> rawMessages, List<OpenAIMessage> formattedMessages) {
            LlmTraceSnapshot snapshot = new LlmTraceSnapshot();
            List<Map<String, Object>> prompts = new ArrayList<>();
            List<Map<String, Object>> inputs = new ArrayList<>();
            List<Map<String, Object>> formattedDetails =
                    OBJECT_MAPPER.convertValue(formattedMessages, MESSAGE_LIST_TYPE);
            if (rawMessages != null) {
                for (int index = 0; index < rawMessages.size(); index++) {
                    Msg message = rawMessages.get(index);
                    Map<String, Object> messageDetail =
                            mergeMessageDetail(
                                    messageDetail(message),
                                    index < formattedDetails.size()
                                            ? formattedDetails.get(index)
                                            : Map.of());
                    if (messageDetail.isEmpty()) {
                        continue;
                    }
                    if (message != null && message.getRole() == io.agentscope.core.message.MsgRole.SYSTEM) {
                        prompts.add(messageDetail);
                    } else {
                        inputs.add(messageDetail);
                    }
                }
            }
            if (prompts.isEmpty() && inputs.isEmpty()) {
                for (Map<String, Object> message : formattedDetails) {
                    String role = safe(message.get("role"));
                    if ("system".equals(role)) {
                        prompts.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(message)));
                    } else {
                        inputs.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(message)));
                    }
                }
            }
            snapshot.promptMessages = List.copyOf(prompts);
            snapshot.inputMessages = List.copyOf(inputs);
            return snapshot;
        }

        private void recordResponse(OpenAIResponse response, ChatResponse parsed) {
            if (response != null) {
                if (!safe(response.getModel()).isBlank()) {
                    this.model = safe(response.getModel());
                }
                if (response.getUsage() != null) {
                    this.usage = OBJECT_MAPPER.convertValue(response.getUsage(), Object.class);
                }
                if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                    List<Map<String, Object>> rawChoices = new ArrayList<>();
                    for (OpenAIChoice choice : response.getChoices()) {
                        OpenAIMessage message = choice == null ? null : choice.getEffectiveMessage();
                        if (message != null) {
                            rawChoices.add(OBJECT_MAPPER.convertValue(message, MAP_TYPE));
                            String text = safe(message.getContentAsString());
                            if (!text.isBlank()) {
                                this.outputText.append(text);
                            }
                        }
                        if (choice != null && !safe(choice.getFinishReason()).isBlank()) {
                            this.finishReason = safe(choice.getFinishReason());
                        }
                    }
                    if (!rawChoices.isEmpty()) {
                        this.outputMessages = List.copyOf(rawChoices);
                    }
                }
            }

            if (parsed != null) {
                if (!safe(parsed.getFinishReason()).isBlank()) {
                    this.finishReason = safe(parsed.getFinishReason());
                }
                if (parsed.getUsage() != null) {
                    this.usage = OBJECT_MAPPER.convertValue(parsed.getUsage(), Object.class);
                }
                if (parsed.getMetadata() != null && !parsed.getMetadata().isEmpty()) {
                    this.metadata = OBJECT_MAPPER.convertValue(parsed.getMetadata(), Object.class);
                }
            }
        }

        private boolean isTerminal(OpenAIResponse response, ChatResponse parsed) {
            boolean terminal = false;
            if (parsed != null) {
                if (!safe(parsed.getFinishReason()).isBlank()) {
                    terminal = true;
                } else if (parsed.getUsage() != null
                        && (!outputText.isEmpty() || !outputMessages.isEmpty())) {
                    terminal = true;
                }
            }
            if (!terminal && response != null && response.getChoices() != null) {
                for (OpenAIChoice choice : response.getChoices()) {
                    if (choice != null && !safe(choice.getFinishReason()).isBlank()) {
                        terminal = true;
                        break;
                    }
                }
                if (!terminal
                        && response.getUsage() != null
                        && (!outputText.isEmpty() || !outputMessages.isEmpty())) {
                    terminal = true;
                }
            }
            return terminal;
        }

        private Map<String, Object> toDetail() {
            Map<String, Object> output = new LinkedHashMap<>();
            if (!model.isBlank()) {
                output.put("model", model);
            }
            if (!finishReason.isBlank()) {
                output.put("finishReason", finishReason);
            }
            if (usage != null) {
                output.put("usage", usage);
            }
            if (!outputText.isEmpty()) {
                output.put("text", outputText.toString());
            }
            if (!outputMessages.isEmpty()) {
                output.put("messages", outputMessages);
            }
            if (metadata != null) {
                output.put("metadata", metadata);
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("prompt", Map.of("messages", promptMessages));
            detail.put("input", Map.of("messages", inputMessages));
            detail.put(
                    "output",
                    output.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(output));
            if (!model.isBlank()) {
                detail.put("model", model);
            }
            if (!finishReason.isBlank()) {
                detail.put("finishReason", finishReason);
            }
            if (usage != null) {
                detail.put("usage", usage);
            }
            return java.util.Collections.unmodifiableMap(detail);
        }

        private void bindReplyId(String replyId) {
            if (!safe(replyId).isBlank()) {
                this.replyId = safe(replyId);
            }
        }

        private static String safe(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }

        private static Map<String, Object> messageDetail(Msg message) {
            if (message == null) {
                return Map.of();
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            if (message.getRole() != null) {
                detail.put("role", message.getRole().name().toLowerCase());
            }
            if (!safe(message.getName()).isBlank()) {
                detail.put("name", safe(message.getName()));
            }
            if (!safe(message.getTextContent()).isBlank()) {
                detail.put("text", message.getTextContent());
            }
            if (message.getContent() != null && !message.getContent().isEmpty()) {
                detail.put("content", OBJECT_MAPPER.convertValue(message.getContent(), Object.class));
            }
            if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
                detail.put("metadata", OBJECT_MAPPER.convertValue(message.getMetadata(), Object.class));
            }
            if (!safe(message.getTimestamp()).isBlank()) {
                detail.put("timestamp", message.getTimestamp());
            }
            return java.util.Collections.unmodifiableMap(detail);
        }

        private static Map<String, Object> mergeMessageDetail(
                Map<String, Object> rawDetail, Map<String, Object> formattedDetail) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (formattedDetail != null && !formattedDetail.isEmpty()) {
                merged.putAll(formattedDetail);
            }
            if (rawDetail != null && !rawDetail.isEmpty()) {
                merged.putAll(rawDetail);
            }
            if (!hasVisibleText(merged) && formattedDetail != null && !formattedDetail.isEmpty()) {
                copyIfPresent(formattedDetail, merged, "text");
                copyIfPresent(formattedDetail, merged, "content");
            }
            return merged.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(merged);
        }

        private static boolean hasVisibleText(Map<String, Object> detail) {
            if (detail == null || detail.isEmpty()) {
                return false;
            }
            if (!safe(detail.get("text")).isBlank()) {
                return true;
            }
            Object content = detail.get("content");
            if (content instanceof String text && !safe(text).isBlank()) {
                return true;
            }
            if (!(content instanceof List<?> list)) {
                return false;
            }
            for (Object item : list) {
                if (item instanceof String text && !safe(text).isBlank()) {
                    return true;
                }
                if (item instanceof Map<?, ?> map) {
                    if (!safe(map.get("text")).isBlank() || !safe(map.get("content")).isBlank()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void copyIfPresent(
                Map<String, Object> source, Map<String, Object> target, String key) {
            if (source == null || target == null || key == null) {
                return;
            }
            Object value = source.get(key);
            if (value != null) {
                target.put(key, value);
            }
        }

        private String replyId() {
            return safe(replyId);
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : Objects.toString(value, "").trim();
    }
}
