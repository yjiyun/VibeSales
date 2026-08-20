package com.agentteams.salesagent.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 探测程序：<b>一个</b> {@link HarnessAgent} 实例能否按请求上下文服务多个租户。
 *
 * <p>这是"单 Agent + 每请求投影 workspace 文件 + isolationScope(USER)"方案的可行性判定。用假模型
 * 把每次调用收到的 SYSTEM 提示词原样截获下来看，不靠推断：
 *
 * <ol>
 *   <li>租户 A 与租户 B 用同一个 Agent 调用两次，各自的 {@code AGENTS.md} 是否分别进了提示词；
 *   <li>投影出的 {@code skills/<name>/SKILL.md} 是否被默认注册的
 *       {@code WorkspaceSkillRepository(..., "skills", currentRcSupplier, ...)} 按命名空间读到；
 *   <li>构造期的 {@code sysPrompt} 与每请求注入是"追加"还是"替换"——决定基座提示词能不能保留。
 * </ol>
 *
 * <p>builder 调用链与 {@code SalesAgentFactory} 保持一致（含 {@code disableFilesystemTools()}），
 * 否则结论对本工程没有意义。
 */
class SingleAgentMultiTenantProbeTest {

    /** 截获模型入参的假模型：不发网络请求，只记录每次收到的消息列表。 */
    private static final class CapturingModel implements Model {

        private final List<List<Msg>> calls = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .id("probe-" + calls.size())
                            .content(List.of(TextBlock.builder().text("ok").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "probe-capturing-model";
        }

        /**
         * 最近一次<b>主推理</b>调用的 SYSTEM 提示词。
         *
         * <p>不能直接取 {@code calls} 的最后一项：实测每轮 {@code streamEvents} 之后 harness 还会
         * 用同一个 Model 再发一次"memory extraction"调用（SYSTEM 是"You are a memory extraction
         * assistant"）。这里按"含租户投影内容"筛出主调用。
         */
        String lastSystemPrompt() {
            for (int i = calls.size() - 1; i >= 0; i--) {
                String system = systemTextOf(calls.get(i));
                if (!system.isBlank()
                        && !system.contains("You are a memory extraction assistant.")) {
                    return system;
                }
            }
            return "";
        }

        private static String systemTextOf(List<Msg> messages) {
            StringBuilder builder = new StringBuilder();
            for (Msg message : messages) {
                if (message.getRole() != MsgRole.SYSTEM) {
                    continue;
                }
                for (ContentBlock block : message.getContent()) {
                    if (block instanceof TextBlock textBlock) {
                        builder.append(textBlock.getText()).append('\n');
                    }
                }
            }
            return builder.toString();
        }

        /** 原始转储：先看清模型到底收到什么，再决定怎么断言。 */
        void dump(String tag) {
            for (int i = 0; i < calls.size(); i++) {
                List<Msg> messages = calls.get(i);
                for (Msg message : messages) {
                    StringBuilder text = new StringBuilder();
                    for (ContentBlock block : message.getContent()) {
                        if (block instanceof TextBlock textBlock) {
                            text.append(textBlock.getText());
                        } else {
                            text.append('<').append(block.getClass().getSimpleName()).append('>');
                        }
                    }
                    String flat = text.toString().replace("\n", "\\n");
                    System.out.println(
                            "DUMP["
                                    + tag
                                    + "] call="
                                    + i
                                    + " role="
                                    + message.getRole()
                                    + " len="
                                    + flat.length()
                                    + " text="
                                    + (flat.length() > 6000 ? flat.substring(0, 6000) + "..." : flat));
                }
            }
        }

        int callCount() {
            return calls.size();
        }
    }

