---
name: p3c-tool-select
description: 当业务分支与 Skill 依赖明确时，选择租户可见 MCP 工具并定义白名单。
---
# p3c-tool-select
- 用途：形成 tools.allow/deny/mcpServers。
- 输入 / 输出：输入 scenarios/clientCode/Skill 依赖；输出 tool.json。
- 调用条件：P3C 工具专家阶段。
- 依赖工具：chatflows-p3c.listToolCandidates。
- MCP 工具调用方式（**直接调用，不要先查工具列表**）：真实工具名是 `chatflows-p3c__listToolCandidates`（服务器名与工具名之间是**双下划线**），由运行时注入，直接发起调用即可。⚠️ 不要用 `curl .../api/agents/default/tools`、`api/tools`、`mcporter list` 去「先确认工具存在」——这些端点只返回内置工具（read_file/write_file/…），**不列出 MCP 注入的工具**，你会误判成「工具不可用」而放弃；也没有 `api/mcp/call` 这种端点。看不到列表不等于不能调用：直接调，真失败了再按失败处理报 RUN_BLOCKED。
- MCP 报 `driver_not_found` 或连接类错误先退避重试，不要立刻报 RUN_BLOCKED：qwenpaw 的 MCP 长连接约 300 秒空闲会被底层传输超时，之后有几秒的自动重连窗口，这期间的调用失败是正常瞬时现象，不是平台永久故障。等 5 秒重试，最多 3 次（间隔 5s/10s/15s），仍失败才报 RUN_BLOCKED（带上最后一次的原始报错）。
- 失败处理：缺依赖工具则报告装配缺口，不写直连地址。
- 安全边界：URL 仅 Higress；无真凭证；只写 experts/tool.json。
- 复用价值：租户工具可见性治理。
- 与协同流程的关系：为 Blueprint tools 提供唯一输入。
- 完成汇报（必做，否则整条流水线停摆）：写完 experts/tool.json 并 filesync push 后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `EXPERT_REPORT role=tool run_id=<run_id> status=SUCCEEDED artifact=experts/tool.json`
  失败/缺口（RUN_BLOCKED 等）汇报同样必须首行照抄完整 MXID。
