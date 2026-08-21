package com.vibesales.salesagent.progress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排层上报给可视化链路的执行进度事件。
 *
 * <p>该对象与具体传输方式解耦：当前阶段会被 Web 层转成 SSE 事件，后续也可复用于日志、审计或调试面板。
 */
public record ExecutionProgressUpdate(
        String phase,
        String step,
        String status,
        String label,
        Long elapsedMs,
        Map<String, Object> detail) {

    public ExecutionProgressUpdate {
        // 不能用 Map.copyOf：它返回无序 immutable map，会把各节点刻意排好的键顺序打乱。
        // 消费端（工作台时间线"补充明细"）只渲染前 8 个键，顺序丢了等于关键字段看不见。
        detail =
                detail == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }
}
