# VibeSales 部署说明

本文档整合了根仓平台层与 `agent-core/` 子仓的部署口径，目标是回答两类问题：

1. 如何单独把 `agent-core` 跑起来做业务验证。
2. 如何把 `agent-console + agent-manager + agent-runtime + agent-core` 作为完整平台栈部署起来。

## 1. 当前目录约定与部署形态

当前仓库包含独立子仓：

- `agent-core/`：业务核心仓，承接业务核心能力（P1~P4 全流程）

部署形态分两种：

| 形态 | 说明 | 典型场景 |
|------|------|----------|
| `agent-core` 独立部署 | 只跑 `agent-core` 三面之一（CLI / DEMO Web / 生产 MCP），对接自有 PG + MinIO + 模型网关 | 业务侧独立联调、回归、冒烟 |
| 根仓整栈部署 | agent-console + agent-manager + agent-runtime + agent-core 四件套，通过 Docker Compose 或脚本统一拉起 | 平台联调、集成环境、生产交付 |

## 2. 推荐目录准备方式

### 2.1 推荐目录结构

```text
VibeSales/
├── agent-core/          # 独立子仓，业务核心
├── agent-console/
├── agent-manager/
├── agent-runtime/
├── catalogs/
├── prompts/
└── flows/
```

`agent-core` 通过环境变量 `CHATFLOWS_ROOT`（默认解析到上级即根仓）加载三类资产：`catalogs/` `prompts/` `flows/`。任一关键文件缺失 fail fast。

## 3. 操作系统与前置条件

### 3.1 操作系统差异

| 操作系统 | 支持情况 | 说明 |
|------|------|------|
| Linux | 推荐 | Shell 脚本可直接执行，最接近 CI / 交付环境 |
| macOS | 支持 | 可直接运行大部分脚本；需预装 Bash、`curl`、`lsof`、`pgrep` |
| Windows | 条件支持 | Docker / Java / Node 可运行；根仓 `*.sh` 脚本需通过 Git Bash 或 WSL 执行，纯 PowerShell 不作为主路径 |

### 3.2 版本要求

#### 根仓完整联调 / Docker 部署

需要：

- Docker 24+
- Docker Compose v2
- Bash 4+（Windows 需 Git Bash 或 WSL）
- 可访问的 AgentTeams 外部网络

#### `agent-core` 独立运行

需要：

- Node.js 22.14+
- npm 10+
- PostgreSQL 16+（生产模式）
- MinIO / S3（生产模式）
- OpenAI-compatible LLM 网关

如需跑 `agent-manager` / `agent-runtime`，还需要：

- JDK 17+
- Maven 3.9+
- Redis 7+（联调 / Compose 模式）

### 3.3 命令差异约定

- Linux / macOS：本文默认命令可直接执行
- Windows：如命令是 `./scripts/*.sh`，请在 Git Bash 或 WSL 中执行
- Windows PowerShell 与 `bash` 的差异主要在：
  - 复制文件：`cp` -> `Copy-Item`
  - 环境变量：`export KEY=value` -> `$env:KEY="value"`
  - 健康检查：优先用 `curl.exe` 或 `Invoke-WebRequest`

### 3.4 `agent-core` 进程入口：三套 Nest 装配（安全边界）

`agent-core` 提供三个进程入口，对应三套 Nest 装配文件，共享同一套业务内核但入口层独立：

| 形态 | 入口文件 | 装配模块 | 构建产物 | 启动命令 | 端口 | 关键安全约束 |
|------|------|------|------|------|------|------|
| CLI | `src/main.ts` | **AppModule** | `dist/main.js` | `npm run cli -- <cmd>` | 不监听 | stdout 仅 JSON；trace 走 stderr；不承载任何 HTTP |
| DEMO Web / BFF | `src/main-web.ts` | **AppWebModule** | `dist/main-web.js` | `npm run web` 或 `npm run start:web` | 默认 3100 | 装 Wizard + 静态托管 + McpModule；`/mcp-servers/*` 未配 MCP_SERVER_TOKEN 返回 **503** |
| 生产 MCP 工具面 | `src/main-mcp.ts` | **AppMcpModule** | `dist/main-mcp.js` | `npm run mcp` 或 `npm run start:prod` | 默认 3100 | 严格**不加载 Wizard 与静态托管**；仅暴露 MCP + 控制面 + Blueprint 管理面 |

