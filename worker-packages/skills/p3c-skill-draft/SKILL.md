---
name: p3c-skill-draft
description: 当 Skill 市场无合适候选时，按八字段起草 inline SKILL.md。
---
# p3c-skill-draft
- 用途：补齐租户特有能力。
- 调用条件：p3c-skill-select 无覆盖项。
- 流程：起草含 frontmatter 的 inline Skill → 由 skill-expert 经 `submitExpertResult` 提交（本 Skill 不单独写盘）。
- 禁止：`write_file` / shell / `recall_history`；不得含绝对路径、凭证、自动 promote。
- 失败处理：触发条件或依赖不完整则不提交，交 Human 评审。
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
