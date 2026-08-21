package com.vibesales.salesagent.tool.profile;

import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.integration.runtime.RuntimeApiResponse;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户主档共享画像合并 Tool。
 *
 * <p>调用后端 {@code POST /api/agent/runtime/customer-profile/merge}，对应原 Coze 节点
 * {@code 2604003 U15-02C}，写回链的第一环（{@code mergeCustomerProfile → createDiagnosis →
 * saveHistorySummary → syncIntentQueue}）。
 *
 * <p><b>为什么是编排骨架 Tool 而不是模型可调用的内置 Tool</b>：主档是跨会话共享的真值，写入时机
 * 必须固定在 run 收口阶段。交给模型自行裁量，会出现"同一轮里模型觉得该写就写两次"，而合并语义
 * （{@code addToSet}）本身不幂等。
 *
 * <p><b>空值门禁</b>：{@link CustomerProfileMergeRequest#hasMeaningfulWrite()} 为 false 时直接返回
 * {@code skipped}，不发请求。这是原 {@code 2604002 U15-02B} 门禁节点的等价物，不能省——省掉就会
 * 用空值覆盖稳定值。
 *
 * <p><b>失败不抛异常</b>：与两个只读 Tool 一致，画像合并失败不该打断本轮回复。回复已经发给客户了，
 * 为一次画像写失败把整轮 run 判失败，代价和收益不成比例；返回 {@code failed} 让上层留痕即可。
 */
public final class MergeCustomerProfileTool {

    private static final Logger log = LoggerFactory.getLogger(MergeCustomerProfileTool.class);

    private final RuntimeToolScope scope;

    /** 占位构造：不接后端，行为与接入前一致。 */
    public MergeCustomerProfileTool() {
        this(RuntimeToolScope.disabled());
    }

    public MergeCustomerProfileTool(RuntimeToolScope scope) {
        this.scope = scope;
    }

    public CustomerProfileMergeResult merge(
            CustomerContext customerContext, CustomerProfileMergeRequest request) {
        String chatUser = customerContext.normalizedChatUser();

        if (request == null || !request.hasMeaningfulWrite()) {
            return CustomerProfileMergeResult.skipped(chatUser, "no_meaningful_write");
        }
        if (!scope.available()) {
            return CustomerProfileMergeResult.skipped(chatUser, "runtime_api_not_configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCode", scope.resolveClientCode(customerContext));
        body.put("cluster", scope.resolveCluster(customerContext));
        body.put("sceneCode", scope.resolveSceneCode(customerContext));
        body.put("chatUser", chatUser);
        body.put("sourceChatUser", chatUser);
        body.put("nickname", customerContext.normalizedUserName());
        body.put("source", "agent_runtime_writeback");
        body.put("set", request.set());
        body.put("addToSet", request.addToSet());
        body.put("removeFromSet", request.removeFromSet());
        body.put("clearFields", request.clearFields());
        body.put("domainProfiles", request.domainProfiles());
        body.put("summary", request.summary());
        body.put("updatedBy", "MergeCustomerProfileTool");
        body.put("updatedFromIntentCode", request.updatedFromIntentCode());
        body.put("updatedFromIntentKey", request.updatedFromIntentKey());
        body.put("sourceNode", request.sourceNode());
        body.put("confidence", request.confidence());

        RuntimeApiResponse response = scope.apiClient().mergeCustomerProfile(body);
        if (!response.success()) {
            String message = response.error().isBlank() ? response.errorCode() : response.error();
            log.warn(
                    "mergeCustomerProfile failed, chatUser={}, errorCode={}, error={}",
                    chatUser,
                    response.errorCode(),
                    response.error());
            return CustomerProfileMergeResult.failed(chatUser, message);
        }
        return CustomerProfileMergeResult.merged(chatUser);
    }
}
