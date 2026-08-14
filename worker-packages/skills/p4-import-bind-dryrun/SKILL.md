---
name: p4-import-bind-dryrun
description: 当上游产物自检通过且 Human 批准后，执行导入、绑定和试运行。
---
# p4-import-bind-dryrun
- 用途：把工作流导入引擎，或把 Blueprint 置 STAGED 并绑定 runtime。
- 输入 / 输出：输入产物与 CheckReport；输出 external_id、binding、dry_run。
- 调用条件：自检全绿；先调 import 取得 approval_id，在 Human Room 发送严格的 APPROVE 或 DENY 命令（同时带 run_id 与 approval_id）；收到 Leader 转发的匹配 HMAC proof 后，用同一 run_id/approval_id 再调 import。
- 依赖工具：chatflows-p4.import、bindProject、dryRun。
- 失败处理：拒绝则 ABORTED；执行失败回滚并留 evidence。
- 安全边界：按 client_code 隔离；proof 只用于匹配 run_id/approval_id 的一次 import，禁止伪造、重放、写入日志或 result.md。
- 复用价值：统一工作流与 Blueprint 发布入口。
- 与协同流程的关系：P1–P4 最终落地与冒烟阶段。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
