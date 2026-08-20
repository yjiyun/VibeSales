package com.agentteams.salesagent.blueprint;

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

    @Test
    void shouldWarnButAcceptUnwiredRule() {
        BlueprintValidationReport report =
                validator.validate(
                        blueprint("test", List.of(rule("intent-priority", true, Map.of()))),
                        "yjiyuncom",
                        "test");

        // 能装出 Agent，只是这条规则本轮不会被调用——warning 而不是 error
        assertTrue(report.valid(), report.errorSummary());
        assertTrue(
                report.warnings().stream().anyMatch(warning -> warning.contains("intent-priority")),
                String.valueOf(report.warnings()));
    }

    @Test
    void disabledUnwiredRuleShouldNotWarn() {
        BlueprintValidationReport report =
                validator.validate(
                        blueprint("test", List.of(rule("intent-priority", false, Map.of()))),
                        "yjiyuncom",
                        "test");

        assertTrue(report.valid(), report.errorSummary());
        assertTrue(
                report.warnings().stream().noneMatch(warning -> warning.contains("intent-priority")),
                String.valueOf(report.warnings()));
    }

    @Test
    void rulesShouldBeListedAsConsumedNotIgnored() {
        BlueprintValidationReport report =
                validator.validate(blueprint("test", List.of()), "yjiyuncom", "test");

        assertTrue(report.consumedFields().contains("rules[].ruleCode"));
        assertTrue(report.consumedFields().contains("cluster"));
        assertTrue(report.ignoredFields().stream().noneMatch(field -> field.startsWith("rules[]")));
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
                new AgentBlueprint.Prompt("# 工作准则\n\n测试", "# 身份\n\n测试", null),
                List.of(),
                rules,
                new AgentBlueprint.Tools(List.of(), List.of(), List.of()),
                new AgentBlueprint.RuntimeSpec("deepseek-v4-flash-0731", "USER", 32000),
                AgentBlueprint.RUNTIME_MODE_SINGLE_AGENT,
                List.of());
    }
}
