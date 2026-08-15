---
name: leader-route
description: 当上游 artifact 齐备时，校验 schema 并按 WizardService 已产出的 gate/build_path 路由。
---
# leader-route
- 用途：推进 P1→P2→Guidance→P3/P3b/P3C→P4 DAG；三条构建分支前统一派 template-personalize.deriveGuidance。
- 阶段 → Worker 映射（**照表派活，禁止按名字猜**；派错的 Worker 没有对应 MCP，只会反复被 Nest 拒绝，run 卡死）：

  | 阶段 | Worker | 它的 MCP |
  |------|--------|----------|
  | P2 模板匹配 | `template-match` | `chatflows-p2` |
  | Guidance 派生（P3/P3B/P3C 都先走这步） | `template-personalize` | `chatflows-p3` |
  | P3 命中模板注入回写 | `template-personalize` | `chatflows-p3` |
  | P3B 自定义 flow 生成 | `flow-generate` | `chatflows-p3b` |
  | P3C 四专家 | `persona-expert` / `business-expert` / `skill-expert` / `tool-expert` | `chatflows-p3c` |
  | **P3C 装配主控（compose + selfcheck + persist）** | **`blueprint-compose`** | `chatflows-p3c` |
  | P4 导入绑定试运行 | `flow-import-run` | `chatflows-p4` |

  特别注意：**P3C 的装配主控是 `blueprint-compose`，不是 `flow-generate`**。`flow-generate` 只有 `chatflows-p3b`，被派到 P3C 装配时只能反复调 `chatflows-p3b__generate` 并被 `requirePath('P3B')` 拒绝。
- 输入 / 输出：输入 artifact 指针；输出下一 Worker 任务规格。
- 调用条件：当前 Matrix 事件中的 task-<run_id> 到达，或当前 run 的 Worker 阶段回报到达后；当前事件 run_id 优先，严禁从历史消息借用旧 run_id。
- 依赖工具：只读 ArtifactStore；TeamHarness filesync 拉取当前 run 的 `shared/tasks`；TeamHarness message 在当前 NEW_RUN 所在的固定 Team Room 发送含完整 Worker MXID（`@name:domain`）的消息，由其生成 `m.mentions`。禁止 roomflow / projectflow / taskflow，也不要调用 `list_rooms`。
- 派活消息必须自带回帖模板（否则该 run 一定停摆）：阶段 Worker **没有** TeamHarness `message` 工具，只能在 Team Room 普通回复；而你只有在消息里被 @mention 时才会被唤醒（未 mention 的群消息只进历史）。Worker 自己拼写你的 MXID 时经常写成裸名 `chatflows-leader` 而漏掉 `:domain:port`，于是你收不到、run 永远停在当前阶段。所以每条 `TASK_ASSIGNED` 消息的**末尾**都要附上这段固定文案，把你自己的完整 MXID 作为**字面量**写出来，让 Worker 只需复制、不需拼写：

  ```
  汇报时请把下面这一行原样复制为你回复的第一行（一个字符都不要改、不要简写）：
  @chatflows-leader:matrix-local.agentteams.io:18080
  ```

  其中 MXID 用你在本 Team Room 的真实发件人 ID（含端口）。失败/RUN_BLOCKED 的回帖同样要求首行照抄。Worker 若回了没有该首行的汇报，就重发一次这段模板并要求它按格式重报。
- 失败处理：schema 不齐回抛原 Worker；两轮失败升 Human。
- 安全边界：不重算 gate，不写业务 artifact，不做业务裁决。
- Room 边界：阶段派发禁止调用 taskflow.delegate_task、roomflow.create_task_room 或任何 `TASK：*` 临时 Room；全部 Worker 只在 manager-free Team Room 接单和汇报。
- 复用价值：任意按稳定契约分阶段的 AgentTeams 流水线。
- 与协同流程的关系：唯一阶段路由器，Worker 之间禁止直连。
- 宣告 SUCCEEDED 前必须核验权威产物（**别只凭 Worker 的话**）：Worker 说「完成」不等于产物落库。曾出现 `flow-import-run` 用 `write_file` 手写 `import_result.json` / `dry_run_result.json`（内容写着 `simulated success`）再宣告 TASK_COMPLETED，Leader 直接发了 🎉 SUCCEEDED，而 Nest 权威仓仍是 `WAITING_HUMAN`、approval 仍 `PENDING`、`import_result`/`binding`/`dry_run` 三个 artifact 一个都没有，向导永远等不到发布。
  判据：**共享目录里的 `.json` 文件不是完成证据**，只有阶段 Worker 汇报的 `kind@version` 指针（由 MCP 写入 Nest）才算。P4 收尾前要确认 Worker 的 `P4_REPORT` 里有 `external_id` / `binding` / `dry_run` 三项且 `status=SUCCEEDED`；Worker 只报 `PENDING_APPROVAL` 时，run 应停在 `WAITING_HUMAN` 等 Human 批准，**不要**写 result.md、不要宣告成功。
  Worker 汇报里出现「手写/模拟/simulated/本地兜底」等字样，或产物只有共享目录文件而无 kind@version 指针，一律按未完成处理，要求它改用真实 MCP 调用或报 RUN_BLOCKED。
- P4 两阶段审批的 Leader 职责：Worker 报 `PENDING_APPROVAL approval_id=<id>` 后，在 **Team Room**（即 `TASK_ASSIGNED` 派活消息所在的同一个房间，`AGENTTEAMS_LEADER_ROOM_ID` / TEAMS.md 里的 `team.teamRoomId`）单行发 `APPROVAL_REQUIRED run_id=<run_id> approval_id=<approval_id>` 等 Human 决策；拿到 Human 批准的 HMAC `proof` 后转发给 `flow-import-run`（连同 run_id/approval_id），要求它用同一组 id 带 proof 再调一次 `chatflows-p4__import`。proof 只转发给该 Worker，不写入 result.md 或日志。
  **禁止发到 `team.leaderDmRoomId` 或 `member.personalRoomId`**：agent-manager 只 join 并轮询 `AGENTTEAMS_LEADER_ROOM_ID`（= Team Room），发到 DM/个人房间会让 manager 永远收不到，run 卡死在 `WAITING_HUMAN`（实测发生过：Leader 把消息发到了 TEAMS.md 里的 `leaderDmRoomId`，manager 没 join 那个房间，approval 请求消失在时间线之外）。
  **转发格式必须是单行 `key=value`，禁止用 ``` 代码块或任何折行包裹 proof**：proof 是一整串 `<base64url>.<签名>`，Nest 按 `.` 切分并要求恰好两段，一旦复制时混入换行、空格或反引号就会被判 `approval proof malformed`（实测就是这样卡住过一次）。照这个模板发一行：
  `@flow-import-run:<domain> APPROVAL_GRANTED run_id=<run_id> approval_id=<approval_id> decision=APPROVE proof=<proof 原文，一行到底，后面不要再接任何字符>`
  说明文字放在 proof 那一行**之前**，不要放在后面，免得 Worker 把后续文字也当成 proof 的一部分。
- 顶层完成汇报：只有 Leader 能写共享任务目录的最终 result.md。frontmatter 必须写同一 run_id 与 status（仅 SUCCEEDED / ABORTED / FAILED）；SUCCEEDED 正文逐行列出 P1、P2、Guidance、所选 P3 分支、approval、import_result、dry_run、evidence 的 `kind@version` 指针。P4 返回 pending_approval 时，在 Leader Room 单行发送 APPROVAL_REQUIRED run_id=<run_id> approval_id=<approval_id>。
