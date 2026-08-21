---
name: p4-import-bind-dryrun
description: 当上游产物自检通过且 Human 批准后，执行导入、绑定和试运行。
---
# p4-import-bind-dryrun
- 用途：导入工作流或暂存 Blueprint，并绑定、试运行。
- **收到 TASK_ASSIGNED 后第一个动作必须是 `chatflows-p4__import`（无 approval）**。不要 filesync list、不要 glob、不要 bash、不要 pull 其它 `task-*`、不要读历史 `result.md` / `blueprint.json`。Blueprint 已在 Nest；`path` 可省略（服务端用 run.build_path 回读）。
- 两阶段审批：
  1. `chatflows-p4__import`（可省略 path）→ 正常返回 `pending_approval` + `approval_id`（不是失败）。
  2. 一行 `P4_REPORT ... status=PENDING_APPROVAL approval_id=...`，等待。
  3. 收到真实 `APPROVAL_GRANTED` + HMAC `proof` 后，用同一 `run_id`/`approval_id` 再调 `import`（`approval:{approval_id,decision:APPROVE,proof}`）。`clientCode` 必须是派活消息里的租户。
  4. import 成功后依次 `bindProject`、`dryRun`（均可省略 path）。
- `already consumed`：若返回带 `external_id` 的导入结果，视为幂等回放，继续 bind/dryRun，不要 `RUN_BLOCKED`。
- 禁止：`write_file` 伪造结果；禁止 shell / `recall_history` / `glob_search` / `grep_search`；禁止 `filesync` list 或 pull **当前 run 以外**的 `shared/tasks/task-*`；禁止编造 blueprintId/proof。
- MCP 直接调：`chatflows-p4__import` / `__bindProject` / `__dryRun`。连接抖动退避最多 3 次。
- 汇报（首行照抄 Leader 完整 MXID）。MCP 本 run 首次写入即为 v1，**不要再查 Nest、不要翻历史**：
  - 待批：`P4_REPORT run_id=<run_id> status=PENDING_APPROVAL approval_id=<id>`
  - 完成：`P4_REPORT run_id=<run_id> status=SUCCEEDED external_id=<id> import_result@v1 binding@v1 dry_run@v1 evidence@v1`
- 观测：每次 MCP 原样携带 `_ctx`。
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 失败处理：MCP 失败报 RUN_BLOCKED；两轮失败升 Human。
- 安全边界：禁止伪造产物、禁止翻历史 task-*、禁止把完整 JSON 贴进 Room。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
