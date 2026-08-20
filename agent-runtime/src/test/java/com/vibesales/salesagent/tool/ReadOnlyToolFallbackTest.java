package com.agentteams.salesagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.config.AppConfig;
import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.integration.runtime.MarketingAgentRuntimeApiClient;
import com.agentteams.salesagent.tool.history.GetHistorySummaryTool;
import com.agentteams.salesagent.tool.profile.GetCustomerProfileTool;
import com.agentteams.salesagent.tool.rulecontext.GetRuleContextTool;
import com.agentteams.salesagent.tool.rulecontext.RuleContextSnapshot;
import com.agentteams.salesagent.tool.taskboard.GetIntentQueueTool;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 只读 Tool 的降级路径测试。
 *
 * <p>验证核心承诺：<b>后端不可用时对话主链路不能被打断</b>。四个 Tool 在"未配置后端"和
 * "配置了但连不上"两种情况下都必须返回可用的快照对象，不抛异常。
 *
 * <p>"连不上"用一个指向未监听端口的 baseUrl 制造，不需要 mock 框架——真实走一次失败的 HTTP
 * 调用，比 mock 更能验证客户端的异常处理是否真的兜住了。
 */
class ReadOnlyToolFallbackTest {

    private static CustomerContext testContext() {
        return new CustomerContext(
                "demo-client",
                "main",
                "BEAUTY_SKINCARE",
                "customer_service",
                "",
                "conv-fallback-1",
                "robot-conv-1",
                "user-fallback-1",
                "robot-key-1",
                "user-1",
                "测试用户",
                "会话名",
                "msg-1",
                "2026-08-14T10:00:00Z",
                "1");
    }

    /** 指向一个几乎不可能被监听的本地端口，制造连接失败。 */
    private static RuntimeToolScope unreachableScope() {
        return new RuntimeToolScope(
                new MarketingAgentRuntimeApiClient(
                        "http://127.0.0.1:59187", "", Duration.ofMillis(800)),
                "demo-client",
                "main",
                "BEAUTY_SKINCARE",
                true);
    }

    @Test
    @DisplayName("未配置后端：四个 Tool 都返回占位快照，不抛异常")
    void allToolsFallBackWhenRuntimeNotConfigured() {
        CustomerContext context = testContext();
        RuntimeToolScope disabled = RuntimeToolScope.disabled();

        assertFalse(disabled.available());

        var profile = new GetCustomerProfileTool(disabled).load(context);
        assertNotNull(profile);
        assertEquals("user-fallback-1", profile.customerId());
        assertFalse(profile.hasConcern());

        var history = new GetHistorySummaryTool(disabled).load(context);
        assertNotNull(history);
        assertFalse(history.summaryText().isBlank());
        assertFalse(history.recoveryPending());

        var queue = new GetIntentQueueTool(disabled).load(context);
        assertNotNull(queue);
        assertEquals(0, queue.totalTasks());
        assertTrue(queue.queueVersion().startsWith("bootstrap-"));

        RuleContextSnapshot ruleContext = new GetRuleContextTool(disabled).load(context);
        assertNotNull(ruleContext);
        assertFalse(ruleContext.fromBackend());
        assertTrue(ruleContext.allowedProductIds().isEmpty());
    }

    @Test
    @DisplayName("后端连不上：四个 Tool 都回退而不抛异常")
    void allToolsFallBackWhenBackendUnreachable() {
        CustomerContext context = testContext();
        RuntimeToolScope unreachable = unreachableScope();

        assertTrue(unreachable.available());

        assertNotNull(new GetCustomerProfileTool(unreachable).load(context));
        assertNotNull(new GetHistorySummaryTool(unreachable).load(context));
        assertNotNull(new GetIntentQueueTool(unreachable).load(context));

        RuleContextSnapshot ruleContext = new GetRuleContextTool(unreachable).load(context);
        // 关键：连不上后端时绝不能编造商品数据，必须让调用方知道"没有可用商品"
        assertFalse(ruleContext.fromBackend());
        assertTrue(ruleContext.allowedProductIds().isEmpty());
    }

    @Test
    @DisplayName("无参构造（占位模式）等价于未配置后端")
    void noArgConstructorsBehaveAsDisabled() {
        CustomerContext context = testContext();

        assertNotNull(new GetCustomerProfileTool().load(context));
        assertNotNull(new GetHistorySummaryTool().load(context));
        assertNotNull(new GetIntentQueueTool().load(context));
        assertFalse(new GetRuleContextTool().load(context).fromBackend());
    }

    @Test
    @DisplayName("clientCode/cluster/sceneCode 解析：请求上下文优先于配置默认值")
    void contextValuesTakePrecedenceOverDefaults() {
        RuntimeToolScope scope =
                new RuntimeToolScope(null, "default-client", "default-cluster", "DEFAULT_SCENE", false);

        assertEquals("demo-client", scope.resolveClientCode(testContext()));
        assertEquals("main", scope.resolveCluster(testContext()));
        assertEquals("BEAUTY_SKINCARE", scope.resolveSceneCode(testContext()));
    }

    @Test
    @DisplayName("请求上下文缺失时回落到配置默认值")
    void defaultsUsedWhenContextValuesBlank() {
        RuntimeToolScope scope =
                new RuntimeToolScope(null, "default-client", "default-cluster", "DEFAULT_SCENE", false);
        CustomerContext blank =
                new CustomerContext(
                        null,
                        "  ",
                        null,
                        "",
                        "",
                        "conv-1",
                        null,
                        "user-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertEquals("default-client", scope.resolveClientCode(blank));
        assertEquals("default-cluster", scope.resolveCluster(blank));
        assertEquals("DEFAULT_SCENE", scope.resolveSceneCode(blank));
    }

    @Test
    @DisplayName("runtime 可用性不应依赖默认 clientCode/cluster，只要 baseUrl 存在且请求会带 scope 就应启用")
    void runtimeScopeEnabledWithoutDefaultScope() {
        AppConfig config =
                new AppConfig(
                        "model",
                        "http://model.test",
                        "key",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "agent_conversations",
                        "agent_chat_runs",
                        "agent_chat_run_events",
                        "http://localhost:3002",
                        "",
                        "classpath",
                        false,
                        "",
                        "",
                        "/api/v1/blueprints/published",
                        3000,
                        5000,
                        5000,
                        "",
                        "",
                        "",
                        5,
                        false,
                        true,
                        "",
                        "",
                        "BEAUTY_SKINCARE",
                        ".agentscope/workspace",
                        "sales-customer-agent",
                        false,
                        "",
                        false,
                        "",
                        "",
                        "",
                        "");

        RuntimeToolScope scope = RuntimeToolScope.from(config);

        assertTrue(config.runtimeApiConfigured());
        assertTrue(scope.available());
        assertEquals("demo-client", scope.resolveClientCode(testContext()));
        assertEquals("main", scope.resolveCluster(testContext()));
    }
}
