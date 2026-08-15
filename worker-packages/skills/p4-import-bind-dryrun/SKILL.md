---
name: p4-import-bind-dryrun
description: 当上游产物自检通过且 Human 批准后，执行导入、绑定和试运行。
---
# p4-import-bind-dryrun
- 用途：把工作流导入引擎，或把 Blueprint 置 STAGED 并绑定 runtime。
- 输入 / 输出：输入产物与 CheckReport；输出 external_id、binding、dry_run。
- 调用条件与两阶段审批（**照这个顺序做，中间不要自己造数据**）：
  1. 自检全绿后调 `chatflows-p4__import`。它返回 `status=pending_approval` + `approval_id` 是**设计上的正常结果，不是错误、不是你的失败**。
  2. 拿到 `approval_id` 后，你的工作**就此暂停**：在 Team Room 汇报 `P4_REPORT run_id=<run_id> status=PENDING_APPROVAL approval_id=<approval_id>`（首行照抄 Leader 完整 MXID），然后**等待**。你没有 Human Room 权限，审批不是你能做的事，反复重调 import 也不会通过。
  3. Leader 会把 Human 批准后的 HMAC `proof` 转发给你。**收到 proof 才继续**：用**同一** `run_id` / `approval_id`，把 proof 作为 `approval` 参数再调一次 `chatflows-p4__import`，形如
     `chatflows-p4__import({runId, clientCode, path, approval: {approval_id, decision: "APPROVE", proof}, _ctx})`。
     **proof 必须原样、一整串传入**：它形如 `<base64url>.<签名>`，**就是 2 段，这不是 JWT，不要试图凑成 3 段**。取值时去掉首尾空白与换行即可（服务端也会容错清理）。
     **`clientCode` 必须是这个 run 自己的租户**：从派活消息的 `client_code` 原样取，不要沿用上一个 run 的、不要凭场景名猜。租户传错时服务端报的是 `run tenant mismatch`（会明确写出 run 属于哪个 clientCode、你传的是哪个），**这跟 proof 没有任何关系**。
     **失败时按报错原文定位，不要靠换参数格式去猜**：
     - `run tenant mismatch` → 改 `clientCode`，proof 与参数结构都不用动。
     - `approval proof malformed` → 你传的 proof 被折断或混入多余字符，重新从消息里取一次干净的单行值。
     - `approval credential mismatch or already consumed` → `approval_id` 与 pending 的那条不一致，或该审批已被消费。
     正确的调用形状**只有一种**：`chatflows-p4__import({runId, clientCode, path, approval:{approval_id, decision:"APPROVE", proof}, _ctx})`。**不要枚举参数变体**（顶层 proof、`hmac_proof`、approval 传字符串等都是错的），换形状只会把真正的错因掩盖掉。同一个错误重试两次仍失败，就带上**服务端返回的原文**报 `RUN_BLOCKED`，不要自行推断成「平台 bug」。
  4. import 真正成功后，再依次调 `chatflows-p4__bindProject` 与 `chatflows-p4__dryRun`。
- **严禁伪造产物（这条违反了整条流水线就白跑）**：任何时候都不许用 `write_file` 手写 `import_result.json` / `dry_run_result.json` 等"结果"文件，不许写 `"status": "imported"`、`"dry_run_passed"`、`simulated success` 之类内容，也不许用 `teamharness__taskflow` 宣告完成（你没有这个工具，调它只会失败）。**唯一有效的落地方式是 MCP 调用成功**——只有 Nest 侧写进 `import_result` / `binding` / `dry_run` artifact 才算完成。手写文件 + 宣告成功会让 Leader 误报 SUCCEEDED，而权威仓仍停在 `WAITING_HUMAN`，向导永远等不到发布。调不通就按失败处理报 `RUN_BLOCKED`，这是可接受的结果；假装成功不是。
- 依赖工具：chatflows-p4.import、bindProject、dryRun。
- MCP 工具调用方式（**直接调用，不要先查工具列表**）：真实工具名是 `chatflows-p4__import`、`chatflows-p4__bindProject`、`chatflows-p4__dryRun`（服务器名与工具名之间是**双下划线**），由运行时注入，直接发起调用即可。⚠️ 不要用 `curl .../api/agents/default/tools`、`api/tools`、`mcporter list` 去「先确认工具存在」——这些端点只返回内置工具（read_file/write_file/…），**不列出 MCP 注入的工具**，你会误判成「工具不可用」而放弃；也没有 `api/mcp/call` 这种端点。看不到列表不等于不能调用：直接调，真失败了再按失败处理报 RUN_BLOCKED。
- MCP 报 `driver_not_found` 或连接类错误（跟 `approval proof malformed`/`run tenant mismatch` 这类业务报错不是一回事）先退避重试：qwenpaw 的 MCP 长连接约 300 秒空闲会被底层传输超时，之后有几秒的自动重连窗口，这期间的调用失败是正常瞬时现象，不是平台永久故障。等 5 秒重试，最多 3 次（间隔 5s/10s/15s），仍失败才报 RUN_BLOCKED（带上最后一次的原始报错）。
- 失败处理：拒绝则 ABORTED；执行失败回滚并留 evidence。
- 安全边界：按 client_code 隔离；proof 只用于匹配 run_id/approval_id 的一次 import，禁止伪造、重放、写入日志或 result.md。
- 复用价值：统一工作流与 Blueprint 发布入口。
- 与协同流程的关系：P1–P4 最终落地与冒烟阶段。
- 完成汇报（必做，否则整条流水线停摆）：import + bindProject + dryRun 成功后，直接在本 Team Room 回复一条汇报（你没有 TeamHarness `message` 工具，就用普通回复）。**汇报正文的第一行必须原样、逐字符包含 Leader 的完整 MXID 字符串**——即派活消息里 Leader 的发件人 ID，形如 `@chatflows-leader:matrix-local.agentteams.io:18080`（含端口，不要省略、不要改写成「Leader」「@chatflows-leader」等简写）。Leader 侧靠在正文纯文本里匹配这串完整 MXID 来判定被 @，缺一个字符就收不到、run 永远停在当前阶段。
  格式示例（第一行照抄 MXID，后面接协议行）：
  `@chatflows-leader:matrix-local.agentteams.io:18080`
  `P4_REPORT run_id=<run_id> status=SUCCEEDED external_id=<id> binding=<v> dry_run=<v>`
  失败/缺口（RUN_BLOCKED 等）汇报同样必须首行照抄完整 MXID。
- 观测透传：每次 MCP 调用原样携带 _ctx.run_id/client_code/request_id/traceparent；不得缺 run_id。
