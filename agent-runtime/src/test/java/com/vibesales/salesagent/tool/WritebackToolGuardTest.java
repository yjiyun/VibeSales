package com.vibesales.salesagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.MarketingAgentRuntimeApiClient;
import com.vibesales.salesagent.tool.diagnosis.CreateDiagnosisTool;
import com.vibesales.salesagent.tool.diagnosis.DiagnosisWriteRequest;
import com.vibesales.salesagent.tool.diagnosis.DiagnosisWriteResult;
import com.vibesales.salesagent.tool.profile.CustomerProfileMergeRequest;
import com.vibesales.salesagent.tool.profile.CustomerProfileMergeResult;
import com.vibesales.salesagent.tool.profile.MergeCustomerProfileTool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个写回 Tool 的门禁与幂等行为测试。
 *
 * <p>这两个 Tool 补的是原 Coze 工作流的两处真实缺陷，所以测试重点不是"能发出 HTTP 请求"，而是
 * 两条不能退让的纪律：
 *
 * <ol>
 *   <li><b>空值不发请求</b>——原 {@code 2604002 U15-02B} / {@code 158133 U6-13} 两个门禁节点的等价物。
 *       没了门禁，空值会覆盖主档里的稳定值。
 *   <li><b>诊断写入带确定性幂等键</b>——原 {@code 1559861 U6-14} 是 {@code retryTimes: 3} 且无幂等键，
 *       重试会重复写。同一份内容必须得到同一个键，不同内容必须得到不同键。
 * </ol>
 */
class WritebackToolGuardTest {

    private static CustomerContext testContext() {
        return new CustomerContext(
                "yjiyuncom",
                "guyu",
                "BEAUTY_SKINCARE",
                "yjiyuncom_guyu_agent",
                "1",
                "conv-writeback-1",
                "robot-conv-1",
                "user-writeback-1",
                "robot-key-1",
                "user-1",
                "测试用户",
                "会话名",
                "msg-1",
                "2026-08-20T10:00:00Z",
                "1");
    }

    /** 指向一个几乎不可能被监听的本地端口：走到这里说明门禁没挡住，请求真发出去了。 */
    private static RuntimeToolScope unreachableScope() {
        return new RuntimeToolScope(
                new MarketingAgentRuntimeApiClient(
                        "http://127.0.0.1:59187", "", Duration.ofMillis(800)),
                "yjiyuncom",
                "guyu",
                "BEAUTY_SKINCARE",
                true);
    }

    private static DiagnosisWriteRequest fullDiagnosis() {
        return new DiagnosisWriteRequest(
                "session-1",
                "seg-oily-acne",
                "油皮控油方案",
                "油性肌，主要问题是痘印",
                "25-30",
                "oily",
                List.of("痘印"),
                200,
                600,
                "老客",
                List.of("p-1", "p-2"),
                List.of("控油优先", "预算匹配中端线"),
                "recommend_now");
    }

    @Test
    @DisplayName("画像合并：五个动作字段全空时不发请求，直接 skipped")
    void mergeSkipsWhenNothingToWrite() {
        CustomerProfileMergeResult result =
                new MergeCustomerProfileTool(unreachableScope())
                        .merge(testContext(), CustomerProfileMergeRequest.empty());

        // 关键：走的是 skipped 而不是 failed——failed 说明请求发出去了才失败，门禁就没起作用
        assertTrue(result.skipped());
        assertFalse(result.merged());
        assertEquals("no_meaningful_write", result.skipReason());
    }

    @Test
    @DisplayName("画像合并：只有 clearFields 也算有内容，必须真的发请求")
    void mergeTreatsClearFieldsAsMeaningful() {
        CustomerProfileMergeRequest request =
                new CustomerProfileMergeRequest(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of("budgetMax"),
                        Map.of(),
                        "",
                        "product_recommend",
                        "",
                        "U15-02A",
                        "medium");

        assertTrue(request.hasMeaningfulWrite());

        CustomerProfileMergeResult result =
                new MergeCustomerProfileTool(unreachableScope()).merge(testContext(), request);

        // "显式清空某字段"与"本轮没抽到所以不传"是两件事，前者必须写下去
        assertFalse(result.skipped());
        assertFalse(result.merged());
        assertFalse(result.errorMessage().isBlank());
    }

    @Test
    @DisplayName("画像合并：未配置后端时也是 skipped 而不是异常")
    void mergeSkipsWhenRuntimeNotConfigured() {
        CustomerProfileMergeRequest request =
                new CustomerProfileMergeRequest(
                        Map.of("skinType", "oily"),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of(),
                        "",
                        "",
                        "",
                        "",
                        "");

        CustomerProfileMergeResult result =
                new MergeCustomerProfileTool().merge(testContext(), request);

        assertTrue(result.skipped());
        assertEquals("runtime_api_not_configured", result.skipReason());
    }

