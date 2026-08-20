package com.agentteams.salesagent.agent;

import com.agentteams.salesagent.agent.middleware.RecoveryPromptContextMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Path;

/**
 * 主 Agent 工厂：装配<b>一个</b>服务全部租户的 {@link HarnessAgent}。
 *
 * <p>租户维度不落在实例上，而是落在每请求的 {@code RuntimeContext.userId} 上。配合
 * {@link IsolationScope#USER}，harness 每请求按命名空间读 {@code <userId>/AGENTS.md} 与
 * {@code <userId>/skills/<name>/SKILL.md}，由 {@code TenantWorkspaceProjector} 在 run 之前投影出来。
 * 实测依据见 {@code SingleAgentMultiTenantProbeTest}：两个租户共用同一个实例，各自只看到自己的
 * 提示词和 Skill，且命名空间里的 {@code SOUL.md} / {@code AGENTS.md} 都会按请求读取。
 *
 * <p><b>{@code disableFilesystemTools()} 不影响这条链路</b>。这一点先前被误判过（曾以为它会让 Agent
 * 读不到磁盘，从而只能在构造期注入提示词、进而每租户一个实例）：实测 {@code HarnessAgent} 里它只决定
 * 要不要注册 {@code FilesystemTool}（模型手里的文件读写工具），而按命名空间读提示词的
 * {@code WorkspaceContextMiddleware} 由另一个开关 {@code disableWorkspaceContext} 控制，本工程从未
 * 设置。"收紧模型权限"和"harness 自己读 workspace"是两件独立的事，可以同时成立。
 */
public final class SalesAgentFactory {

    /** 共享 Agent 的名称与 agentId，保持与迁移前一致，避免影响既有日志/追踪筛选。 */
    public static final String DEFAULT_AGENT_NAME = "sales-customer-agent";

    /**
     * @param workspaceRoot 租户命名空间目录的父目录，必须与 {@code TenantWorkspaceProjector} 用的是
     *     同一个路径，否则 Agent 读的目录和投影写的目录对不上
     */
    public HarnessAgent createSharedAgent(Model model, Path workspaceRoot) {
        return HarnessAgent.builder()
                .name(DEFAULT_AGENT_NAME)
                .agentId(DEFAULT_AGENT_NAME)
                .sysPrompt("")
                .model(model)
                // 每请求按 userId 取命名空间：AGENTS.md 与 skills/ 都从 <workspaceRoot>/<userId>/ 下读。
                // IsolationScope.USER 本身已是 build() 的默认值，这里显式写出来是因为多租户隔离完全
                // 依赖它——留给下一个读代码的人一个明确的锚点，而不是靠"默认值恰好是对的"。
                .workspace(workspaceRoot)
                .additionalContextFile("SOUL.md")
                .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER))
                // 动态恢复上下文由编排层先写入 RuntimeContext，再在 onSystemPrompt(...) 阶段
                // 统一注入系统提示词，避免把内部判断结果伪装成客户原话污染 USER 历史。
                .middleware(new RecoveryPromptContextMiddleware())
                // 显式切到内存态 state store，绕开默认文件落盘链路对自定义上下文对象的
                // 序列化限制，保证本地联调页与可视化链路先可用。
                .stateStore(new InMemoryAgentStateStore())
                // 收紧默认 Toolkit（最小权限原则）：客服场景不需要 shell 执行、文件系统读写、
                // 子agent派生这些能力，默认全部启用会带来不必要的攻击面（比如客户输入注入
                // 导致模型调用 execute 执行任意命令）。实测确认（见13号文档第3轮记录）：
                // 关闭这四项后 Skill 加载与 memory_save 均不受影响。
                .disableShellTool()
                .disableFilesystemTools()
                // 仍然不能关 disableDynamicSkills，而且现在理由是硬的：实测它会让
                // HarnessSkillMiddleware 走 frozen(...) 分支，用 RuntimeContext.empty() 把 Skill 清单
                // 快照一次并 short-circuit 掉每请求枚举（见 HarnessSkillMiddleware.skillsForCall）。
                // 那样租户 Skill 投影会彻底失效。
                //
                // 阶段二接入 streamEvents(...) 后，HarnessAgent 在本地默认会话状态落盘阶段
                // 出现 "Failed to save state: agent_state"，当前链路不依赖它，先关掉。
                .disableSessionPersistence()
                .disableSubagents()
                .disableDynamicSubagents()
                // 开启框架自带的推理生命周期追踪日志（PRE_CALL/PRE_REASONING/POST_REASONING/
                // POST_CALL/PRE_ACTING/POST_ACTING/ERROR），走标准 slf4j，零额外依赖。
                .enableAgentTracingLog(true)
                .build();
    }
}
