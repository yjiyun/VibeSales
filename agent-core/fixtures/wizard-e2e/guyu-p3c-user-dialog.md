# 谷雨金标准 · 纯用户侧对话脚本（→ P3C）

> 依据：[专家团深入设计.md](VIBE_SALES_BASE/docs/agentteams/专家团深入设计.md) §6 走读 + 附录 A（企微美肤师「小雨滴」）；产品切面见 [AgentTeams架构v5.md](VIBE_SALES_BASE/docs/agentteams/AgentTeams架构v5.md)。  
> 机读步骤与断言见同目录 [p3c-guyu-wecom.yaml](./p3c-guyu-wecom.yaml)（Playwright 驱动器读 YAML，本文给人读）。  
> **本脚本只写用户会点/会说的话**，不含专家团内部契约（BusinessSpec / 九意图表等）。  
> **主路径不出向导、不打开编排看板。** Human Gate 仍是 `WAITING_HUMAN` / `approval_id`，交互在向导「确认发布」。  
> 自动化入口与变量说明写在 [启动说明.md §8.3](VIBE_SALES_BASE/docs/agentteams/启动说明.md)。宿主：`scripts/run-console-p3c-e2e.sh`；驱动器：`agent-console/scripts/test-console-p3c-e2e.mjs`。

## 定位（用户口吻）

我要在企业微信里搭一个美妆私聊客服：能答护肤常见问题、按肤质推荐产品、复杂或投诉转人工；客户下次来还要记得肤质和偏好，中间挂起的推荐说「好的继续」还能接着聊。

| 项 | 期望（系统侧，用户不可见） |
|----|---------------------------|
| 行业 | `beauty`（UI：美妆） |
| 场景 | `beauty_wecom_cs` |
| 闸门 | `gate=PASS` |
| P3C 信号 | `needs_long_term_memory=true`（简述触发词） |
| 搭建路径 | `build_path=P3C` |

## 对话步骤

### 0. 欢迎屏

1. 打开 VibeSales Harness「搭建向导」
2. **核对右上角模型下拉**：默认 `deepseek-v4-flash-0731`；可选项还有 `qwen3.8-max`、`qwen3.7-plus`、`deepseek-v4-pro`（本脚本不改，开始后下拉会锁住）
3. **关闭**「LLM 接待员」（保证无 Key 也可确定性跑完；自动化会再拦截 `createSession` 强制 `llm:false`）
4. 点击「开始」

### 1. S1 · 行业

- **点选：** 美妆

### 2. S2 · 业务目标（多选后提交）

勾选以下三项，再点「提交」：

1. 回答客户常见问题，减少流失和重复咨询  
2. 介绍产品/服务，并推荐合适方案  
3. 收集问题信息，并转交给人工处理  

（对齐附录 A：FAQ + 推品 + 转人工；售后/过敏等由系统后续能力覆盖，用户侧不必点额外目标。）

### 3. S3 · 业务简述（底部输入框 Enter 发送）

**原文必须保留触发词：** `跨会话` / `记住` / `偏好` / `挂起` / `好的继续`  
（工程侧 `inferP3cSignals` 据此置 `needs_long_term_memory`；`rawBrief` 会保留改写前原文。）

```text
我们是美妆品牌，要在企业微信做私聊客服：自动回答护肤咨询、按肤质推荐产品、复杂或投诉转人工。客户下次再来要跨会话记住肤质和偏好，挂起的推荐任务说「好的继续」能接着聊。
```

自动化不走逐字 `keyboard.type`（XSender 发送钮不同步且慢），改为调用向导 `answer({ text })`，原文仍写入会话。

### 4. S4 · CTA

- **点选：** 先看看效果  
- **期望可见：** 「向导已完成」、闸门 PASS、头栏 DONE  
- **可选核对：** 「查看 Phase1Result JSON」→ `scene_id=beauty_wecom_cs`，`needs_long_term_memory: true`

### 5. 结果卡 · 开始生成

1. 等待时间线出现 P2 匹配卡（系统自动，不用离开向导）  
2. 点击「开始生成（local）」  
3. **期望：** 向导内出现「确认发布」卡；可见 `WAITING_HUMAN`、`P3C`；「确认发布」可点  
4. **不要**去「编排看板」点批准

### 6. 确认发布

