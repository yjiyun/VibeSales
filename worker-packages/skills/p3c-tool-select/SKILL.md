---
name: p3c-tool-select
description: 当业务分支与 Skill 依赖明确时，选择租户可见 MCP 工具并定义白名单。
---
# p3c-tool-select
- 用途：形成 tools.allow/deny/mcpServers。
- 输入 / 输出：输入 scenarios/clientCode/Skill 依赖；输出 tool.json。
- 调用条件：P3C 工具专家阶段。
- 依赖工具：chatflows-p3c.listToolCandidates。
- 失败处理：缺依赖工具则报告装配缺口，不写直连地址。
- 安全边界：URL 仅 Higress；无真凭证；只写 experts/tool.json。
- 复用价值：租户工具可见性治理。
- 与协同流程的关系：为 Blueprint tools 提供唯一输入。
