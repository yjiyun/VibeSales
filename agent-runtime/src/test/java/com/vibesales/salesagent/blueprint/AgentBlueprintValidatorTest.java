package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 锁定校验器的"什么算 error、什么算 warning"边界。
 *
 * <p>重点不在于"能不能装出 Agent"，而在于<b>上游能不能看出自己配错了</b>：cluster 放错槽位、
 * Rule 参数键拼错这两类都会导致"看起来配了但没生效"，所以必须是 error 而不是静默忽略。
 */
class AgentBlueprintValidatorTest {

    private final AgentBlueprintValidator validator = new AgentBlueprintValidator();

    @Test
    void shouldAcceptMinimalValidBlueprint() {
        BlueprintValidationReport report =
                validator.validate(blueprint("test", List.of()), "yjiyuncom", "test");

        assertTrue(report.valid(), report.errorSummary());
    }

    @Test
    void shouldRejectClusterMismatchBetweenIndexAndJson() {
        // 索引把它放在 cluster=test 槽位，JSON 里却写 prod：蓝图放错位置，会串集群
        BlueprintValidationReport report =
                validator.validate(blueprint("prod", List.of()), "yjiyuncom", "test");

        assertFalse(report.valid());
        assertTrue(
                report.errors().stream().anyMatch(error -> error.startsWith("cluster mismatch")),
                report.errorSummary());
    }

    @Test
    void shouldAcceptPromptRefWithoutInlineAgentsMd() {
        AgentBlueprint base = blueprint("test", List.of());
        AgentBlueprint promptRefOnly =
                new AgentBlueprint(
                        base.blueprintId(),
                        base.version(),
                        base.clientCode(),
                        base.cluster(),
                        base.runtimeAgentId(),
                        base.meta(),
                        new AgentBlueprint.Prompt("", "prompts/blueprints/guyu/AGENTS.single.md", "", null, null),
                        base.skills(),
                        base.rules(),
                        base.tools(),
                        base.runtime(),
                        base.runtimeMode(),
                        base.stages());

        BlueprintValidationReport report = validator.validate(promptRefOnly, "yjiyuncom", "test");

        assertTrue(report.valid(), report.errorSummary());
    }

    @Test
    void shouldRejectUnknownRuleCode() {
        BlueprintValidationReport report =
                validator.validate(
                        blueprint("test", List.of(rule("no-such-rule", true, Map.of()))),
                        "yjiyuncom",
                        "test");

        assertFalse(report.valid());
        assertTrue(
                report.errors().stream().anyMatch(error -> error.contains("no-such-rule")),
                report.errorSummary());
    }

    @Test
    void shouldRejectMisspelledRuleParamKey() {
        // continuationKeywords 写成 keywords：静默忽略的话上游会以为租户词表生效了
        BlueprintValidationReport report =
                validator.validate(
                        blueprint(
                                "test",
                                List.of(
                                        rule(
                                                "recovery-detection",
                                                true,
                                                Map.of("keywords", List.of("继续"))))),
                        "yjiyuncom",
                        "test");

        assertFalse(report.valid());
        assertTrue(
                report.errors().stream()
                        .anyMatch(error -> error.contains("does not accept param 'keywords'")),
                report.errorSummary());
    }

    @Test
    void shouldRejectDuplicateRuleCode() {
        BlueprintValidationReport report =
                validator.validate(
                        blueprint(
                                "test",
                                List.of(
                                        rule("recovery-detection", true, Map.of()),
                                        rule("recovery-detection", false, Map.of()))),
                        "yjiyuncom",
                        "test");

        assertFalse(report.valid());
        assertTrue(
                report.errors().contains("duplicate ruleCode: recovery-detection"),
                report.errorSummary());
    }