三者共享的业务内核：阶段服务（P1~P4）、ArtifactStore + BlobStore、PipelineService + ApprovalProofService、Qwen + Token + Trace 横切。入口层各自独立，禁止互相混用。

### 3.5 控制面与鉴权令牌（默认关闭端点 = 503）

所有令牌**默认关闭对应端点**（未配置时返回 503），刻意设计「不配置就不暴露」：

| 变量 | 保护端点 | 约束 |
|------|------|------|
| `MCP_SERVER_TOKEN` | `/mcp-servers/*`（两端口共用） | ≥16 字符；是网关注入的上游凭证，不是 Worker 业务 Key；未配置 = 503 |
| `PIPELINE_CONTROL_TOKEN` | `/api/v1/pipeline/*` | ≥16 字符；未配置 = 503 |
| `BLUEPRINT_ADMIN_TOKEN` | `/api/v1/blueprints/*` | ≥16 字符；需同时传 `X-Role: admin` + `X-Actor`；未配置 = 503 |
| `PIPELINE_APPROVAL_SIGNING_SECRET` | `/api/v1/pipeline/:runId/approval` | ≥32 字符，HMAC-SHA256，15 分钟防重放 |
| `P3C_BUSINESS_MCP_URL` | Blueprint `tools.mcpServers[].url` | 必须 `/mcp-servers/` 路径；公网需 HTTPS，私网（10./172.16-31/192.168./localhost/无点内网名）允许 HTTP |
| `WEB_AUTH_TOKEN` 或 `WEB_AUTH_CREDENTIALS` | DEMO Web `/api/**`、`/api/v1/**`、向导会话 | 二者给一；同时给 token + credentials 时重复直接拒绝启动；`client_code` 只从服务端映射，请求体同名字段被覆盖 |

### 3.6 DEMO Web 上并存两套鉴权域

在 DEMO Web（AppWebModule，`main-web.ts`）面上同时存在两套独立鉴权：

- **域 A：向导与 BFF（WebAuth）** — `/api/**`、`/api/v1/**`、`/api/wizard/*`
  - 凭据：`WEB_AUTH_TOKEN` 或 `WEB_AUTH_CREDENTIALS` + `X-Role` + `X-Actor`
  - `client_code` 只从服务端凭据映射取得，请求体同名字段被覆盖
  - 跨租户会话读取返回 404（不泄露存在性）而非 403
- **域 B：MCP 工具面（McpAuthGuard）** — `/mcp-servers/*`
  - 凭据：`MCP_SERVER_TOKEN`（双校验：`Authorization: Bearer <token>` 或 `X-MCP-Server-Token: <token>`）
  - **未配置 MCP_SERVER_TOKEN = 503**（默认关闭，禁止绕过网关直连）
  - 该端点**不走** WebAuth，两套鉴权独立

实践中常见配置错误：反向代理只鉴权了 `/api/**`，却把 `/mcp-servers/**` 暴露到公网。生产部署建议把 MCP 工具面从 DEMO UI 解耦，单独由 `main-mcp.ts`（生产工具面）承载。

## 4. `agent-core` 独立部署

### 4.1 安装依赖 + 构建（三入口分别构建或统一 build）

Linux / macOS：

```bash
cd agent-core
cp .env.example .env
npm install
npm run build    # 同时产出 dist/main.js / dist/main-web.js / dist/main-mcp.js
```

Windows PowerShell：

```powershell
Set-Location agent-core
Copy-Item .env.example .env
npm install
npm run build
```

说明：

- `agent-core/Dockerfile` 默认依赖 `package-lock.json`，若当前子仓无 lock 文件，先执行一次 `npm install`
- `npm run build` 统一产出三份入口构建物；单入口也可直接 `npm run build` 后只启动需要的那一份
- `npm run web` / `npm run cli` 在开发时会直接编译 TS，生产必须走 build + start

### 4.2 最小本地验证（file 存储，不接 PG 与对象存储）

适合只验证业务逻辑，不接数据库与对象存储：

`.env` 最小配置示例：

