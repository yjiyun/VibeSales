package com.vibesales.salesagent.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import java.util.List;

/**
 * 读取 classpath 预置 Skill 的最小工厂。
 *
 * <p>只负责"指向哪个资源路径"这一件配置层面的事，不在这里编写任何 Skill 内容或业务判断——
 * 具体判断逻辑应该在 {@code SKILL.md} 里，或者在 Skill 依赖的 Tool/Rule 里。
 *
 * <p>用 {@link ClasspathSkillRepository} 而不是 {@code FileSystemSkillRepository}：当前项目通过
 * {@code exec-maven-plugin} 直接运行 {@code target/classes}（未打包），未来若改为 {@code java -jar}
 * 运行，资源会被打进 jar 内部，{@code ClasspathSkillRepository} 对这两种场景一致工作，
 * {@code FileSystemSkillRepository} 则要求一个真实存在的文件系统目录，jar 内资源不满足这个前提。
 */
public final class SkillRepositoryFactory {

    private static final String SKILLS_RESOURCE_PATH = "skills";

    private SkillRepositoryFactory() {
    }

    /**
     * 兜底 Skill 内容。
     *
     * <p>返回解析好的 {@link AgentSkill} 而不是仓库对象：共享 Agent 上挂构造期仓库会对<b>所有</b>
     * 租户可见（实测见 {@code SingleAgentMultiTenantProbeTest}），所以这些 Skill 也只能经
     * {@code TenantWorkspaceProjector} 投影到租户命名空间目录下下发。
     */
    public static List<AgentSkill> loadDefaultSkills() {
        try (ClasspathSkillRepository repository =
                new ClasspathSkillRepository(SKILLS_RESOURCE_PATH)) {
            return List.copyOf(repository.getAllSkills());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load skill repository from classpath: " + SKILLS_RESOURCE_PATH, e);
        }
    }
}
