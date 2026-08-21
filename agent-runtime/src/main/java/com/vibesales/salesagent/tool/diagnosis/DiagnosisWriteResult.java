package com.vibesales.salesagent.tool.diagnosis;

/**
 * 诊断写入结果。
 *
 * <p>{@code writeState} 取值对齐原 {@code 107354 U6-15 诊断写入结果收口} 的四态：
 * {@code success} / {@code skipped} / {@code failed} / {@code not_configured}。
 * 四态分开是有用的——{@code skipped} 是业务上"本轮不该写"，{@code failed} 才需要告警。
 */
public record DiagnosisWriteResult(
        String writeState,
        String diagnosisId,
        String diagnosisCode,
        String finalSegmentId,
        String skipReason,
        String errorMessage,
        String idempotencyKey) {

    public static final String STATE_SUCCESS = "success";
    public static final String STATE_SKIPPED = "skipped";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_NOT_CONFIGURED = "not_configured";

    public boolean written() {
        return STATE_SUCCESS.equals(writeState);
    }

    public static DiagnosisWriteResult skipped(String finalSegmentId, String skipReason) {
        return new DiagnosisWriteResult(
                STATE_SKIPPED, "", "", finalSegmentId, skipReason, "", "");
    }

    public static DiagnosisWriteResult notConfigured(String finalSegmentId) {
        return new DiagnosisWriteResult(
                STATE_NOT_CONFIGURED, "", "", finalSegmentId, "runtime_api_not_configured", "", "");
    }

    public static DiagnosisWriteResult failed(
            String finalSegmentId, String errorMessage, String idempotencyKey) {
        return new DiagnosisWriteResult(
                STATE_FAILED, "", "", finalSegmentId, "", errorMessage, idempotencyKey);
    }
}