```env
DASHSCOPE_API_KEY=<YOUR_DEV_KEY>
ARTIFACT_STORE=file
ORCHESTRATION_MODE=local
FLOW_PLATFORM_MODE=local
CHATFLOWS_ROOT=<VibeSales 根目录绝对路径>
LOG_STDERR=on
```

执行：

```bash
cd agent-core
npm run cli -- p1 --client-code <CLIENT_CODE> --triage fixtures/p1/beauty/pass-full.json
npm run cli -- match --client-code <CLIENT_CODE> --utterance "<用户诉求>"
npm run web                  # 启动 AppWebModule（DEMO + McpModule token 默认 503）
```

默认 Web 入口：

- `http://127.0.0.1:3100`

说明：本地开发模式 DASHSCOPE_API_KEY 可用；进入生产模式（ARTIFACT_STORE=postgres）该变量**必须为空**。

### 4.3 `agent-core` 生产模式（postgres + MinIO + Higress）

生产模式必须使用：

- PostgreSQL
- MinIO
- Higress / OpenAI-compatible 网关（公网 HTTPS，私网可豁免 HTTP）

关键环境变量：

```env
ARTIFACT_STORE=postgres
DATABASE_URL=postgresql://chatflows_app_login:<DB_PASSWORD>@<DB_HOST>:5432/chatflows
MINIO_ENDPOINT=http://<MINIO_HOST>:9000
MINIO_ACCESS_KEY=<MINIO_ACCESS_KEY>
MINIO_SECRET_KEY=<MINIO_SECRET_KEY>
MINIO_BUCKET=chatflows-artifacts

QWEN_GATEWAY_TOKEN=<QWEN_GATEWAY_TOKEN>
QWEN_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
QWEN_MODEL=qwen3.7-plus

FLOW_PLATFORM_MODE=production
YUNFLOW_BASE_URL=http://<YUNFLOW_HOST>:8080
YUNFLOW_TOKEN=<YUNFLOW_TOKEN>
YUNFLOW_SPACE_ID=<YUNFLOW_SPACE_ID>
YUNFLOW_DRY_RUN_PATH=/api/workflow/{workflow_id}/run

AGENT_RUNTIME_URL=http://<AGENT_RUNTIME_HOST>:8088
AGENT_RUNTIME_TOKEN=<AGENT_RUNTIME_TOKEN>

MCP_SERVER_TOKEN=<MCP_SERVER_TOKEN>
PIPELINE_CONTROL_TOKEN=<PIPELINE_CONTROL_TOKEN>
BLUEPRINT_ADMIN_TOKEN=<BLUEPRINT_ADMIN_TOKEN>
PIPELINE_APPROVAL_SIGNING_SECRET=<PIPELINE_APPROVAL_SIGNING_SECRET>
P3C_BUSINESS_MCP_URL=https://<HIGRESS_HOST>/mcp-servers/business-tools/mcp
```

关键约束（违反直接启动失败）：

- 生产模式下 `DASHSCOPE_API_KEY` **必须为空**（真实上游 Key 不得下发到业务进程）
- `QWEN_GATEWAY_TOKEN` 非空且至少 16 字符
- `PIPELINE_APPROVAL_SIGNING_SECRET` 至少 32 字符
- `P3C_BUSINESS_MCP_URL` 必须是 `/mcp-servers/` 路径；公网须 HTTPS + 主机名含 higress；私网（10./172.16-31/192.168./localhost/::1/无点内网名）允许 HTTP 且无主机名要求

### 4.4 初始化 `agent-core` 数据库（三管理员变量）

执行前准备管理员变量（一次性，仅初始化用）：

Linux / macOS：

```bash
export DATABASE_ADMIN_URL="postgresql://<ADMIN_USER>:<ADMIN_PASSWORD>@<DB_HOST>:5432/chatflows"
export CHATFLOWS_APP_DB_PASSWORD="<CHATFLOWS_APP_DB_PASSWORD>"
export AGENT_RUNTIME_DB_PASSWORD="<AGENT_RUNTIME_DB_PASSWORD>"
```

Windows PowerShell：

```powershell
$env:DATABASE_ADMIN_URL="postgresql://<ADMIN_USER>:<ADMIN_PASSWORD>@<DB_HOST>:5432/chatflows"
$env:CHATFLOWS_APP_DB_PASSWORD="<CHATFLOWS_APP_DB_PASSWORD>"
$env:AGENT_RUNTIME_DB_PASSWORD="<AGENT_RUNTIME_DB_PASSWORD>"
```

