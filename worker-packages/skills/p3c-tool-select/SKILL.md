---
name: p3c-tool-select
description: 当业务分支与 Skill 依赖明确时，选择租户可见 MCP 工具并定义白名单。
---
# p3c-tool-select
- 用途：形成 tools.allow/deny/mcpServers。
- 硬约束：必须先调 `chatflows-p3c__listToolCandidates`，**禁止手写工具候选**。再用返回值调 `chatflows-p3c__submitExpertResult`，参数 `role=tool-expert`、`payload=<listToolCandidates 返回值>`。收到 TASK_ASSIGNED 后第一个动作必须是 `listToolCandidates`。候选为空（当前无租户 MCP 注册表）时提交空数组，禁止补写未返回的工具名。
- 禁止：`write_file` / shell / `recall_history`；禁止 filesync push；禁止贴完整 JSON；禁止写直连 URL/凭证。
- MCP 直接调。连接抖动：10/20/30s 退避最多 3 次。
- 失败处理：缺依赖工具则报告装配缺口。
- 汇报（首行照抄 Leader 完整 MXID）：
  `EXPERT_REPORT role=tool run_id=<run_id> status=SUCCEEDED expert_result@v<n>`
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
