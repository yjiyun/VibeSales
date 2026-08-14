# VibeSales 架构说明

## 1. 系统定位

`VibeSales` 是 Agentic Assistant 的平台层总仓，当前由两部分组成：

1. 根仓平台层：`agent-console`、`agent-manager`、`agent-runtime`、`agentteams-resources`、`worker-packages`
2. 业务核心子仓：`agent-core/`

本文中仍统一用 `VIBE_SALES_CORE` 指代 AgenticAssistant 的业务核心能力；在当前工作区内，`VIBE_SALES_CORE` 的实际代码载体就是 `agent-core/` 这个独立子仓。

## 2. 总体拓扑

```text
用户/运营
   |
   v
agent-console
   |-- /api ----------> agent-core Web/BFF
   |-- /orchestration -> agent-manager
   `-- /runtime ------> agent-runtime

agent-manager
   |-- REST ----------> AgentTeams Controller
   |-- Matrix --------> AgentTeams Rooms
   |-- S3/MinIO ------> shared/tasks/task-{run_id}/
   `-- HTTP ----------> agent-core control / blueprint / pipeline 面

agent-core
   |-- CLI -----------> 本地验证 / 回归
   |-- Web -----------> 向导 / BFF / Demo
   |-- MCP -----------> P1-P4 工具面
   |-- PostgreSQL ----> run / artifact / blueprint / binding / skills
   |-- MinIO ---------> 大产物正文
   |-- Higress/LLM ---> P1 / P2 / P3C 裁决
   `-- YunFlow -------> 工作流导入与试运行

agent-runtime
   |-- PostgreSQL ----> blueprint / binding / skills
   |-- Redis ---------> distributed state
   `-- Higress/LLM ---> runtime model + MCP servers
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

它负责：

- P1：向导与意图分诊
- P2：模板匹配
- P3：模板个性化
- P3B：从零生成工作流
- P3C：组装 Blueprint
- P4：导入、绑定、dry-run、审批

#### 3.4.1 三个入口

`agent-core` 提供三个进程入口，共享同一套业务内核：

| 形态 | 入口 | 命令 | 用途 |
|------|------|------|------|
| CLI | `src/main.ts` | `npm run cli -- <command>` | 本地验证、脚本化、回归 |
| Demo Web / BFF | `src/main-web.ts` | `npm run web` / `npm run start:web` | 向导、前端联调 |
| 生产 MCP Plane | `src/main-mcp.ts` | `npm run mcp` / `npm run start:prod` | MCP 工具面、审批、Blueprint 管理 |

其中生产 MCP Plane 刻意不加载 Demo 向导控制器与静态前端托管，这是明确的安全边界。

#### 3.4.2 分层视图

```text
接入层
  CLI Commands / Web Controllers / MCP Controller / Control API
      |
      v
编排层
  PipelineService / ApprovalProofService
      |
      v
业务层
  wizard / intent / match / p3 / p3b / p3c / p4 / preview
      |
      v
能力层
  qwen / catalogs / prompts / templates / tenant / common
      |
      v
存储层
  ArtifactStoreService / BlobStore / BlueprintAdmin
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
- `mcp/`：P1-P4 工具面
- `web/`：Demo Web / BFF
- `artifacts/`：产物存储与 Blueprint 管理
- `qwen/`：唯一 LLM 出口

#### 3.4.4 P1-P4 管线

```text
用户诉求
   |
   v
P1  意图与向导
   |
   v
P2  模板匹配
   |---- 命中模板 ------------------> P3  模板个性化
   |---- 需长记忆/技能演进/多轮工具 --> P3C Blueprint 组装
   `---- 无匹配 --------------------> P3B 工作流生成
   |
   v
P4  导入 / 绑定 / dry-run / 人工审批
```

构建路径由 `decideBuildPath` 统一裁定：

- 需要长记忆、技能演进、多轮工具编排时走 `P3C`
- 模板命中时走 `P3`
- 否则走 `P3B`

#### 3.4.5 `agent-core` 对外接口

MCP 工具面：

- `POST /mcp-servers/:server/mcp`
- `GET /healthz`

其中 server 包括：

- `chatflows-p1`
- `chatflows-p2`
- `chatflows-p3`
- `chatflows-p3b`
- `chatflows-p3c`
- `chatflows-p4`

每个工具输入都必须携带 `_ctx`，至少包含：

- `run_id`
- `client_code`

控制面：

- `GET /api/v1/pipeline/health`
- `POST /api/v1/pipeline/runs`
- `POST /api/v1/pipeline/start`
- `GET /api/v1/pipeline/:runId`
- `POST /api/v1/pipeline/:runId/abort`
- `POST /api/v1/pipeline/:runId/approval`

Blueprint 管理面：

- `POST /api/v1/blueprints/publish`
- `POST /api/v1/blueprints/rollback`

Demo 向导面：

- `GET /api/health`
- `GET /api/catalogs`
- `POST /api/wizard/sessions`
- `GET /api/wizard/sessions/:id`
- `POST /api/wizard/sessions/:id/answer`
- `POST /api/wizard/sessions/:id/preview`
- `POST /api/wizard/sessions/:id/template`

#### 3.4.6 数据模型

`agent-core` 在生产模式下使用 PostgreSQL + MinIO。

核心表共 5 张：

- `run`
- `artifact`
- `agent_blueprint`
- `agent_binding`
- `agentscope_skills`

关键约束：

- `artifact` 采用追加写 + 版本化
- 所有跨表外键带 `client_code`
- 启用 `ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`
- Blueprint 生命周期由数据库触发器强约束

15 类核心产物包括：

- P1：`wizard_state`、`triage`
- P2：`match_result`
- P3：`guidance`、`personalized_package`、`flow_check`
- P3B：`flow_yaml`、`flow_check`
- P3C：`blueprint`、`blueprint_check`、`expert_dispatch`、`expert_result`
- P4：`import_result`、`dry_run`、`approval`、`evidence`

#### 3.4.7 第三方依赖

`agent-core` 直接对接：

- PostgreSQL：权威状态
- MinIO：大产物正文
- Higress / OpenAI-compatible LLM：P1、P2、P3C
- YunFlow：P3 / P3B 工作流导入与试运行
- `agent-runtime`：P3C Blueprint 冒烟
- AgentLoop：可选观测出口

其中 `QwenService` 是唯一 LLM egress，负责统一调用和 token 记账。

### 3.5 `worker-packages`

这里存放 AgentTeams Worker 使用的技能包，每个目录包含 `SKILL.md`。

当前主要覆盖：

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

这些 YAML 会通过 `agentteams-apply.sh` 渲染并申报到平台。

### 3.7 `catalogs` / `prompts` / `flows`

这三类目录是平台与 `agent-core` 共用的配置资产：

- `catalogs/`：行业、场景、能力、连接器等词表真源
- `prompts/`：提示词模板，例如 `matcher.yaml`
- `flows/`：工作流定义、模板和导出包

`agent-core` 启动时会直接读取这些目录；任一关键文件缺失会 fail fast。

## 4. 关键时序

### 4.1 本地 `agent-core` 直驱模式

```text
用户 -> agent-console 或 agent-core Web
     -> agent-core PipelineService
     -> P1 -> P2 -> P3/P3B/P3C -> P4
     -> 写 run / artifact / blueprint / evidence