    @Test
    @DisplayName("诊断写入：缺 segmentId 或摘要全空时不发请求")
    void diagnosisSkipsWhenNotWritable() {
        CreateDiagnosisTool tool = new CreateDiagnosisTool(unreachableScope());

        DiagnosisWriteRequest noSegment =
                new DiagnosisWriteRequest(
                        "session-1", "", "有摘要", "有肤质摘要", null, null, null, null, null, null,
                        List.of(), List.of(), "recommend_now");
        DiagnosisWriteResult r1 = tool.create(testContext(), noSegment);
        assertEquals(DiagnosisWriteResult.STATE_SKIPPED, r1.writeState());
        assertEquals("final_segment_id_missing", r1.skipReason());

        DiagnosisWriteRequest noSummary =
                new DiagnosisWriteRequest(
                        "session-1", "seg-1", "  ", "", null, null, null, null, null, null,
                        List.of(), List.of(), "recommend_now");
        DiagnosisWriteResult r2 = tool.create(testContext(), noSummary);
        assertEquals(DiagnosisWriteResult.STATE_SKIPPED, r2.writeState());
        assertEquals("summary_and_skin_summary_both_blank", r2.skipReason());
    }

    @Test
    @DisplayName("诊断写入：同一份内容重复调用得到同一个幂等键")
    void diagnosisIdempotencyKeyIsDeterministic() {
        String first = CreateDiagnosisTool.idempotencyKey(testContext(), fullDiagnosis());
        String second = CreateDiagnosisTool.idempotencyKey(testContext(), fullDiagnosis());

        // 这一条正是原工作流缺的：retryTimes=3 时三次重试必须携带同一个键，否则写三条
        assertEquals(first, second);
        assertTrue(first.startsWith("diag-"));
    }

    @Test
    @DisplayName("诊断写入：内容变了幂等键必须变，否则真正的新诊断会被当成重复丢掉")
    void diagnosisIdempotencyKeyChangesWithContent() {
        String base = CreateDiagnosisTool.idempotencyKey(testContext(), fullDiagnosis());

        DiagnosisWriteRequest otherSegment =
                new DiagnosisWriteRequest(
                        "session-1", "seg-dry-sensitive", "油皮控油方案", "油性肌，主要问题是痘印",
                        "25-30", "oily", List.of("痘印"), 200, 600, "老客",
                        List.of("p-1", "p-2"), List.of("控油优先", "预算匹配中端线"), "recommend_now");
        assertNotEquals(base, CreateDiagnosisTool.idempotencyKey(testContext(), otherSegment));

        DiagnosisWriteRequest otherProducts =
                new DiagnosisWriteRequest(
                        "session-1", "seg-oily-acne", "油皮控油方案", "油性肌，主要问题是痘印",
                        "25-30", "oily", List.of("痘印"), 200, 600, "老客",
                        List.of("p-3"), List.of("控油优先", "预算匹配中端线"), "recommend_now");
        assertNotEquals(base, CreateDiagnosisTool.idempotencyKey(testContext(), otherProducts));
    }

    @Test
    @DisplayName("诊断写入：不同会话的相同内容不能共用幂等键")
    void diagnosisIdempotencyKeyIsScopedToConversation() {
        CustomerContext other =
                new CustomerContext(
                        "yjiyuncom",
                        "guyu",
                        "BEAUTY_SKINCARE",
                        "yjiyuncom_guyu_agent",
                        "1",
                        "conv-writeback-2",
                        "robot-conv-1",
                        "user-writeback-1",
                        "robot-key-1",
                        "user-1",
                        "测试用户",
                        "会话名",
                        "msg-1",
                        "2026-08-20T10:00:00Z",
                        "1");

        assertNotEquals(
                CreateDiagnosisTool.idempotencyKey(testContext(), fullDiagnosis()),
                CreateDiagnosisTool.idempotencyKey(other, fullDiagnosis()));
    }

    @Test
    @DisplayName("诊断写入：未配置后端时返回 not_configured，不抛异常")
    void diagnosisReportsNotConfigured() {
        DiagnosisWriteResult result = new CreateDiagnosisTool().create(testContext(), fullDiagnosis());

        assertEquals(DiagnosisWriteResult.STATE_NOT_CONFIGURED, result.writeState());
        assertFalse(result.written());
    }

    @Test
    @DisplayName("诊断写入：后端连不上时返回 failed 并带上幂等键，便于对账重放")
    void diagnosisReportsFailureWithKey() {
        DiagnosisWriteResult result =
                new CreateDiagnosisTool(unreachableScope()).create(testContext(), fullDiagnosis());

        assertEquals(DiagnosisWriteResult.STATE_FAILED, result.writeState());
        assertFalse(result.idempotencyKey().isBlank());
    }
}
