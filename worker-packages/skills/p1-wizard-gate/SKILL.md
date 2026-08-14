---
name: p1-wizard-gate
description: 当向导收到用户答复或修订时，通过 chatflows-p1 计算下一问和唯一 gate。
---
# p1-wizard-gate
- 用途：填槽、追问、修订并形成 Phase1Result。
- 输入 / 输出：输入用户答复与 Triage；输出 ASK/CUSTOM/PASS 和完整 Triage。
- 调用条件：每个 P1 回合必调。
- 依赖工具：chatflows-p1.ask、revise、buildPhase1Result。
- 参数契约：`triage` 必须是 JSON 对象，禁止传 JSON 字符串或空对象；集成验收时原样使用 spec.md 给出的 P1 输入调用 buildPhase1Result，gate 只接受工具返回。
- 失败处理：保留 wizard_state 并请求重试，不伪造 PASS。
- 安全边界：选项只取 catalogs；不调用 P2。
- 复用价值：多回合人在环需求收集。
- 与协同流程的关系：P1→P2 的唯一准入闸门。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
