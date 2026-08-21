package com.vibesales.salesagent.tool.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.RuntimeApiResponse;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 推荐诊断写入 Tool。
 *
 * <p>调用后端 {@code POST /api/agent/runtime/diagnoses}，对应原 Coze 节点 {@code 1559861 U6-14}，
 * 写回链的第二环（{@code mergeCustomerProfile → createDiagnosis → saveHistorySummary →
 * syncIntentQueue}）。
 *
 * <p><b>补上原工作流缺的幂等键。</b>原节点 {@code retryTimes: 3} 且 body 里没有任何幂等标识，
 * 一次网络抖动就会写出多条重复诊断。这里用 {@link #idempotencyKey} 生成一个确定性键：同一份诊断
 * 内容重试多少次都是同一个键，内容不同则键不同。
 *
 * <p><b>幂等键的已知边界（未实测）</b>：键由「会话 + 客户 + 消息 ID + 诊断内容」派生。若同一会话
 * 的后续某轮产出了<b>逐字节相同</b>的诊断内容且 {@code messageId} 也相同（例如上游未透传真实
 * messageId 而落到占位值），这条本该新增的记录会被后端按重复请求丢弃。这是刻意选的方向——重复
 * 写脏数据比漏写一条内容完全相同的诊断更难修。后端是否真的按这个键去重仍需确认。
 */
public final class CreateDiagnosisTool {

    private static final Logger log = LoggerFactory.getLogger(CreateDiagnosisTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端，行为与接入前一致。 */
    public CreateDiagnosisTool() {
        this(RuntimeToolScope.disabled());
    }

    public CreateDiagnosisTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public DiagnosisWriteResult create(
            CustomerContext customerContext, DiagnosisWriteRequest request) {
        if (request == null) {
            return DiagnosisWriteResult.skipped("", "request_missing");
        }
        String skipReason = request.skipReason();
        if (!skipReason.isEmpty()) {
            return DiagnosisWriteResult.skipped(request.finalSegmentId(), skipReason);
        }
        if (!scope.available()) {
            return DiagnosisWriteResult.notConfigured(request.finalSegmentId());
        }

        String key = idempotencyKey(customerContext, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCode", scope.resolveClientCode(customerContext));
        body.put("cluster", scope.resolveCluster(customerContext));
        body.put("sceneCode", scope.resolveSceneCode(customerContext));
        body.put("conversationId", customerContext.normalizedConversationId());
        body.put("chatUser", customerContext.normalizedChatUser());
        putIfPresent(body, "sessionId", request.sessionId());
        body.put("finalSegmentId", request.finalSegmentId());
        body.put("summary", request.summary());
        body.put("skinSummary", request.skinSummary());
        putIfPresent(body, "ageRange", request.ageRange());
        putIfPresent(body, "skinType", request.skinType());
        putIfPresent(body, "concerns", request.concerns());
        putIfPresent(body, "budgetMin", request.budgetMin());
        putIfPresent(body, "budgetMax", request.budgetMax());
        putIfPresent(body, "profileSummary", request.profileSummary());
        body.put("recommendedProductIds", nullToEmpty(request.recommendedProductIds()));

        Map<String, Object> diagnosisPayload = new LinkedHashMap<>();
        diagnosisPayload.put("reasoning", nullToEmpty(request.reasoning()));
        diagnosisPayload.put(
                "nextStep",
                request.nextStep() == null || request.nextStep().isBlank()
                        ? "recommend_now"
                        : request.nextStep().trim());
        body.put("diagnosisPayload", diagnosisPayload);

        RuntimeApiResponse response = scope.apiClient().createDiagnosis(body, key);
        if (!response.success()) {
            String message = response.error().isBlank() ? response.errorCode() : response.error();
            log.warn(
                    "createDiagnosis failed, conversationId={}, errorCode={}, error={}",
                    customerContext.normalizedConversationId(),
                    response.errorCode(),
                    response.error());
            return DiagnosisWriteResult.failed(request.finalSegmentId(), message, key);
        }

        JsonNode data = response.data();
        // 后端可能返回 skipped=true（它自己判定重复或不满足写入条件），这不是失败
        if (data != null && data.path("skipped").asBoolean(false)) {
            return new DiagnosisWriteResult(
                    DiagnosisWriteResult.STATE_SKIPPED,
                    text(data, "diagnosisId"),
                    text(data, "diagnosisCode"),
                    firstNonBlank(text(data, "finalSegmentId"), request.finalSegmentId()),
                    firstNonBlank(text(data, "skipReason"), "skipped_by_backend"),
                    "",
                    key);
        }
        return new DiagnosisWriteResult(
                DiagnosisWriteResult.STATE_SUCCESS,
                text(data, "diagnosisId"),
                text(data, "diagnosisCode"),
                firstNonBlank(text(data, "finalSegmentId"), request.finalSegmentId()),
                "",
                "",
                key);
    }

    /**
     * 由「会话 + 客户 + 消息 ID + 诊断内容」派生的确定性幂等键。
     *
     * <p>用 SHA-256 而不是直接拼串：拼串会把 summary 全文带进 HTTP 头，既超长又可能带上客户原话
     * 造成信息外泄。取前 32 个 hex 字符（128 bit）足够避免碰撞。
     *
     * <p>公开而不是包内可见，是为了让对账/重放侧能用同一份输入重算出同一个键——失败结果里带回的
     * {@link DiagnosisWriteResult#idempotencyKey()} 只有能被独立复算才有对账价值。
     */
    public static String idempotencyKey(
            CustomerContext customerContext, DiagnosisWriteRequest request) {
        String seed =
                String.join(
                        "|",
                        customerContext.normalizedClientCode(),
                        customerContext.normalizedCluster(),
                        customerContext.normalizedConversationId(),
                        customerContext.normalizedChatUser(),
                        customerContext.normalizedMessageId(),
                        safe(request.finalSegmentId()),
                        safe(request.summary()),
                        safe(request.skinSummary()),
                        String.join(",", nullToEmpty(request.recommendedProductIds())));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "diag-" + hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境已经不正常了；不能静默降级成
            // 「没有幂等键」——那会让重试重复写，所以退回一个仍然确定性的弱键
            log.warn("SHA-256 unavailable, falling back to hashCode-based idempotency key", e);
            return "diag-fallback-" + Integer.toHexString(seed.hashCode());
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                return;
            }
            body.put(key, text.trim());
            return;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return;
            }
        }
        body.put(key, value);
    }

    private static List<String> nullToEmpty(List<String> value) {
        return value == null ? List.of() : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String text(JsonNode data, String field) {
        return data == null ? "" : data.path(field).asText("");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? safe(fallback) : preferred;
    }
}
