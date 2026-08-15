---
name: expert-dispatch
description: 当 P3C 被选中时，同时拆分并派发四位专家子任务，收齐结果后交给装配主控 blueprint-compose。
---
# expert-dispatch
- 用途：并行派发 persona/business/skill/tool 四份规格。
- 输入 / 输出：输入 Triage、Guidance；输出四个 task 指针与齐备状态。
- 调用条件：build_path=P3C。
- 依赖工具：shared/tasks；TeamHarness message 在唯一 `Team: chatflows-build-team` Room 一次性发送四个完整专家 MXID，由 `m.mentions` 同时触发。
- 派活消息必须自带回帖模板（否则四专家汇报你收不到）：专家 Worker 没有 `message` 工具、只能普通回复，而你只在被 @mention 时被唤醒；它们自己拼你的 MXID 时常写成裸名 `chatflows-leader`。所以这条派发消息**末尾**要附上固定文案，把你的完整 MXID 作为字面量给出，让四位专家只需复制：

  ```
  汇报时请把下面这一行原样复制为你回复的第一行（一个字符都不要改、不要简写）：
  @chatflows-leader:matrix-local.agentteams.io:18080
  ```

  MXID 用你在本 Team Room 的真实发件人 ID（含端口）。缺口/RUN_BLOCKED 汇报同样要求首行照抄；收到没有该首行的汇报就重发模板要求重报。
- 失败处理：单专家定向重派，两轮失败升 Human。
- 安全边界：专家互不通信，Leader 只验齐不评优。
- Room 边界：禁止 taskflow.delegate_task、roomflow.create_task_room 与 `TASK：*` 临时 Room；四专家必须在同一个 manager-free Team Room 的同一事件中被 mention。
- 复用价值：正交专家团并行拆解。
- 与协同流程的关系：P3C 五步流水线第 1–2 步。
- 收齐后派给谁（**照名字派，不要猜**）：四专家产物齐备后，把装配任务派给 **`blueprint-compose`**（它持有 `chatflows-p3c`，负责 composeBlueprint + blueprintSelfcheck + persistBlueprint）。**不要派给 `flow-generate`** —— 那是 P3B 自定义 flow 的 Worker，只有 `chatflows-p3b`，被派到 P3C 装配时只会反复调 `chatflows-p3b__generate` 并被 Nest 的 `requirePath('P3B')` 拒绝，run 就此卡死。
- 产物路径统一：派活时明确要求四专家把产物写到 `shared/tasks/task-<run_id>/experts/` 下（`experts/persona.json`、`experts/business.json`、`experts/skill.json`、`experts/tool.json`），并 filesync push。专家把文件写到 task 根目录（如 `skill.json`）会让你核验时找不到、误判缺口。
