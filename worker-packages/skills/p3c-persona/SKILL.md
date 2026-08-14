---
name: p3c-persona
description: 当收到人格专家规格时，把 Guidance 转成 AGENTS.md 与 SOUL.md。
---
# p3c-persona
- 用途：定义身份、语气、回复长度和转人工表达。
- 输入 / 输出：输入 Guidance；输出 persona.json。
- 调用条件：P3C 专家并行阶段。
- 依赖工具：chatflows-p3c.renderPersona。
- 失败处理：缺字段返回缺口，不越权补 Skill/Tool。
- 安全边界：不决定能力和权限；只写 experts/persona.json。
- 复用价值：跨行业人格模板渲染。
- 与协同流程的关系：四专家之一，冲突优先级最低。
