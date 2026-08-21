---
name: p3c-business
description: 当收到业务专家规格时，拆分业务分支、异常兜底与转人工边界。
---
# p3c-business
- 用途：建立 AgentBlueprint 的业务骨架。
- 流程：根据派活消息中的 Triage/Guidance 生成业务规则对象 → `chatflows-p3c__submitExpertResult`（`role=business-expert`，`payload=<业务对象>`）→ 一行 `EXPERT_REPORT`。无确定性渲染工具，允许 LLM 生成；但生成结果必须经 `submitExpertResult` 落 Nest，禁止贴完整 JSON 到 Room。禁止 filesync list 其它 `task-*`。收到 TASK_ASSIGNED 后尽快 submit，不要先长篇复述 spec。
- 禁止：`write_file` / shell / `recall_history`；禁止 filesync push；禁止写 Skill 正文或选 MCP 权限。
- 可选只读：`chatflows-p3c__listSkillCandidates` / `__listToolCandidates`（需要时）。连接抖动退避最多 3 次。
- 失败处理：无法判定则标记 Human review，不虚构政策。
- 汇报（首行照抄 Leader 完整 MXID）：
  `EXPERT_REPORT role=business run_id=<run_id> status=SUCCEEDED expert_result@v<n>`
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
