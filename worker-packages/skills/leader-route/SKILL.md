---
name: leader-route
description: 当上游 artifact 齐备时，校验 schema 并按 WizardService 已产出的 gate/build_path 路由。
---
# leader-route
- 用途：推进 P1→P2→Guidance→P3/P3b/P3C→P4 DAG；三条构建分支前统一派 template-personalize.deriveGuidance。
- 阶段 → Worker（**照表派活，禁止猜名**）。**「派活首行」列是字面量，原样复制到 TASK_ASSIGNED 的第一行，不要改写、不要简写、不要去掉 `:matrix-local.agentteams.io:18080`**：

  | 阶段 | Worker | 派活首行（原样复制） | MCP |
  |------|--------|--------------------|-----|
  | P2 | `template-match` | `@template-match:matrix-local.agentteams.io:18080` | `chatflows-p2` |
  | Guidance（P3/P3B/P3C 都先走） | `template-personalize` | `@template-personalize:matrix-local.agentteams.io:18080` | `chatflows-p3` |
  | P3 注入回写 | `template-personalize` | `@template-personalize:matrix-local.agentteams.io:18080` | `chatflows-p3` |
  | P3B | `flow-generate` | `@flow-generate:matrix-local.agentteams.io:18080` | `chatflows-p3b` |
  | P3C 四专家 | `persona-expert` / `business-expert` / `skill-expert` / `tool-expert` | `@persona-expert:matrix-local.agentteams.io:18080 @business-expert:matrix-local.agentteams.io:18080 @skill-expert:matrix-local.agentteams.io:18080 @tool-expert:matrix-local.agentteams.io:18080` | `chatflows-p3c` |
  | P3C 装配 | **`blueprint-compose`** | `@blueprint-compose:matrix-local.agentteams.io:18080` | `chatflows-p3c` |
  | P4 | `flow-import-run` | `@flow-import-run:matrix-local.agentteams.io:18080` | `chatflows-p4` |

- **派活首行必须是上表的完整 Worker MXID**：Worker 只在正文纯文本出现自己完整 MXID 时才被唤醒（`m.mentions` 字段不够，且你无法为别人构造）。把目标写成 `worker=template-match`、裸名 `template-match`、`@template-match`（缺 `:domain:port`）都会让 Worker 侧记录 `group text not mentioned, cached to history` —— 消息只进历史、**不唤醒对方**，你会永远等不到 REPORT。`worker=<name>` 只能作为协议字段写在第二行起，**不能替代首行 MXID**。
- 派活后若迟迟收不到该阶段 REPORT，**先自查上一条派活首行是否为完整 MXID**；是拼写问题就用上表首行原样重发同一个 `run_id`（不新建 spec、不换 run_id），不要改判为 Worker 故障或升 Human。
- P3C 装配主控是 `blueprint-compose`，不是 `flow-generate`。
- **禁止代写产物**：执行 Worker 被治理禁止 `write_file`；权威仓是 Nest `kind@version`。不要为拒写去 `recall_history`，不要写 `p2_match.json` / `guidance.json` / `experts/*.json`，不要把完整 JSON 贴进 Room。
- **P4 派发顺序**：必须等 `BLUEPRINT_REPORT status=SUCCEEDED`（含 `blueprint@vN` / `blueprint_check@vN`）后，用汇报里的真实 `blueprintId` 再派 `flow-import-run`。禁止同批提前派 P4，禁止自编 `blueprintId`。
- 输入 / 输出：输入 artifact 指针；输出下一 Worker 任务规格。当前事件 `run_id` 优先，禁止借用历史 run_id。
- 每条 `TASK_ASSIGNED` 必须带 `run_id` 与 `client_code`，且**第一行是目标 Worker 的完整 MXID**（见上表「派活首行」）。禁止在派活前后用自然语言复述 Worker 已汇报的协议行；见到合格 REPORT 立即派下一棒。
- `filesync` 仅允许 pull `shared/tasks/task-<当前 run_id>/` 读 `spec.md`。禁止 list `shared/tasks/`、禁止 pull 其它 `task-*`。派发仅通过 TeamHarness message（固定 Team Room，首行完整 Worker MXID）。禁止 roomflow / projectflow / taskflow / `list_rooms`。
- **P4 派发**：除真实 `blueprintId` 外写明「path 可省略，禁止翻目录」。Worker 报 `P4_REPORT status=SUCCEEDED` 且含 `external_id` 即收口：缺省指针按 `import_result@v1 binding@v1 dry_run@v1 evidence@v1` 写入 `result.md`。**禁止**再派 `P4_REPORT_FIX`，禁止让 Worker 翻历史 `result.md` 核对版本号。
- 每条 `TASK_ASSIGNED` 末尾附回帖模板（Worker 无 message 工具，只能普通回复；你只在被 @ 时唤醒）：

  ```
  汇报时请把下面这一行原样复制为你回复的第一行（一个字符都不要改、不要简写）：
  @chatflows-leader:matrix-local.agentteams.io:18080
  ```

  MXID 用你在本 Team Room 的真实发件人 ID。缺首行则重发模板要求重报。
- 失败处理：schema 不齐回抛原 Worker；两轮失败升 Human。
- 安全边界：不重算 gate，不写业务 artifact，不做业务裁决。阶段派发禁止 taskflow / create_task_room / `TASK：*` 临时房。
- 验收只认 MCP `kind@version` 指针，共享目录 `.json` 不是完成证据。Worker 写「手写/模拟/simulated」一律按未完成。P4 成功汇报若漏写 `@v1` 指针但已有真实 `external_id`，Leader 自行补 v1，不要重派 Worker。
- P4 审批：Worker 报 `PENDING_APPROVAL` 后，在 **Team Room**（非 DM）单行发 `APPROVAL_REQUIRED run_id=... approval_id=...`。只有收到 Manager/Human 的真实 `APPROVAL_PROOF ... proof=<原文>` 才转发；禁止编造 proof / `APPROVAL_GRANTED`。转发必须单行：

  `@flow-import-run:<domain> APPROVAL_GRANTED run_id=<run_id> approval_id=<approval_id> decision=APPROVE proof=<proof 原文一行到底>`

- Worker 报 `approval credential mismatch or already consumed`：不要重开审批。已有 `external_id` 或 Nest 已 `SUCCEEDED` 则核验指针后收口；否则升 Human。
- 顶层完成：只有 Leader 写最终 `result.md`。SUCCEEDED 正文逐行列出 P1、P2、Guidance、所选 P3 分支、approval、import_result、dry_run、evidence 的 `kind@version`。
- **RUN_BLOCKED 升 Human**：Worker 报 RUN_BLOCKED 且原因是环境/平台缺陷（重派必然同样失败）时，不重派、不改判为 Worker 故障，直接在 Team Room 发一行 `RUN_BLOCKED run_id=<run_id> stage=<阶段> reason=<原文错误>`，并列出已产出的 `kind@version` 指针。禁止伪造缺失产物，禁止宣告 SUCCEEDED。
- Worker 与 Leader 都**没有**仓库文档目录，禁止试图 read_file 任何仓库内的说明文档；判据只在本 Skill 与 Room 内的协议行。
- 调用条件：收到本阶段 TASK_ASSIGNED。
- 依赖工具：本阶段 chatflows-* MCP；filesync 仅允许当前 task spec.md。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
