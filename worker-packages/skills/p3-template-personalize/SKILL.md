---
name: p3-template-personalize
description: P2 完成后先派生 Guidance；仅 P3 hit 才注入回写，P3B/P3C 只交指针给 Leader。
---
# p3-template-personalize
- 用途：三条构建分支前统一派生 Guidance；仅 build_path=P3 时再注入回写。
- 流程：`chatflows-p3__deriveGuidance` →（仅 P3）`__injectSections` + `__writeBack` → 一行 `GUIDANCE_REPORT`。禁止 `write_file` / shell / `recall_history`；禁止贴完整 Guidance JSON；禁止写 `guidance.json`；禁止 filesync list 其它 `task-*`。收到 TASK_ASSIGNED 后第一个动作必须是 `deriveGuidance`。
- MCP 直接调：`chatflows-p3__deriveGuidance`、`chatflows-p3__injectSections`、`chatflows-p3__writeBack`。
- MCP 连接抖动：10/20/30s 退避最多 3 次，再 `RUN_BLOCKED`。
- 安全边界：只改允许标注章节；模板 ID 与 build_path 以 Nest 返回为准。
- 汇报（首行照抄 Leader 完整 MXID）：
  `GUIDANCE_REPORT run_id=<run_id> status=SUCCEEDED guidance=guidance@v<n> build_path=<P3|P3B|P3C>`
- 观测：每次 MCP 原样携带 `_ctx`。
- 输入 / 输出：输入派活消息中的指针与当前 run_id；输出一行协议 REPORT（kind@version）。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 失败处理：MCP 失败报 RUN_BLOCKED；两轮失败升 Human。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
