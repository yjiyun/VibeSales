package com.vibesales.salesagent.progress;

/**
 * 编排层执行进度监听器。
 */
@FunctionalInterface
public interface ExecutionProgressListener {

    ExecutionProgressListener NOOP = update -> {};

    void onUpdate(ExecutionProgressUpdate update);

    static ExecutionProgressListener noop() {
        return NOOP;
    }
}
