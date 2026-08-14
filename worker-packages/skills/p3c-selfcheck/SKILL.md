---
name: p3c-selfcheck
description: 当 Blueprint 草案形成时，执行 13 项装配一致性检查。
---
# p3c-selfcheck
- 用途：拦截人格、Skill、工具、隔离与转人工条件缺失。
- 输入 / 输出：输入 AgentBlueprint；输出 13 项 CheckReport。
- 调用条件：persist/import 前必跑。
- 依赖工具：chatflows-p3c.blueprintSelfcheck；不调 LLM。
- 失败处理：error 阻断 STAGED；语义 warning 交 Human。
- 安全边界：只校验不修写，不接触凭证。
- 复用价值：任意 AgentBlueprint 的准入检查。
- 与协同流程的关系：P3C→P4 强制闸门。