    /**
     * "未接线规则"这个状态的两种处理：启用出 warning、禁用不出 warning。
     *
     * <p>原先这两条是分开的两个 {@code @Test}，各自用 {@link RuleCapabilityCatalog#IMPLEMENTED_NOT_WIRED_RULES}
     * 里现取的一个码。那个集合现在空了（六条规则已全部接线），所以合成一条并显式分两种情形：
     *
     * <ul>
     *   <li>集合非空——按原来的方式断言 warning 的有无，覆盖校验器里那两个
     *       {@code implemented_not_wired} 分支；
     *   <li>集合为空——断言它确实是空的。这不是静默跳过：它把"六条已全接线"这个事实也钉住了，
     *       而且下一条规则落到这一类的那一刻，上面的真断言就自动重新生效。
     * </ul>
     *
     * <p>没有改成写死一个假码：假码得给校验器开一个只有测试用的分类注入口，为了测两个 warning 分支
     * 往生产代码里加一个接缝不划算。
     */
    @Test
    void unwiredRuleShouldWarnOnlyWhenEnabled() {
        String exemplar =
                RuleCapabilityCatalog.IMPLEMENTED_NOT_WIRED_RULES.stream().sorted().findFirst().orElse(null);
        if (exemplar == null) {
            assertTrue(
                    RuleCapabilityCatalog.IMPLEMENTED_NOT_WIRED_RULES.isEmpty(),
                    "六条规则已全部接线，这一类当前应为空");
            return;
        }

        // 能装出 Agent，只是这条规则本轮不会被调用——warning 而不是 error
        BlueprintValidationReport enabled =
                validator.validate(
                        blueprint("test", List.of(rule(exemplar, true, Map.of()))),
                        "yjiyuncom",
                        "test");
        assertTrue(enabled.valid(), enabled.errorSummary());
        assertTrue(
                enabled.warnings().stream().anyMatch(warning -> warning.contains(exemplar)),
                String.valueOf(enabled.warnings()));

        BlueprintValidationReport disabled =
                validator.validate(
                        blueprint("test", List.of(rule(exemplar, false, Map.of()))),
                        "yjiyuncom",
                        "test");
        assertTrue(disabled.valid(), disabled.errorSummary());
        assertTrue(
                disabled.warnings().stream().noneMatch(warning -> warning.contains(exemplar)),
                String.valueOf(disabled.warnings()));
    }

    @Test
    void rulesShouldBeListedAsConsumedNotIgnored() {
        BlueprintValidationReport report =
                validator.validate(blueprint("test", List.of()), "yjiyuncom", "test");

        assertTrue(report.consumedFields().contains("rules[].ruleCode"));
        assertTrue(report.consumedFields().contains("cluster"));
        assertTrue(report.ignoredFields().stream().noneMatch(field -> field.startsWith("rules[]")));
    }

    /**
     * 回归 run b571982f 的发布事故：P3C 装配侧把 MCP 工具 {@code crm_query} 写进 tools.allow，
     * 而 catalog 当时只认 Java 工具，于是判 unsupported → dryRun HTTP 500 → 整个发布卡死。
     * {@code crm_query} 在 workspace/tools.json 里是注册可用的，必须放行。
     *
     * <p>用临时 tools.json 而不是依赖仓库里那份：catalog 会缓存首次读取结果，且工作目录随
     * 构建方式变化，直接依赖真实文件会让这条断言变成"看环境脸色"。这里显式造一份等价内容，
     * 断言的是<b>机制</b>——enableTools 里的名字必须被认可。
     */
    @Test
    void shouldAcceptMcpProvidedToolDeclaredInToolsJson() throws Exception {
        withToolsJson(
                "{\"mcpServers\":{\"business-tools\":{\"enableTools\":[\"crm_query\"]}}}",
                () -> {
                    BlueprintValidationReport report =
                            validator.validate(
                                    blueprintWithTools(List.of("crm_query")), "yjiyuncom", "test");

                    assertTrue(report.valid(), report.errorSummary());
                    assertTrue(
                            report.errors().stream().noneMatch(error -> error.contains("crm_query")),
                            report.errorSummary());
                    // MCP 工具是真能用的，不该退化成 warning 让上游以为没生效
                    assertTrue(
                            report.warnings().stream()
                                    .noneMatch(warning -> warning.contains("crm_query")),
                            String.valueOf(report.warnings()));
                });
    }

