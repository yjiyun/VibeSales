---
name: p3c-skill-draft
description: 当 Skill 市场无合适候选时，按八字段起草 inline SKILL.md。
---
# p3c-skill-draft
- 用途：补齐租户特有能力。
- 输入 / 输出：输入业务缺口；输出含 frontmatter 的 inline Skill 草案。
- 调用条件：p3c-skill-select 无覆盖项。
- 依赖工具：无外部工具；只使用已给业务规格。
- 失败处理：触发条件或依赖不完整则不提交，交 Human 评审。
- 安全边界：不得含绝对路径、凭证、自动 promote 或不可信代码。
- 复用价值：规范化新 Skill 起草。
- 与协同流程的关系：为 Blueprint skills[] 提供 inline 条目。
