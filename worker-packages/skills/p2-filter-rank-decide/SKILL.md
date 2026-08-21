---
name: p2-filter-rank-decide
description: 当 P1 gate=PASS 时，过滤、排序并裁决模板，必要时生成预览。
---
# p2-filter-rank-decide
- 用途：从模板库确定 hit/custom。
- 输入 / 输出：输入 Triage 与 tenant；输出 MatchResult 的 Nest 指针。
- 调用条件：仅 gate=PASS，且 Nest 当前 run 已处于 P2。
- 流程：必要时 `filesync pull` **仅当前** `shared/tasks/task-<run_id>/spec.md` → `chatflows-p2__match`（需要时再 `__preview`）→ 一行 `P2_REPORT`。禁止 `write_file` / `execute_shell_command` / `recall_history`；禁止 filesync list 或 pull 其它 `task-*`；禁止把完整 MatchResult JSON 贴进 Room；禁止写 `p2_match.json`。收到 TASK_ASSIGNED 后应尽快调 MCP，不要先解释任务。
- MCP 直接调（双下划线）：`chatflows-p2__match`、`chatflows-p2__preview`。不要先查工具列表。
- MCP `driver_not_found` / 连接类错误：等 10s 重试，最多 3 次（10/20/30s），仍失败再 `RUN_BLOCKED`。
- 失败处理：MCP 失败只报 `RUN_BLOCKED`；模型裁决失败退规则 rank#1；硬过滤不得绕过。
- 安全边界：不读取 workflow YAML 正文，不修改 Triage。
- 汇报（首行照抄 Leader 完整 MXID）：
  `P2_REPORT run_id=<run_id> status=SUCCEEDED match_result=match_result@v<n> template_id=<id> build_path=<P3|P3B|P3C>`
- 观测：每次 MCP 原样携带 `_ctx.run_id/client_code/request_id/traceparent`。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
