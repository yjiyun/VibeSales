# VibeSales 架构说明

## 1. 系统定位

`VibeSales` 是平台层总仓，当前由两部分组成：

1. 根仓平台层：`agent-console`、`agent-manager`、`agent-runtime`、`agentteams-resources`、`worker-packages`
2. 业务核心子仓：`agent-core/`

`agent-core/` 是业务核心能力子仓，负责 P1~P4 全流程业务裁决与产物生成。本文中凡是涉及业务核心构建、运行、文档、接口、存储、鉴权，路径与命名一律以 `agent-core` 为准。

## 2. 总体拓扑

```text
用户/运营
   |
   v
agent-console
   |-- /api ----------> agent-core Web/BFF (AppWebModule: 向导 + McpModule, MCP_TOKEN 未配 503)
   |-- /orchestration -> agent-manager
   `-- /runtime ------> agent-runtime

agent-manager
   |-- REST ----------> AgentTeams Controller
   |-- Matrix --------> AgentTeams Rooms
   |-- S3/MinIO ------> shared/tasks/task-{run_id}/
   |-- HTTP ----------> agent-core control / blueprint / pipeline 面 (PIPELINE_CONTROL_TOKEN / BLUEPRINT_ADMIN_TOKEN)
   `-- MCP -----------> agent-core MCP 生产面 (AppMcpModule, MCP_SERVER_TOKEN, 严格无 Wizard)

agent-core
   |-- CLI (AppModule, 不监听端口) -> 本地验证 / 回归 / 管道
   |-- Web (AppWebModule, 向导 + McpModule) -> 向导 / BFF / Demo
   |-- MCP (AppMcpModule, 仅生产工具, 无 Wizard) -> P1-P4 工具面
   |-- PostgreSQL ----> run / artifact / blueprint / binding / skills (RLS + 复合外键 + 触发器)
   |-- MinIO ---------> 大产物正文 (blob 引用 + sha256 校验)
   |-- Higress/LLM ---> P1 / P2 / P3C 裁决 (私网 HTTP 豁免)
   `-- YunFlow -------> 工作流导入与试运行

agent-runtime
   |-- PostgreSQL ----> blueprint / binding / skills (JDBC 直读 PUBLISHED)
   |-- Redis ---------> distributed state
   `-- Higress/LLM ---> runtime model + MCP servers

可观测横切
   |-- agent-core: TraceService -> TraceSink -> Stderr/LogFile/Otlp/AgentLoop/Web 事件流
   |                             -> 独立文件 sink: TokenUsage(token.log) / TokenLedger(token-total.json)
   `-- agent-manager: ManagerTelemetry -> OtlpSDK (启用时跳过 AgentLoop ROA 镜像防双份)
                                     -> AgentLoopExporter(off/stderr/on, ROA 签名)
