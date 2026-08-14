---
name: expert-dispatch
description: 当 P3C 被选中时，同时拆分并派发四位专家子任务，收齐结果后交给装配主控。
---
# expert-dispatch
- 用途：并行派发 persona/business/skill/tool 四份规格。
- 输入 / 输出：输入 Triage、Guidance；输出四个 task 指针与齐备状态。
- 调用条件：build_path=P3C。
- 依赖工具：shared/tasks；TeamHarness message 在唯一 `Team: chatflows-build-team` Room 一次性发送四个完整专家 MXID，由 `m.mentions` 同时触发。
- 失败处理：单专家定向重派，两轮失败升 Human。
- 安全边界：专家互不通信，Leader 只验齐不评优。
- Room 边界：禁止 taskflow.delegate_task、roomflow.create_task_room 与 `TASK：*` 临时 Room；四专家必须在同一个 manager-free Team Room 的同一事件中被 mention。
- 复用价值：正交专家团并行拆解。
- 与协同流程的关系：P3C 五步流水线第 1–2 步。