1. 核对卡上的需求摘要与「将发布的智能体」（场景 / 记忆 / Skill）  
2. 点击「确认发布」  
3. **期望：** 卡头变为「已发布」，出现「去试聊」  
4. 若失败：卡上会留下真实错误，「确认发布」不可再点；点「返回修改需求」重新走生成。不要连点——第二次会报 `run is not waiting for P4 approval`（审批已被消费）

### 7. 产物对话 · 必测冒烟

1. 在向导里点「去试聊」（不要手填 id，不要用启动脚本的 seed `smoke_beauty`）  
2. **期望可见：** 已绑定本次 `clientCode` / `runtimeAgentId`（一般为 `acme_beauty` / `beauty_wecom_cs-acme_beauty`）  
3. **按顺序发送下列全部句子**（每句等助手 `done` 且正文非空后再发下一句）：

| # | 用户说法 | 标签 | 目的 | 期望回复 |
|---|----------|------|------|----------|
| 7.1 | `SSE health check` | `sse_probe` | SSE / 投影通 | 允许链路探针（`DRY_RUN_OK` / 「链路探针，非智能体」） |
| 7.2 | `帮我推荐一套提亮的，预算三百左右，混合皮` | `reco_slot` | 推品抽槽 | **智能体**回复，禁止探针 |
| 7.3 | `用完脸红刺痛` | `allergy_intake` | 过敏收集 | **智能体**回复，禁止探针 |
| 7.4 | `又刺痛又想退货` | `priority_conflict` | 过敏/售后压过推品 | **智能体**回复，禁止探针 |
| 7.5 | `转人工` | `escalate` | 转人工 | **智能体**回复，禁止探针 |
| 7.6 | `好的继续刚才那个推荐` | `task_recover` | 挂起任务恢复 | **智能体**回复，禁止探针 |

4. **7.1 链路探针：** 本机 stub / `deterministic-test` 可只跑这一句，证明 SSE 与本次产物绑定。  
5. **7.2–7.6 必测智能体：** 必须打到已发布 Blueprint 的真模型 runtime（混合栈 `RUNTIME_MODE=production`）。助手气泡要有 `done`、正文非空，**不得**出现 `DRY_RUN_OK`、`BLUEPRINT_OK`、「链路探针，非智能体」、「产物探针，非真模型」。意图质量（优先级、过敏静默等）仍不在本脚本机读断言里。  
6. 隔离栈 `./scripts/run-console-p3c-e2e.sh` 默认是 `deterministic-test`：只强制 7.1；7.2–7.6 需挂混合栈并 `CONSOLE_P3C_AGENT_CHAT=1`（见下节）。

## 自动化怎么跑

完整变量表见 [启动说明.md §8.3](VIBE_SALES_BASE/docs/agentteams/启动说明.md)。最短命令：

```bash
cd <VIBE_SALES_BASE>

# 1) 链路门禁（隔离栈，约 10s，只强制 7.1）
./scripts/run-console-p3c-e2e.sh

# 2) 智能体必测（先起混合栈吃最新 env.local，含重配的 LLM KEY）
START_MANAGER=0 ./scripts/run-agentteams-local-dev.sh
# 另开终端：
CONSOLE_P3C_ATTACH=1 ./scripts/run-console-p3c-e2e.sh
```

改过 `docs/agentteams/local-development.env.local` 必须重启混合栈。可视化用 Cursor 右侧打开 `http://127.0.0.1:5173/`，不要 `CONSOLE_P3C_HEADED=1`。截图：`agent-core/tmp/wizard-p3c-e2e/`（隔离栈另存 `tmp/wizard-p3c-e2e-isolated/`）。

Higress `429 insufficient_quota` 时不要反复点「确认发布」：dry-run 会 500，审批可能已被消费。KEY 余量恢复后再重新「开始生成 → 确认发布」。

---

## 与金标准文档的差距（走读时心里有数）

- 文档目标流水线：`domain-architect` → **BusinessSpec** → 能力包专家 → compose。  
- **现行工程：** 四专家（persona/business/skill/tool）直出 `blueprint`，**不会**出现 `business_spec` artifact。  
- 本脚本验收的是：**谷雨级用户意图经向导命中现行 P3C 通路，并在向导内确认发布、产物对话聊本次 Blueprint**，不是 BusinessSpec 机读夹具已落库。  
- 「编排看板」只做排障（时间线 / 重放 409），local 主路径不必打开。
