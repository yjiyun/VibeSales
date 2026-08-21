package com.vibesales.salesagent.tool.profile;

import java.util.List;
import java.util.Map;

/**
 * 客户主档共享画像合并入参。
 *
 * <p>字段来自原 Coze 节点 {@code 2604001 U15-02A 共享画像写回入参组装}。五个动作字段各表达一种
 * 合并语义，<b>不是</b>一份可以整体覆写的画像：
 *
 * <ul>
 *   <li>{@code set} —— 覆盖标量字段（肤质、年龄段等）
 *   <li>{@code addToSet} —— 往集合字段里追加且去重（关注点、忌讳成分等）
 *   <li>{@code removeFromSet} —— 从集合字段里移除
 *   <li>{@code clearFields} —— 显式清空某个字段，与"没抽到所以不传"区分开
 *   <li>{@code domainProfiles} —— 按业务域分区的子画像
 * </ul>
 *
 * <p>{@code source}/{@code updatedBy}/{@code sourceNode}/{@code confidence} 是写入溯源字段：
 * 主档是跨会话共享的真值，出现脏数据时必须能查出"是哪一轮、哪个意图、哪个环节写进去的"。
 */
public record CustomerProfileMergeRequest(
        Map<String, Object> set,
        Map<String, List<String>> addToSet,
        Map<String, List<String>> removeFromSet,
        List<String> clearFields,
        Map<String, Object> domainProfiles,
        String summary,
        String updatedFromIntentCode,
        String updatedFromIntentKey,
        String sourceNode,
        String confidence) {

    public static CustomerProfileMergeRequest empty() {
        return new CustomerProfileMergeRequest(
                Map.of(), Map.of(), Map.of(), List.of(), Map.of(), "", "", "", "", "");
    }

    /**
     * 本轮是否真的有东西要写。
     *
     * <p>对齐原 {@code U15-02A} 的 {@code hasMeaningfulWrite} + {@code 2604002 U15-02B} 门禁：
     * 五个动作字段全空就<b>不发请求</b>。空 body 打到合并接口，语义上等于"请把这个客户的画像
     * 按空值合并一次"，后端要么白跑一次要么真把稳定值覆盖成空——两种都不能接受。
     */
    public boolean hasMeaningfulWrite() {
        return !isEmpty(set)
                || !isEmpty(addToSet)
                || !isEmpty(removeFromSet)
                || !isEmpty(domainProfiles)
                || (clearFields != null && !clearFields.isEmpty());
    }

    private static boolean isEmpty(Map<String, ?> value) {
        return value == null || value.isEmpty();
    }
}