然后执行：

```bash
cd agent-core
npm run db:init
```

该脚本会：

1. 初始化 5 张核心表（run / artifact / agent_blueprint / agent_binding / agentscope_skills）
2. 创建登录 `chatflows_app_login` 与 `agent_runtime_login`
3. 配置 RLS、FORCE ROW LEVEL SECURITY、角色分写（chatflows_app / leader / worker_p1~p4 + p3b + p3c / blueprint_admin / agent_runtime + 辅助角色）
4. 安装 Blueprint 生命周期双触发器（status_guard + role_guard）
5. 固化「哪个角色写哪种 artifact kind」的 INSERT 策略到 SQL 层

### 4.5 启动 `agent-core` 生产入口（按装配选择）

Web/BFF（AppWebModule，含向导 + McpModule 503 降级）：

```bash
cd agent-core
npm run build
npm run start:web     # 运行 dist/main-web.js
```

MCP 生产面（AppMcpModule，严格无 Wizard）：

```bash
cd agent-core
npm run build
npm run start:prod    # 运行 dist/main-mcp.js
```

默认端口：

- `3100`

健康检查：

Linux / macOS：

```bash
curl http://127.0.0.1:3100/healthz | jq
```

Windows PowerShell：

```powershell
curl.exe http://127.0.0.1:3100/healthz
```

生产模式（`ARTIFACT_STORE=postgres`）下，以下任一项不满足 `healthz` 返回 **503**：`DATABASE_URL`、`MINIO_*`、`QWEN_GATEWAY_TOKEN`、`QWEN_BASE_URL`、`AGENT_RUNTIME_URL`（2.5s 超时）。返回 503 说明配置不完整，不要放流量。

## 5. 根仓本机最小验证（UI 快速走通）

这个模式适合快速走通 UI，不依赖真实 AgentTeams 平台。

启动前：

1. 准备好 `agent-core/`
2. 确保 `agent-core` 已执行过 `npm install` 和 `npm run build`

执行：

Linux / macOS：

```bash
./scripts/start-local-manual-stack.sh
```

Windows：

```bash
bash ./scripts/start-local-manual-stack.sh
```

该脚本会启动：

- `agent-core` Web / BFF（AppWebModule）
- `agent-runtime`
- `agent-console`

默认入口：

- Console：`http://127.0.0.1:25273`
- BFF：`http://127.0.0.1:23401`
- Runtime：`http://127.0.0.1:28288`

特点：

- `ORCHESTRATION_MODE=local`
- 不启动 `agent-manager`
- 适合走「向导 -> 发布 -> 试聊」

## 6. 本机接真实平台联调

如需本机同时启动四件套，并接入真实：

- Controller
- Matrix
- MinIO
- PostgreSQL
- Redis

先准备：

- `docs/agentteams/local-development.env.local`
- `agent-core/.env`

然后执行：

Linux / macOS：

```bash
./scripts/run-agentteams-local-dev.sh all
```

Windows：

```bash
bash ./scripts/run-agentteams-local-dev.sh all
```

只做预检不启动：

Linux / macOS：

```bash
AGENTTEAMS_PREFLIGHT_ONLY=1 ./scripts/run-agentteams-local-dev.sh all
```

Windows Git Bash / WSL：

```bash
AGENTTEAMS_PREFLIGHT_ONLY=1 bash ./scripts/run-agentteams-local-dev.sh all
```

这个模式会检查：

- Controller 可达
- Matrix 可达
- 任务目录 MinIO 可达
- PostgreSQL / Redis 可达
- 网关 URL 与 token 是否符合生产约束（包括私网豁免判断）

## 7. Docker Compose 完整部署

### 7.1 先准备外部网络

`deploy/agentteams/compose.yaml` 中的 `agentteams` 网络是 external 网络，名称来自：

```env
AGENTTEAMS_DOCKER_NETWORK=agentteams-net
```

如不存在：

```bash
docker network create agentteams-net
```

### 7.2 生成环境文件

Linux / macOS：

```bash
cp deploy/agentteams/integration.env.example deploy/agentteams/integration.env
```

