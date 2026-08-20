package com.agentteams.salesagent.tool.profile;

/**
 * 客户画像 Tool 的返回对象。
 *
 * <p>除了客户标识、画像摘要和版本号，还保留了场景卡片1画像充分度公式需要的六个布尔信号
 * （{@code hasConcern}/{@code hasTargetBenefit}/{@code hasCoreNeed}/{@code hasSkinType}/
 * {@code hasBudget}/{@code hasCategoryPreference}），供 {@code ProfileCompletenessRule} 消费。
 * 当前 {@link GetCustomerProfileTool} 仍是占位实现，这些信号默认返回 {@code false}；
 * 接入真实 {@code customer-profile} 接口后应替换为后端返回的真实字段。
 */
public final class CustomerProfileSnapshot {
    private final String customerId;
    private final String summary;
    private final String profileVersion;
    private final boolean hasConcern;
    private final boolean hasTargetBenefit;
    private final boolean hasCoreNeed;
    private final boolean hasSkinType;
    private final boolean hasBudget;
    private final boolean hasCategoryPreference;

    public CustomerProfileSnapshot(
            String customerId,
            String summary,
            String profileVersion,
            boolean hasConcern,
            boolean hasTargetBenefit,
            boolean hasCoreNeed,
            boolean hasSkinType,
            boolean hasBudget,
            boolean hasCategoryPreference) {
        this.customerId = customerId;
        this.summary = summary;
        this.profileVersion = profileVersion;
        this.hasConcern = hasConcern;
        this.hasTargetBenefit = hasTargetBenefit;
        this.hasCoreNeed = hasCoreNeed;
        this.hasSkinType = hasSkinType;
        this.hasBudget = hasBudget;
        this.hasCategoryPreference = hasCategoryPreference;
    }

    /** 占位/测试场景下的简化构造：只给基础三个字段，六个画像信号全部置为 {@code false}。 */
    public static CustomerProfileSnapshot placeholder(
            String customerId, String summary, String profileVersion) {
        return new CustomerProfileSnapshot(
                customerId, summary, profileVersion, false, false, false, false, false, false);
    }

    public String customerId() {
        return customerId;
    }

    public String summary() {
        return summary;
    }

    public String profileVersion() {
        return profileVersion;
    }

    public boolean hasConcern() {
        return hasConcern;
    }

    public boolean hasTargetBenefit() {
        return hasTargetBenefit;
    }

    public boolean hasCoreNeed() {
        return hasCoreNeed;
    }

    public boolean hasSkinType() {
        return hasSkinType;
    }

    public boolean hasBudget() {
        return hasBudget;
    }

    public boolean hasCategoryPreference() {
        return hasCategoryPreference;
    }
}
