package com.agentteams.salesagent.context;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 把业务侧 {@link CustomerContext} 映射成 AgentScope 运行时使用的 {@link RuntimeContext}。
 *
 * <p><b>userId 必须带租户前缀</b>。{@code InMemoryAgentStateStore} 的存储槽位是
 * {@code (userId, sessionId)} 二元组（实测其类注释："the {@code (userId, sessionId)} pair is the
 * storage slot"）。{@code chatUser} 是各租户自己的账号体系产出的，两个租户撞出同一个
 * {@code chatUser} 完全可能——不加前缀就会串到同一个状态槽位上，这是多租户里最难查的一类问题。
 *
 * <p>前缀取 {@code clientCode + "/" + cluster}，所以这个 userId 同时是两样东西的键：状态槽位，
 * 以及 {@code TenantWorkspaceProjector} 投影租户提示词/skill 的命名空间（{@code IsolationScope.USER}
 * 的 namespace 就是 {@code List.of(userId)}）。改这里会同时挪动状态和投影目录，不是纯存储细节。
 */
public final class RuntimeContextMapper {

    public RuntimeContext map(CustomerContext customerContext) {
        return RuntimeContext.builder()
                .sessionId(customerContext.normalizedConversationId())
                .userId(tenantScopedUserId(customerContext))
                .build();
    }

    /** 租户作用域限定后的 userId，形如 {@code yjiyuncom/test/vip-8821}。 */
    public static String tenantScopedUserId(CustomerContext customerContext) {
        return customerContext.normalizedClientCode()
                + "/"
                + customerContext.normalizedCluster()
                + "/"
                + customerContext.normalizedChatUser();
    }
}
