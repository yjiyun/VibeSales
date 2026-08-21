package com.vibesales.salesagent.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibesales.salesagent.tool.rulecontext.RuleContextSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code GET /rule-context}（{@code responseMode=llm}）的响应转成 {@link RuleContextSnapshot}。
 *
 * <p>除了字段搬运，这里还负责把分层/商品/约束压成一段紧凑的提示词文本
 * （{@link RuleContextSnapshot#promptText()}）——直接把整个 JSON 塞进提示词既费 token
 * 又不易读，压成结构化文本更适合模型消费。
 */
public final class RuleContextMapper {

    private RuleContextMapper() {}

    /**
     * @param data {@code GET /rule-context} 成功响应里的 {@code data} 节点
     */
    public static RuleContextSnapshot fromResponse(JsonNode data, String fallbackSceneCode) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return RuleContextSnapshot.unavailable(fallbackSceneCode);
        }

        String sceneCode = data.path("sceneCode").asText(fallbackSceneCode);
        String ruleVersion = data.path("ruleVersion").asText("");
        String taskGoal = data.path("taskGoal").asText("");

        List<String> decisionRules = textList(data.path("decisionRules"));
        JsonNode contract = data.path("agentOutputContract");
        List<String> mustReturnFields = textList(contract.path("mustReturnFields"));
        List<String> recommendationConstraints =
                textList(contract.path("recommendationConstraints"));

        JsonNode products = data.path("productKnowledge");
        List<String> allowedProductIds = new ArrayList<>();
        if (products.isArray()) {
            for (JsonNode product : products) {
                String productId = product.path("productId").asText("");
                if (!productId.isBlank()) {
                    allowedProductIds.add(productId);
                }
            }
        }

        String promptText =
                buildPromptText(
                        taskGoal,
                        decisionRules,
                        data.path("recommendedSegments"),
                        products,
                        recommendationConstraints,
                        mustReturnFields);

        return new RuleContextSnapshot(
                sceneCode,
                ruleVersion,
                taskGoal,
                decisionRules,
                mustReturnFields,
                recommendationConstraints,
                allowedProductIds,
                promptText,
                true);
    }

    private static String buildPromptText(
            String taskGoal,
            List<String> decisionRules,
            JsonNode segments,
            JsonNode products,
            List<String> constraints,
            List<String> mustReturnFields) {
        StringBuilder builder = new StringBuilder();

        if (!taskGoal.isBlank()) {
            builder.append("【推荐任务目标】\n").append(taskGoal).append('\n');
        }

        appendNumberedList(builder, "【决策规则】", decisionRules);

        if (segments.isArray() && segments.size() > 0) {
            builder.append("\n【候选分层】\n");
            for (JsonNode segment : segments) {
                builder.append("- ")
                        .append(segment.path("segmentName").asText(""))
                        .append("（segmentCode=")
                        .append(segment.path("segmentCode").asText(""))
                        .append("，匹配度=")
                        .append(segment.path("matchLevel").asText(""))
                        .append("）\n");
                JsonNode tiers = segment.path("recommendedTierOptions");
                if (tiers.isArray()) {
                    for (JsonNode tier : tiers) {
                        builder.append("    档位 tierCode=")
                                .append(tier.path("tierCode").asText(""))
                                .append("，组合价=")
                                .append(tier.path("bundlePrice").asText("-"))
                                .append("，说明：")
                                .append(tier.path("summary").asText(""))
                                .append('\n');
                    }
                }
            }
        }

        if (products.isArray() && products.size() > 0) {
            builder.append("\n【可推荐商品（只能从这里选，productId 必须原样引用）】\n");
            for (JsonNode product : products) {
                builder.append("- productId=")
                        .append(product.path("productId").asText(""))
                        .append("，名称=")
                        .append(product.path("productName").asText(""))
                        .append("，单买价=")
                        .append(product.path("singlePurchasePrice").asText("-"))
                        .append("，链接=")
                        .append(product.path("productUrl").asText(""))
                        .append('\n');
            }
        }

        appendNumberedList(builder, "\n【推荐硬约束】", constraints);

        if (!mustReturnFields.isEmpty()) {
            builder.append("\n【推荐决策必须包含的字段】\n")
                    .append(String.join("、", mustReturnFields))
                    .append('\n');
        }

        return builder.toString();
    }

    private static void appendNumberedList(StringBuilder builder, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        builder.append(title).append('\n');
        for (int i = 0; i < items.size(); i++) {
            builder.append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }
}