    /** 仓库里那份 tools.json 必须真的注册着 crm_query——它是 P3C 唯一的工具候选。 */
    @Test
    void repositoryToolsJsonShouldRegisterCrmQuery() {
        java.nio.file.Path toolsFile = java.nio.file.Path.of("workspace", "tools.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(toolsFile),
                "未在 agent-runtime 模块目录下运行，跳过真实 tools.json 断言");

        ToolCapabilityCatalog.reloadMcpProvidedTools();
        try {
            assertTrue(
                    ToolCapabilityCatalog.mcpProvidedTools().contains("crm_query"),
                    "workspace/tools.json 必须注册 crm_query，否则 P4 dryRun 会判它未实现：实际读到 "
                            + ToolCapabilityCatalog.mcpProvidedTools());
        } finally {
            ToolCapabilityCatalog.reloadMcpProvidedTools();
        }
    }

    /** 在临时 tools.json 生效的作用域内跑断言，跑完恢复系统属性与缓存。 */
    private static void withToolsJson(String json, Runnable assertions) throws Exception {
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("tools", ".json");
        String previous = System.getProperty("salesagent.tools.file");
        try {
            java.nio.file.Files.writeString(temp, json);
            System.setProperty("salesagent.tools.file", temp.toString());
            ToolCapabilityCatalog.reloadMcpProvidedTools();
            assertions.run();
        } finally {
            if (previous == null) {
                System.clearProperty("salesagent.tools.file");
            } else {
                System.setProperty("salesagent.tools.file", previous);
            }
            ToolCapabilityCatalog.reloadMcpProvidedTools();
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    @Test
    void shouldStillRejectToolThatIsNeitherJavaNorMcpProvided() {
        BlueprintValidationReport report =
                validator.validate(
                        blueprintWithTools(List.of("no_such_tool")), "yjiyuncom", "test");

        assertFalse(report.valid());
        assertTrue(
                report.errors().stream()
                        .anyMatch(
                                error ->
                                        error.contains("no_such_tool")
                                                && error.contains("does not implement at all")),
                report.errorSummary());
    }

    @Test
    void unsupportedToolErrorShouldListSupportedToolsToBeActionable() {
        // 原始报错只说"未实现"，Leader 据此误判为平台问题；报错必须自带可行动信息
        BlueprintValidationReport report =
                validator.validate(
                        blueprintWithTools(List.of("no_such_tool")), "yjiyuncom", "test");

        assertTrue(
                report.errors().stream()
                        .anyMatch(
                                error ->
                                        error.contains("supported tools are")
                                                && error.contains("workspace/tools.json")),
                report.errorSummary());
    }

    private static AgentBlueprint blueprintWithTools(List<String> allow) {
        AgentBlueprint base = blueprint("test", List.of());
        return new AgentBlueprint(
                base.blueprintId(),
                base.version(),
                base.clientCode(),
                base.cluster(),
                base.runtimeAgentId(),
                base.meta(),
                base.prompt(),
                base.skills(),
                base.rules(),
                new AgentBlueprint.Tools(allow, List.of(), List.of()),
                base.runtime(),
                base.runtimeMode(),
                base.stages());
    }

    private static AgentBlueprint.RuleSpec rule(
            String ruleCode, boolean enabled, Map<String, Object> params) {
        return new AgentBlueprint.RuleSpec(ruleCode, enabled, params);
    }

    private static AgentBlueprint blueprint(String cluster, List<AgentBlueprint.RuleSpec> rules) {
        return new AgentBlueprint(
                "unit_test_v1",
                1,
                "yjiyuncom",
                cluster,
                "unit_test_agent",
                new AgentBlueprint.Meta("测试", List.of("BEAUTY_SKINCARE"), "unit-test", "run-1"),
                new AgentBlueprint.Prompt("# 工作准则\n\n测试", null, "# 身份\n\n测试", null, null),
                List.of(),
                rules,
                new AgentBlueprint.Tools(List.of(), List.of(), List.of()),
                new AgentBlueprint.RuntimeSpec("deepseek-v4-flash", "USER", 32000),
                AgentBlueprint.RUNTIME_MODE_SINGLE_AGENT,
                List.of());
    }
}
