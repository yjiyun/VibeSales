package com.vibesales.salesagent.tool.profile;

/**
 * 共享画像合并结果。
 *
 * <p>{@code skipped=true} 表示"本轮没有可写内容，请求没发出去"，这是<b>正常路径</b>而不是失败——
 * 调用方不能把它当错误上报。{@code merged=false} 且 {@code skipped=false} 才是真的写失败。
 */
public record CustomerProfileMergeResult(
        boolean merged, boolean skipped, String skipReason, String chatUser, String errorMessage) {

    public static CustomerProfileMergeResult skipped(String chatUser, String reason) {
        return new CustomerProfileMergeResult(false, true, reason, chatUser, "");
    }

    public static CustomerProfileMergeResult merged(String chatUser) {
        return new CustomerProfileMergeResult(true, false, "", chatUser, "");
    }

    public static CustomerProfileMergeResult failed(String chatUser, String errorMessage) {
        return new CustomerProfileMergeResult(false, false, "", chatUser, errorMessage);
    }
}
