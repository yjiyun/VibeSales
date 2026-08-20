package com.agentteams.salesagent.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.tool.rulecontext.RuleContextSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则上下文转换测试。
 *
 * <p>重点验证 {@code allowedProductIds} 白名单和 {@code recommendationConstraints} 被正确提取
 * ——这两项是场景卡片1"不能编造商品/链接/价格"硬约束的落地依据。
 */
class RuleContextMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 简化自真实探测结果的响应片段。 */
    private static final String REAL_RESPONSE =
            """
            {
              "responseMode": "llm_friendly",
              "sceneCode": "BEAUTY_SKINCARE",
              "ruleVersion": 1781273190083,
              "taskGoal": "你需要基于客户画像与候选分层，选择更合适的推荐方向。",
              "decisionRules": [
                "优先结合客户主要问题、肤质和预算范围选择候选分层。",
                "没有明确优惠信号时，不主动给出优惠方案。"
              ],
              "agentOutputContract": {
                "mustReturnFields": ["selectedSegmentCode", "selectedTierCode", "recommendedProductIds"],
                "recommendationConstraints": [
                  "优先从 recommendedSegments 中选择方案。",
                  "不要推荐 productKnowledge 之外的商品。"
                ]
              },
              "recommendedSegments": [
                {
                  "segmentCode": "beauty-brightening",
                  "segmentName": "美白淡斑诉求",
                  "matchLevel": "高匹配",
                  "recommendedTierOptions": [
                    {"tierCode":"primary","summary":"优先推荐美白提亮产品","bundlePrice":716}
                  ]
                }
              ],
              "productKnowledge": [
                {
                  "productId": "6",
                  "productName": "光感美白修护乳液",
                  "singlePurchasePrice": 189,
                  "productUrl": "https://h5.youzan.com/v2/showcase/goods?alias=2x7xlrjh2kmq3ej"
                },
                {
                  "productId": "23",
                  "productName": "氨基酸净润卸妆水",
                  "singlePurchasePrice": 99,
                  "productUrl": "https://h5.youzan.com/v2/showcase/goods?alias=2oo08nacw6cuzlk"
                }
              ]
            }
            """;

    private RuleContextSnapshot mapRealResponse() throws Exception {
        return RuleContextMapper.fromResponse(
                objectMapper.readTree(REAL_RESPONSE), "BEAUTY_SKINCARE");
    }

    @Test
    @DisplayName("商品白名单提取出全部真实 productId")
    void extractsAllowedProductIds() throws Exception {
        RuleContextSnapshot snapshot = mapRealResponse();

        assertEquals(2, snapshot.allowedProductIds().size());
        assertTrue(snapshot.allowedProductIds().contains("6"));
        assertTrue(snapshot.allowedProductIds().contains("23"));
    }

    @Test
    @DisplayName("agentOutputContract 的两组约束都被提取")
    void extractsOutputContract() throws Exception {
        RuleContextSnapshot snapshot = mapRealResponse();

        assertEquals(3, snapshot.mustReturnFields().size());
        assertTrue(snapshot.mustReturnFields().contains("recommendedProductIds"));
        assertEquals(2, snapshot.recommendationConstraints().size());
        assertTrue(
                snapshot.recommendationConstraints().stream()
                        .anyMatch(rule -> rule.contains("不要推荐 productKnowledge 之外的商品")));
    }

    @Test
    @DisplayName("基础字段与 fromBackend 标记正确")
    void extractsBasicFields() throws Exception {
        RuleContextSnapshot snapshot = mapRealResponse();

        assertEquals("BEAUTY_SKINCARE", snapshot.sceneCode());
        assertEquals("1781273190083", snapshot.ruleVersion());
        assertFalse(snapshot.taskGoal().isBlank());
        assertEquals(2, snapshot.decisionRules().size());
        assertTrue(snapshot.fromBackend());
    }

    @Test
    @DisplayName("promptText 应包含真实 productId、价格和硬约束，供提示词直接注入")
    void promptTextCarriesProductsAndConstraints() throws Exception {
        String promptText = mapRealResponse().promptText();

        assertTrue(promptText.contains("productId=6"));
        assertTrue(promptText.contains("光感美白修护乳液"));
        assertTrue(promptText.contains("189"));
        assertTrue(promptText.contains("beauty-brightening"));
        assertTrue(promptText.contains("不要推荐 productKnowledge 之外的商品"));
        assertTrue(promptText.contains("recommendedProductIds"));
    }

    @Test
    @DisplayName("后端不可用时 fromBackend 为 false 且商品白名单为空——调用方据此拒绝进入推荐")
    void unavailableSnapshotHasNoProducts() {
        RuleContextSnapshot snapshot = RuleContextSnapshot.unavailable("BEAUTY_SKINCARE");

        assertFalse(snapshot.fromBackend());
        assertTrue(snapshot.allowedProductIds().isEmpty());
        assertTrue(snapshot.recommendationConstraints().isEmpty());
        assertEquals("BEAUTY_SKINCARE", snapshot.sceneCode());
    }

    @Test
    @DisplayName("data 为 null 时回落到 unavailable，不抛异常")
    void nullDataFallsBackToUnavailable() {
        RuleContextSnapshot snapshot = RuleContextMapper.fromResponse(null, "BEAUTY_SKINCARE");
        assertFalse(snapshot.fromBackend());
    }

    @Test
    @DisplayName("缺 agentOutputContract 时不抛异常，两组约束为空列表")
    void missingContractDegradesGracefully() throws Exception {
        RuleContextSnapshot snapshot =
                RuleContextMapper.fromResponse(
                        objectMapper.readTree("{\"sceneCode\":\"X\",\"productKnowledge\":[]}"), "X");

        assertTrue(snapshot.mustReturnFields().isEmpty());
        assertTrue(snapshot.recommendationConstraints().isEmpty());
        assertTrue(snapshot.allowedProductIds().isEmpty());
    }
}
