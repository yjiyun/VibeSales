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
- 安全边界：不得含绝对路径、凭证、自动 promote 或不可信代码；只写 `shared/tasks/task-<run_id>/experts/skill.json`（**必须带 `experts/` 前缀**，写到 task 根目录会让 Leader 核验时找不到、误判缺口）。
- 复用价值：规范化新 Skill 起草。
- 与协同流程的关系：为 Blueprint skills[] 提供 inline 条目。
- 完成汇报（必做，否则整条流水线停摆）：inline Skill 草案就绪并 filesync push 后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `EXPERT_REPORT role=skill run_id=<run_id> status=SUCCEEDED artifact=experts/skill.json`
  失败/缺口（RUN_BLOCKED 等）汇报同样必须首行照抄完整 MXID。