Windows PowerShell：

```powershell
Copy-Item deploy/agentteams/integration.env.example deploy/agentteams/integration.env
```

至少填写：

```env
AGENTTEAMS_DOCKER_NETWORK=agentteams-net

POSTGRES_ADMIN_PASSWORD=<POSTGRES_ADMIN_PASSWORD>
CHATFLOWS_APP_DB_PASSWORD=<CHATFLOWS_APP_DB_PASSWORD>
AGENT_RUNTIME_DB_PASSWORD=<AGENT_RUNTIME_DB_PASSWORD>

MINIO_ENDPOINT=http://agentteams-minio:9000
MINIO_ACCESS_KEY=<MINIO_ACCESS_KEY>
MINIO_SECRET_KEY=<MINIO_SECRET_KEY>
MINIO_BUCKET=agentteams-storage

RUNTIME_AUTH_TOKEN=<RUNTIME_AUTH_TOKEN>
RUNTIME_ADMIN_TOKEN=<RUNTIME_ADMIN_TOKEN>
MANAGER_AUTH_TOKEN=<MANAGER_AUTH_TOKEN>
MANAGER_ADMIN_TOKEN=<MANAGER_ADMIN_TOKEN>
WEB_AUTH_TOKEN=<WEB_AUTH_TOKEN>
PIPELINE_CONTROL_TOKEN=<PIPELINE_CONTROL_TOKEN>
BLUEPRINT_ADMIN_TOKEN=<BLUEPRINT_ADMIN_TOKEN>
APPROVAL_SIGNING_SECRET=<APPROVAL_SIGNING_SECRET>
MCP_SERVER_TOKEN=<MCP_SERVER_TOKEN>

NEST_LLM_BASE_URL=<HIGRESS_OPENAI_COMPATIBLE_URL>
NEST_LLM_TOKEN=<HIGRESS_CONSUMER_TOKEN>
RUNTIME_LLM_BASE_URL=<HIGRESS_RUNTIME_MODEL_URL>
RUNTIME_LLM_TOKEN=<HIGRESS_RUNTIME_TOKEN>
RUNTIME_MCP_URL=<HIGRESS_MCP_SERVER_URL>
RUNTIME_MCP_TOKEN=<HIGRESS_MCP_SERVER_TOKEN>

AGENTTEAMS_CONTROLLER_URL=http://agentteams-controller:8090
AGENTTEAMS_AUTH_TOKEN=<AGENTTEAMS_AUTH_TOKEN>
AGENTTEAMS_MATRIX_URL=http://agentteams-controller:6167
AGENTTEAMS_MATRIX_USER_ID=@agent-manager:localhost
AGENTTEAMS_MATRIX_ACCESS_TOKEN=<AGENTTEAMS_MATRIX_ACCESS_TOKEN>

CHATFLOWS_TASK_FS_ENDPOINT=http://agentteams-controller:9000
CHATFLOWS_TASK_FS_ACCESS_KEY=<CHATFLOWS_TASK_FS_ACCESS_KEY>
CHATFLOWS_TASK_FS_SECRET_KEY=<CHATFLOWS_TASK_FS_SECRET_KEY>
CHATFLOWS_TASK_FS_BUCKET=agentteams-storage
CHATFLOWS_TASK_FS_PREFIX=teams/chatflows-build-team/shared/tasks

# ==============
# 可观测（可选）
# ==============
# agent-core 侧四档：off / on / verbose，各 sink 独立互不影响
LOG_STDERR=on
LOG_FILE=on
OTEL_EXPORTER_OTLP_ENDPOINT=
AGENTLOOP_EXPORTER=off           # off | stderr | on
AGENTLOOP_ENDPOINT=
AGENTLOOP_ACCESS_KEY=
AGENTLOOP_ACCESS_SECRET=
AGENTLOOP_SAMPLE_RATE=1.0

# agent-manager 侧可观测（与 AgentLoop ROA 互斥，防双份）
# 二选一：
#   (a) MANAGER_TELEMETRY=otlp  走 OTel SDK，自动停用 AgentLoop ROA 镜像
#   (b) 不设 MANAGER_TELEMETRY   走 AGENTLOOP_EXPORTER（off/stderr/on）
MANAGER_TELEMETRY=otlp
```

