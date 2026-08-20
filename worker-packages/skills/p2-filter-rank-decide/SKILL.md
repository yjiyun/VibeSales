---
name: p2-filter-rank-decide
description: 当 P1 gate=PASS 时，过滤、排序并裁决模板，必要时生成预览。
---
# p2-filter-rank-decide
- 用途：从模板库确定 hit/custom。
- 输入 / 输出：输入 Triage 与 tenant；输出 MatchResult、可选 v0 preview。
- 调用条件：仅 gate=PASS，且 Nest 当前 run 已处于 P2。
- 依赖工具：chatflows-p2.match、preview；TeamHarness 仅 filesync / health。禁止 taskflow / projectflow / roomflow。
- MCP 工具调用方式（**直接调用，不要先查工具列表**）：真实工具名是 `chatflows-p2__match`、`chatflows-p2__preview`（服务器名与工具名之间是**双下划线**），由运行时注入，直接发起调用即可。⚠️ 不要用 `curl .../api/agents/default/tools`、`api/tools`、`mcporter list` 去「先确认工具存在」——这些端点只返回内置工具（read_file/write_file/…），**不列出 MCP 注入的工具**，你会误判成「工具不可用」而放弃；也没有 `api/mcp/call` 这种端点。看不到列表不等于不能调用：直接调，真失败了再按失败处理报 RUN_BLOCKED。
- MCP 报 `driver_not_found` 或连接类错误先退避重试，不要立刻报 RUN_BLOCKED：qwenpaw 的 MCP 长连接约 300 秒空闲会被底层传输超时，之后有几秒的自动重连窗口，这期间的调用失败是正常瞬时现象，不是平台永久故障。等 10 秒重试，最多 3 次（间隔 10s/20s/30s，覆盖实测最长 58 秒的 MCP client 重连抖动窗口，见 platform_bug.md §3.30），仍失败才报 RUN_BLOCKED（带上最后一次的原始报错）。
- 失败处理：MCP 失败只报 RUN_BLOCKED；模型裁决失败退规则 rank#1；硬过滤不得绕过。禁止用 taskflow 宣告 SUCCESS。
- 安全边界：不读取 workflow YAML 正文，不修改 Triage。
- 复用价值：规则优先的模板推荐。
- 与协同流程的关系：向 Leader 提供三分支路由输入。
- 完成汇报（必做，否则整条流水线停摆）：MCP match/preview 成功后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `P2_REPORT run_id=<run_id> status=SUCCEEDED match_result=match_result@v<n> template_id=<id> build_path=<P3|P3B|P3C>`
  失败/缺口（RUN_BLOCKED 等）汇报同样必须首行照抄完整 MXID。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