```

这时 `ORCHESTRATION_MODE=local`，由业务核心自身串起完整 P1-P4。

### 4.2 平台编排模式

```text
用户 -> agent-console
     -> agent-manager
     -> Controller 创建/使用 Team
     -> Matrix 驱动 Leader/Worker 协作
     -> Worker 通过 MCP 调用 agent-core
     -> agent-core 产生产物
     -> 结果写入 shared/tasks/task-{run_id}/
     -> agent-manager 回调控制面 / 审批 / 收尾
```

### 4.3 已发布智能体对话

```text
用户 -> agent-console
     -> agent-runtime /api/v1/chat
     -> BlueprintProjector 投影已发布 Blueprint
     -> HarnessAgent + LLM/MCP
     -> SSE 返回 message / done / error
```

## 5. 命名与目录约定

当前仓库的业务核心目录统一为 `agent-core/`。

在本文以及根仓当前脚本、Compose、环境样例中，凡是涉及业务核心构建、运行和代理转发的路径，都以 `agent-core` 为准。

## 6. 安全边界

### 6.1 Token 分层

根仓及 `agent-core` 显式区分多类 token：

- Web 访问 token
- MCP server token
- Pipeline control token
- Runtime token / admin token
- Manager token / admin token
- Blueprint admin token
- 审批签名 secret

### 6.2 最小权限

- Manager 对任务目录使用专属前缀身份
- Runtime 按租户安装工具权限
- `agent-core` 数据库通过 RLS、角色分写、复合外键隔离租户
- Human、Leader、Manager 的 Matrix ID 有显式 allowlist

### 6.3 生产约束

- `agent-manager`、`agent-runtime`、`agent-core` 生产模式都要求走网关
- `agent-core` 生产模式不允许把真实 `DASHSCOPE_API_KEY` 注入业务进程
- `admin` 与普通 token 不能复用

## 7. 启动模式

### 7.1 `agent-core` 独立启动

适合单独验证业务核心：

- `npm run cli -- ...`
- `npm run web`
- `npm run start:prod`

### 7.2 `scripts/start-local-manual-stack.sh`

启动：

- `agent-core` Web / BFF
- `agent-runtime`
- `agent-console`

特点：

- 本机自足
- 不起 manager
- 适合 UI 手工验证

### 7.3 `scripts/run-agentteams-local-dev.sh`

启动：

- `agent-core`
- `agent-console`
- `agent-runtime`
- `agent-manager`

特点：

- 联通真实平台
- 会做严格 preflight

### 7.4 `scripts/deploy-agentteams-stack.sh`

特点：

- 面向 Docker Compose 完整部署
- 依赖外部 AgentTeams 网络
- 适合集成环境和交付环境

## 8. 建议阅读顺序

1. [README.md](./README.md)
2. [DEPLOY.MD](./DEPLOY.MD)
3. `agent-console/src/shared/api.js`
4. `agent-manager/ManagerConfig -> ManagerComposition -> OrchestrationController`
5. `agent-runtime/RuntimeApplication -> RuntimeService -> BlueprintProjector`
6. `worker-packages/skills/*/SKILL.md`

## 9. 结论

`VibeSales` 现在已经不是单一平台壳层仓库，而是：

- 用根仓承载平台层与运行层
- 用 `agent-core/` 独立子仓承载业务核心层

它们之间的职责划分很清晰：

- `agent-core` 负责 P1-P4 业务判断与产物生成
- `agent-manager` 负责平台编排
- `agent-runtime` 负责线上对话承载
- `agent-console` 负责统一入口
- `agentteams-resources` 与 `worker-packages` 负责平台执行面

这套拆分让系统同时支持：

- `agent-core` 独立开发与验证
- 根仓本机联调
- 完整平台化交付
