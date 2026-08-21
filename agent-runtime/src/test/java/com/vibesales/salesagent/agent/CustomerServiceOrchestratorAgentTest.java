package com.vibesales.salesagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomerServiceOrchestratorAgentTest {

    @Test
    void compactQuestionFocusShouldKeepShortInput() {
        assertEquals("油皮提亮精华", CustomerServiceOrchestratorAgent.compactQuestionFocus("油皮提亮精华"));
    }

    @Test
    void compactQuestionFocusShouldTrimLongInputToDatabaseSafeLength() {
        String compacted =
                CustomerServiceOrchestratorAgent.compactQuestionFocus(
                        "这次我想先看清爽一点的提亮精华，有没有适合油皮、预算 200 左右的推荐？");

        assertEquals("这次我想先看清爽一点的提亮精华，有没有适合油皮、预算 200 左", compacted);
        assertTrue(compacted.length() <= 32);
    }

    /**
     * {@code orchestration:prompt.compose} 的输出必须是 {@code {messages: [...]}} 且数组非空。
     *
     * <p>这个节点先前只放 {@code injectionMode} / {@code userMessageLength} 两个平铺字段，前端读的却是
     * {@code detail.output}，于是「输出」栏一直显示"无"——而它恰恰是判断"到底注入了什么"的关键节点。
     * 数组非空同样是硬约束：空数组会让前端回退去 JSON.stringify 整个 bundle，渲染成字面量
     * {@code {"messages": []}}。
     */
    @Test
    void promptComposeOutputShouldCarryNonEmptyMessageBundle() {
        Map<String, Object> output =
                CustomerServiceOrchestratorAgent.promptComposeOutput(
                        Msg.builderForRole(MsgRole.USER).textContent("这个产品还有货吗？").build());

        List<?> messages = (List<?>) output.get("messages");
        assertFalse(messages.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messages.get(0);
        assertEquals("user", message.get("role"));
        assertEquals("这个产品还有货吗？", message.get("text"));
    }

    /** composed 为 null 时也不能退化成空数组，否则前端显示 {@code {"messages": []}}。 */
    @Test
    void promptComposeOutputShouldStayRenderableWhenMessageIsMissing() {
        Map<String, Object> output = CustomerServiceOrchestratorAgent.promptComposeOutput(null);

        List<?> messages = (List<?>) output.get("messages");
        assertEquals(1, messages.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messages.get(0);
        assertEquals("user", message.get("role"));
        assertEquals("", message.get("text"));
    }

    /** {@code agent.run} 的输出要带回复正文本身，而不是只有先前那两个 replyLength / eventCount。 */
    @Test
    void agentRunOutputShouldCarryReplyText() {
        Map<String, Object> output =
                CustomerServiceOrchestratorAgent.agentRunOutput("库存充足，随时可以下单。", 42);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) output.get("messages");
        assertEquals("assistant", messages.get(0).get("role"));
        assertEquals("库存充足，随时可以下单。", messages.get(0).get("text"));
        assertEquals(42, output.get("eventCount"));
    }

    @Test
    void singleStageAgentOutputShouldParseStructuredAfterSalesIntent() {
        String rawReply =
                """
                ```json
                {"reply":"不好意思呀，麻烦把破损那支拍张照发我，再给我个订单号，我帮你登记补发。","intentCode":"return_exchange","needHumanHandoff":false}
                ```
                """;

        CustomerServiceOrchestratorAgent.SingleStageAgentOutput output =
                CustomerServiceOrchestratorAgent.parseSingleStageAgentOutput(
                        rawReply, "general_consultation");

        assertEquals("不好意思呀，麻烦把破损那支拍张照发我，再给我个订单号，我帮你登记补发。", output.reply());
        assertEquals("return_exchange", output.intentCode());
        assertFalse(output.needHumanHandoff());
    }

    @Test
    void singleStageAgentOutputShouldPromoteExplicitHandoffToTransferIntent() {
        String rawReply =
                """
                {"reply":"这边先帮你记录，我让专门的同学尽快跟你联系。","needHumanHandoff":true}
                """;

        CustomerServiceOrchestratorAgent.SingleStageAgentOutput output =
                CustomerServiceOrchestratorAgent.parseSingleStageAgentOutput(
                        rawReply, "general_consultation");

        assertEquals("这边先帮你记录，我让专门的同学尽快跟你联系。", output.reply());
        assertEquals("transfer_to_human", output.intentCode());
        assertTrue(output.needHumanHandoff());
    }

    @Test
    void singleStageAgentOutputShouldFallbackToRawTextAndDerivedIntent() {
        String rawReply = "先别着急，你把肤质和主要诉求告诉我，我直接帮你配。";

        CustomerServiceOrchestratorAgent.SingleStageAgentOutput output =
                CustomerServiceOrchestratorAgent.parseSingleStageAgentOutput(
                        rawReply, "product_recommend");

        assertEquals("先别着急，你把肤质和主要诉求告诉我，我直接帮你配。", output.reply());
        assertEquals("product_recommend", output.intentCode());
        assertFalse(output.needHumanHandoff());
    }
}
