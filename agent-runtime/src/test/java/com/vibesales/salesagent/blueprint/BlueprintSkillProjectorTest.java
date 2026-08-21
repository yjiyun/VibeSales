package com.vibesales.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 锁定「Nest 侧 Skill 市场声明的 library skill」与「本工程 classpath 真的有的 skill」不脱节。
 *
 * <p>回归 run ebb13650 的发布事故：Nest 的 {@code SkillCatalogService} 宣告了 3 个 library skill
 * （human-handoff / product-recommend / after-sales），而 {@code resources/skills/} 下一个都没有，
 * 于是 P3C 装出的 blueprint 一进 P4 dryRun 就 500 —— 且 selfcheck#5「library Skill 引用存在」
 * 是拿 blueprint 去比对<b>产生它的同一份 Nest 目录</b>，必然通过，闸门形同虚设。两份目录必须对齐。
 *
 * <p>{@code agent-core} 是与本仓并列的独立仓库（见根 {@code .gitignore}），单独 clone 本仓时不存在。
 * 因此读不到 Nest 目录时按 {@code assumeTrue} 跳过对齐断言，而不是把测试判失败——否则只 clone
 * 本仓的人会看到一个与自己改动无关的红。{@link #missingLibrarySkillMustFailLoudlyRatherThanSilentlyDrop}
 * 不依赖 Nest 目录，任何环境下都会执行。
 */
class BlueprintSkillProjectorTest {

    /** Nest 侧 Skill 市场（local 种子）的真源，避免在这里再抄一份名字。 */
    private static final Path NEST_SKILL_CATALOG =
            Path.of("..", "agent-core", "src", "p3c", "skill-catalog.service.ts");

    private static final Pattern CATALOG_ENTRY = Pattern.compile("name:'([a-z0-9-]+)',ref:'skill:");

    private final BlueprintSkillProjector projector = new BlueprintSkillProjector();

    @Test
    void everyLibrarySkillOfferedByNestMustExistOnClasspath() throws IOException {
        List<String> offered = nestOfferedSkills();
        assumeTrue(!offered.isEmpty(), "agent-core 未与本仓并列 clone，跳过跨仓对齐断言");

        for (String name : offered) {
            AgentBlueprint blueprint = blueprintWithLibrarySkill(name);
            assertDoesNotThrow(
                    () -> projector.project(blueprint),
                    "Nest 宣告了 library skill '"
                            + name
                            + "'，但 agent-runtime 的 resources/skills/ 缺少它；P4 dryRun 会 500");
        }
    }

    @Test
    void offeredSkillsShouldProjectAsLibrarySource() throws IOException {
        List<String> offered = nestOfferedSkills();
        assumeTrue(!offered.isEmpty(), "agent-core 未与本仓并列 clone，跳过跨仓对齐断言");

        for (String name : offered) {
            BlueprintSkillProjector.Projection projection =
                    projector.project(blueprintWithLibrarySkill(name));

            assertEquals(1, projection.skills().size(), name);
            assertEquals("library", projection.sourceByName().get(name), name);
        }
    }

    @Test
    void missingLibrarySkillMustFailLoudlyRatherThanSilentlyDrop() {
        // 少一个 Skill 却装配成功，是最难查的失败：上游会以为能力已生效
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> projector.project(blueprintWithLibrarySkill("no-such-skill")));

        assertTrue(failure.getMessage().contains("no-such-skill"), failure.getMessage());
        assertTrue(failure.getMessage().contains("missing from classpath"), failure.getMessage());
    }

    private static List<String> nestOfferedSkills() throws IOException {
        if (!Files.isRegularFile(NEST_SKILL_CATALOG)) {
            return List.of();
        }
        String source = Files.readString(NEST_SKILL_CATALOG, StandardCharsets.UTF_8);
        Matcher matcher = CATALOG_ENTRY.matcher(source);
        List<String> names = new java.util.ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    private static AgentBlueprint blueprintWithLibrarySkill(String name) {
        return new AgentBlueprint(
                "bp_skill_projection_test",
                1,
                "yjiyuncom",
                "test",
                "unit_test_agent",
                new AgentBlueprint.Meta("测试", List.of("BEAUTY_SKINCARE"), "unit-test", "run-1"),
                new AgentBlueprint.Prompt("# 工作准则\n\n测试", null, "# 身份\n\n测试", null, null),
                List.of(
                        new AgentBlueprint.Skill(
                                name, AgentBlueprint.Skill.SOURCE_LIBRARY, "skill:" + name + "@1", null)),
                List.of(),
                new AgentBlueprint.Tools(List.of(), List.of(), List.of()),
                new AgentBlueprint.RuntimeSpec("deepseek-v4-flash", "USER", 32000),
                AgentBlueprint.RUNTIME_MODE_SINGLE_AGENT,
                List.of());
    }
}
