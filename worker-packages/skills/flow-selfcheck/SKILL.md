---
name: flow-selfcheck
description: 当工作流生成或回写完成时，必跑 11 项结构检查并阻断静默缺陷。
---
# flow-selfcheck
- 用途：检查 ref、拓扑、占位符、HTTP 引用和 list items 等不变式。
- 输入 / 输出：输入 YAML；输出逐项 CheckReport。
- 调用条件：P3b 生成后必跑，无例外。
- 依赖工具：chatflows-p3b.selfcheck；不调 LLM。
- 失败处理：任一 error 不进 P4，回生成器；两轮失败升 Human。
- 安全边界：只读产物，不出网，不修业务语义。
- 复用价值：所有 YunFlow 产物统一准入检查。
- 与协同流程的关系：P3b→P4 强制闸门 A11。
