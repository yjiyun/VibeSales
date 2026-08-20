package com.agentteams.salesagent.tool.taskboard;

/**
 * 任务板读取结果的最小快照对象。
 *
 * <p>它记录当前任务总数、活跃任务数、挂起任务数和队列版本号，
 * 用来给最小闭环阶段展示“任务板大概是什么状态”，后续再扩展到真实任务明细。
 */
public final class IntentTaskSnapshot {
    private final int totalTasks;
    private final int activeTasks;
    private final int suspendedTasks;
    private final String queueVersion;

    public IntentTaskSnapshot(int totalTasks, int activeTasks, int suspendedTasks, String queueVersion) {
        this.totalTasks = totalTasks;
        this.activeTasks = activeTasks;
        this.suspendedTasks = suspendedTasks;
        this.queueVersion = queueVersion;
    }

    public int totalTasks() {
        return totalTasks;
    }

    public int activeTasks() {
        return activeTasks;
    }

    public int suspendedTasks() {
        return suspendedTasks;
    }

    public String queueVersion() {
        return queueVersion;
    }
}
