package com.vibesales.salesagent.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;

/**
 * 把 {@code GET /customer-profile} 的响应 DTO 转成 {@link CustomerProfileSnapshot}。
 *
 * <p>这一层存在的价值：让 {@code tool/} 层完全不感知后端字段名——后端改字段名时只改这里。
 *
 * <p><b>核心职责是推导六个画像布尔信号</b>。这六个信号是 {@code ProfileCompletenessRule} 执行
 * 场景卡片1充分度公式（{@code (关注点|目标功效|核心需求) AND (肤质|预算|品类偏好)}）的唯一输入，
 * 此前占位实现把它们全写死成 {@code false}，导致画像充分度判断恒定走"画像不足"分支，
 * 规则实现得再对也验证不了。这里按后端真实字段做推导，让那条规则第一次真正生效。
 */
public final class CustomerProfileMapper {

    private CustomerProfileMapper() {}

    /**
     * @param data {@code GET /customer-profile} 成功响应里的 {@code data} 节点
     */
    public static CustomerProfileSnapshot fromResponse(JsonNode data, String fallbackCustomerId) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return emptyProfile(fallbackCustomerId);
        }

        String customerId = text(data, "customerId", fallbackCustomerId);
        String profileVersion = text(data, "profileVersion", "0");
        String summary = resolveSummary(data);

        // 意图侧信号：关注点 / 目标功效 / 核心需求，三者任一命中即可
        boolean hasConcern = isNonEmptyArray(data.path("concerns"));
        boolean hasTargetBenefit =
                hasDomainValue(data, "targetBenefit", "targetBenefits", "goalBenefit");
        boolean hasCoreNeed = hasDomainValue(data, "coreNeed", "coreNeeds", "primaryNeed");

        // 上下文侧信号：肤质 / 预算 / 品类偏好，三者任一命中即可
        boolean hasSkinType = isNonBlankText(data.path("skinType"));
        boolean hasBudget = hasBudget(data);
        boolean hasCategoryPreference =
                hasDomainValue(data, "categoryPreference", "categoryPreferences", "preferredCategories");

        return new CustomerProfileSnapshot(
                customerId,
                summary,
                profileVersion,
                hasConcern,
                hasTargetBenefit,
                hasCoreNeed,
                hasSkinType,
                hasBudget,
                hasCategoryPreference);
    }

    /**
     * 后端返回 404（新客户还没有画像）时使用的空画像。
     *
     * <p>六个信号全 {@code false} 是<b>语义正确</b>的——新客户确实什么都还没收集到，
     * {@code ProfileCompletenessRule} 应该判定"不能推荐、需要追问"。这跟此前占位实现
     * 恒返回 {@code false} 的区别在于：那时候是"永远拿不到真实值"，现在是"真实值就是空"。
     */
    public static CustomerProfileSnapshot emptyProfile(String customerId) {
        return CustomerProfileSnapshot.placeholder(
                customerId, "后端暂无该客户画像记录（新客户），画像信号均为空。", "0");
    }

    private static String resolveSummary(JsonNode data) {
        String summary = text(data, "profileSummary", "");
        if (!summary.isBlank()) {
            return summary;
        }
        summary = text(data, "sharedProfileSummary", "");
        return summary.isBlank() ? "后端已返回画像记录，但摘要字段为空。" : summary;
    }

    /** 预算：{@code budgetMin} 或 {@code budgetMax} 任一为非空数值即视为已知预算。 */
    private static boolean hasBudget(JsonNode data) {
        return isPositiveNumber(data.path("budgetMin")) || isPositiveNumber(data.path("budgetMax"));
    }

    /**
     * 在顶层字段和 {@code domainProfiles} / {@code sharedProfileSnapshot.domainProfiles} 里
     * 依次查找给定的候选键名。
     *
     * <p>之所以要试多个键名和多个层级：实测新客户的 {@code domainProfiles} 是空对象 {@code {}}，
     * 顶层只有 {@code skinType}/{@code concerns}/{@code budgetMin}/{@code budgetMax}/
     * {@code sensitivityLevel} 这几个字段被平铺出来，目标功效/核心需求/品类偏好这三类信号
     * 在画像收集起来之后才会出现，具体落在哪一层尚未观测到真实样本。这里做宽松查找，
     * 命中任一即算有值，避免因为猜错层级而漏判。
     */
    private static boolean hasDomainValue(JsonNode data, String... candidateKeys) {
        for (String key : candidateKeys) {
            if (hasValueAt(data, key)) {
                return true;
            }
            if (hasValueAt(data.path("domainProfiles"), key)) {
                return true;
            }
            if (hasValueAt(data.path("sharedProfileSnapshot").path("domainProfiles"), key)) {
                return true;
            }
            if (hasValueAt(data.path("commonProfile"), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValueAt(JsonNode node, String key) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode value = node.path(key);
        if (value.isArray()) {
            return isNonEmptyArray(value);
        }
        if (value.isNumber()) {
            return true;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return isNonBlankText(value);
    }

    private static boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0;
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node != null
                && node.isTextual()
                && node.asText() != null
                && !node.asText().isBlank();
    }

    private static boolean isPositiveNumber(JsonNode node) {
        return node != null && node.isNumber() && node.asDouble() > 0;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String asText = value.asText("");
        return asText.isBlank() ? fallback : asText;
    }
}
