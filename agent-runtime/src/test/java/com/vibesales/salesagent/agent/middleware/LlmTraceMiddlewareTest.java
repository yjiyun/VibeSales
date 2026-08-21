package com.vibesales.salesagent.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 覆盖 LLM 埋点中间件的核心契约：提示词与用户输入按 role 拆成两段、输出正文按 delta 累加、
 * 快照按 replyId 一次性取走、异常终止不残留。
 *
 * <p>这些断言正是先前 Formatter 版实现做不到的地方（提示词全空 / 跨请求串位 / static 队列泄漏），
 * 所以这里逐条钉住，避免以后有人再把采集点挪回 Formatter 层。
 */
class LlmTraceMiddlewareTest {

    private static final String REPLY_ID = "reply-1";

    private final LlmTraceMiddleware middleware = new LlmTraceMiddleware();

    @Test
    void shouldSplitSystemPromptAndUserInputThenCaptureOutput() {
        ModelCallInput input =
                new ModelCallInput(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .name("system")
                                        .textContent("你是客服助手。")
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .name("customer")
                                        .textContent("这个产品还有货吗？")
                                        .build()),
                        List.of(),
                        null,
                        null);

        drain(input, this::modelEvents);

        Map<String, Object> detail = middleware.consumeTraceDetail(REPLY_ID);

