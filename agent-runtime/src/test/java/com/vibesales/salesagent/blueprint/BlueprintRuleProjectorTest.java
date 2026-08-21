package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibesales.salesagent.rule.profile.FollowUpRoundLimitRule;
import com.vibesales.salesagent.rule.profile.ProfileCompletenessRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 锁住"蓝图参数到底有没有生效"这条链。
 *
 * <p>这里断言的多数是<b>负向</b>行为：参数没配、配成 {@code 0}、配了个字符串数字时投影出什么。
 * 阈值静默失效（上游以为配了、运行期用的还是 Java 默认）是这一层最难排查的问题，
 * 所以每种"没配"的形态都要有一条断言把当时的结论钉住。
 */
class BlueprintRuleProjectorTest {

    private final BlueprintRuleProjector projector = new BlueprintRuleProjector();

    private static AgentBlueprint blueprintWith(AgentBlueprint.RuleSpec... rules) {
        return new AgentBlueprint(
                "bp-test",
                1,
                "yjiyuncom",
                "test",
                "yjiyun-service-agent",
                null,
                null,
                List.of(),
                List.of(rules),
                null,
                null,
                AgentBlueprint.RUNTIME_MODE_MULTI_STAGE,
                List.of());
    }

    private static AgentBlueprint.RuleSpec rule(String code, Map<String, Object> params) {
        return new AgentBlueprint.RuleSpec(code, true, params);
    }

