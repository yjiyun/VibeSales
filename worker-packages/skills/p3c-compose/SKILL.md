---
name: p3c-compose
description: 当四位专家结果齐备时，按业务优先级合成 AgentBlueprint 并持久化 DRAFT。
---
# p3c-compose
- 用途：装配 Prompt、Skills、Tools 与 runtime 配置。
- 输入 / 输出：输入四份 experts JSON；输出 AgentBlueprint。
- 调用条件：build_path=P3C、guidance artifact 已就绪且专家产物齐备。
- 依赖工具（**直接调用，不要先查工具列表**）：真实工具名是 `chatflows-p3c__composeBlueprint`、`chatflows-p3c__blueprintSelfcheck`、`chatflows-p3c__persistBlueprint`（服务器名与工具名之间是**双下划线**）。这三个是运行时注入的原生工具，**直接发起调用即可**。
  ⚠️ 不要用 `curl http://127.0.0.1:8088/api/agents/default/tools`、`api/tools`、`mcporter list` 之类的方式「先确认工具存在」——那些端点只返回内置工具（read_file/write_file/…），**不列出 MCP 注入的工具**，你会误判成「工具不可用」然后放弃。也不要试图用 `curl`/`execute_shell_command` 直接打 MCP HTTP 端点或 `api/mcp/call`（没有这个端点）。看不到列表不等于不能调用：直接调，失败了再按失败处理报 RUN_BLOCKED。
- 参数传递（关键）：`composeBlueprint` 返回的是**完整 Blueprint 对象**（含 blueprintId / prompt / skills[] / tools.allow[] / tools.mcpServers[] / runtime / guidance）。调 `blueprintSelfcheck` 与 `persistBlueprint` 时，`blueprint` 参数必须**原样回传这个完整对象**，不要只传 `blueprintId` 字符串、不要自己裁剪或重写字段。只传 ID 或缺字段会被服务端拒绝并列出缺失项（`blueprint is incomplete ... missing: ...`）。
- MCP 报 `driver_not_found` 或连接类错误先退避重试，不要立刻报 RUN_BLOCKED：qwenpaw 的 MCP 长连接约 300 秒空闲会被底层传输超时，之后有几秒的自动重连窗口，这期间的调用失败是正常瞬时现象，不是平台永久故障。等 5 秒重试，最多 3 次（间隔 5s/10s/15s），仍失败才报 RUN_BLOCKED（带上最后一次的原始报错）。composeBlueprint 是纯计算，重试无副作用；已拿到 blueprintId 的话下次不必重新调 composeBlueprint，直接原样传上次的完整 Blueprint 对象去重试 blueprintSelfcheck 即可。
- 失败处理：按缺口定向重派专家；自检失败不得持久化。
- 安全边界：产物是数据，不生成 Java 代码；冲突顺序 business>skill>tool>persona。
- 复用价值：多租户智能体制品装配。
- 与协同流程的关系：P3C 五步流水线第 3、5 步。
- 完成汇报（必做，否则整条流水线停摆）：composeBlueprint + blueprintSelfcheck + persistBlueprint 成功后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `BLUEPRINT_REPORT run_id=<run_id> status=SUCCEEDED blueprint=blueprint@v<n> blueprint_check=blueprint_check@v<n>`
  失败/缺口（RUN_BLOCKED 等）汇报同样必须首行照抄完整 MXID。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
