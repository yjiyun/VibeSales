package com.agentteams.salesagent.skill;

/**
 * 恢复判断结果对象。
 *
 * <p>它用于把恢复链路的输出结构化，避免页面层或主 Agent 直接依赖临时字符串拼接。
 */
public final class RecoveryDecision {
    private final boolean recoveryMessage;
    private final String recoveryMode;
    private final String targetIntent;
    private final String promptHint;

    public RecoveryDecision(
            boolean recoveryMessage, String recoveryMode, String targetIntent, String promptHint) {
        this.recoveryMessage = recoveryMessage;
        this.recoveryMode = recoveryMode;
        this.targetIntent = targetIntent;
        this.promptHint = promptHint;
    }

    public boolean recoveryMessage() {
        return recoveryMessage;
    }

    public String recoveryMode() {
        return recoveryMode;
    }

    public String targetIntent() {
        return targetIntent;
    }

    public String promptHint() {
        return promptHint;
    }
}
