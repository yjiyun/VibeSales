package com.vibesales.salesagent.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibesales.salesagent.tool.taskboard.IntentTaskSnapshot;

/**
 * 把 {@code GET /intent-queue} 的响应 DTO 转成 {@link IntentTaskSnapshot}。
 *
 * <p><b>关于 totalTasks 的算法</b>：后端不直接返回总数，只返回 {@code items} 数组和三个分状态计数器
 * （{@code suspendedIntentCount}/{@code sleepingIntentCount}/{@code closedIntentCount}）。这里用
 * {@code items.size()} 作为总数——因为 {@code items} 是当前队列的真实条目列表，而三个计数器
 * 相加会漏掉 active 状态的条目（后端没有 activeIntentCount 字段，只有 {@code activeIntentCode}
 * 表示哪一个是活跃的）。
 *
 * <p><b>关于 queueVersion 的类型</b>：后端返回的是数值（新会话为 {@code 0}），而
 * {@link IntentTaskSnapshot} 用 String 承载。这里转成字符串保留原值，
 * 不做任何格式化——因为 {@code POST /intent-queue/sync} 的乐观锁校验要求回传的
 * {@code queueVersion} 必须是非负整数，将来接入写操作 Tool 时需要原值可解析回数值。
 */
public final class IntentQueueMapper {

    private IntentQueueMapper() {}

    /**
     * @param data {@code GET /intent-queue} 成功响应里的 {@code data} 节点
     */
    public static IntentTaskSnapshot fromResponse(JsonNode data, String fallbackQueueVersion) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return emptyQueue(fallbackQueueVersion);
        }

        JsonNode items = data.path("items");
        int totalTasks = items.isArray() ? items.size() : 0;

        // active 数量：后端只给 activeIntentCode（哪一个活跃），没有计数器，
        // 所以按"有非空 activeIntentCode 就是 1 个活跃任务"推导——这符合场景卡片8
        // "本轮只处理最高优先级的一个意图"的设计（同时最多一个 active）
        String activeIntentCode = data.path("activeIntentCode").asText("");
        int activeTasks = activeIntentCode.isBlank() ? 0 : 1;

        int suspendedTasks = data.path("suspendedIntentCount").asInt(0);

        String queueVersion = data.path("queueVersion").asText(fallbackQueueVersion);
        if (queueVersion.isBlank()) {
            queueVersion = fallbackQueueVersion;
        }

        return new IntentTaskSnapshot(totalTasks, activeTasks, suspendedTasks, queueVersion);
    }

    public static IntentTaskSnapshot emptyQueue(String queueVersion) {
        return new IntentTaskSnapshot(0, 0, 0, queueVersion);
    }
}
