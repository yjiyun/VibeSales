package com.vibesales.salesagent.tool.diagnosis;

import java.util.List;

/**
 * 推荐诊断写入入参。
 *
 * <p>字段来自原 Coze 节点 {@code 106062 U6-12 决策结果清洗与映射} 产出的 {@code diagnosisRequest}，
 * 由 {@code 1559861 U6-14} 发往 {@code POST /diagnoses}。
 *
 * <p>{@code reasoning} 是推荐理由链，{@code nextStep} 是下一步动作码（默认 {@code recommend_now}）。
 * 这两个字段在原工作流里包在 {@code diagnosisPayload} 对象内，这里保持同样的嵌套形状发出去，
 * 避免后端要同时兼容两种 body 形状。
 */
public record DiagnosisWriteRequest(
        String sessionId,
        String finalSegmentId,
        String summary,
        String skinSummary,
        String ageRange,
        String skinType,
        List<String> concerns,
        Integer budgetMin,
        Integer budgetMax,
        String profileSummary,
        List<String> recommendedProductIds,
        List<String> reasoning,
        String nextStep) {

    /**
     * 本轮是否够条件写诊断。
     *
     * <p>对齐原 {@code U6-12} 的 {@code skipReason} 判定与 {@code 158133 U6-13} 门禁：缺 segmentId
     * 的诊断记录挂不到任何人群分段上，写进去就是脏数据；{@code summary} 与 {@code skinSummary} 同时
     * 为空的记录没有任何可读内容。两种都直接不写。
     *
     * @return 不该写时返回原因码，该写时返回空串
     */
    public String skipReason() {
        if (isBlank(finalSegmentId)) {
            return "final_segment_id_missing";
        }
        if (isBlank(summary) && isBlank(skinSummary)) {
            return "summary_and_skin_summary_both_blank";
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
