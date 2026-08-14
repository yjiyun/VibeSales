---
name: p3-template-personalize
description: 当 MatchResult 为 hit 时，派生指导、按标注注入并校验回写模板包。
---
# p3-template-personalize
- 用途：在不改业务逻辑的前提下个性化命中模板。
- 输入 / 输出：输入 MatchResult、Triage；输出 Guidance 与 personalized_package。
- 调用条件：build_path=P3。
- 依赖工具：chatflows-p3.deriveGuidance、injectSections、writeBack。
- 失败处理：校验失败回滚整包并重做；两次失败升 Human。
- 安全边界：只改允许标注章节；禁止自主修活死代码。
- 复用价值：标注驱动模板定制。
- 与协同流程的关系：P2 hit 到 P4 的产物转换器。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