说明：

- 运行时的模型凭证与 MCP 令牌是**两套东西**，混用会得到 401——实践中最常见配置错误
- `MANAGER_TELEMETRY=otlp` 与 `AGENTLOOP_EXPORTER=on` 互斥：OTLP 模式下 ManagerTelemetry 跳过 AgentLoop ROA 镜像，防双份 span
- `AGENTLOOP_EXPORTER=on` 要求 `AGENTLOOP_ENDPOINT` / `AGENTLOOP_ACCESS_KEY` / `AGENTLOOP_ACCESS_SECRET` 三者同给；取非法值容器启动报错

### 7.3 启动前检查

执行 Compose 前，确认以下条件已经满足：

1. 根仓下存在 `agent-core/`
2. `agent-core/Dockerfile` 存在
3. `deploy/agentteams/integration.env` 已按实际环境填写
4.（可选）`docker network ls | grep agentteams-net` 已存在

### 7.4 执行部署

Linux / macOS：

```bash
./scripts/deploy-agentteams-stack.sh deploy/agentteams/integration.env
```

Windows：

```bash
bash ./scripts/deploy-agentteams-stack.sh deploy/agentteams/integration.env
```

脚本会依次：

1. 构建 `agent-core`（三份入口 build 产物 + Dockerfile 打包）
2. 构建 `agent-runtime`
3. 构建 `agent-manager`
4. 构建 `agent-console`
5. 启动 PostgreSQL、Redis、初始化任务桶
6. 启动 BFF（AppWebModule）、runtime、manager、console
7. 运行连通性检查

### 7.5 访问入口

默认宿主机回环地址：

- Console：`http://127.0.0.1:15173`
- Manager HTTP：`http://127.0.0.1:18090`
- MCP/BFF：`http://127.0.0.1:13100`

对应环境变量：

- `AGENT_CONSOLE_HOST_PORT`
- `AGENT_MANAGER_HOST_PORT`
- `CHATFLOWS_MCP_HOST_PORT`

### 7.6 部署后申报 AgentTeams 资源

```bash
CHATFLOWS_MCP_BASE_URL=<HIGRESS_BASE_URL> \
HIGRESS_CONSUMER_TOKEN=<HIGRESS_CONSUMER_TOKEN> \
./agentteams-apply.sh
```

说明：

- 会渲染 `agentteams-resources/`
- 会同步 `worker-packages/skills/*/SKILL.md`

## 8. 验证步骤

### 8.1 Compose 栈状态

```bash
docker compose --env-file deploy/agentteams/integration.env -f deploy/agentteams/compose.yaml ps
```

### 8.2 `agent-core` 健康检查

Linux / macOS：

```bash
curl http://127.0.0.1:13100/healthz | jq
```

Windows PowerShell：

```powershell
curl.exe http://127.0.0.1:13100/healthz
```

返回 503 时用字段名定位缺失依赖：`dependencies.postgres` / `minio` / `qwen_gateway` / `agent_runtime`。

### 8.3 `agent-manager` 健康检查

Linux / macOS：

```bash
curl -H "Authorization: Bearer <MANAGER_AUTH_TOKEN>" http://127.0.0.1:18090/api/v1/health
```

Windows PowerShell：

```powershell
curl.exe -H "Authorization: Bearer <MANAGER_AUTH_TOKEN>" http://127.0.0.1:18090/api/v1/health
```

### 8.4 `agent-core` 契约与数据库验证

进入 `agent-core/` 后可执行：

```bash
npm run test:qwen-gateway       # 校验生产网关约束 + 私网豁免判断
npm run test:postgres-contract  # 校验 5 表 + RLS + 触发器 + 角色
npm run test:mcp-production     # 断言生产装配不含 Wizard 控制器
npm run test:control            # 控制面 + 审批 + 签名校验
```

如需完整平台链路验证，可继续执行根仓已有 E2E 脚本：

- `scripts/verify-all.sh`（根仓整栈冒烟）
- `scripts/verify-contracts.sh`（跨仓 API 契约）

## 9. 常见问题

### 9.1 `agent-core/Dockerfile` 找不到

原因通常是：

- `agent-core/` 子仓尚未检出
- 当前目录不是仓库根目录