```

## 3. 根仓内核心模块

### 3.1 `agent-console`

技术栈：

- Vue 3
- Vite
- Element Plus
- `vue-element-plus-x`
- Nginx（生产容器）

职责：

- 提供搭建向导界面
- 展示编排运行状态
- 提供已发布智能体的对话界面

前端代理关系来自 [`agent-console/nginx.conf`](./agent-console/nginx.conf) 和 [`agent-console/vite.config.js`](./agent-console/vite.config.js)：

- `/api/` -> `agent-core` Web/BFF
- `/orchestration/` -> `agent-manager`
- `/runtime/` -> `agent-runtime`

前端 API 抽象位于 [`agent-console/src/shared/api.js`](./agent-console/src/shared/api.js)，可看到三组接口：

- `wizardApi`
- `managerApi`
- `pipelineApi`

这意味着控制台本身不承载业务判断，而是把请求分别转发给业务核心、平台编排器和运行时。

### 3.2 `agent-manager`

技术栈：

- Java 17
- AgentScope `agentscope-core:2.0.1`
- JDK `HttpServer`
- MinIO Java SDK

定位：

- 平台外侧编排器
- 不是 AgentTeams 平台内置的 Manager CR
- 负责把一次平台编排任务拆解成 Team / Worker / Human 可执行的链路

核心装配类：

- [`ManagerConfig.java`](./agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerConfig.java)
- [`ManagerComposition.java`](./agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerComposition.java)
- [`ManagerServer.java`](./agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerServer.java)

启动时装配的关键组件：

- `RestPlatformClient`：访问 AgentTeams Controller
- `RestMatrixClient`：收发 Matrix 消息
- `MinioTaskArtifactStore`：读写任务目录
- `PipelineControlClient`：回调业务控制面
- `OrchestrationPlanner`：确定性或 LLM 编排决策
- `OrchestrationStore`：本地编排状态
- `AgentLoopExporter`：可选观测出口（三档 off/stderr/on，含 ROA 签名）
- `ManagerTelemetry`：OTLP 模式注册全局 SDK，为真时跳过 AgentLoop ROA 镜像（防双份）

由 [`OrchestrationController.java`](./agent-manager/src/main/java/com/yjiyun/chatflows/manager/api/OrchestrationController.java) 暴露：

- `POST /api/v1/orchestrations`
- `GET /api/v1/orchestrations/{runId}`
- `GET /api/v1/orchestrations/{runId}/events`
- `POST /api/v1/orchestrations/{runId}/approval`
- `POST /api/v1/orchestrations/{runId}/abort`
- `GET /api/v1/health`

Manager 的三条核心外部链路：

1. 控制面：Controller 申报/查询 Team、Worker、Human
2. 协作面：通过 Matrix Room 与 Leader/Human 通信
3. 产物面：通过 MinIO 读写 `shared/tasks/task-{run_id}/`
4. MCP 业务调用面：通过 MCP_SERVER_TOKEN 调用 agent-core MCP 生产面（AppMcpModule，严格无 Wizard）

### 3.3 `agent-runtime`

技术栈：

- Java 17
- AgentScope `agentscope-harness:2.0.1`
- PostgreSQL
- Redis
- OpenAI-compatible runtime model adapter

定位：

- 承载已发布智能体的在线对话
- 按 `clientCode + userId + runtimeAgentId` 投影 Blueprint
- 通过 SSE 向前端流式回传消息

核心类：

- [`RuntimeApplication.java`](./agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/RuntimeApplication.java)
- [`RuntimeService.java`](./agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/api/RuntimeService.java)
- [`ChatController.java`](./agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/api/ChatController.java)
- [`BlueprintProjector.java`](./agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/blueprint/BlueprintProjector.java)

对外接口：

- `GET /healthz`
- `POST /api/v1/chat`
- `POST /api/v1/dryrun`
- `POST /api/v1/publish`
- `POST /api/v1/rollback`
- `POST /api/v1/ingest`（仅 local 模式）

Runtime 会把 Blueprint 投影成 AgentScope workspace，包含：

- `AGENTS.md`
- `SOUL.md`
- `knowledge/KNOWLEDGE.md`
- `skills/<name>/SKILL.md`

### 3.4 `agent-core`

`agent-core/` 是新增的独立子仓，也是整个系统的业务中枢。

#### 3.4.1 三个入口（对应三套 Nest 装配）

`agent-core` 提供三个进程入口，共享同一套业务内核，但入口装配的模块集合不同。这是安全边界而非工程洁癖：

| 形态 | 入口文件 | 装配模块 | 命令 | 端口 | 用途 | 关键约束 |
|------|------|------|------|------|------|------|
| CLI | `src/main.ts` | **AppModule** | `npm run cli -- <command>` | 不监听 | 本地验证、脚本化、回归管道 | stdout 仅输出 JSON；trace 全走 stderr；不承载任何 HTTP |
| DEMO Web / BFF | `src/main-web.ts` | **AppWebModule** | `npm run web` / `npm run start:web` | 默认 3100 | 向导、前端联调 | 装配 Wizard 控制器 + 静态托管 + McpModule（`/mcp-servers/*` 未配置 MCP_SERVER_TOKEN 返回 **503**） |
| 生产 MCP 工具面 | `src/main-mcp.ts` | **AppMcpModule** | `npm run mcp` / `npm run start:prod` | 默认 3100 | 生产部署 MCP 面 + 控制面 + Blueprint 管理面 | 严格不加载 Wizard 控制器与静态托管；仅暴露 MCP/控制/管理 三面 |

三者共享的业务内核：阶段服务（P1~P4）、存储服务（ArtifactStore + BlobStore）、编排服务（PipelineService + ApprovalProofService）、Token/Qwen/Trace 横切。入口层各自独立，禁止互相混用。

模块装配矩阵：

| 模块 | AppModule (CLI) | AppWebModule (DEMO) | AppMcpModule (生产) |
|------|:---:|:---:|:---:|
| Catalogs / Prompts / Templates / Tenant | ✓ | ✓ | ✓ |
| Qwen / Trace / TokenLedger | ✓ | ✓ | ✓ |
| Intent / Match / P3 / P3B / P3C / P4 | ✓ | ✓ | ✓ |
| Artifacts（产物存储） | ✓ | ✓ | ✓ |
| Orchestration（管线 + 控制面 HTTP） | ✓ | ✓ | ✓ |
| MCP 工具服务 `/mcp-servers/*` | — | ✓（MCP_SERVER_TOKEN 未配 = 503） | ✓ |
| Blueprint 管理面 `/api/v1/blueprints/*` | — | — | ✓ |
| Wizard 会话服务 `/api/wizard/*` | — | ✓ | — |
| 静态前端托管（ServeStatic） | — | ✓ | — |

#### 3.4.2 分层视图

```text
接入层
  CLI Commands / Web Controllers / MCP Controller / Control API
  (nest-commander) (/api/wizard/*)   (/mcp-servers/*)   (/api/v1/*)
      | 统一鉴权：WebAuth / MCP Token / Control Token / Blueprint Token
      v
编排层
  PipelineService (local 串 P1-P4) / ApprovalProofService (HMAC-SHA256 短时)
      |
      v
业务层
  wizard / intent / match / p3 / p3b / p3c / p4 / preview
      |
      v
能力层
  qwen / catalogs / prompts / templates / tenant / common
      |   (唯一 LLM 出口)    (外部资产装载)    (横切工具：trace · token · flow-package · rls)
      v
存储层
  ArtifactStoreService (追加写+版本化, file | postgres)
  BlobStore (MinIO 大产物 + sha256 校验)
  BlueprintAdmin (发布/回滚唯一入口)
```

#### 3.4.3 关键业务模块

`agent-core/src/` 的主模块包括：

- `wizard/`：多轮向导与接待话术
- `intent/`：P1 意图与分诊
- `match/`：P2 Filter -> Rank -> Decide
- `p3/`：模板个性化
- `p3b/`：工作流从零生成
- `p3c/`：四专家并行组装 Blueprint
- `p4/`：导入、绑定、试运行、审批
- `orchestration/`：本地编排与控制面
- `mcp/`：P1-P4 工具面（MCP_SERVER_TOKEN 未配 = 503）
- `web/`：Demo Web / BFF（Wizard 会话内存态，不持久化）
- `artifacts/`：产物存储与 Blueprint 管理
- `qwen/`：唯一 LLM 出口（token 记账在解析之前；生产私网 HTTP 豁免）
- `common/`：横切工具（types / trace / token-ledger / approval-proof / flow-package / rls / time）

`common/` 目录横切职责（与 agent-core/ARCHITECTURE §4.17 对齐）：

| 文件 | 职责 |
|------|------|
| `types.ts` | 全链路共享契约（ArtifactKind / BuildPath / AgentBlueprint / Guidance / MatchResult） |
| `product-phase.ts` | 阶段枚举、闸门阈值、中文标签 |
| `trace-sink.ts` / `trace.service.ts` / `trace.module.ts` | TraceSink 抽象 + 统一打点 + 注册 |
| `stderr-sink.ts` / `log.service.ts` / `otlp-sink.ts` / `agentloop-sink.ts` | 四类 TraceSink 实现（stderr 流水 / 文件滚动 / OTel GenAI / AgentLoop ROA） |
| `token-usage.service.ts` / `token-ledger.service.ts` | 独立文件 sink：token.log 明细 + token-total.json 累计（原子替换） |
| `flow-package.ts` | Coze 工作流包编解码 + 11 项结构自检 |
| `approval-proof.service.ts` | HMAC-SHA256 审批凭据（15 分钟防重放） |
| `flow-context.ts` / `request-context.ts` / `runtime-id.ts` | 链路上下文（AsyncLocalStorage） |
| `redact.ts` / `input-digest.ts` / `artifact-inspector.ts` | 脱敏/摘要/检视辅助 |
| `time.ts` / `wizard-models.ts` | 北京时间 + 向导可选模型 |

#### 3.4.4 P1-P4 管线阶段矩阵

```text
用户诉求
   |
   v
P1  意图与向导 (wizard + intent)  ── 闸门 PASS/ASK/CUSTOM/ERROR
   |                               产物: wizard_state、triage
   v
P2  模板匹配 (match) ──────────── Filter→Rank→Decide
   |                               产物: match_result
   |---- hit ----------------------> P3   模板个性化 (p3)
   |                                   产物: guidance / personalized_package / flow_check
   |---- 长记忆/技能演进/多轮工具 ---> P3C  Blueprint 组装 (p3c)
   |                                   四专家并行: persona / business / skill / tool
   |                                   产物: blueprint / blueprint_check / expert_dispatch / expert_result
   `---- 无匹配 --------------------> P3B  工作流生成 (p3b)
                                       产物: flow_yaml / flow_check
   |
   v
P4  导入 / 绑定 / dry-run / 人工审批 (p4)
   人工审批 = 前置硬门 (HITL)，ApprovalProof HMAC 15 分钟
   产物: import_result / dry_run / approval / evidence
```

构建路径分流（`decideBuildPath`）：

- 需要长记忆 / 技能演进 / 多轮工具编排 → **P3C**（Blueprint 路径，非工作流）
- 模板命中（`match.action === 'hit'`）→ **P3**（工作流个性化路径）
- 否则 → **P3B**（从零生成工作流路径）

#### 3.4.5 `agent-core` 对外接口（四组 API）

MCP 工具面（AppWebModule 未配 token = 503；AppMcpModule 常驻）：

- `POST /mcp-servers/:server/mcp`  其中 server ∈ {chatflows-p1, chatflows-p2, chatflows-p3, chatflows-p3b, chatflows-p3c, chatflows-p4}
- `GET /healthz`

每个工具输入强制携带 `_ctx`（至少 `run_id` + `client_code`），无则 400；与入参同名字段交叉校验，防止租户越权。

控制面：

- `GET /api/v1/pipeline/health`
- `POST /api/v1/pipeline/runs`
- `POST /api/v1/pipeline/start`（platform 模式返回 409，由 agent-manager 逐 MCP 推进）
- `GET /api/v1/pipeline/:runId`
- `POST /api/v1/pipeline/:runId/abort`
- `POST /api/v1/pipeline/:runId/approval`

Blueprint 管理面（仅 AppMcpModule，生产面）：

- `POST /api/v1/blueprints/publish`
- `POST /api/v1/blueprints/rollback`

Demo 向导面（仅 AppWebModule）：

- `GET /api/health`
- `GET /api/catalogs`
- `POST /api/wizard/sessions`
- `GET /api/wizard/sessions/:id`
- `POST /api/wizard/sessions/:id/answer`
- `POST /api/wizard/sessions/:id/preview`
- `POST /api/wizard/sessions/:id/template`

#### 3.4.6 数据模型（RLS + 复合外键 + 触发器）

`agent-core` 生产模式 PostgreSQL + MinIO，核心表 5 张：

- `run`
- `artifact`（追加写 + 版本化，永不覆盖）
- `agent_blueprint`
- `agent_binding`
- `agentscope_skills`

关键约束（与 agent-core/ARCHITECTURE §5.2/5.3/5.4 对齐）：

- 所有跨表外键携带 `client_code`（复合外键）；即使拿到别家不透明 id 也无法拼合法行
- 四张业务表 `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`（表属主也不例外）
- 每个事务内 `set_config('app.client_code', $1, true)` + `set local role <worker_role>`
- `artifact` 写入策略固化「哪个角色写哪种 kind」在 SQL 层
- Blueprint 生命周期双触发器强约束：
  - `agent_blueprint_status_guard` 允许 4 条正向迁移 + 1 条回滚（DRAFT→STAGED→PUBLISHED→RETIRED，PUBLISHED←RETIRED）
  - `agent_blueprint_role_guard` 写死：INSERT 只允许 `worker_p3c` + DRAFT；`runtime.isolationScope` 首次写入后**永久不可变**；各角色只允许自己那几条合法迁移
- 角色拆分：`chatflows_app` / `leader` / `worker_p1` / `worker_p2` / `worker_p3` / `worker_p3b` / `worker_p3c` / `worker_p4` / `blueprint_admin` / `agent_runtime` + 辅助角色

15 类核心产物（ArtifactKind 恰好 15 种，按阶段归属）：

| 阶段 | Artifact Kind |
|------|------|
| P1 | `wizard_state`、`triage` |
| P2 | `match_result` |
| P3 | `guidance`、`personalized_package`、`flow_check` |
| P3B | `flow_yaml`、`flow_check` |
| P3C | `blueprint`、`blueprint_check`、`expert_dispatch`、`expert_result` |
| P4 | `import_result`、`dry_run`、`approval`、`evidence` |

#### 3.4.7 第三方依赖与出口约束

`agent-core` 直接对接：

- PostgreSQL：权威状态（postgres 模式永不静默降级到 file）
- MinIO：大产物正文（blob 引用 + sha256 校验；读取比对，不一致直接抛错）
- Higress / OpenAI-compatible LLM：P1、P2、P3C（生产模式：真实 DASHSCOPE_API_KEY 必须为空；QWEN_GATEWAY_TOKEN 至少 16）
- YunFlow：P3 / P3B 工作流导入与试运行
- `agent-runtime`：P3C Blueprint 冒烟（AGENT_RUNTIME_URL + AGENT_RUNTIME_TOKEN）
- TraceService → TraceSink（stderr / file / otlp / agentloop / web 事件流）
- 两独立文件 sink：`logs/token.log` 明细 + `logs/token-total.json` 累计（原子替换）
- AgentLoop（可选）：三档 off/stderr/on，on 模式需 endpoint + access key + secret，ROA 签名上报
- OTLP（可选）：agent-core OtlpSink / agent-manager ManagerTelemetry otlpMode 启用时**跳过** AgentLoop ROA 镜像（防双份）

### 3.5 `worker-packages`

这里存放 AgentTeams Worker 使用的技能包，每个目录包含 `SKILL.md`。当前覆盖：

- 路由：`leader-route`、`expert-dispatch`
- P1：`p1-wizard-gate`
- P2：`p2-filter-rank-decide`
- P3：`p3-template-personalize`
- P3b：`p3b-flow-generate`
- P3C：`p3c-persona`、`p3c-business`、`p3c-skill-select`、`p3c-skill-draft`、`p3c-tool-select`、`p3c-compose`、`p3c-selfcheck`
- P4：`p4-import-bind-dryrun`
- 验证：`flow-selfcheck`

其中：

- [`p3c-compose/SKILL.md`](./worker-packages/skills/p3c-compose/SKILL.md) 负责合成 Blueprint
- [`p4-import-bind-dryrun/SKILL.md`](./worker-packages/skills/p4-import-bind-dryrun/SKILL.md) 负责导入、绑定和试运行

### 3.6 `agentteams-resources`

这里是 AgentTeams 平台资源定义：

- `team.yaml`
- `humans.yaml`
- `workers/*.yaml`

这些 YAML 会通过根仓 `agentteams-apply.sh` 渲染并申报到平台。

### 3.7 `catalogs` / `prompts` / `flows`

三类目录是平台根仓与 `agent-core` 共用的配置资产，通过环境变量 `CHATFLOWS_ROOT`（默认上级目录即根仓）解析：

- `catalogs/`：行业、场景、能力、连接器等词表真源
- `prompts/`：提示词模板（receptionist / router / matcher）
- `flows/`：工作流定义、模板和导出包

`agent-core` 启动会直接读取；任一关键文件缺失 **fail fast**（抛错退出），宁可启动失败也不允许「空词表下看起来成功」。

## 4. 关键时序

### 4.1 本地 `agent-core` 直驱模式

```text
用户 -> agent-console 或 agent-core Web (AppWebModule)
     -> agent-core PipelineService (ORCHESTRATION_MODE=local)
     -> P1 -> P2 gate -> P3/P3B/P3C -> P4(审批前置门)
     -> 追加写 run + 15 artifact kind
     -> Blueprint 或 workflow package 产出
```

local 模式下 PipelineService 自身串起完整 P1→P4；platform 模式下 `POST /api/v1/pipeline/start` 返回 409，由 agent-manager 逐 MCP 调用推进。

### 4.2 平台编排模式（platform）

```text
用户 -> agent-console
     -> agent-manager (OrchestrationPlanner + TraceSink + AgentLoopExporter(或 Otlp 模式跳过 ROA 镜像))
     -> Controller REST: 创建/使用 Team / Worker / Human
     -> Matrix: 驱动 Leader / Worker / Human 协作
     -> Worker 通过 MCP → agent-core MCP 生产面 (AppMcpModule, MCP_SERVER_TOKEN, 无 Wizard)
     -> agent-core 追加写 artifact + 版本化
     -> 大产物入 shared/tasks/task-{run_id}/ (MinIO)
     -> agent-manager 回调控制面 / 审批 / 收尾
```

### 4.3 已发布智能体对话

```text
用户 -> agent-console
     -> agent-runtime /api/v1/chat
     -> BlueprintProjector 投影 PUBLISHED Blueprint (JDBC 直读 PG, 带 RLS 隔离)
     -> HarnessAgent + LLM/MCP servers
     -> SSE 返回 message / done / error
```

## 5. 命名与目录约定

当前仓库的业务核心目录统一为 `agent-core/`。在本文以及根仓脚本、Compose、环境样例中，凡是涉及业务核心构建、运行和代理转发的路径，都以 `agent-core` 为准。

## 6. 安全边界

### 6.1 Token 分层 + 两套鉴权域

根仓及 `agent-core` 显式区分多类 token，DEMO Web 面上**并存两套鉴权域**：

| 域 | 适用端点 | 保护凭据 |
|------|------|------|
| DEMO Web 域（AppWebModule Wizard） | `/api/**`、`/api/v1/**`、向导会话 | WebAuth：`WEB_AUTH_TOKEN` / `WEB_AUTH_CREDENTIALS` + `X-Role` + `X-Actor`；`client_code` 只从服务端凭据映射，请求体同名字段会被覆盖 |
| MCP 工具域（两端口共用） | `/mcp-servers/*`（在 DEMO Web 和生产 MCP 面上都存在） | McpAuthGuard：`MCP_SERVER_TOKEN`（`Authorization: Bearer` 或 `X-MCP-Server-Token:`，定长时间比较）；**未配置 = 503** |
| 生产控制面 | `/api/v1/pipeline/*` | `PIPELINE_CONTROL_TOKEN` |
| Blueprint 管理面 | `/api/v1/blueprints/*`（仅生产 MCP 面） | `BLUEPRINT_ADMIN_TOKEN` + `X-Role: admin` + `X-Actor` |
| 审批签名 | `approval` | `PIPELINE_APPROVAL_SIGNING_SECRET`（>=32 字符，HMAC-SHA256，15 分钟防重放） |
| Runtime | `agent-runtime` API | `RUNTIME_AUTH_TOKEN` / `RUNTIME_ADMIN_TOKEN` |
| Manager | `agent-manager` API | `MANAGER_AUTH_TOKEN` / `MANAGER_ADMIN_TOKEN` |
| Manager 侧可观测 | AgentLoopExporter / OTLP | `AGENTLOOP_EXPORTER`（off/stderr/on）+ otlpMode，启用 OTLP 会停用 ROA 镜像防双份 |

实践中最常见配置错误：「运行时模型凭证 vs MCP 令牌混用 → 401」。

### 6.2 最小权限

- Manager 对任务目录使用专属前缀身份
- Runtime 按租户安装工具权限
- `agent-core` 数据库通过 RLS、角色分写、复合外键隔离租户；`FORCE ROW LEVEL SECURITY` 覆盖表属主
- Human、Leader、Manager 的 Matrix ID 有显式 allowlist
- `artifact` 的 INSERT 策略写死「哪个角色写哪种 kind」在 SQL 层

### 6.3 生产约束（含私网豁免）

- `agent-manager`、`agent-runtime`、`agent-core` 生产模式都要求走网关
- `agent-core` 生产模式（ARTIFACT_STORE=postgres）：
  - 真实 `DASHSCOPE_API_KEY` 必须为空（不能下发真实上游 Key）
  - `QWEN_GATEWAY_TOKEN` ≥16、`QWEN_BASE_URL` 至少是 /compatible-mode/v1 兼容
  - `P3C_BUSINESS_MCP_URL` 必须是 `/mcp-servers/` 路径
  - 公网模型网关：必须 HTTPS + 主机名含 higress（双保险，校验失败启动抛错）
  - **私网豁免**：以下来源允许 HTTP 且无主机名要求
    - `10.0.0.0/8`
    - `172.16.0.0/12`（172.16-31）
    - `192.168.0.0/16`
    - `localhost` / `127.0.0.1` / `::1`
    - 无点的纯内网名（不含 `.` 的 hostname）
- `admin` 与普通 token 不能复用
- Blueprint `runtime.isolationScope` 首次写入后永久不可变（触发器强约束）

## 7. 启动模式

### 7.1 `agent-core` 独立启动（三套入口对应三命令）

- CLI：`cd agent-core && npm run cli -- p1 --client-code <cc> --triage fixtures/p1/beauty/pass-full.json`
- DEMO Web：`cd agent-core && npm run web`（AppWebModule，含 Wizard + McpModule 503 降级）
- 生产 MCP：`cd agent-core && npm run start:prod`（AppMcpModule，严格无 Wizard）

### 7.2 `scripts/start-local-manual-stack.sh`

启动 `agent-core` Web/BFF + `agent-runtime` + `agent-console`；本机自足，不起 manager；适合 UI 手工验证。

### 7.3 `scripts/run-agentteams-local-dev.sh`

启动四件套；联通真实平台；会做严格 preflight（Controller/Matrix/MinIO/PG+Redis/网关 URL 与 Token 生产约束）。

### 7.4 `scripts/deploy-agentteams-stack.sh`

面向 Docker Compose 完整部署；依赖外部 AgentTeams 网络；适合集成与交付。

## 8. 建议阅读顺序

1. [base_README.md](./base_README.md)
2. [DEPLOY.MD](./DEPLOY.MD)
3. `agent-console/src/shared/api.js`
4. `agent-manager/ManagerConfig -> ManagerComposition -> OrchestrationController`
5. `agent-runtime/RuntimeApplication -> RuntimeService -> BlueprintProjector`
6. `agent-core/src/main.ts / main-web.ts / main-mcp.ts`（三入口装配差异）
7. `agent-core/src/common/trace-sink.ts` 与四类 sink + 两独立文件 sink
6. `worker-packages/skills/*/SKILL.md`

## 9. 结论

`VibeSales` 已不是单一平台壳层仓库，而是明确三层拆分的完整栈：

- 资产层（根仓 `catalogs/` `prompts/` `flows/`）→ CHATFLOWS_ROOT 共用
- 平台层（根仓 `agent-console` `agent-manager` `agent-runtime` `agentteams-resources` `worker-packages`）
- 业务核心层（独立子仓 `agent-core/`）三入口三装配（AppModule / AppWebModule / AppMcpModule，安全边界明确）

职责划分清晰：

- `agent-core` 负责 P1-P4 业务裁决与产物追加写（永不覆盖）
- `agent-manager` 负责平台编排 + AgentLoop/Otlp 可选观测（互斥防双份）
- `agent-runtime` 负责线上对话承载（JDBC 直读 PUBLISHED Blueprint）
- `agent-console` 负责统一入口
- `agentteams-resources` 与 `worker-packages` 负责平台执行面

这套拆分同时支持三种形态：

1. `agent-core` 独立开发与验证（CLI / DEMO Web / 生产 MCP 三面独立）
2. 根仓本机联调（start-local-manual-stack / run-agentteams-local-dev）
3. 完整平台化交付（Docker Compose + AgentTeams 平台 + Higress 网关）
