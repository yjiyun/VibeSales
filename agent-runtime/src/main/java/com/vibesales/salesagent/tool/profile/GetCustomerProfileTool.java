package com.vibesales.salesagent.tool.profile;

import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.RuntimeApiResponse;
import com.vibesales.salesagent.mapping.CustomerProfileMapper;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户画像读取 Tool。
 *
 * <p>调用后端 {@code GET /api/agent/runtime/customer-profile}，输出 {@link CustomerProfileSnapshot}
 * ——其中六个画像布尔信号是 {@code ProfileCompletenessRule} 执行场景卡片1充分度公式的唯一输入。
 *
 * <p>这一层只做"对外暴露方法 + 返回快照对象"，HTTP 细节在 {@code integration/runtime}，
 * 字段转换在 {@code mapping}，符合04号文档的三层落位设计。
 *
 * <p><b>三条降级路径</b>，都不抛异常打断对话主链路：
 * <ul>
 *   <li>未配置后端 → 返回占位画像
 *   <li>后端 404（新客户还没画像）→ 返回空画像，这是<b>正常业务状态不是故障</b>，只记 debug 日志
 *   <li>其他调用失败（超时/5xx/解析失败）→ 返回占位画像并记 warn 日志
 * </ul>
 */
public final class GetCustomerProfileTool {

    private static final Logger log = LoggerFactory.getLogger(GetCustomerProfileTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端，行为与接入前一致。 */
    public GetCustomerProfileTool() {
        this(RuntimeToolScope.disabled());
    }

    public GetCustomerProfileTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public CustomerProfileSnapshot load(CustomerContext customerContext) {
        String chatUser = customerContext.normalizedChatUser();

        if (!scope.available()) {
            return placeholderSnapshot(chatUser);
        }

        String sceneCode = scope.resolveSceneCode(customerContext);
        RuntimeApiResponse response =
                scope.apiClient()
                        .getCustomerProfile(
                                scope.resolveClientCode(customerContext),
                                scope.resolveCluster(customerContext),
                                sceneCode,
                                chatUser);

        if (response.success()) {
            return CustomerProfileMapper.fromResponse(response.data(), chatUser);
        }

        if (response.notFound()) {
            // 新客户尚无画像记录，属正常业务状态：空画像会让 ProfileCompletenessRule
            // 正确判定"不能推荐、需要追问"，这正是期望行为
            log.debug(
                    "customer profile not found (new customer), chatUser={}, sceneCode={}",
                    chatUser,
                    sceneCode);
            return CustomerProfileMapper.emptyProfile(chatUser);
        }

        log.warn(
                "customer profile call failed, falling back to placeholder. chatUser={}, errorCode={}, error={}",
                chatUser,
                response.errorCode(),
                response.error());
        return placeholderSnapshot(chatUser);
    }

    private static CustomerProfileSnapshot placeholderSnapshot(String chatUser) {
        return CustomerProfileSnapshot.placeholder(
                chatUser, "后端画像接口不可用，当前返回占位画像。", "placeholder");
    }
}
