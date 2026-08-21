package com.vibesales.salesagent.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibesales.salesagent.tool.history.HistorySummarySnapshot;
import com.vibesales.salesagent.tool.taskboard.IntentTaskSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 历史摘要与任务板 DTO 转换测试。 */
class HistoryAndQueueMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("HistorySummaryMapper")
    class HistorySummaryMapperTest {

        @Test
        @DisplayName("新会话的真实响应：摘要为空串时替换为可读提示，recoveryPending 为 false")
        void newConversationGetsReadableFallbackSummary() throws Exception {
            // 取自真实探测结果
            String json =
                    """
                    {
                      "sceneCode": "BEAUTY_SKINCARE",
                      "conversationId": "probe-conv-001",
                      "historySummary": "",
                      "summaryVersion": 0,
                      "needHumanHandoff": false,
                      "recoverPending": false,
                      "activeIntentCode": "",
                      "queueVersion": 0
                    }
                    """;
            HistorySummarySnapshot snapshot =
                    HistorySummaryMapper.fromResponse(objectMapper.readTree(json));

            assertFalse(snapshot.summaryText().isBlank());
            assertFalse(snapshot.recoveryPending());
            assertEquals("", snapshot.activeIntentCode());
        }

        @Test
        @DisplayName("recoverPending=true 应被透传——这是 RecoveryDetectionRule 的强信号")
        void recoverPendingIsPassedThrough() throws Exception {
            String json =
                    "{\"historySummary\":\"客户在问补水推荐\",\"recoverPending\":true,"
                            + "\"activeIntentCode\":\"product_recommend\"}";
            HistorySummarySnapshot snapshot =
                    HistorySummaryMapper.fromResponse(objectMapper.readTree(json));

            assertTrue(snapshot.recoveryPending());
            assertEquals("客户在问补水推荐", snapshot.summaryText());
            assertEquals("product_recommend", snapshot.activeIntentCode());
        }

        @Test
        @DisplayName("data 为 null 时回落到空摘要，不抛异常")
        void nullDataFallsBack() {
            HistorySummarySnapshot snapshot = HistorySummaryMapper.fromResponse(null);
            assertFalse(snapshot.summaryText().isBlank());
            assertFalse(snapshot.recoveryPending());
        }
    }

    @Nested
    @DisplayName("IntentQueueMapper")
    class IntentQueueMapperTest {

        @Test
        @DisplayName("新会话的真实响应：空 items、queueVersion=0")
        void newConversationHasEmptyQueue() throws Exception {
            String json =
                    """
                    {
                      "sceneCode": "BEAUTY_SKINCARE",
                      "activeIntentCode": "",
                      "suspendedIntentCount": 0,
                      "sleepingIntentCount": 0,
                      "closedIntentCount": 0,
                      "queueVersion": 0,
                      "items": []
                    }
                    """;
            IntentTaskSnapshot snapshot =
                    IntentQueueMapper.fromResponse(objectMapper.readTree(json), "fallback");

            assertEquals(0, snapshot.totalTasks());
            assertEquals(0, snapshot.activeTasks());
            assertEquals(0, snapshot.suspendedTasks());
            assertEquals("0", snapshot.queueVersion());
        }

        @Test
        @DisplayName("activeIntentCode 非空时活跃任务数为 1（场景卡片8：同时最多一个 active）")
        void nonBlankActiveIntentCodeMeansOneActiveTask() throws Exception {
            String json =
                    """
                    {
                      "activeIntentCode": "return_exchange",
                      "suspendedIntentCount": 2,
                      "queueVersion": 7,
                      "items": [
                        {"intentCode":"return_exchange","status":"active"},
                        {"intentCode":"product_recommend","status":"suspended"},
                        {"intentCode":"member_benefit","status":"suspended"}
                      ]
                    }
                    """;
            IntentTaskSnapshot snapshot =
                    IntentQueueMapper.fromResponse(objectMapper.readTree(json), "fallback");

            assertEquals(3, snapshot.totalTasks());
            assertEquals(1, snapshot.activeTasks());
            assertEquals(2, snapshot.suspendedTasks());
            assertEquals("7", snapshot.queueVersion());
        }

        @Test
        @DisplayName("queueVersion 必须保留可解析回数值的原值（写操作乐观锁要用）")
        void queueVersionStaysNumericParseable() throws Exception {
            IntentTaskSnapshot snapshot =
                    IntentQueueMapper.fromResponse(
                            objectMapper.readTree("{\"queueVersion\":42,\"items\":[]}"), "fallback");

            assertEquals(42, Integer.parseInt(snapshot.queueVersion()));
        }

        @Test
        @DisplayName("data 为 null 时回落到空队列并保留兜底 queueVersion")
        void nullDataFallsBackKeepingFallbackVersion() {
            IntentTaskSnapshot snapshot = IntentQueueMapper.fromResponse(null, "bootstrap-conv-1");
            assertEquals(0, snapshot.totalTasks());
            assertEquals("bootstrap-conv-1", snapshot.queueVersion());
        }
    }
}
