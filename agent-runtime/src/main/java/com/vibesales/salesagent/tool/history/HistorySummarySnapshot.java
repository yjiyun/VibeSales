package com.vibesales.salesagent.tool.history;

/**
 * 历史摘要 Tool 的返回快照对象。
 *
 * <p>当前阶段先把“历史总结文本、是否处于恢复态、当前活跃意图”收口成一个简单只读对象，
 * 供主 Agent 和恢复判断链路直接消费，后续再替换为真实接口返回结构。
 */
public final class HistorySummarySnapshot {
    private final String summaryText;
    private final boolean recoveryPending;
    private final String activeIntentCode;

    public HistorySummarySnapshot(String summaryText, boolean recoveryPending, String activeIntentCode) {
        this.summaryText = summaryText;
        this.recoveryPending = recoveryPending;
        this.activeIntentCode = activeIntentCode;
    }

    public String summaryText() {
        return summaryText;
    }

    public boolean recoveryPending() {
        return recoveryPending;
    }

    public String activeIntentCode() {
        return activeIntentCode;
    }
}