        assertEquals(List.of(Map.of("role", "system", "name", "system", "text", "你是客服助手。")),
                messagesOf(detail, "prompt"));
        assertEquals(List.of(Map.of("role", "user", "name", "customer", "text", "这个产品还有货吗？")),
                messagesOf(detail, "input"));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) detail.get("output");
        assertEquals("库存充足。", output.get("text"));
        assertEquals("先查库存。", output.get("thinking"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) output.get("toolCalls");
        assertEquals(1, toolCalls.size());
        assertEquals("get_stock", toolCalls.get(0).get("toolName"));
        assertEquals("{\"sku\":\"A1\"}", toolCalls.get(0).get("arguments"));

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) detail.get("usage");
        assertEquals(120, usage.get("inputTokens"));
        assertEquals(8, usage.get("outputTokens"));
    }

    /**
     * 快照的存活期不能绑在模型调用流的生命周期上。{@code streamEvents(...)} 经 {@code FluxSink} 把事件
     * 转发给编排层，模型调用内层的 {@code doFinally} 完全可能早于下游收到 end 事件就触发——这里模拟
     * "流已经完整终止之后才来取"，必须仍能取到数据。
     */
    @Test
    void shouldKeepSnapshotAfterModelCallStreamTerminates() {
        drain(simpleInput(), this::modelEvents);

        assertEquals(1, middleware.pendingTraceCount());
        assertEquals("库存充足。", outputTextOf(middleware.consumeTraceDetail(REPLY_ID)));
    }

    /** 取走即删：同一个 replyId 第二次取应为空，防止一份快照被多个节点重复渲染。 */
    @Test
    void shouldConsumeSnapshotOnlyOnce() {
        drain(simpleInput(), this::modelEvents);

        assertFalse(middleware.consumeTraceDetail(REPLY_ID).isEmpty());
        assertTrue(middleware.consumeTraceDetail(REPLY_ID).isEmpty());
        assertEquals(0, middleware.pendingTraceCount());
    }

    /**
     * 模型调用没跑到 {@code ModelCallEndEvent} 就中断时（异常/取消）快照必须被回收。先前的 static 队列
     * 版本在这条路径上会留下永久孤儿，既泄漏内存又会被下一轮请求误取。
     */
    @Test
    void shouldReleaseSnapshotWhenModelCallFails() {
        Flux<AgentEvent> events =
                middleware.onModelCall(
                        null,
                        runtimeContext(),
                        simpleInput(),
                        callInput ->
                                Flux.concat(
                                        Flux.just(new ModelCallStartEvent(REPLY_ID)),
                                        Flux.error(new IllegalStateException("上游超时"))));

        events.onErrorResume(error -> Flux.empty()).collectList().block();

        assertEquals(0, middleware.pendingTraceCount());
        assertTrue(middleware.consumeTraceDetail(REPLY_ID).isEmpty());
    }

    /** 两次独立调用各自成一个作用域，不共享可变状态——这是换到 onModelCall 的主要动机。 */
    @Test
    void shouldKeepConcurrentCallsIsolated() {
        drain(
                inputWithUserText("第一轮问题"),
                callInput ->
                        Flux.just(
                                new ModelCallStartEvent("reply-a"),
                                new TextBlockDeltaEvent("reply-a", "b1", "第一轮回答"),
                                new ModelCallEndEvent("reply-a", null)));
        drain(
                inputWithUserText("第二轮问题"),
                callInput ->
                        Flux.just(
                                new ModelCallStartEvent("reply-b"),
                                new TextBlockDeltaEvent("reply-b", "b1", "第二轮回答"),
                                new ModelCallEndEvent("reply-b", null)));

        Map<String, Object> first = middleware.consumeTraceDetail("reply-a");
        Map<String, Object> second = middleware.consumeTraceDetail("reply-b");

        assertEquals("第一轮问题", messagesOf(first, "input").get(0).get("text"));
        assertEquals("第二轮问题", messagesOf(second, "input").get(0).get("text"));
        assertEquals("第一轮回答", outputTextOf(first));
        assertEquals("第二轮回答", outputTextOf(second));
    }

    @Test
    void shouldIgnoreBlankReplyId() {
        assertTrue(middleware.consumeTraceDetail(null).isEmpty());
        assertTrue(middleware.consumeTraceDetail("  ").isEmpty());
    }

    /**
     * 流式分片的 {@code ToolCallDeltaEvent} 带的是框架占位名 {@code __fragment__}（{@code ReActAgent}
     * 用 {@code !startsWith("__")} 门控可见工具名，只有 START 事件带真名）。照收就会把真名盖掉，
     * 时间线上工具名变成 {@code __fragment__}——这正是修复前的实际现象。
     */
    @Test
    void shouldKeepRealToolNameAgainstPlaceholderDeltas() {
        drain(
                simpleInput(),
                callInput ->
                        Flux.just(
                                new ModelCallStartEvent(REPLY_ID),
                                new ToolCallStartEvent(REPLY_ID, "call-1", "get_stock"),
                                new ToolCallDeltaEvent(REPLY_ID, "call-1", "__fragment__", "{\"sku\":"),
                                new ToolCallDeltaEvent(REPLY_ID, "call-1", "__fragment__", "\"A1\"}"),
                                new ModelCallEndEvent(REPLY_ID, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) middleware.consumeTraceDetail(REPLY_ID).get("output"))
                                .get("toolCalls");

        assertEquals(1, toolCalls.size());
        assertEquals("get_stock", toolCalls.get(0).get("toolName"));
        assertEquals("{\"sku\":\"A1\"}", toolCalls.get(0).get("arguments"));
    }

    /**
     * 工具结果消息的正文装在 {@link ToolResultBlock} 里，而 {@code Msg.getTextContent()} 只收
     * {@code TextBlock}——先前时间线上这类历史消息只剩 {@code role} / {@code name}，正文整段消失。
     */
    @Test
    void shouldRenderToolResultBlocksInMessageText() {
        ModelCallInput input =
                new ModelCallInput(
                        List.of(
                                Msg.builder().role(MsgRole.SYSTEM).textContent("你是客服助手。").build(),
                                Msg.builder()
                                        .role(MsgRole.TOOL)
                                        .name("get_stock")
                                        .content(
                                                ToolResultBlock.builder()
                                                        .id("call-1")
                                                        .name("get_stock")
                                                        .state(ToolResultState.SUCCESS)
                                                        .output(TextBlock.builder().text("库存 12 件").build())
                                                        .build())
                                        .build()),
                        List.of(),
                        null,
                        null);

        drain(input, this::modelEvents);

        String toolMessageText =
                String.valueOf(
                        messagesOf(middleware.consumeTraceDetail(REPLY_ID), "input")
                                .get(0)
                                .get("text"));

        assertTrue(toolMessageText.contains("库存 12 件"), toolMessageText);
        assertTrue(toolMessageText.contains("get_stock"), toolMessageText);
        assertTrue(toolMessageText.contains("SUCCESS"), toolMessageText);
    }

    /**
     * 提示词单条上限是 16000 而不是流式文本那个 4000：本工程系统提示词（AGENTS.md + SOUL.md +
     * 恢复上下文）单条就有 6000+ 字符，按 4000 截会把最需要看的尾部切掉。
     */
    @Test
    void shouldKeepLongSystemPromptBeyondStreamTextLimit() {
        String longPrompt = "指令".repeat(3000);
        ModelCallInput input =
                new ModelCallInput(
                        List.of(
                                Msg.builder().role(MsgRole.SYSTEM).textContent(longPrompt).build(),
                                Msg.builder().role(MsgRole.USER).textContent("在吗").build()),
                        List.of(),
                        null,
                        null);

        drain(input, this::modelEvents);

        String promptText =
                String.valueOf(
                        messagesOf(middleware.consumeTraceDetail(REPLY_ID), "prompt")
                                .get(0)
                                .get("text"));

        assertEquals(6000, promptText.length());
    }

    private void drain(
            ModelCallInput input,
            java.util.function.Function<ModelCallInput, Flux<AgentEvent>> next) {
        middleware.onModelCall(null, runtimeContext(), input, next).collectList().block();
    }

    private Flux<AgentEvent> modelEvents(ModelCallInput input) {
        return Flux.just(
                new ModelCallStartEvent(REPLY_ID),
                new ThinkingBlockDeltaEvent(REPLY_ID, "t1", "先查库存。"),
                new ToolCallStartEvent(REPLY_ID, "call-1", "get_stock"),
                new ToolCallDeltaEvent(REPLY_ID, "call-1", "get_stock", "{\"sku\":"),
                new ToolCallDeltaEvent(REPLY_ID, "call-1", "get_stock", "\"A1\"}"),
                new TextBlockDeltaEvent(REPLY_ID, "b1", "库存"),
                new TextBlockDeltaEvent(REPLY_ID, "b1", "充足。"),
                new ModelCallEndEvent(
                        REPLY_ID,
                        ChatUsage.builder()
                                .inputTokens(120)
                                .outputTokens(8)
                                .cachedTokens(0)
                                .time(1.5)
                                .build()));
    }

    private static ModelCallInput simpleInput() {
        return inputWithUserText("这个产品还有货吗？");
    }

    private static ModelCallInput inputWithUserText(String text) {
        return new ModelCallInput(
                List.of(
                        Msg.builder().role(MsgRole.SYSTEM).textContent("你是客服助手。").build(),
                        Msg.builder().role(MsgRole.USER).textContent(text).build()),
                List.of(),
                null,
                null);
    }

    private static RuntimeContext runtimeContext() {
        return RuntimeContext.builder().sessionId("conv-1").userId("user-1").build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(Map<String, Object> detail, String key) {
        Map<String, Object> bundle = (Map<String, Object>) detail.get(key);
        return (List<Map<String, Object>>) bundle.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static Object outputTextOf(Map<String, Object> detail) {
        return ((Map<String, Object>) detail.get("output")).get("text");
    }
}