    @Test
    void shouldProjectBothProfileGateThresholds() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                rule("profile-completeness", Map.of("forcedRoundThreshold", 5)),
                                rule("follow-up-round-limit", Map.of("maxFollowUpRounds", 2))));

        assertEquals(
                List.of("profile-completeness", "follow-up-round-limit"),
                projection.effectiveRuleCodes());
        assertEquals(5, projection.profileCompletenessRule().orElseThrow().forcedRoundThreshold());
        assertEquals(2, projection.followUpRoundLimitRule().orElseThrow().maxFollowUpRounds());
        assertEquals("blueprint", projection.profileThresholdSource());
    }

    /**
     * 两条阈值分别可配，所以"只配一条"必须能表达：另一条要落回 Java 默认实例（投影为 empty），
     * 而不是被顺带覆盖成同一个值。
     */
    @Test
    void overridingOnlyOneThresholdShouldLeaveTheOtherAtJavaDefault() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                rule("profile-completeness", Map.of("forcedRoundThreshold", 6)),
                                rule("follow-up-round-limit", Map.of())));

        assertTrue(projection.profileCompletenessRule().isPresent());
        assertTrue(projection.followUpRoundLimitRule().isEmpty());
        // 规则仍算"本轮生效"——生效与否看开关，投影出实例与否看有没有覆盖参数
        assertTrue(projection.effectiveRuleCodes().contains("follow-up-round-limit"));
        assertEquals("blueprint", projection.profileThresholdSource());
    }

    /** 「启用但不覆盖参数」是合法配置：两闸都生效，阈值来源如实显示 java-default。 */
    @Test
    void enabledWithoutParamsShouldReportJavaDefaultSource() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                rule("profile-completeness", Map.of()),
                                rule("follow-up-round-limit", Map.of())));

        assertTrue(projection.profileCompletenessRule().isEmpty());
        assertTrue(projection.followUpRoundLimitRule().isEmpty());
        assertEquals("java-default", projection.profileThresholdSource());
    }

    /**
     * 「配了 0」是配置事故而不是配置意图：0 轮上限意味着一轮都不许追问、第一轮就强制放行。
     * 投影层直接当成没配，好让时间线上的 {@code thresholdSource} 显示 java-default 而不是
     * 让人以为 0 生效了。
     */
    @Test
    void zeroOrNegativeThresholdShouldBeTreatedAsAbsent() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                rule("profile-completeness", Map.of("forcedRoundThreshold", 0)),
                                rule("follow-up-round-limit", Map.of("maxFollowUpRounds", -1))));

        assertTrue(projection.profileCompletenessRule().isEmpty());
        assertTrue(projection.followUpRoundLimitRule().isEmpty());
        assertEquals("java-default", projection.profileThresholdSource());
    }

    /** 经 JDBC/远端源转一手后数字可能是字符串形态；因形态差异让阈值静默失效是最难查的一类问题。 */
    @Test
    void numericStringThresholdShouldStillTakeEffect() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                rule("profile-completeness", Map.of("forcedRoundThreshold", " 4 ")),
                                rule("follow-up-round-limit", Map.of("maxFollowUpRounds", "7"))));

        assertEquals(4, projection.profileCompletenessRule().orElseThrow().forcedRoundThreshold());
        assertEquals(7, projection.followUpRoundLimitRule().orElseThrow().maxFollowUpRounds());
    }

    /** 非数字文本读不出阈值，按"没配"处理并回落默认，不抛异常打断整份蓝图的装配。 */
    @Test
    void garbageThresholdShouldFallBackToJavaDefault() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(rule("follow-up-round-limit", Map.of("maxFollowUpRounds", "三轮"))));

        assertTrue(projection.followUpRoundLimitRule().isEmpty());
        assertEquals(
                FollowUpRoundLimitRule.DEFAULT_MAX_FOLLOW_UP_ROUNDS,
                new FollowUpRoundLimitRule().maxFollowUpRounds());
        assertEquals(
                ProfileCompletenessRule.DEFAULT_FORCED_ROUND_THRESHOLD,
                new ProfileCompletenessRule().forcedRoundThreshold());
    }

    /**
     * {@code human-handoff-trigger} 是"已接线但不投影实例"的唯一一条：它没有可覆盖参数，所以
     * 生效与否只看开关。这条断言存在的意义是钉住"effective 里有它、但 Projection 上没有它的槽位"
     * 不是漏实现——投影层的 {@code default} 分支会对"wired 却没有构造分支"的规则抛异常，
     * 所以必须有一条测试证明这条规则走的是那个刻意留空的分支而不是 default。
     */
    @Test
    void humanHandoffTriggerShouldBeEffectiveWithoutAnyProjectedInstance() {
        BlueprintRuleProjector.Projection projection =
                projector.project(blueprintWith(rule("human-handoff-trigger", Map.of())));

        assertEquals(List.of("human-handoff-trigger"), projection.effectiveRuleCodes());
        assertEquals(List.of(), projection.unwiredRuleCodes());
        assertEquals("wired", projection.classification().get("human-handoff-trigger"));
        // 阈值来源不受它影响：它压根没有阈值
        assertEquals("java-default", projection.profileThresholdSource());
    }

    /** 关掉安全阀是合法配置（比如纯自动化测试租户），但必须留痕在 disabled 里而不是静默消失。 */
    @Test
    void disabledHumanHandoffTriggerShouldBeRecordedAsDisabled() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                new AgentBlueprint.RuleSpec("human-handoff-trigger", false, Map.of())));

        assertEquals(List.of("human-handoff-trigger"), projection.disabledRuleCodes());
        assertFalse(projection.effectiveRuleCodes().contains("human-handoff-trigger"));
    }

    /** {@code enabled=false} 在分类前就被拦下：既不生效也不算 unwired。 */
    @Test
    void disabledRuleShouldNotBeProjectedAtAll() {
        BlueprintRuleProjector.Projection projection =
                projector.project(
                        blueprintWith(
                                new AgentBlueprint.RuleSpec(
                                        "profile-completeness",
                                        false,
                                        Map.of("forcedRoundThreshold", 9))));

        assertTrue(projection.profileCompletenessRule().isEmpty());
        assertEquals(List.of("profile-completeness"), projection.disabledRuleCodes());
        assertFalse(projection.effectiveRuleCodes().contains("profile-completeness"));
        assertEquals(
                "disabled_by_blueprint", projection.classification().get("profile-completeness"));
    }

    /**
     * "已实现未接线"这一类当前是空的（六条规则已全部接线），所以没有真实规则码能走
     * {@code implemented_not_wired} 分支。这条断言把那个事实钉住：一旦有规则被挪回那一类，
     * 它会先炸，提醒把下面那条留痕断言恢复成用真码。
     *
     * <p>留痕语义本身仍有覆盖——{@code AgentBlueprintValidatorTest} 里同样带条件地测了它。
     */
    @Test
    void noRuleShouldRemainUnwired() {
        assertTrue(RuleCapabilityCatalog.IMPLEMENTED_NOT_WIRED_RULES.isEmpty());

        BlueprintRuleProjector.Projection projection =
                projector.project(blueprintWith(rule("closure-writeback-required-fields", Map.of())));

        assertEquals(List.of(), projection.unwiredRuleCodes());
        assertEquals(List.of("closure-writeback-required-fields"), projection.effectiveRuleCodes());
    }

    /**
     * {@code closure-writeback-required-fields} 是第三条"已接线、无参可配、不投影实例"的规则。
     * 和另外两条一样，这条断言证明它走的是刻意留空的 {@code case} 分支而不是会抛异常的 {@code default}。
     */
    @Test
    void closureWritebackRequiredFieldsShouldBeEffectiveWithoutAnyProjectedInstance() {
        BlueprintRuleProjector.Projection projection =
                projector.project(blueprintWith(rule("closure-writeback-required-fields", Map.of())));

        assertEquals(List.of("closure-writeback-required-fields"), projection.effectiveRuleCodes());
        assertEquals(
                "wired", projection.classification().get("closure-writeback-required-fields"));
        assertEquals("java-default", projection.profileThresholdSource());
    }

    /**
     * {@code queue-version-guard} 和 {@code human-handoff-trigger} 同类：已接线、无参可配、不投影实例。
     * 单列一条是因为它刚从 {@code IMPLEMENTED_NOT_WIRED_RULES} 迁过来，
     * 迁回去（或忘了加投影分支）都会让这条断言先炸。
     */
    @Test
    void queueVersionGuardShouldBeEffectiveWithoutAnyProjectedInstance() {
        BlueprintRuleProjector.Projection projection =
                projector.project(blueprintWith(rule("queue-version-guard", Map.of())));

        assertEquals(List.of("queue-version-guard"), projection.effectiveRuleCodes());
        assertEquals(List.of(), projection.unwiredRuleCodes());
        assertEquals("wired", projection.classification().get("queue-version-guard"));
    }

    /**
     * 校验阶段本该按 error 拦下未知 ruleCode，投影层再撞上就说明校验被绕过了，
     * 这时候必须炸而不是静默跳过：静默跳过等于让一个拼错的规则名装出"配了但没生效"的样子。
     */
    @Test
    void unsupportedRuleCodeShouldFailFast() {
        assertThrows(
                IllegalStateException.class,
                () -> projector.project(blueprintWith(rule("no-such-rule", Map.of()))));
    }
}
