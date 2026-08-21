---
name: p3c-compose
description: 当四位专家结果齐备时，按业务优先级合成 AgentBlueprint 并持久化 DRAFT。
---
# p3c-compose
- 用途：装配 Prompt、Skills、Tools 与 runtime 配置。
- 调用条件：build_path=P3C、guidance 已就绪、四专家已各自 `submitExpertResult`。
- 流程（工具之间不发旁白）：直接
  1. `chatflows-p3c__composeBlueprint`（**不要传 persona/business/skills/tools**，服务端从 `expert_result` 回读）
  2. `chatflows-p3c__blueprintSelfcheck`（**不要传 blueprint**，回读 `blueprint_draft`）
  3. `chatflows-p3c__persistBlueprint`（**不要传 blueprint**，回读 `blueprint_draft`）
  然后一行 `BLUEPRINT_REPORT`。`client_code` 用派活消息；失败不要改调 `list*` 做诊断。
- 禁止：`write_file` / shell / `recall_history` / `read_file` 翻历史或读 experts 目录；禁止把完整 Blueprint 贴进 Room；禁止工具前后旁白。
- MCP 直接调（双下划线）。连接抖动：10/20/30s 退避最多 3 次。
- 失败处理：按缺口定向重派专家；自检失败不得 persist。
- 安全边界：产物是数据；冲突顺序 business>skill>tool>persona。
- 汇报（首行照抄 Leader 完整 MXID）：
  `BLUEPRINT_REPORT run_id=<run_id> status=SUCCEEDED blueprint=blueprint@v<n> blueprint_check=blueprint_check@v<n> blueprintId=<id>`
- 观测：每次 MCP 原样携带 `_ctx`。
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
