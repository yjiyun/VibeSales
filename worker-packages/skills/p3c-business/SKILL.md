---
name: p3c-business
description: 当收到业务专家规格时，拆分业务分支、异常兜底与转人工边界。
---
# p3c-business
- 用途：建立 AgentBlueprint 的业务骨架。
- 输入 / 输出：输入 Triage、Guidance；输出 business.json。
- 调用条件：P3C 专家并行阶段。
- 依赖工具：chatflows-p3c 只读候选查询。
- 失败处理：无法判定则标记 Human review，不虚构政策。
- 安全边界：不写 Skill 正文，不选 MCP 权限；只写 experts/business.json。
- 复用价值：行业业务分支结构化。
- 与协同流程的关系：四专家之一，冲突优先级最高。
