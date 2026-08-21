package com.vibesales.salesagent.observability;

public final class RuntimeTelemetryInstallSelfTest {

    public static void main(String[] args) {
        if (!"vibe-sales-runtime".equals(RuntimeTelemetry.DEFAULT_SERVICE_NAME)) {
            throw new AssertionError("default service name drifted: " + RuntimeTelemetry.DEFAULT_SERVICE_NAME);
        }
        if (RuntimeTelemetry.otlpMode()) {
            throw new AssertionError("otlp mode should be off before install");
        }
        System.out.println("[PASS] runtime telemetry default service name stays stable");
    }
}
