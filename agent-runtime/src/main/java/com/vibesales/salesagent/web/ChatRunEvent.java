package com.vibesales.salesagent.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 输出给前端的统一事件信封。
 */
public record ChatRunEvent(
        String runId,
        long seq,
        String phase,
        String step,
        String status,
        String label,
        String nodeType,
        String nodeName,
        Long elapsedMs,
        Map<String, Object> detail,
        long ts,
        boolean terminal) {

    public ChatRunEvent {
        // 同 ExecutionProgressUpdate：这里是键顺序的最后一道关口，用 Map.copyOf 会前功尽弃
        detail =
                detail == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }
}
