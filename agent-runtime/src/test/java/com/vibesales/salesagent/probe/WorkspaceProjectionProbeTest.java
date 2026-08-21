package com.vibesales.salesagent.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 探测程序：验证"单 Agent + 每请求投影 workspace 文件 + isolationScope(USER)"是否真的可行。
 *
 * <p>不是业务单元测试，是对 harness 行为的事实核查，要回答三个问题：
 *
 * <ol>
 *   <li>{@code WorkspaceManager.readAgentsMd(rc)} 是否真的随 {@code rc.userId} 变化读到不同文件；
 *   <li>namespace 前缀是否按 {@link IsolationScope#USER} 生效；
 *   <li>没投影时是否回落到 workspace 根目录的共享文件——这决定"漏投影"是静默串租户还是读到空。
 * </ol>
 */
class WorkspaceProjectionProbeTest {

    private static RuntimeContext rcFor(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s-" + userId).build();
    }

    private static WorkspaceManager managerFor(Path workspace) {
        NamespaceFactory ns = IsolationScope.USER.toNamespaceFactory();
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .isolationScope(IsolationScope.USER)
                        .toFilesystem(workspace, ns);
        return new WorkspaceManager(workspace, fs, null, ns);
    }

    @Test
    void readAgentsMdShouldFollowPerRequestUserId(@TempDir Path workspace) throws Exception {
        WorkspaceManager manager = managerFor(workspace);

        Files.createDirectories(workspace.resolve("yjiyuncom/test/vip-1"));
        Files.writeString(workspace.resolve("yjiyuncom/test/vip-1/AGENTS.md"), "TENANT-A-PROMPT");
        Files.createDirectories(workspace.resolve("hanmei_apparel/main/vip-1"));
        Files.writeString(
                workspace.resolve("hanmei_apparel/main/vip-1/AGENTS.md"), "TENANT-B-PROMPT");

        String a = manager.readAgentsMd(rcFor("yjiyuncom/test/vip-1"));
        String b = manager.readAgentsMd(rcFor("hanmei_apparel/main/vip-1"));

        System.out.println("PROBE tenantA=[" + a.strip() + "] tenantB=[" + b.strip() + "]");
        assertEquals("TENANT-A-PROMPT", a.strip());
        assertEquals("TENANT-B-PROMPT", b.strip());
    }

    @Test
    void missingProjectionShouldFallBackToSharedRootFile(@TempDir Path workspace) throws Exception {
        WorkspaceManager manager = managerFor(workspace);

        Files.writeString(workspace.resolve("AGENTS.md"), "SHARED-ROOT-PROMPT");

        String fallback = manager.readAgentsMd(rcFor("yjiyuncom/test/vip-1"));
        System.out.println("PROBE missingProjection=[" + fallback.strip() + "]");
        assertEquals("SHARED-ROOT-PROMPT", fallback.strip());
    }

    @Test
    void namespaceShouldDegradeFromUserIdToSessionIdToEmpty() {
        NamespaceFactory ns = IsolationScope.USER.toNamespaceFactory();

        RuntimeContext noUser = RuntimeContext.builder().sessionId("sess-only").build();
        System.out.println("PROBE ns(noUser)=" + ns.getNamespace(noUser));
        assertEquals(List.of("sess-only"), ns.getNamespace(noUser));

        RuntimeContext empty = RuntimeContext.empty();
        System.out.println("PROBE ns(empty)=" + ns.getNamespace(empty));
        assertTrue(ns.getNamespace(empty).isEmpty());
    }
}
