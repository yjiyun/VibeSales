package com.agentteams.salesagent.blueprint;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读内存 Skill 仓库，承载单个租户由 Blueprint 投影出来的 Skill 集合。
 *
 * <p>为什么用内存仓库，而不是把 inline skill 物化成临时目录再交给
 * {@code FileSystemSkillRepository}：
 *
 * <ol>
 *   <li>{@code FileSystemSkillRepository} 要求一个真实存在、非空的目录，物化会引入临时目录生命周期、
 *       并发写、陈旧清理这三类问题——本次模拟不需要为此付代价
 *   <li>{@code HarnessSkillMiddleware} 消费的只是 {@code AgentSkillRepository#getAllSkills()}
 *       返回的 {@link AgentSkill} 列表（实测 {@code HarnessSkillMiddleware.java:308-314}），
 *       内存实现和文件实现在这个接口面前完全等价
 *   <li>inline skill 的正文本来就已经在 Blueprint JSON 里，落盘再读回是一次无意义的往返
 * </ol>
 *
 * <p>写操作全部拒绝：Blueprint 是唯一真值来源，运行时不应该反向改写租户 Skill。
 */
public final class InMemorySkillRepository implements AgentSkillRepository {

    private final Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();
    private final String source;

    public InMemorySkillRepository(String source, List<AgentSkill> skills) {
        this.source = source == null || source.isBlank() ? "blueprint" : source;
        if (skills != null) {
            for (AgentSkill skill : skills) {
                if (skill != null) {
                    skillsByName.put(skill.getName(), skill);
                }
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        return name == null ? null : skillsByName.get(name.trim());
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(skillsByName.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return new ArrayList<>(skillsByName.values());
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return skillName != null && skillsByName.containsKey(skillName.trim());
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("blueprint-memory", source, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读仓库，忽略写开关
    }

    @Override
    public boolean isWriteable() {
        return false;
    }
}
