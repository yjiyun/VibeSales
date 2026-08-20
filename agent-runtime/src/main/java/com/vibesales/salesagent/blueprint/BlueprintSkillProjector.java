package com.agentteams.salesagent.blueprint;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Blueprint 的 {@code skills[]} 投影成该租户可用的 Skill 集合。
 *
 * <p>两类 source 分开处理：
 * <ul>
 *   <li>{@code inline}：正文就在 {@code skillMd} 里，用框架自己的 {@link SkillUtil#createFrom} 解析，
 *       解析成功即等价于框架能加载
 *   <li>{@code library}：从 classpath 预置 skill 库（{@code resources/skills/}）按名字取；取不到就
 *       报错，而不是静默少一个 Skill——上游最需要验证的正是"声明的 skill 到底有没有真的进运行时"
 * </ul>
 *
 * <p><b>产出是解析好的 {@link AgentSkill} 列表，不是 Agent 构造期的仓库</b>。实测确认（见
 * {@code SingleAgentMultiTenantProbeTest#inMemorySkillRepositoryIsSharedAcrossTenantsProbe}）：挂在
 * {@code HarnessAgent.Builder#skillRepository} 上的仓库对<b>所有</b>租户可见，共享 Agent 下就是串租户。
 * 所以这些 Skill 由 {@link TenantWorkspaceProjector} 写进租户命名空间目录，交给默认注册的
 * {@code WorkspaceSkillRepository} 每请求按 {@code userId} 重新枚举。
 */
public final class BlueprintSkillProjector {

    private static final String LIBRARY_RESOURCE_PATH = "skills";

    /**
     * @return 该租户解析成功的 Skill 列表与来源留痕
     */
    public Projection project(AgentBlueprint blueprint) {
        List<AgentSkill> skills = new ArrayList<>();
        Map<String, String> sourceByName = new LinkedHashMap<>();

        for (AgentBlueprint.Skill declared : blueprint.skills()) {
            if (declared == null || declared.name() == null || declared.name().isBlank()) {
                continue;
            }
            String name = declared.name().trim();
            if (declared.isInline()) {
                skills.add(
                        SkillUtil.createFrom(
                                declared.skillMd(), null, "blueprint-inline:" + blueprint.blueprintId()));
                sourceByName.put(name, "inline");
            } else if (declared.isLibrary()) {
                AgentSkill librarySkill = loadLibrarySkill(name);
                if (librarySkill == null) {
                    throw new IllegalStateException(
                            "blueprint "
                                    + blueprint.blueprintId()
                                    + " declares library skill '"
                                    + name
                                    + "' but it is missing from classpath "
                                    + LIBRARY_RESOURCE_PATH
                                    + "/");
                }
                skills.add(librarySkill);
                sourceByName.put(name, "library");
            } else {
                throw new IllegalStateException(
                        "blueprint "
                                + blueprint.blueprintId()
                                + " declares skill '"
                                + name
                                + "' with unsupported source: "
                                + declared.source());
            }
        }

        return new Projection(skills, sourceByName);
    }

    private AgentSkill loadLibrarySkill(String name) {
        try (ClasspathSkillRepository repository =
                new ClasspathSkillRepository(LIBRARY_RESOURCE_PATH)) {
            if (!repository.skillExists(name)) {
                return null;
            }
            return repository.getSkill(name);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to open classpath skill library: " + LIBRARY_RESOURCE_PATH, exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Failed to load library skill '" + name + "' from classpath", exception);
        }
    }

    /**
     * @param skills 解析成功、待写入租户命名空间目录的 Skill
     * @param sourceByName 每个 Skill 的来源（inline / library），用于时间线留痕
     */
    public record Projection(List<AgentSkill> skills, Map<String, String> sourceByName) {

        public Projection {
            skills = skills == null ? List.of() : List.copyOf(skills);
            sourceByName =
                    sourceByName == null
                            ? Map.of()
                            : java.util.Collections.unmodifiableMap(
                                    new LinkedHashMap<>(sourceByName));
        }

        /** 本轮装配进运行时的 Skill 名单。 */
        public List<String> skillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }
    }
}
