---
name: p3-template-personalize
description: P2 完成后先派生 Guidance；仅 P3 hit 才注入回写，P3B/P3C 只交指针给 Leader。
---
# p3-template-personalize
- 用途：三条构建分支前统一派生 Guidance；仅 build_path=P3 时再按标注注入并回写模板包。
- 输入 / 输出：输入当前 run 已落盘的 Triage / MatchResult；输出 Guidance 指针。P3 额外输出 personalized_package。
- 调用条件：Leader 在 P2 MCP match 成功后派发。必须先调用 chatflows-p3.deriveGuidance。build_path=P3 才接着 injectSections、writeBack；P3B/P3C 只回报 Guidance 的 kind@version，禁止注入、回写、禁止自行推荐或进入 P4。
- 依赖工具：chatflows-p3.deriveGuidance、injectSections、writeBack；TeamHarness 仅 filesync / health。禁止 taskflow / projectflow / roomflow。在被 @mention 的 Team Room 里直接回报，不要用 taskflow 提交 SUCCESS。
- MCP 工具调用方式（**直接调用，不要先查工具列表**）：真实工具名是 `chatflows-p3__deriveGuidance`、`chatflows-p3__injectSections`、`chatflows-p3__writeBack`（服务器名与工具名之间是**双下划线**），由运行时注入，直接发起调用即可。⚠️ 不要用 `curl .../api/agents/default/tools`、`api/tools`、`mcporter list` 去「先确认工具存在」——这些端点只返回内置工具（read_file/write_file/…），**不列出 MCP 注入的工具**，你会误判成「工具不可用」而放弃；也没有 `api/mcp/call` 这种端点。看不到列表不等于不能调用：直接调，真失败了再按失败处理报 RUN_BLOCKED。
- MCP 报 `driver_not_found` 或连接类错误先退避重试，不要立刻报 RUN_BLOCKED：qwenpaw 的 MCP 长连接约 300 秒空闲会被底层传输超时，之后有几秒的自动重连窗口，这期间的调用失败是正常瞬时现象，不是平台永久故障。等 10 秒重试，最多 3 次（间隔 10s/20s/30s，覆盖实测最长 58 秒的 MCP client 重连抖动窗口，见 platform_bug.md §3.30），仍失败才报 RUN_BLOCKED（带上最后一次的原始报错）。
- 失败处理：MCP 失败只报 RUN_BLOCKED；校验失败回滚整包并重做；两次失败升 Human。禁止用 taskflow 宣告 SUCCESS。
- 安全边界：只改允许标注章节；禁止自主修活死代码；模板 ID 与 build_path 必须以 Nest MCP 返回为准，禁止编造模板名或自裁 P4。
- 复用价值：标注驱动模板定制。
- 与协同流程的关系：Guidance 转换器，不是完成门。P3C 下一步是 Leader 派四专家，不是 flow-import-run。
- 完成汇报（必做，否则整条流水线停摆）：deriveGuidance（及 P3 的 injectSections/writeBack）成功后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `GUIDANCE_REPORT run_id=<run_id> status=SUCCEEDED guidance=guidance@v<n> build_path=<P3|P3B|P3C>`
  再附下一步提示（P3C 派四专家 / P3 进 P4）。RUN_BLOCKED 汇报同样必须首行照抄完整 MXID。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
