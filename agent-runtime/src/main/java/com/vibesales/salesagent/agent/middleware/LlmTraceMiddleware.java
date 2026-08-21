package com.vibesales.salesagent.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 采集每次模型调用真实的提示词、输入与输出，供执行时间线的 LLM 节点展示。
 *
 * <p><b>为什么挂在 {@code onModelCall} 而不是 Formatter 层。</b>先前的实现（{@code
 * TelemetryOpenAIFormatter}）在 {@code Formatter} 上装饰，靠 {@code ThreadLocal} + 一组 static 队列
 * 把 {@code format(...)} 抓到的提示词和 {@code parseResponse(...)} 抓到的响应关联起来。这条路走不通，
 * 原因是硬的：
 *
 * <ul>
 *   <li>{@code OpenAIChatModel} 在调用方线程上直接调 {@code formatter.format(...)}，但把
 *       {@code parseResponse(...)} 放在 {@code Flux.defer(...).subscribeOn(boundedElastic())} 里，
 *       ThreadLocal 跨不过这个线程边界；
 *   <li>流式模式下框架强制开启 {@code stream_options.include_usage}，服务端会在 {@code finish_reason}
 *       之后补发一个 {@code choices:[]} 的 usage chunk。这个 chunk 会造出一个既不 terminal 也不被清理的
 *       空快照，永久留在 boundedElastic worker 的 ThreadLocal 里，被下一轮请求复用 —— 于是"输出完整、
 *       提示词全空"；
 *   <li>{@code Formatter} 层拿不到 {@code replyId}，只能靠队列顺序猜关联；而 harness 内部的 memory
 *       flush / 会话压缩 / 记忆整合各自还会并发调 {@code model.stream(...)}，共用同一批 static 队列。
 * </ul>
 *
 * <p>{@code onModelCall} 天生就是"一次模型调用一个作用域"：{@link ModelCallInput#messages()} 就是本次
 * 真正发给模型的消息（已经过 {@code onSystemPrompt} 拼装），而 {@code next.apply(input)} 返回的事件流
 * 里就包含 {@link ModelCallStartEvent}（{@code replyId}）、{@link TextBlockDeltaEvent}（输出正文）与
 * {@link ModelCallEndEvent}（usage）。快照因此是 lambda 内的局部对象，不需要 ThreadLocal、不跨请求串位，
 * 也天然屏蔽了 harness 内部那些不属于任何时间线节点的模型调用。
 *
 * <p>快照按 {@code replyId} 暂存，由编排层的时间线累加器在收到 {@link ModelCallEndEvent} 时
 * {@link #consumeTraceDetail(String)} 取走（取走即删）。没跑到 end 事件的调用（异常/取消）在流终止时
 * 自动回收。
 */
public final class LlmTraceMiddleware implements MiddlewareBase {

    /**
     * 单条提示词 / 输入消息正文的展示上限。
     *
     * <p>这里给到 16000 而不是沿用流式文本的 4000：实测本工程的系统提示词（AGENTS.md + SOUL.md +
     * 恢复上下文块）单条就有 6000+ 字符，用 4000 截断会把最需要排查的尾部切掉——而"提示词到底注入了
     * 什么"正是这套埋点存在的理由。上限按<b>单条消息</b>生效，长历史仍然逐条受控。
     */
    private static final int MAX_MESSAGE_TEXT_LENGTH = 16000;

    /** 流式累加文本（输出正文 / 思考 / 工具参数）的上限，避免边收边涨把 SSE 事件撑爆。 */
    private static final int MAX_STREAM_TEXT_LENGTH = 4000;

    /**
     * 未被取走的快照上限。正常一轮请求只会留下个位数条目，这个上限只是兜底：万一编排层某条路径
     * 忘了消费，最老的会被自动淘汰，而不是无界堆积。
     */
    private static final int MAX_PENDING_TRACES = 64;

    /**
     * replyId -> 本次模型调用的快照。正常路径由 {@link #consumeTraceDetail(String)} 取走即删。
     *
     * <p>用带 FIFO 淘汰的 {@code LinkedHashMap} 而不是 {@code ConcurrentHashMap}，是因为快照的存活
     * 期不能绑定在模型调用流的生命周期上：{@code streamEvents(...)} 把事件经 {@code FluxSink} 转发给
     * 编排层，模型调用内层的 {@code doFinally} 完全可能早于下游收到 {@link ModelCallEndEvent} 就触发。
     * 所以正常收尾的快照必须留到被消费，只有"没跑到 end 事件"（异常/取消）的才立即回收，
     * 剩下的靠这个上限兜底。
     */
    private final Map<String, ModelCallTrace> tracesByReplyId =
            Collections.synchronizedMap(
                    new LinkedHashMap<>() {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, ModelCallTrace> eldest) {
                            return size() > MAX_PENDING_TRACES;
                        }
                    });

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 提示词在进入模型之前就已经确定，先算好；replyId 要等 ModelCallStartEvent 才知道，
        // 所以这里只建快照，绑定推迟到事件流里做。
        ModelCallTrace trace = ModelCallTrace.fromInput(input);
        return next.apply(input)
                .doOnNext(event -> trace.accept(event, tracesByReplyId))
                .doFinally(signal -> trace.releaseIfIncomplete(tracesByReplyId));
    }

    /**
     * 取出并移除指定 {@code replyId} 的快照明细。
     *
     * @return 时间线节点可直接 {@code putAll} 的明细；未找到时返回空 Map（调用方据此走占位分支）
     */
    public Map<String, Object> consumeTraceDetail(String replyId) {
        String normalized = safe(replyId);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        ModelCallTrace trace = tracesByReplyId.remove(normalized);
        return trace == null ? Map.of() : trace.toDetail();
    }

    /** 当前暂存的快照数量，仅用于测试与排查泄漏。 */
    public int pendingTraceCount() {
        return tracesByReplyId.size();
    }

    /**
     * 一次模型调用的输入/输出快照。
     *
     * <p>实例只在单条 {@code onModelCall} 事件流里被访问。Reactor 的 {@code doOnNext} 对同一个订阅是
     * 串行的，但 {@code onNext} 与 {@code doFinally} 可能落在不同线程上，所以可变字段的读写统一加锁，
     * 保证累加结果对 {@link #consumeTraceDetail} 所在线程可见。
     */
    private static final class ModelCallTrace {

        private final List<Map<String, Object>> promptMessages;
        private final List<Map<String, Object>> inputMessages;
        private final List<String> toolNames;
        private final StringBuilder outputText = new StringBuilder();
        private final StringBuilder thinkingText = new StringBuilder();
        private final List<Map<String, Object>> toolCalls = new ArrayList<>();
        private final Map<String, Map<String, Object>> toolCallsById = new LinkedHashMap<>();
        private final Object lock = new Object();

        private String replyId = "";
        private ChatUsage usage;
        private boolean completed;

        private ModelCallTrace(
                List<Map<String, Object>> promptMessages,
                List<Map<String, Object>> inputMessages,
                List<String> toolNames) {
            this.promptMessages = promptMessages;
            this.inputMessages = inputMessages;
            this.toolNames = toolNames;
        }

        private static ModelCallTrace fromInput(ModelCallInput input) {
            List<Map<String, Object>> prompts = new ArrayList<>();
            List<Map<String, Object>> inputs = new ArrayList<>();
            if (input != null && input.messages() != null) {
                for (Msg message : input.messages()) {
                    Map<String, Object> detail = messageDetail(message);
                    if (detail.isEmpty()) {
                        continue;
                    }
                    // 系统提示词归 prompt，其余（USER / ASSISTANT / 工具结果回填）归 input：
                    // 时间线右侧「提示词」和「输入」就是这两段。
                    if (message.getRole() == MsgRole.SYSTEM) {
                        prompts.add(detail);
                    } else {
                        inputs.add(detail);
                    }
                }
            }
            List<String> toolNames = new ArrayList<>();
            if (input != null && input.tools() != null) {
                for (ToolSchema tool : input.tools()) {
                    String name = tool == null ? "" : safe(tool.getName());
                    if (!name.isEmpty()) {
                        toolNames.add(name);
                    }
                }
            }
            return new ModelCallTrace(
                    List.copyOf(prompts), List.copyOf(inputs), List.copyOf(toolNames));
        }

        private void accept(AgentEvent event, Map<String, ModelCallTrace> registry) {
            if (event instanceof ModelCallStartEvent startEvent) {
                // replyId 在这里才第一次出现，立刻登记，编排层随后按同一个 replyId 取。
                synchronized (lock) {
                    this.replyId = safe(startEvent.getReplyId());
                }
                if (!this.replyId.isEmpty()) {
                    registry.put(this.replyId, this);
                }
                return;
            }
            if (event instanceof TextBlockDeltaEvent textDelta) {
                appendCapped(outputText, textDelta.getDelta());
                return;
            }
            if (event instanceof ThinkingBlockDeltaEvent thinkingDelta) {
                appendCapped(thinkingText, thinkingDelta.getDelta());
                return;
            }
            if (event instanceof ToolCallStartEvent toolCallStart) {
                recordToolCall(toolCallStart.getToolCallId(), toolCallStart.getToolCallName());
                return;
            }
            if (event instanceof ToolCallDeltaEvent toolCallDelta) {
                recordToolCall(toolCallDelta.getToolCallId(), toolCallDelta.getToolCallName());
                appendToolArguments(toolCallDelta.getToolCallId(), toolCallDelta.getDelta());
                return;
            }
            if (event instanceof ModelCallEndEvent endEvent) {
                synchronized (lock) {
                    this.usage = endEvent.getUsage();
                    // 走到 end 事件说明本次调用正常收尾，快照要留着等编排层来取，
                    // 不能被随后触发的 doFinally 回收。
                    this.completed = true;
                }
            }
        }

        /**
         * 模型调用流终止后，若这次调用<b>没有</b>跑到 {@link ModelCallEndEvent}（异常、超时、上游取消），
         * 把快照从注册表里摘掉——那种情况下编排层也不会来取，留着就是泄漏。
         *
         * <p>反过来，正常收尾的快照必须保留：{@code streamEvents(...)} 经 {@code FluxSink} 转发事件，
         * 内层 {@code doFinally} 可能早于下游收到 end 事件就触发，此时回收会让时间线又看不到数据。
         */
        private void releaseIfIncomplete(Map<String, ModelCallTrace> registry) {
            String currentReplyId;
            synchronized (lock) {
                if (completed) {
                    return;
                }
                currentReplyId = replyId;
            }
            if (!currentReplyId.isEmpty()) {
                registry.remove(currentReplyId, this);
            }
        }

        private void recordToolCall(String toolCallId, String toolName) {
            String id = safe(toolCallId);
            String name = safe(toolName);
            if (id.isEmpty() && name.isEmpty()) {
                return;
            }
            synchronized (lock) {
                Map<String, Object> entry =
                        toolCallsById.computeIfAbsent(
                                id.isEmpty() ? name : id,
                                key -> {
                                    Map<String, Object> created = new LinkedHashMap<>();
                                    created.put("toolCallId", id);
                                    created.put("toolName", name);
                                    toolCalls.add(created);
                                    return created;
                                });
                // 只有"真名"才允许覆盖已记录的名字。流式分片的 ToolCallDeltaEvent 带的是框架占位名
                // （见 isPlaceholderToolName），照收会把 ToolCallStartEvent 那个唯一带真名的事件盖掉，
                // 时间线上就变成 toolName=__fragment__。
                if (!name.isEmpty() && !isPlaceholderToolName(name)) {
                    entry.put("toolName", name);
                }
            }
        }

        /**
         * 是否是 AgentScope 的工具名占位符。
         *
         * <p>流式解析在拿到完整 tool_call 之前会先吐分片块，块名是 {@code OpenAIResponseParser
         * .FRAGMENT_PLACEHOLDER}（{@code "__fragment__"}）。框架自己在 {@code ToolCallsAccumulator
         * .isPlaceholder} 里按同样规则（{@code __} 前缀）忽略这些名字，{@code ReActAgent.startToolCall}
         * 也用 {@code !startsWith("__")} 门控 {@code ToolCallStartEvent}——这里保持一致，而不是只硬编码
         * 单个字面量。
         */
        private static boolean isPlaceholderToolName(String toolName) {
            return toolName.startsWith("__");
        }

        private void appendToolArguments(String toolCallId, String delta) {
            String id = safe(toolCallId);
            String fragment = delta == null ? "" : delta;
            if (id.isEmpty() || fragment.isEmpty()) {
                return;
            }
            synchronized (lock) {
                Map<String, Object> entry = toolCallsById.get(id);
                if (entry == null) {
                    return;
                }
                String current = entry.get("arguments") == null ? "" : String.valueOf(entry.get("arguments"));
                if (current.length() >= MAX_STREAM_TEXT_LENGTH) {
                    return;
                }
                entry.put("arguments", truncate(current + fragment, MAX_STREAM_TEXT_LENGTH));
            }
        }

        private void appendCapped(StringBuilder target, String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            synchronized (lock) {
                if (target.length() >= MAX_STREAM_TEXT_LENGTH) {
                    return;
                }
                target.append(delta);
            }
        }

        private Map<String, Object> toDetail() {
            synchronized (lock) {
                Map<String, Object> output = new LinkedHashMap<>();
                if (outputText.length() > 0) {
                    output.put("text", truncate(outputText.toString(), MAX_STREAM_TEXT_LENGTH));
                }
                if (thinkingText.length() > 0) {
                    output.put("thinking", truncate(thinkingText.toString(), MAX_STREAM_TEXT_LENGTH));
                }
                if (!toolCalls.isEmpty()) {
                    List<Map<String, Object>> snapshot = new ArrayList<>(toolCalls.size());
                    for (Map<String, Object> entry : toolCalls) {
                        snapshot.add(Collections.unmodifiableMap(new LinkedHashMap<>(entry)));
                    }
                    output.put("toolCalls", List.copyOf(snapshot));
                }
                if (usage != null) {
                    output.put("usage", usageDetail(usage));
                }

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("prompt", Map.of("messages", promptMessages));
                detail.put("input", Map.of("messages", inputMessages));
                detail.put(
                        "output", output.isEmpty() ? Map.of() : Collections.unmodifiableMap(output));
                if (!toolNames.isEmpty()) {
                    detail.put("tools", toolNames);
                }
                if (usage != null) {
                    detail.put("usage", usageDetail(usage));
                }
                return Collections.unmodifiableMap(detail);
            }
        }

        private static Map<String, Object> usageDetail(ChatUsage usage) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("inputTokens", usage.getInputTokens());
            detail.put("outputTokens", usage.getOutputTokens());
            detail.put("cachedTokens", usage.getCachedTokens());
            detail.put("totalTokens", usage.getTotalTokens());
            detail.put("time", usage.getTime());
            return Collections.unmodifiableMap(detail);
        }

        private static Map<String, Object> messageDetail(Msg message) {
            if (message == null) {
                return Map.of();
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            if (message.getRole() != null) {
                detail.put("role", message.getRole().name().toLowerCase());
            }
            if (!safe(message.getName()).isEmpty()) {
                detail.put("name", safe(message.getName()));
            }
            String text = textOf(message);
            if (!text.isEmpty()) {
                detail.put("text", truncate(text, MAX_MESSAGE_TEXT_LENGTH));
            }
            return detail.isEmpty() ? Map.of() : Collections.unmodifiableMap(detail);
        }

        /**
         * 把消息正文拍平成一段文本。前端「提示词 / 输入」面板读的就是 {@code text} 字段
         * （见 workbench 的 {@code extractTimelineMessageText}），所以这里不再原样透出
         * {@code content} 块结构，避免面板退化成一坨 JSON。
         *
         * <p>四类块都要覆盖，尤其 {@link ToolResultBlock}：{@code Msg.getTextContent()} 只收集
         * {@link TextBlock}，而工具结果回填进下一轮模型输入时用的正是 {@code ToolResultBlock}。漏掉它
         * 的后果是第二次模型调用的「输入」里那条 {@code role=tool} 消息完全没有 {@code text} 键——
         * 模型明明是拿着工具输出在推理，时间线上却像凭空丢了一段。
         */
        private static String textOf(Msg message) {
            StringBuilder builder = new StringBuilder();
            String plain = safe(message.getTextContent());
            if (!plain.isEmpty()) {
                builder.append(plain);
            }
            if (message.getContent() == null) {
                return builder.toString();
            }
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock) {
                    // getTextContent() 已经覆盖 TextBlock，跳过避免重复。
                    continue;
                }
                if (block instanceof ThinkingBlock thinkingBlock) {
                    appendSection(builder, "[thinking] ", thinkingBlock.getThinking());
                } else if (block instanceof ToolUseBlock toolUseBlock) {
                    appendSection(
                            builder,
                            "[tool_use " + safe(toolUseBlock.getName()) + "] ",
                            toolUseBlock.getInput() == null
                                    ? safe(toolUseBlock.getContent())
                                    : String.valueOf(toolUseBlock.getInput()));
                } else if (block instanceof ToolResultBlock toolResultBlock) {
                    appendSection(
                            builder,
                            "[tool_result "
                                    + safe(toolResultBlock.getName())
                                    + " · "
                                    + safe(toolResultBlock.getState())
                                    + "] ",
                            toolResultOutputText(toolResultBlock));
                }
            }
            return builder.toString();
        }

        /**
         * 展开 {@link ToolResultBlock#getOutput()}。它本身又是一串 {@link ContentBlock}，最常见是单个
         * {@link TextBlock}；非文本块退化成 {@code toString()}，保证至少能看出"有东西"而不是空白。
         */
        private static String toolResultOutputText(ToolResultBlock block) {
            List<ContentBlock> output = block.getOutput();
            if (output == null || output.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (ContentBlock item : output) {
                String piece;
                if (item instanceof TextBlock textBlock) {
                    piece = safe(textBlock.getText());
                } else if (item instanceof ThinkingBlock thinkingBlock) {
                    piece = safe(thinkingBlock.getThinking());
                } else {
                    piece = safe(item);
                }
                if (piece.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(piece);
            }
            return builder.toString();
        }

        private static void appendSection(StringBuilder builder, String prefix, String body) {
            String value = safe(body);
            if (value.isEmpty()) {
                return;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(prefix).append(value);
        }

        private static String truncate(String value, int maxLength) {
            if (value == null) {
                return "";
            }
            if (value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, maxLength)
                    + "\n…（已截断，原始长度 "
                    + value.length()
                    + " 字符）";
        }

        private static String safe(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