    /** 与 SalesAgentFactory 同构的装配，只多两项：workspace 与 USER 级隔离。 */
    private static HarnessAgent singleAgent(Model model, Path workspace) {
        return HarnessAgent.builder()
                .name("sales-customer-agent")
                .agentId("sales-customer-agent")
                .sysPrompt("")
                .model(model)
                .workspace(workspace)
                .additionalContextFile("SOUL.md")
                .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER))
                .stateStore(new InMemoryAgentStateStore())
                .disableShellTool()
                .disableFilesystemTools()
                .disableSessionPersistence()
                .disableSubagents()
                .disableDynamicSubagents()
                .build();
    }

    private static void projectTenant(
            Path workspace,
            String namespace,
            String agentsMd,
            String soulMd,
            String skillName,
            String skillMd)
            throws Exception {
        Path root = workspace.resolve(namespace);
        Files.createDirectories(root);
        Files.writeString(root.resolve("AGENTS.md"), agentsMd);
        Files.writeString(root.resolve("SOUL.md"), soulMd);
        if (skillName != null) {
            Path skillDir = root.resolve("skills").resolve(skillName);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), skillMd);
        }
    }

    private static String runOnce(HarnessAgent agent, String namespace, String text) {
        RuntimeContext rc =
                RuntimeContext.builder().userId(namespace).sessionId("s-" + namespace).build();
        Msg userMsg = Msg.builderForRole(MsgRole.USER).textContent(text).build();
        List<?> events =
                new ArrayList<>(agent.streamEvents(List.of(userMsg), rc).collectList().block());
        return "events=" + events.size();
    }

    @Test
    void oneAgentShouldPickTenantPromptFromProjectedWorkspaceFiles(@TempDir Path workspace)
            throws Exception {
        String skillFrontMatter =
                """
                ---
                name: %s
                description: %s
                ---
                %s
                """;
        projectTenant(
                workspace,
                "yjiyuncom/test/vip-1",
                "TENANT-A-AGENTS-MD",
                "TENANT-A-SOUL-MD",
                "tenant-a-skill",
                skillFrontMatter.formatted(
                        "tenant-a-skill", "tenant A only skill", "TENANT-A-SKILL-BODY"));
        projectTenant(
                workspace,
                "hanmei_apparel/main/vip-1",
                "TENANT-B-AGENTS-MD",
                "TENANT-B-SOUL-MD",
                "tenant-b-skill",
                skillFrontMatter.formatted(
                        "tenant-b-skill", "tenant B only skill", "TENANT-B-SKILL-BODY"));

        CapturingModel model = new CapturingModel();
        HarnessAgent agent = singleAgent(model, workspace);

        runOnce(agent, "yjiyuncom/test/vip-1", "hello A");
        String promptA = model.lastSystemPrompt();
        runOnce(agent, "hanmei_apparel/main/vip-1", "hello B");
        String promptB = model.lastSystemPrompt();

        System.out.println("PROBE callCount=" + model.callCount());
        System.out.println("PROBE A.hasOwnAgentsMd=" + promptA.contains("TENANT-A-AGENTS-MD"));
        System.out.println("PROBE A.hasOwnSoulMd=" + promptA.contains("TENANT-A-SOUL-MD"));
        System.out.println("PROBE A.leaksOtherAgentsMd=" + promptA.contains("TENANT-B-AGENTS-MD"));
        System.out.println("PROBE A.leaksOtherSoulMd=" + promptA.contains("TENANT-B-SOUL-MD"));
        System.out.println("PROBE A.hasOwnSkill=" + promptA.contains("tenant-a-skill"));
        System.out.println("PROBE A.leaksOtherSkill=" + promptA.contains("tenant-b-skill"));
        System.out.println("PROBE B.hasOwnAgentsMd=" + promptB.contains("TENANT-B-AGENTS-MD"));
        System.out.println("PROBE B.hasOwnSoulMd=" + promptB.contains("TENANT-B-SOUL-MD"));
        System.out.println("PROBE B.leaksOtherAgentsMd=" + promptB.contains("TENANT-A-AGENTS-MD"));
        System.out.println("PROBE B.leaksOtherSoulMd=" + promptB.contains("TENANT-A-SOUL-MD"));
        System.out.println("PROBE B.hasOwnSkill=" + promptB.contains("tenant-b-skill"));
        System.out.println("PROBE B.leaksOtherSkill=" + promptB.contains("tenant-a-skill"));

        model.dump("tenants");
        assertTrue(promptA.contains("TENANT-A-AGENTS-MD"), "租户 A 的 AGENTS.md 应进入提示词");
        assertTrue(promptA.contains("TENANT-A-SOUL-MD"), "租户 A 的 SOUL.md 应进入提示词");
        assertTrue(promptB.contains("TENANT-B-AGENTS-MD"), "租户 B 的 AGENTS.md 应进入提示词");
        assertTrue(promptB.contains("TENANT-B-SOUL-MD"), "租户 B 的 SOUL.md 应进入提示词");
        assertFalse(promptA.contains("TENANT-B-AGENTS-MD"), "租户 A 不应看到租户 B 的 AGENTS.md");
        assertFalse(promptA.contains("TENANT-B-SOUL-MD"), "租户 A 不应看到租户 B 的 SOUL.md");
        assertFalse(promptB.contains("TENANT-A-AGENTS-MD"), "租户 B 不应看到租户 A 的 AGENTS.md");
        assertFalse(promptB.contains("TENANT-A-SOUL-MD"), "租户 B 不应看到租户 A 的 SOUL.md");
        // Skill 同样按 userId 命名空间隔离：默认注册的 WorkspaceSkillRepository 是
        // RuntimeContextSkillRepository，HarnessSkillMiddleware.mergeRepositories(ctx) 每请求重新枚举
        assertTrue(promptA.contains("tenant-a-skill"), "租户 A 的投影 Skill 应进入提示词");
        assertTrue(promptB.contains("tenant-b-skill"), "租户 B 的投影 Skill 应进入提示词");
        assertFalse(promptA.contains("tenant-b-skill"), "租户 A 不应看到租户 B 的 Skill");
        assertFalse(promptB.contains("tenant-a-skill"), "租户 B 不应看到租户 A 的 Skill");
    }

    @Test
    void inMemorySkillRepositoryIsSharedAcrossTenantsProbe(@TempDir Path workspace)
            throws Exception {
        // 对照组：构造期注册的 skillRepository 是否对所有租户都可见（判断现有投影方式能否留用）
        AgentSkill shared =
                SkillUtil.createFrom(
                        """
                        ---
                        name: shared-skill
                        description: registered at construction time
                        ---
                        SHARED-SKILL-BODY
                        """,
                        null,
                        "probe");
        CapturingModel model = new CapturingModel();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("sales-customer-agent")
                        .sysPrompt("")
                        .model(model)
                        .workspace(workspace)
                        .additionalContextFile("SOUL.md")
                        .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER))
                        .stateStore(new InMemoryAgentStateStore())
                        .skillRepository(
                                new com.agentteams.salesagent.blueprint.InMemorySkillRepository(
                                        "probe", List.of(shared)))
                        .disableShellTool()
                        .disableFilesystemTools()
                        .disableSessionPersistence()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .build();

        runOnce(agent, "yjiyuncom/test/vip-1", "hello A");
        String promptA = model.lastSystemPrompt();
        runOnce(agent, "hanmei_apparel/main/vip-1", "hello B");
        String promptB = model.lastSystemPrompt();

        System.out.println("PROBE shared.visibleToA=" + promptA.contains("shared-skill"));
        System.out.println("PROBE shared.visibleToB=" + promptB.contains("shared-skill"));
        // 结论：构造期注册的仓库对所有租户都可见。所以 BlueprintSkillProjector 产出的
        // InMemorySkillRepository 不能再挂到共享 Agent 上，必须改走"投影到租户命名空间目录"。
        assertTrue(promptA.contains("shared-skill"), "构造期注册的 Skill 对租户 A 可见");
        assertTrue(promptB.contains("shared-skill"), "构造期注册的 Skill 对租户 B 同样可见（即会串租户）");
    }
}
