---
name: p2-filter-rank-decide
description: 当 P1 gate=PASS 时，过滤、排序并裁决模板，必要时生成预览。
---
# p2-filter-rank-decide
- 用途：从模板库确定 hit/custom。
- 输入 / 输出：输入 Triage 与 tenant；输出 MatchResult、可选 v0 preview。
- 调用条件：仅 gate=PASS。
- 依赖工具：chatflows-p2.match、preview。
- 失败处理：模型裁决失败退规则 rank#1；硬过滤不得绕过。
- 安全边界：不读取 workflow YAML 正文，不修改 Triage。
- 复用价值：规则优先的模板推荐。
- 与协同流程的关系：向 Leader 提供三分支路由输入。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
