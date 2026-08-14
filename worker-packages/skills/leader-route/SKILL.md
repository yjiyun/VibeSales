---
name: leader-route
description: 当上游 artifact 齐备时，校验 schema 并按 WizardService 已产出的 gate/build_path 路由。
---
# leader-route
- 用途：推进 P1→P2→Guidance→P3/P3b/P3C→P4 DAG；三条构建分支前统一派 template-personalize.deriveGuidance。
- 输入 / 输出：输入 artifact 指针；输出下一 Worker 任务规格。
- 调用条件：当前 Matrix 事件中的 task-<run_id> 到达，或当前 run 的 Worker 阶段回报到达后；当前事件 run_id 优先，严禁从历史消息借用旧 run_id。
- 依赖工具：只读 ArtifactStore；TeamHarness roomflow.list_rooms 发现唯一 `Team: chatflows-build-team`；TeamHarness message 向该固定 Team Room 发送含完整 Worker MXID（`@name:domain`）的消息，由其生成 `m.mentions`。
- 失败处理：schema 不齐回抛原 Worker；两轮失败升 Human。
- 安全边界：不重算 gate，不写业务 artifact，不做业务裁决。
- Room 边界：阶段派发禁止调用 taskflow.delegate_task、roomflow.create_task_room 或任何 `TASK：*` 临时 Room；全部 Worker 只在 manager-free Team Room 接单和汇报。
- 复用价值：任意按稳定契约分阶段的 AgentTeams 流水线。
- 与协同流程的关系：唯一阶段路由器，Worker 之间禁止直连。
- 顶层完成汇报：只有 Leader 能写共享任务目录的最终 result.md。frontmatter 必须写同一 run_id 与 status（仅 SUCCEEDED / ABORTED / FAILED）；SUCCEEDED 正文逐行列出 P1、P2、Guidance、所选 P3 分支、approval、import_result、dry_run、evidence 的 `kind@version` 指针。P4 返回 pending_approval 时，在 Leader Room 单行发送 APPROVAL_REQUIRED run_id=<run_id> approval_id=<approval_id>。
