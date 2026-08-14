---
name: p3c-skill-select
description: 当业务能力明确时，从只读 Skill 市场筛选可复用项。
---
# p3c-skill-select
- 用途：优先复用已有 Skill，避免重复造轮子。
- 输入 / 输出：输入 industry/scenarios；输出 library Skill 引用。
- 调用条件：P3C Skill 专家阶段先执行。
- 依赖工具：chatflows-p3c.listSkillCandidates。
- 失败处理：无候选时转 p3c-skill-draft。
- 安全边界：市场只读，不 promote，不修改 catalog。
- 复用价值：跨租户能力复用。
- 与协同流程的关系：为 Blueprint skills[] 提供 library 条目。
