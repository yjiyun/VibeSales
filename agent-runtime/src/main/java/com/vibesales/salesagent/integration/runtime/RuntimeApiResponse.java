package com.vibesales.salesagent.integration.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code marketing-agent-service} agent runtime 接口的统一响应信封。
 *
 * <p>后端成功响应形如 <code>{"success":true,"traceId":...,"scope":{...},"data":{...}}</code>，
 * 失败响应形如 <code>{"success":false,"error":"...","errorCode":"not_found","retryable":false,...}</code>。
 *
 * <p><b>已核实的信封不一致</b>：{@code GET /rule-context} 的失败分支只返回 {@code success}/{@code error}
 * 两个字段，没有 {@code errorCode}/{@code retryable}/{@code traceId}。所以调用方不能假设
 * {@link #errorCode()} 在所有接口的失败响应里都有值——判断失败要用 {@link #success()}，
 * 判断具体失败原因才看 {@link #errorCode()}（可能为空字符串）。
 */
public final class RuntimeApiResponse {
    private final boolean success;
    private final JsonNode data;
    private final String error;
    private final String errorCode;
    private final boolean retryable;
    private final int httpStatus;

    public RuntimeApiResponse(
            boolean success,
            JsonNode data,
            String error,
            String errorCode,
            boolean retryable,
            int httpStatus) {
        this.success = success;
        this.data = data;
        this.error = error == null ? "" : error;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public boolean success() {
        return success;
    }

    /** 成功时的业务数据节点；失败时为 {@code null}。 */
    public JsonNode data() {
        return data;
    }

    public String error() {
        return error;
    }

    /** 失败原因码；{@code rule-context} 接口失败时该值为空字符串，见类注释。 */
    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 是否属于"资源不存在"这类可以按业务空值处理的失败。
     *
     * <p>典型场景：新客户还没有画像时，{@code GET /customer-profile} 返回 404 +
     * {@code errorCode=not_found}。这不是故障，是正常业务状态——调用方应该把它当"空画像"，
     * 而不是当调用失败去触发降级告警。
     */
    public boolean notFound() {
        return !success && (httpStatus == 404 || "not_found".equals(errorCode));
    }
}
