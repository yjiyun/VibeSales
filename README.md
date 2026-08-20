# VibeSales

`VibeSales` 是 Agent Teams 平台层仓库，负责控制台、平台编排、运行时、AgentTeams 资源与技能包，并集成业务逻辑子仓 `agent-core/`。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](./LICENSE)

## 项目定位与总览

`VibeSales` 承载 Agent Teams 平台的**资产真源 + 平台组件**，并通过环境变量 `CHATFLOWS_ROOT`（默认解析为本仓库目录，即 `agent-core/` 的上一级）为子仓 `agent-core/` 提供 `catalogs/`、`prompts/`、`flows/` 三类资产。`agent-core` 启动时会经 `CHATFLOWS_ROOT` 读取以下具体资产：
- `catalogs/` 下的行业/场景/能力等词表 YAML
- `prompts/` 下的千问提示词模板
- `flows/Chatflow-*/` 下的工作流模板包（meta.yaml + BRIEF.md + workflow/*.yaml）

当前仓库的三层：

- **资产层**：`catalogs/`（词表真源）、`prompts/`（提示词）、`flows/`（工作流/模板资产）
- **平台层**：`agent-console/`（控制台）、`agent-manager/`（平台外侧编排器）、`agent-runtime/`（运行时）、`agentteams-resources/` + `worker-packages/`（资源/技能包）
- **业务核心层**：`agent-core/`，独立子仓，独立开发、独立部署

在当前工作区里，`VIBE_SALES_CORE` 的实际本地承载目录就是 `agent-core/`；`VIBE_SALES_BASE` 即指本仓库根目录。

## 本仓跟踪什么

| 路径 | 说明 |
|------|------|
| [`docs/`](./docs/) | 产品 / 工程文档（AgentTeams 总览见 [`docs/agentteams/AgentTeams 项目架构说明.md`](./docs/agentteams/AgentTeams%20项目架构说明.md)） |
| [`catalogs/`](./catalogs/) | 词表真源（行业 / 场景 / 能力 / 渠道 / 连接器 / 业务目标 / Agent Families YAML） |
| [`prompts/`](./prompts/) | 千问提示词（`receptionist` 接待 / `router` 路由 / `matcher` 匹配） |
| [`flows/`](./flows/) | 模板 / 导出包（`Chatflow-*`、`yunflow-*`；大包默认不入库，见 `flows/README.md`） |
| [`agent-console/`](./agent-console/) | Vue 3 控制台（向导闭环 / 产物对话 / 编排看板排障 / 试聊） |
| [`agent-manager/`](./agent-manager/) | Java 17 平台外侧编排器（HTTP `serve` + CLI；AgentScope；15 个 Worker 技能包） |
| [`agent-runtime/`](./agent-runtime/) | Java 17 P3C 产物运行时（AgentScope Harness；SSE 对话 / Blueprint 投影 / RLS 隔离） |
| [`agentteams-resources/`](./agentteams-resources/) | Team / Worker / Human / Kustomization 资源声明 |
| [`worker-packages/`](./worker-packages/) | Worker 技能包与身份声明（15 个 Skill：`leader-route` → `flow-selfcheck`） |
| [`.claude/`](./.claude/) | Claude 计划与共享配置（不含本机 `settings.local.json`） |

**不在本仓提交**：`agent-core/`（NestJS 业务核心，独立仓库）、`.env`、`logs/`、`var/`、`*_unsafe.lock`、`PPT/`、平台敏感清单。建议布局：

```text
VibeSales/                  ← 本仓（git root）
├── docs/
├── catalogs/
├── prompts/
├── flows/                  ← Chatflow-* / yunflow-*（见 flows/README.md）
├── agent-console/
├── agent-manager/
├── agent-runtime/
├── agentteams-resources/ · worker-packages/
├── deploy/ · scripts/
├── .claude/
└── agent-core/             ← 独立子仓（被 .gitignore 排除，自行 clone）
```

重新拉代码后跑通 P1～P4 到发布与试聊的完整初始化步骤：见 [`docs/agentteams/部署初始化.md`](./docs/agentteams/部署初始化.md)。本机点 UI（不起 manager）：见 [`docs/agentteams/启动说明.md`](./docs/agentteams/启动说明.md)。P3C 浏览器自动化（向导 → 发布 → 试聊）见同文档 §8.3，入口 `./scripts/run-console-p3c-e2e.sh`。

## 文档导航

按下面顺序阅读：

- [base_README.md](./base_README.md)（本文件）：项目总览、模块定位、快速开始
- [ARCHITECTURE.MD](./ARCHITECTURE.MD)：平台整体架构 + agent-core 业务架构、交互链路、运行模式
- [DEPLOY.MD](./DEPLOY.MD)：根仓完整部署（含 OS 差异、Compose、环境要求）、验证步骤、操作手册
- [`docs/agentteams/部署初始化.md`](./docs/agentteams/部署初始化.md)：重新拉代码后跑通 P1～P4 到发布与试聊的初始化指南
- [`docs/agentteams/启动说明.md`](./docs/agentteams/启动说明.md)：本机点 UI（不起 manager）与 P3C 浏览器自动化的操作说明（§8.3 含向导→发布→试聊 E2E）

## 总体架构

```text
          ┌─────────────── 浏览器 / API 调用方 ───────────────┐
          │                                                  │
          ▼                                                  ▼
   agent-console (Vue 3)                       agent-manager (Java 编排器)
   │  /api ───────────┐                         │ Controller REST → AgentTeams 平台
   │  /runtime ───┐   │                         │ Matrix → Leader / Human / Worker
   │  /orchestration ─┼──────────────────────▶  │ MinIO → shared/tasks/task-{run_id}/
   └───────────────┘  │                         └───────────────┬──────────────┘
                      ▼                                        │
            agent-core (Web/BFF)                               │
            ├─ DEMO 向导面（/api/**, WebAuth）                 │
            ├─ 管线控制面（/api/v1/pipeline/*）                 │
            ├─ MCP 工具面（/mcp-servers/*，MCP_SERVER_TOKEN）  │
            ├─ Blueprint 管理面                                │
            └─ CLI（p1 / p1-wizard / match）                  │
                                                               │
                                                               ▼
            agent-core (MCP 生产面)  ◀────── MCP 工具调用 ─────┘
            │ P1 / P2 / P3 / P3B / P3C / P4
            │ 产物存储：PostgreSQL + MinIO（RLS 按租户隔离）
            ├─ 导入/绑定/dry-run：YunFlow
            └─ 冒烟/摄取：agent-runtime  ──────▶  试聊 SSE
                                                    │
                                                    ├─ PostgreSQL (Blueprint / Binding / Skills)
                                                    ├─ Redis (分布式状态)
                                                    └─ Higress 网关 → LLM + MCP 业务工具
```

## 仓库结构

```text
VibeSales/
├── agent-console/           # Vue 3 + Vite + Element Plus 控制台
├── agent-manager/           # Java 17 + AgentScope 平台编排器
├── agent-runtime/           # Java 17 + AgentScope Harness 运行时
├── agent-core/              # 独立子仓：NestJS 业务核心（VIBE_SALES_CORE）
├── agentteams-resources/    # Team / Worker / Human YAML 声明
├── worker-packages/         # Worker 技能包 + Agent Identities（Markdown Skill 包）
├── catalogs/                # 词表真源 YAML（行业/场景/能力/…）
├── prompts/                 # 千问提示词模板 YAML
├── flows/                   # 工作流与模板资产（Chatflow-*  meta.yaml + BRIEF.md + workflow/*.yaml）
├── deploy/                  # Docker Compose 与集成配置（agentteams/compose.yaml、integration.env.example）
├── scripts/                 # 启动、部署、验证、平台配置脚本
└── docs/                    # 设计、工程、AgentTeams 文档
```

## 核心组件

| 组件 | 技术栈 | 作用 |
|------|--------|------|
| `agent-console` | Vue 3 + Vite + Element Plus | 向导、编排看板、试聊入口、浏览器自动化 E2E |
| `agent-manager` | Java 17 + AgentScope | 平台外侧编排、审批流、任务跟踪、Matrix 消息、MinIO 大载荷交换、15 个 Worker 技能包驱动 |
| `agent-runtime` | Java 17 + AgentScope Harness | Blueprint 投影、SSE 对话、租户级权限、发布/回滚生命周期 |
| `agent-core` | NestJS + TypeScript + PostgreSQL 16 + MinIO | P1–P4 业务核心、CLI/Web/MCP 三入口、产物存储、MCP 工具面、Blueprint 管理 |
| `agentteams-resources` | YAML | AgentTeams 平台资源（Team/Worker/Human） |
| `worker-packages` | Markdown Skill 包 | Worker 执行规范与能力边界（`leader-route` … `flow-selfcheck`） |

## agent-core 业务能力与阶段矩阵

`agent-core` 是整套系统的业务中枢，四阶段**仅追加写入、永不覆盖**，全流程审计保留。

| 阶段 | CLI 入口 | 主要产物 / 输出 | 不在此处做 |
|------|----------|-----------------|------------|
| **P1** 向导 + 意图分诊 | `p1` / `p1-wizard` | `Phase1Result`（summary / detail / next_action）；闸门 PASS/ASK/CUSTOM/ERROR | 模板选择、启动真实 P3/P4 |
| **P2** 模板匹配（Filter→Rank→Decide） | `match` | `MatchResult` + 可选 v0 预览 | 变更场景、询问部署参数 |
| **P3** 模板个性化 / **P3B** 从零生成工作流 / **P3C** Agent 组装（四专家 + 13 自检） | MCP 工具驱动 | 个性化模板 / `flow_package` / `agent_blueprint` | — |
| **P4** 导入·绑定·dry-run + 审批闸 | MCP 工具驱动 | 导入记录 / agent_binding / dry-run 证据 / 审批 HMAC 凭据 | — |

三入口共享同一业务核心：

| 入口 | 命令 | 是否监听 | 用途 |
|------|------|:--------:|------|
| **CLI** | `npm run cli -- <command>` | 否（ApplicationContext） | 本地验证、回归测试、CI 校验 |
| **Web / BFF** | `npm run web` / `npm run start:web` | 3100 | 向导、前端联调、管线控制 |
| **MCP 生产面** | `npm run mcp` / `npm run start:prod` | 3100 / 3200 | MCP 工具面、审批、Blueprint 管理 |

- **输出契约**：CLI stdout 仅结果 JSON（可 `| jq`），所有 trace 走 stderr；多通道 sink 档位互不影响。
- **安全边界**：MCP 生产面（AppMcpModule）严格不加载 DEMO 向导控制器 / 静态托管；DEMO Web 面（AppWebModule）也装载 McpModule，但 `/mcp-servers/*` 受 `MCP_SERVER_TOKEN` 保护，未配置时端点返回 503。Blueprint 管理面仅 MCP 生产面。
- **资产快速失败**：`catalogs/` `prompts/` 任一缺失或枚举与 `flows/` `meta.yaml` 不符 → Nest 启动阶段抛错，**不静默降级**。

## 运行模式（四档从简到繁）

### 1. agent-core 本地最小验证（无 DB / 无对象存储）

```bash
cd agent-core
cp .env.example .env
npm install
npm run cli -- p1 --client-code <CLIENT_CODE> --triage fixtures/p1/beauty/pass-full.json
npm run cli -- p1-wizard --client-code <CLIENT_CODE> --no-llm
npm run cli -- match --client-code <CLIENT_CODE> --list-templates
```

建议 `.env` 起步配置（文件存储模式）：
```dotenv
ARTIFACT_STORE=file
ORCHESTRATION_MODE=local
FLOW_PLATFORM_MODE=local
CHATFLOWS_ROOT=<指向 VibeSales 根目录>
```

### 2. 根仓本机最小三件套（控制台 + 向导 + runtime）

```bash
./scripts/start-local-manual-stack.sh
```

启动：agent-core Web（向导）+ agent-runtime + agent-console。本机点 UI 指引见 `docs/agentteams/启动说明.md`。P3C 浏览器自动化（向导 → 发布 → 试聊）：

```bash
./scripts/run-console-p3c-e2e.sh
```

### 3. 本机接真实平台联调（四件套）

```bash
./scripts/run-agentteams-local-dev.sh all
```

### 4. Docker Compose 完整部署（≈11 服务）

```bash
./scripts/deploy-agentteams-stack.sh deploy/agentteams/integration.env
```

完整步骤见 [DEPLOY.MD](./DEPLOY.MD)。

## 关键脚本

| 脚本 | 说明 |
|------|------|
| `scripts/start-local-manual-stack.sh` | 本机最小三件套 |
| `scripts/run-agentteams-local-dev.sh` | 本机四件套联调 |
| `scripts/deploy-agentteams-stack.sh` | Docker Compose 完整部署 |
| `scripts/prepare-agentteams-stack-env.js` | 生成 Compose `.env` 与令牌 |
| `scripts/preflight-agentteams-integration.js` | 启动前预检（平台侧端口/凭证/资源） |
| `scripts/run-agentteams-e2e.sh` / `run-agentteams-platform-e2e.js` | 端到端集成验证 |
| `scripts/test-agentteams-*.{mjs,sh}` | 契约、preflight、REST apply、本地联调 |
| `scripts/discover-agentteams-*.js` | 资源 / 运行时 / 本地环境自动发现 |
| `scripts/configure-agentteams-*.js` / `configure-higress-*.mjs` | 网关 / Leader 工具 / Worker 文件系统 / Worker MCP 配置 |
| `scripts/e2e-manager-run.js` / `e2e-human-approver.js` | 平台模式 E2E 编排器 + 人工审批 |
| `scripts/apply-agentteams-rest.js` / `agentteams-apply.sh` | 向 AgentTeams 平台 REST 应用资源 |
| `scripts/validate-agentteams.js` | 资源有效性校验 |
| `scripts/run-console-*.sh` | 控制台 UI / P3C / 积飞手册证据 |
| `verify-all.sh` | 全量验证；`verify-contracts.sh` 契约验证 |

## agent-core 必要信息摘要（来自 agent-core/README.md）

### 最小环境要求

| 依赖 | 版本 | 判定依据 |
|------|------|----------|
| Node.js | **≥ 22.14.0**，建议锁到 22.14.x | agent-core Docker 镜像固定 `node:22.14.0-bookworm-slim` |
| npm | ≥ 10.x（随 Node 22 自带） | `package-lock.json` lockfileVersion 3 |
| PostgreSQL | **≥ 16**（生产） | RLS + 触发器 + 复合外键（生产强依赖 RLS） |
| MinIO / S3 兼容存储 | 任意支持 S3 v4 签名的版本 | 大产物正文外置（`personalized_package` / `flow_yaml` 等） |
| LLM 端点 | OpenAI 兼容 `/v1/chat/completions` | 默认 Qwen / DashScope，生产经 Higress 网关 |
| Java | 17 | agent-manager / agent-runtime 运行时（Spring Boot） |
| Docker | ≥ 24（需 BuildKit） | 完整 Compose 部署，Dockerfile 多阶段 `--target` |

### 配置核心变量（节选）

| 变量 | 说明 |
|------|------|
| `CHATFLOWS_ROOT` | 资产根，agent-core 读 `catalogs/` `prompts/` `flows/`；根仓下无需设置（默认上级） |
| `DASHSCOPE_API_KEY` | 开发直连；生产必须空（否则生产网关校验失败） |
| `QWEN_GATEWAY_TOKEN` / `QWEN_BASE_URL` | 生产网关消费者凭证 + 网关地址 |
| `ARTIFACT_STORE` | `file` 开发 / `postgres` 生产 |
| `ORCHESTRATION_MODE` | `local` 本项目串 P1–P4；`platform` 由 agent-manager 驱动 MCP |
| `MCP_SERVER_TOKEN` | /mcp-servers/* 端点 Bearer；未配置=503（网关唯一上游凭证） |
| `PIPELINE_CONTROL_TOKEN` / `BLUEPRINT_ADMIN_TOKEN` / `PIPELINE_APPROVAL_SIGNING_SECRET` | 管线、发布回滚、审批 HMAC |
| `WEB_AUTH_CREDENTIALS` / `WEB_AUTH_TOKEN` + `WEB_AUTH_CLIENT_CODE` | DEMO Web 向导面鉴权（含 X-Role + X-Actor） |

生产（`ARTIFACT_STORE=postgres`）启动时构造函数强校验五条断言：生产网关地址仅对公网要求 HTTPS + 主机名含 higress；私网（10./172.16–31/192.168./localhost/::1/无点内网名）允许 HTTP、不限主机名，便于整栈内网部署。

### PostgreSQL + RLS 关键事实

5 表（`run` / `artifact` / `agent_blueprint` / `agent_binding` / `agentscope_skills`）+ 10 角色（应用 + 1 只读 leader + p1~p4 + p3b/p3c worker + blueprint_admin + runtime），外加两个辅助角色。隔离方式：`FORCE ROW LEVEL SECURITY` + 事务级 `app.client_code` GUC + `set local role`。两张触发器：Blueprint `DRAFT→STAGED→PUBLISHED→RETIRED→…` 生命周期，运行时隔离范围首写后不可变。初始化：

```bash
cd agent-core && npm run db:init
```

需要三个**仅初始化脚本可见**的管理员变量：`DATABASE_ADMIN_URL` / `CHATFLOWS_APP_DB_PASSWORD` / `AGENT_RUNTIME_DB_PASSWORD`。

### agent-core 接口清单速查（四组）

- **MCP 工具面**（生产面 / 已配置令牌的 DEMO Web 面）：`POST /mcp-servers/:server/mcp`（`chatflows-p1` … `chatflows-p4` 共 6 个 server）+ `GET /healthz`，`MCP_SERVER_TOKEN` 鉴权
- **管线控制面**：`/api/v1/pipeline/{health,runs,start,:runId{,/abort,/approval}}`，`PIPELINE_CONTROL_TOKEN` + `X-Role` + `X-Actor`
- **Blueprint 管理面**（MCP 生产面独有）：`POST /api/v1/blueprints/{publish,rollback}`，`BLUEPRINT_ADMIN_TOKEN` + `X-Role: admin`
- **DEMO 向导面**（Web 独有）：`/api/health`、`/api/catalogs`、`/api/wizard/sessions{,/:id{,/answer,/preview,/template}}`，WebAuth + Bearer + X-Role + X-Actor

### 可观测性与 Token 会计

agent-core 使用「单一打点 API → trace-sink 抽象 → 多 sink 实现」的分层。档位 `off / on / verbose` 按 sink 独立，互不影响。

| 输出 | 位置 | 含义 |
|------|------|------|
| stderr trace | 终端 | `LOG_STDERR` 档位 |
| `logs/app.log` | 文件 | 全量 trace（LOG_FILE 档位），模块 / flow / req / #seq / Σt |
| `logs/token.log` | 文件 | 每 LLM 调用 JSON Lines |
| `logs/token-total.json` | 文件 | 累计台账（按天/月/节点/模型/全时；原子替换，不滚动）|
| 响应流事件 | Web 向导 UI 右侧面板 | 每次请求独立，行号与 app.log 对齐 |
| OTel GenAI（可选） | AgentLoop 上报 | `AGENTLOOP_EXPORTER` 三档：off / stderr / on |

三累计 token 范围：`running_total_tokens`（当前流程/请求）/ `day_total_tokens`（当日北京时间）/ `all_time_total_tokens`（永不重置）。常用命令：

```bash
cd agent-core
npm run token:total                  # 展示快照并从 token.log 重建
npm run token:total -- --rebuild     # 强制从明细重建
```

### 最小测试集（发布前必跑）

```bash
cd agent-core
npm run test:qwen-gateway           # 自包含
npm run test:approval-proof         # 自包含
npm run test:postgres-contract      # 需要临时 DB
npm run test:mcp-production         # 生产装配边界（无 DEMO 控制器）
npm run test:control                # 控制面契约
npm run test:p1                     # P1 回归
npm run test:mcp                    # MCP 契约
```

根仓全量 / 契约：

```bash
./verify-all.sh
./verify-contracts.sh
```

## 快速开始（Clone + 最小验证）

```bash
git clone <VibeSales 仓库地址> VibeSales
cd VibeSales

# agent-core 是独立子仓，可单独 clone
git clone <agent-core 仓库地址> agent-core
```

如果只是先验证业务核心 → 进入 `agent-core/` 走独立最小档；如果要完整平台链路 → 回根仓跑 `agent-console + agent-manager + agent-runtime + agent-core` 联调。

**建议工作流（双仓分工）**

| 修改内容 | 提交位置 |
|----------|----------|
| 文档 / 词表 / 提示词 / flows 模板 / console / manager / runtime / 资源包 / Worker Skills | 本仓（VibeSales） |
| NestJS 代码 / agent-core 脚本 / SQL 迁移 | agent-core 独立仓 |

版本对齐方式：互相在提交说明里写「依赖 VibeSales@<sha>」「兼容 agent-core@<sha>」即可。

## 开源许可证说明

本仓库整体采用 [Apache License 2.0](./LICENSE) 发布。

其中：
- `agent-manager/` 基于 AgentScope Java 的 `agentscope-core` 构建
- `agent-runtime/` 基于 AgentScope Java 的 `agentscope-harness` 构建
- AgentScope Java 开源项目地址：`https://github.com/agentscope-ai/agentscope-java`
- 第三方归属说明见 [NOTICE](./NOTICE)

如果后续继续直接引入或改写 `agentscope-java` 源码文件，建议在对应文件头部保留原 Apache 2.0 版权头。

## License

本仓库整体采用 [Apache License 2.0](./LICENSE)；第三方归属说明见 [NOTICE](./NOTICE)。agent-core 子仓许可证见 `agent-core/LICENSE`。
