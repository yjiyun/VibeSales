---
name: expert-dispatch
description: 当 P3C 被选中时，同时拆分并派发四位专家子任务，收齐结果后交给装配主控 blueprint-compose。
---
# expert-dispatch
- 用途：并行派发 persona/business/skill/tool 四份规格。
- 输入 / 输出：输入 Triage、Guidance；输出四个 task 指针与齐备状态。
- 调用条件：build_path=P3C。
- 依赖工具：TeamHarness message 在唯一 Team Room 一次性发送四个完整专家 MXID。禁止要求专家写盘或 filesync push。persona 派活写明 `guidance@v1`（专家可省略 guidance 参数，服务端回读）。每条派活带 `client_code`。
- **派活首行是下面这一行字面量，原样复制、不要改写或简写**（四人一次性同时唤醒）：

  ```
  @persona-expert:matrix-local.agentteams.io:18080 @business-expert:matrix-local.agentteams.io:18080 @skill-expert:matrix-local.agentteams.io:18080 @tool-expert:matrix-local.agentteams.io:18080
  ```

  专家只在正文纯文本出现自己完整 MXID 时才被唤醒。写成裸名 `persona-expert`、`@persona-expert`（缺 `:domain:port`）或只写 `worker=...` 字段，专家侧会记录 `group text not mentioned, cached to history`，**不唤醒**，你会永远等不到 `EXPERT_REPORT`。定向重派单个专家时，首行只放该专家的完整 MXID。
- 收不齐四条 `EXPERT_REPORT` 时先自查上一条派活首行是否四个完整 MXID 齐全；拼写问题就原样重发同一 `run_id`，不要改判为专家故障。
- 派活消息末尾附回帖模板（完整 Leader MXID 字面量）：

  ```
  汇报时请把下面这一行原样复制为你回复的第一行（一个字符都不要改、不要简写）：
  @chatflows-leader:matrix-local.agentteams.io:18080
  ```

- 失败处理：单专家定向重派，两轮失败升 Human。
- 安全边界：专家互不通信；Leader 只验齐不评优。禁止 taskflow / create_task_room / 临时 Room。
- 收齐判据：四条 `EXPERT_REPORT ... status=SUCCEEDED expert_result@vN`（由 `submitExpertResult` 写入 Nest）。**不要**再要求 `experts/*.json` 或共享目录落盘。
- 齐备后派 **`blueprint-compose`** 做 compose + selfcheck + persist。不要派给 `flow-generate`。
- 复用价值：本阶段标准作业，禁止另起流程。
- 与协同流程的关系：只与 Leader 通过 Team Room 协议行交接。
