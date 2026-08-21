---
name: p3c-persona
description: 当收到人格专家规格时，把 Guidance 转成 AGENTS.md 与 SOUL.md。
---
# p3c-persona
- 用途：定义身份、语气、回复长度和转人工表达。
- **收到 TASK_ASSIGNED 后第一个动作必须是 `chatflows-p3c__renderPersona`**。`guidance` 可省略（服务端回读已落库的 guidance）；也可把派活消息里的指针原样传入（`guidance@v1`）。禁止手写 persona JSON，禁止到 spec/meta/共享目录里找 guidance 正文。
- 再用工具返回值调 `chatflows-p3c__submitExpertResult`，参数 `role=persona-expert`、`payload=<renderPersona 返回值>`。
- 禁止：`write_file` / shell / `recall_history` / `glob_search`；禁止 filesync push；禁止 filesync list 其它 `task-*`；禁止把完整产物贴进 Room；禁止工具前后旁白。
- MCP 直接调（双下划线）。连接抖动：10/20/30s 退避最多 3 次。
- 失败处理：缺字段返回缺口，不越权补 Skill/Tool。
- 汇报（首行照抄 Leader 完整 MXID）：
  `EXPERT_REPORT role=persona run_id=<run_id> status=SUCCEEDED expert_result@v<n>`
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