### 9.2 `agent-core/dist/main-web.js` 或 `dist/main-mcp.js` 找不到

原因通常是：

- `agent-core` 还没 `npm install`
- 还没执行 `npm run build`（三份入口产物都由它生成）

### 9.3 `agent-core` 生产模式启动失败

优先检查（对照 healthz 503 明细）：

- `DATABASE_URL`
- `MINIO_*`
- `QWEN_GATEWAY_TOKEN`（至少 16；DASHSCOPE_API_KEY 必须空）
- `QWEN_BASE_URL`（公网需 HTTPS + 含 higress，私网 HTTP 豁免）
- `P3C_BUSINESS_MCP_URL`（/mcp-servers/ 路径 + HTTPS/豁免）
- `PIPELINE_APPROVAL_SIGNING_SECRET`（≥32）
- 三个入口是否混用了错的构建产物（Web 应该 start:web 对应 main-web.js；生产应 start:prod 对应 main-mcp.js）

### 9.4 `agent-manager` 启动失败

优先检查：

- `AGENTTEAMS_CONTROLLER_URL`
- `AGENTTEAMS_MATRIX_URL`
- `CHATFLOWS_TASK_FS_*`
- `AGENTTEAMS_MANAGER_IDS` 是否包含 `AGENTTEAMS_MATRIX_USER_ID`
- `MANAGER_TELEMETRY=otlp` 与 `AGENTLOOP_EXPORTER=on` 互斥（同时设会启动失败）
- `AGENTLOOP_SAMPLE_RATE` 越界（须 0.0..1.0）

### 9.5 Console 能打开但接口 401

优先检查：

- `WEB_AUTH_TOKEN`（向导 `/api/**`，两套鉴权域之一）
- `PIPELINE_CONTROL_TOKEN`（管线 `/api/v1/pipeline/*`）
- `RUNTIME_AUTH_TOKEN`（runtime）
- `MANAGER_AUTH_TOKEN`（orchestration）
- `MCP_SERVER_TOKEN`（如果浏览器页面在调 `/mcp-servers/*`，DEMO Web 上这套端点不走 WebAuth，需单独配置 McpAuthGuard）

### 9.6 DEMO Web 上 MCP 工具面 503 / 401 排查

DEMO Web（AppWebModule）上 `/mcp-servers/*` 返回：

- **503**：`MCP_SERVER_TOKEN` 未配置（默认关闭端点）
  - 处理：在 agent-core `.env` 或 Compose `integration.env` 里补 `MCP_SERVER_TOKEN=...`；重启后再查 `/healthz`
  - 只需要做向导演示、不需要 MCP 工具面时：可以让它一直 503（安全默认，不会暴露）
- **401 Unauthorized**：`MCP_SERVER_TOKEN` 已配，但请求头缺失或不一致
  - DEMO Web 该端点接受两种头：`Authorization: Bearer <MCP_SERVER_TOKEN>` 或 `X-MCP-Server-Token: <MCP_SERVER_TOKEN>`
  - 注意：它**不走** WebAuth，WEB_AUTH_TOKEN 在这里没用
  - 如果反向代理把 `/api/**` 鉴权了，但把 `/mcp-servers/**` 放通到公网，务必补上同等粒度的访问控制
- **生产最佳实践**：把 MCP 工具面从 DEMO UI 解耦出来，单独走 `main-mcp.ts`（AppMcpModule，无 Wizard，严格无静态托管）在 Higress 后面对外；DEMO Web 仅保留 `/api/**` 向导域

### 9.7 Token 会计与用量对账

`agent-core` 在 `logs/` 下产出：

- `token.log`：每次调用 JSON Lines 明细（可追溯到 scope + purpose）
- `token-total.json`：按 scope/purpose/model 三维累计台账（原子替换，不滚动）

重算累计：

```bash
cd agent-core
npm run token:total --rebuild
```

## 10. 相关文档

- [base_README.md](./base_README.md)：仓库总览
- [ARCHITECTURE.MD](./ARCHITECTURE.MD)：整体架构说明

## 11. `agent-core` 独立部署方式（systemd / 容器 / K8s）

`agent-core` 独立部署时（不和根仓整栈放一起），进程托管推荐：

