---
name: p3c-skill-select
description: 当业务能力明确时，从只读 Skill 市场筛选可复用项。
---
# p3c-skill-select
- 用途：优先复用已有 Skill。
- 流程：`chatflows-p3c__listSkillCandidates` →（无覆盖时再走 p3c-skill-draft 起草）→ `chatflows-p3c__submitExpertResult`（`role=skill-expert`，`payload=<候选或草案>`）→ 一行 `EXPERT_REPORT`。收到 TASK_ASSIGNED 后第一个动作必须是 `listSkillCandidates`。
- 禁止：`write_file` / shell / `recall_history`；禁止 filesync push；禁止贴完整 JSON；市场只读不 promote。
- MCP 直接调。连接抖动退避最多 3 次。
- 汇报（首行照抄 Leader 完整 MXID）：
  `EXPERT_REPORT role=skill run_id=<run_id> status=SUCCEEDED expert_result@v<n>`
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 失败处理：MCP 失败报 RUN_BLOCKED；两轮失败升 Human。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
