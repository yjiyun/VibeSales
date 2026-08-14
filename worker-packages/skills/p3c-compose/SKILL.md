---
name: p3c-compose
description: 当四位专家结果齐备时，按业务优先级合成 AgentBlueprint 并持久化 DRAFT。
---
# p3c-compose
- 用途：装配 Prompt、Skills、Tools 与 runtime 配置。
- 输入 / 输出：输入四份 experts JSON；输出 AgentBlueprint。
- 调用条件：build_path=P3C、guidance artifact 已就绪且专家产物齐备。
- 依赖工具：chatflows-p3c.composeBlueprint、persistBlueprint。
- 失败处理：按缺口定向重派专家；自检失败不得持久化。
- 安全边界：产物是数据，不生成 Java 代码；冲突顺序 business>skill>tool>persona。
- 复用价值：多租户智能体制品装配。
- 与协同流程的关系：P3C 五步流水线第 3、5 步。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