| 平台 | 推荐方式 |
|------|----------|
| Linux | systemd unit（`Restart=always`、`EnvironmentFile=/etc/vibesales-agent-core.env`）或直接容器化 |
| macOS | `launchd` plist（仅开发场景） |
| Windows | NSSM 或 `sc.exe` 注册服务；或 WSL2 内用 systemd；生产优先容器 |
| 任意 | Docker / Kubernetes —— 与宿主 OS 解耦，最省心（与整栈 Compose 容器同构） |

Linux systemd 最小示例（生产 MCP 面）：

```ini
[Unit]
Description=agent-core MCP production plane
After=network.target postgresql.service

[Service]
Type=simple
User=vibesales
Group=vibesales
WorkingDirectory=/opt/vibesales/agent-core
EnvironmentFile=/etc/vibesales-agent-core.env
ExecStart=/usr/bin/npm run start:prod
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

如果是 DEMO Web 面，把 `ExecStart` 改为 `/usr/bin/npm run start:web`，并加 `WEB_AUTH_TOKEN` / `MCP_SERVER_TOKEN`。

## 12. 根仓脚本速查

| 脚本 | 用途 |
|------|------|
| `scripts/start-local-manual-stack.sh` | 本机最小验证（console + runtime + agent-core Web） |
| `scripts/run-agentteams-local-dev.sh all` | 本机四件套 + 真实平台联调（含 preflight） |
| `scripts/deploy-agentteams-stack.sh` | Docker Compose 整栈部署 |
| `scripts/verify-all.sh` | 根仓整栈冒烟 |
| `scripts/verify-contracts.sh` | 跨仓 API 契约校验 |
| `agentteams-apply.sh` | 部署后申报平台资源 + 同步 worker SKILL.md |

## 13. 独立部署 vs 整栈部署差异说明

当 `agent-core` 被**单独部署**（不跟随根仓整栈）时，相对整栈形态有以下差异。运维侧需要在两种形态间切换时可对照此清单：

| 维度 | `agent-core` 独立部署 | 根仓整栈部署（Compose） |
|------|------|------|
| 入口面 | 按需只启动一个：CLI 或 AppWebModule 或 AppMcpModule | 三份入口构建物都会构建；运行时默认拉起 AppWebModule（BFF）；MCP 生产面可单独追加 Compose service |
| 网络 | 自管反代 / 自管 NS；无 Docker external 网要求 | 必须先 `docker network create agentteams-net`；所有容器挂 `agentteams-net` 外部网络 |
| PostgreSQL | 自备实例；`db:init` 单独在目标 PG 上执行 | 随 Compose 自带 PostgreSQL 容器；`db:init` 由 agent-core 容器 entrypoint 调用 |
| MinIO / 任务目录 | 自备对象存储；没有 AgentTeams 任务桶前缀约束 | Compose 自带 MinIO 容器；任务桶前缀固定 `teams/chatflows-build-team/shared/tasks` |
| Matrix + Controller | 不需要（只跑业务） | 必须可达：`AGENTTEAMS_CONTROLLER_URL` + `AGENTTEAMS_MATRIX_URL` + Matrix 用户/Token |
| AgentTeams 资源申报 | 不需要 | 部署后执行 `agentteams-apply.sh` 渲染并申报 |
| 鉴权变量 | 只要配 `agent-core` 自身那 6 个（MCP/CONTROL/BLUEPRINT/APPROVAL/P3C_URL/WEB_AUTH） | 还要追加：RUNTIME_*、MANAGER_*、AGENTTEAMS_*、CHATFLOWS_TASK_FS_*、NEST_LLM_* 一整套 |
| 可观测 | 只配 `agent-core` 的 LOG_* / OTEL_* / AGENTLOOP_* | 还要配 `MANAGER_TELEMETRY`（otlp 或留空走 AgentLoop，二选一互斥） |
| systemd 单元 | 自建（参考 §11 示例，Description 按面命名） | 容器托管为主；systemd 只包住 `docker compose up -d` |
| Console / Manager / Runtime | 没有；agent-core 只暴露自己的 HTTP | 必须同时健康；Console 把三类请求分别反代到三个后端 |

两种形态的 `agent-core` **代码、构建产物、配置变量、鉴权约束、生产私网豁免规则完全一致**；差异只出在根仓附带的平台侧组件是否同机部署。
