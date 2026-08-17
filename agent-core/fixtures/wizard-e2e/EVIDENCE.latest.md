# 谷雨 P3C 用户侧走读 · 证据说明

> 执行时间：以 [`shots/manifest.json`](/tmp/chatflows-p3c-e2e/shots/manifest.json) 的 `at` 为准。  
> 对话脚本：[guyu-p3c-user-dialog.md](./guyu-p3c-user-dialog.md)  
> 机读用例：[p3c-guyu-wecom.yaml](./p3c-guyu-wecom.yaml)  
> 宿主：`./scripts/run-console-p3c-e2e.sh`（本机三件套，**非** `run-agentteams-local-dev`）

## 1. 结论

**本次走读已确认工程走 P3C 分支并 SUCCEEDED。**

| 判据 | 结果 |
|------|------|
| 用户简述触发 `needs_long_term_memory` | 是（跨会话 / 记住 / 偏好 / 挂起 / 好的继续） |
| `run.build_path` | **`P3C`** |
| `run.status` | **`SUCCEEDED`** |
| 专家团 artifact | 有 `expert_dispatch` + 4×`expert_result` + `blueprint` |
| 误走 P3 | 否（无 `personalized_package`） |
| BusinessSpec 落库 | **否**（无 `business_spec`；现行四专家直出 Blueprint） |

本机模型为 `deterministic-test`：产物对话只证明 **本次 Blueprint 可投影 + SSE `done`**，不验证附录 B 意图质量。

## 2. 本次 run 标识

来源：`/tmp/chatflows-p3c-e2e/shots/manifest.json` + `pipeline-snapshot.json`

| 字段 | 值 |
|------|-----|
| `run_id` | `780433cc-e91f-4a8c-abe2-ae1ee99b8951` |
| `approval_id` | `1b7a094d-be24-4f21-8e82-ceb1bb47061b` |
| `build_path` | `P3C` |
| `client_code` | `acme_beauty` |
| `runtime_agent_id`（产物对话） | `beauty_wecom_cs-acme_beauty` |
| Console | `http://127.0.0.1:25183` |
| 取证时间 (UTC) | `2026-08-14T05:49:04.939Z` |
| expert batchId | `a65fef87-11ac-4828-af5a-93d8ebeaadd8` |

**重要：** 产物对话使用的是**本次搭建**的 `runtimeAgentId`，不是启动脚本 seed 的 `smoke_beauty`。

## 3. 用户侧输入如何命中 P3C

```text
S3 简述 → inferP3cSignals(rawBrief) → triage.needs_long_term_memory=true
       → decideBuildPath → P3C
```

用户操作（与脚本一致）：

1. 关 LLM → 开始  
2. 行业：美妆  
3. 目标：FAQ + 推品 + 转人工  
4. 简述含记忆/挂起触发词  
5. 「先看看效果」→「开始搭建（local）」→ 看板「批准」→ 产物对话冒烟  

工程侧补充：`WizardSessionService` 保留 `rawBrief`，避免 LLM `structureBrief` 改写丢触发词。

## 4. Artifact 时间线与含义

完整计数见 `pipeline-snapshot.json`。顺序与含义：

| kind | 次数（本 run） | 含义 | 对照专家团文档 |
|------|----------------|------|----------------|
| `wizard_state` | 1 | 向导会话收口状态 | P1 UX |
| `triage` | 1 | 闸门输入；含 `scene_id=beauty_wecom_cs`、`needs_long_term_memory=true` | A1 分流真源在 Wizard |
| `match_result` | 1 | 模板命中谷雨装机稿（`guyudefinal_dev_0708_test21`） | P2；专家团不改路径 |
| `guidance` | 1 | 进入搭建前的导引 | P3 共性前置 |
| `expert_dispatch` | 1 | 同 `batchId` 派发四角色 | 文档目标含 architect；**现行四专家** |
| `expert_result` | 4 | `persona-expert` / `business-expert` / `skill-expert` / `tool-expert` | **无** `domain-architect` |
| `blueprint` | 1 | 合并后的可运行 Blueprint | 文档目标「BusinessSpec→编译」；现行直出 |
| `blueprint_check` | 2 | 13 项装配自检（`ok=true`） | 覆盖自检 C1–C6 **未**作为独立 kind |
| `approval` | 3 | PENDING → PROCESSING → APPROVED | Human Gate |
| `import_result` | 1 | Nest 侧 STAGED + runtime ingest | P4 |
| `dry_run` | 1 | `ok=true`，响应含 `DRY_RUN_OK` | P4 冒烟 |
| `evidence` | 2 | 控制面事件摘要 | 审计 |

**未出现（与金标准差距）：**

- `business_spec` / `persistBusinessSpec`  
- `domain-architect`、compliance veto、qa-expert 独立 artifact  
- P3 路径的 `personalized_package`（若出现则说明误分流）

## 5. 截图证据清单

目录：`/tmp/chatflows-p3c-e2e/shots/`

| 文件 | 对应步骤 |
|------|----------|
| `01-wizard-welcome.png` | 欢迎屏 / 关 LLM |
| `02-wizard-s1-industry.png` | 选美妆 |
| `03-wizard-s2-goals.png` | 三项业务目标 |
| `04-wizard-s3-brief.png` | 含触发词的简述 |
| `05-wizard-s4-cta.png` | 「先看看效果」 |
| `06-wizard-result-gate-pass.png` | 闸门 PASS / DONE |
| `07-wizard-p2-match.png` | P2 匹配卡 |
| `08-wizard-build-run-created.png` | 已创建 run |
| `09-runs-waiting-human-p3c.png` | 看板 `WAITING_HUMAN` + `P3C` |
| `10-runs-approved-succeeded-p3c.png` | `SUCCEEDED` |
| `11-chat-form-ready-p3c.png` | 填入本次 agent id |
| `12-chat-sse-done-p3c.png` | SSE `done` |

## 6. 与金标准的一句话差距

附录 A 要求先落 **BusinessSpec** 再编译 Blueprint；本走读证明的是：**谷雨级用户意图经向导命中现行「四专家直出」P3C 通路，并能批准、ingest、对话**——BusinessSpec 机读夹具尚未作为运行产物出现。

## 7. 复现命令

```bash
cd <VIBE_SALES_BASE>
./scripts/run-console-p3c-e2e.sh
# 截图与 manifest → /tmp/chatflows-p3c-e2e/shots/
```

手工可见 UI（端口不同，勿与 e2e 并行）：

```bash
./scripts/start-local-manual-stack.sh
# http://127.0.0.1:25273 ，按 guyu-p3c-user-dialog.md 点选
```


> 截图与 pipeline-snapshot 的权威落盘目录：`/tmp/chatflows-p3c-e2e/shots/`（每次 e2e 会覆盖）。
