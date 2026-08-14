# VibeSales 部署说明

本文档整合了根仓平台层与 `agent-core/` 子仓的部署口径，目标是回答两类问题：

1. 如何单独把 `agent-core` 跑起来做业务验证。
2. 如何把 `agent-console + agent-manager + agent-runtime + agent-core` 作为完整平台栈部署起来。

## 1. 当前目录约定

当前仓库包含独立子仓：

- `agent-core/`：业务核心仓，承接 `VIBE_SALES_CORE` 的职责

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
  - 环境变量：`export KEY=value` -> `$env:KEY=\"value\"`
  - 健康检查：优先用 `curl.exe` 或 `Invoke-WebRequest`

## 4. `agent-core` 独立部署

`agent-core` 是整个业务核心，支持 CLI、Web/BFF、MCP Plane 三种入口。

### 4.1 安装依赖

Linux / macOS：

```bash
cd agent-core
cp .env.example .env
npm install
```

Windows PowerShell：

```powershell
Set-Location agent-core
Copy-Item .env.example .env
npm install
```

说明：

- `agent-core/Dockerfile` 默认依赖 `package-lock.json`
- 如果当前子仓没有 lock 文件，先执行一次 `npm install`

### 4.2 最小本地验证

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
npm run cli -- p1 --client-code <CLIENT_CODE> --triage fixtures/p1/beauty/pass-full.json
npm run cli -- match --client-code <CLIENT_CODE> --utterance "<用户诉求>"
npm run web
```

默认 Web 入口：

- `http://127.0.0.1:3100`

### 4.3 `agent-core` 生产模式

生产模式必须使用：

- PostgreSQL
- MinIO
- Higress / OpenAI-compatible 网关

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

关键约束：

- 生产模式下 `DASHSCOPE_API_KEY` 必须为空
- `QWEN_GATEWAY_TOKEN` 必须非空且长度至少 16
- `PIPELINE_APPROVAL_SIGNING_SECRET` 至少 32 字符
- `P3C_BUSINESS_MCP_URL` 必须是 `/mcp-servers/` 路径

### 4.4 初始化 `agent-core` 数据库

执行前准备管理员变量：

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
npm run db:init
```

该脚本会：

1. 初始化 5 张核心表
2. 创建 `chatflows_app_login`
3. 创建 `agent_runtime_login`
4. 配置 RLS、角色、触发器

### 4.5 启动 `agent-core` 生产入口

Web/BFF：

```bash
npm run build
npm run start:web
```

MCP Plane：

```bash
npm run build
npm run start:prod
```

默认端口：

- `3100`

健康检查：

Linux / macOS：

```bash
curl http://127.0.0.1:3100/healthz
```

Windows PowerShell：

```powershell
curl.exe http://127.0.0.1:3100/healthz
```

## 5. 根仓本机最小验证

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

- `agent-core` Web / BFF
- `agent-runtime`
- `agent-console`

默认入口：

- Console：`http://127.0.0.1:25273`
- BFF：`http://127.0.0.1:23401`
- Runtime：`http://127.0.0.1:28288`

特点：

- `ORCHESTRATION_MODE=local`
- 不启动 `agent-manager`
- 适合走“向导 -> 发布 -> 试聊”

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
- 网关 URL 与 token 是否符合生产约束

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
```

### 7.3 启动前检查

执行 Compose 前，确认以下条件已经满足：

1. 根仓下存在 `agent-core/`
2. `agent-core/Dockerfile` 存在
3. `deploy/agentteams/integration.env` 已按实际环境填写

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

1. 构建 `agent-core`
2. 构建 `agent-runtime`
3. 构建 `agent-manager`
4. 构建 `agent-console`
5. 启动 PostgreSQL、Redis、初始化任务桶
6. 启动 BFF、runtime、manager、console
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
curl http://127.0.0.1:13100/healthz
```

Windows PowerShell：

```powershell
curl.exe http://127.0.0.1:13100/healthz
```

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
npm run test:qwen-gateway
npm run test:postgres-contract
npm run test:mcp-production
npm run test:control
```

如需完整平台链路验证，可继续执行根仓已有 E2E 脚本。

## 9. 常见问题

### 9.1 `agent-core/Dockerfile` 找不到

原因通常是：

- `agent-core/` 子仓尚未检出
- 当前目录不是仓库根目录

### 9.2 `agent-core/dist/main-web.js` 找不到

原因通常是：

- `agent-core` 还没 `npm install`
- 还没 `npm run build`

### 9.3 `agent-core` 生产模式启动失败

优先检查：

- `DATABASE_URL`
- `MINIO_*`
- `QWEN_GATEWAY_TOKEN`
- `QWEN_BASE_URL`
- `P3C_BUSINESS_MCP_URL`
- `PIPELINE_APPROVAL_SIGNING_SECRET`

### 9.4 `agent-manager` 启动失败

优先检查：

- `AGENTTEAMS_CONTROLLER_URL`
- `AGENTTEAMS_MATRIX_URL`
- `CHATFLOWS_TASK_FS_*`
- `AGENTTEAMS_MANAGER_IDS` 是否包含 `AGENTTEAMS_MATRIX_USER_ID`

### 9.5 Console 能打开但接口 401

优先检查：

- `WEB_AUTH_TOKEN`
- `PIPELINE_CONTROL_TOKEN`
- `RUNTIME_AUTH_TOKEN`
- `MANAGER_AUTH_TOKEN`

## 10. 相关文档

- [README.md](./README.md)：仓库总览
- [ARCHITECTURE.MD](./ARCHITECTURE.MD)：整体架构说明

## 11. GitHub 脱敏提交

用于公开提交前的根仓脱敏脚本：

```bash
node sanitize-for-github.js
```

只检查不修改：

```bash
node sanitize-for-github.js --check
```

说明：

- 脚本会处理根仓代码与根文档中的公开发布口径
- `docs/`、`flows/`、`PPT/` 是当前脱敏流程的例外目录，默认不做内容改写
- 这些例外目录已经写入 `.gitignore`，但若当前 Git 历史里已被跟踪，仍需在发布前显式清理索引或使用新仓初始化
