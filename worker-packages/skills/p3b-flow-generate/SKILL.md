---
name: p3b-flow-generate
description: 当需求可由固定 DAG 表达但无高匹配模板时，生成 YunFlow YAML。
---
# p3b-flow-generate
- 用途：从精简上下文包生成工作流。
- 输入 / 输出：输入 Triage、Guidance；输出 flow_yaml。
- 调用条件：build_path=P3B 且 guidance artifact 已由 template-personalize 写入。
- 依赖工具：chatflows-p3b.generate。
- 失败处理：交 flow-selfcheck；失败最多重生成两轮。
- 安全边界：不使用缺陷存量工作流作 few-shot；不直连导入器。
- 复用价值：固定 DAG 的快速生产。
- 与协同流程的关系：P2 custom 到 P4 的生成分支。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
