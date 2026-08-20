# VibeSales 生产环境部署指南 (Ubuntu)

> 本文档持续更新，记录 VibeSales 系列服务在 Ubuntu 生产环境的部署分析、所需信息清单及操作流程。

---

## 1. 部署方案总览

### 1.1 测试环境服务映射 → 生产部署目标

根据测试环境 `ps -ef` 输出，当前服务构成如下：

| 测试环境进程 | 对应生产部署组件 | 说明 |
|---|---|---|
| `postgres ... chatflows` | **PostgreSQL 16** (容器化或独立实例) | 业务核心数据库，5 张核心表 + RLS |
| `qwenpaw-worker --name chatflows-leader ...` | **AgentTeams 外部平台** (Controller + Worker) | 不在 VibeSales 仓内，需独立部署/对接 |
| `teamharness/mcp/server.py` + `workerflow/mcp/server.py` | AgentTeams Worker 侧 MCP 工具 | 同上，AgentTeams 平台自带 |
| `docker exec ... agentteams-worker-wizard-intent python3` | AgentTeams Worker 容器探针 | 同上，AgentTeams 平台自带 |

VibeSales 仓内需要部署的服务为 **4 件套 + 3 个基础服务**：

```text
┌───────────────────────────────────────────────────────────────┐
│                      VibeSales 生产栈                         │
├───────────────────────────────────────────────────────────────┤
│  基础服务 (Compose 自带 或 外部接入)                           │
│  ├── PostgreSQL 16        (chatflows 库)                      │
│  ├── Redis 7             (分布式状态/缓存)                    │
│  └── MinIO / S3          (Artifact 大对象存储)                │
├───────────────────────────────────────────────────────────────┤
│  业务服务 (4 件套)                                            │
│  ├── agent-core (MCP 生产面)   AppMcpModule → port 3100       │
│  ├── agent-core-bff (Web/BFF)  AppWebModule → port 3101       │
│  ├── agent-runtime  (Java)     AgentScope 执行面 → 8088       │
│  ├── agent-manager  (Java)     编排管理器 → 8090              │
│  └── agent-console  (Vue/nginx) 前端控制台 → 5173/8080        │
├───────────────────────────────────────────────────────────────┤
│  外部依赖 (必须可达)                                           │
│  ├── AgentTeams Controller     (8090 编排 + 6167 Matrix)      │
│  ├── AgentTeams MinIO/FS       (任务目录 9000)                │
│  └── Higress / OpenAI 网关     (LLM + MCP 统一入口)           │
└───────────────────────────────────────────────────────────────┘
```

### 1.2 部署模式选择

| 模式 | 适用场景 | 推荐度 |
|---|---|---|
| **A. Docker Compose 整栈部署** | 单服务器、快速落地、标准交付 | ⭐⭐⭐ 推荐 |
| **B. 混合部署** (DB 外部 + 业务 Compose) | 生产已有独立 PG/MinIO/Redis；业务容器化 | ⭐⭐⭐⭐ 生产推荐 |
| **C. 全拆分 systemd (原生)** | 禁止使用 Docker；精细运维、资源可控、多机拆分 | ⭐⭐⭐ 原生生产推荐 |
| **D. agent-core 独立 systemd** | 只部署业务核心，不跑 Console/Manager/Runtime | ⭐⭐ 业务侧联调 |

本文 **§4~§8** 描述模式 A/B（Docker Compose）；**§9** 专门描述模式 C（全原生 systemd，无 Docker）。

---

## 2. 生产服务器前置要求 (Ubuntu)

### 2.1 硬件最低配置

| 组件 | CPU | 内存 | 磁盘 |
|---|---|---|---|
| 业务四件套 (容器) | 8 核 | 16 GB | 100 GB SSD (含镜像与构建缓存) |
| PostgreSQL 16 | 4 核 | 8 GB | 50 GB SSD (数据盘独立) |
| Redis 7 | 2 核 | 4 GB | 20 GB (AOF) |
| MinIO | 4 核 | 8 GB | 500 GB+ (对象存储盘) |

> 合并部署单服务器建议：**16 核 / 32 GB / 1 TB SSD**。

### 2.2 操作系统与基础软件

#### 2.2.1 通用基础工具 (所有模式都需要)

```bash
sudo apt-get update
sudo apt-get install -y curl jq lsof procps netcat-openbsd bash
```

#### 2.2.2 模式 A/B (Docker Compose) 需要

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# Docker 外部网络
docker network create agentteams-net 2>/dev/null || true
```

#### 2.2.3 模式 C (原生 systemd，无 Docker) 需要

```text
Ubuntu 22.04 LTS / 24.04 LTS
├── Node.js 22.14+  (含 npm 10+)          agent-core 构建 + 运行
├── JDK 17+         (建议 Eclipse Temurin)  agent-manager / agent-runtime
├── Maven 3.9+                              构建 Java 两组件
├── Nginx 1.24+                             托管 agent-console 静态 + 统一反代
└── 专用运行用户 agentteams (UID 固定，禁止 root 运行业务)
```

一键安装：

```bash
# Node.js 22 (NodeSource)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

# JDK 17 + Maven
sudo apt-get install -y openjdk-17-jdk maven

# Nginx
sudo apt-get install -y nginx

# 专用运行用户
sudo useradd -r -m -d /opt/agentteams -s /usr/sbin/nologin agentteams
sudo mkdir -p /opt/agentteams /var/log/agentteams /var/lib/agentteams
sudo chown -R agentteams:agentteams /opt/agentteams /var/log/agentteams /var/lib/agentteams
```

版本检查：

```bash
node -v   # >= v22.14
npm -v    # >= 10
java -version   # >= 17
mvn -version    # >= 3.9
nginx -v
```

---

## 3. 部署前必须收集的信息清单

### 3.1 基础服务信息 (PostgreSQL / Redis / MinIO)

| # | 变量名 | 说明 | 约束 | 提供方 |
|---|---|---|---|---|
| 3.1.1 | `POSTGRES_HOST` | PG 主机名/IP | 容器内可达 | DBA |
| 3.1.2 | `POSTGRES_PORT` | PG 端口 | 默认 5432 | DBA |
| 3.1.3 | `POSTGRES_ADMIN_PASSWORD` | PG postgres 用户密码 | URL-safe (用于 DATABASE_URL) | DBA |
| 3.1.4 | `CHATFLOWS_APP_DB_PASSWORD` | chatflows_app_login 密码 | ≥16 字符，URL-safe | DBA 或部署 |
| 3.1.5 | `AGENT_RUNTIME_DB_PASSWORD` | agent_runtime_login 密码 | ≥16 字符，URL-safe，≠ 3.1.4 | DBA 或部署 |
| 3.1.6 | `REDIS_URL` | Redis 连接串 | `redis://user:pass@host:port/db` | DBA |
| 3.1.7 | `MINIO_ENDPOINT` | MinIO/S3 端点 | 容器内可达；公网需 HTTPS | 存储管理员 |
| 3.1.8 | `MINIO_ACCESS_KEY` | MinIO Access Key | — | 存储管理员 |
| 3.1.9 | `MINIO_SECRET_KEY` | MinIO Secret Key | — | 存储管理员 |
| 3.1.10 | `MINIO_BUCKET` | Artifact 存储桶 | 默认 `chatflows-artifacts` | 存储管理员 |

### 3.2 LLM 网关信息 (Higress / OpenAI-compatible)

| # | 变量名 | 说明 | 约束 | 提供方 |
|---|---|---|---|---|
| 3.2.1 | `NEST_LLM_BASE_URL` | agent-core 用网关 URL | 公网需 HTTPS + 主机名含 higress；私网 IP 豁免 HTTP | 平台运维 |
| 3.2.2 | `NEST_LLM_TOKEN` | agent-core 用网关 Token | ≥16 字符 | 平台运维 |
| 3.2.3 | `RUNTIME_LLM_BASE_URL` | agent-runtime 用网关 URL | 同上 | 平台运维 |
| 3.2.4 | `RUNTIME_LLM_TOKEN` | agent-runtime 用网关 Token | ≥16 字符，可与 3.2.2 不同 | 平台运维 |
| 3.2.5 | `HIGRESS_CONSUMER_TOKEN` | Higress 消费者统一 Token | AgentTeams 申报资源用 | 平台运维 |

### 3.3 MCP 工具面与业务控制面鉴权

| # | 变量名 | 说明 | 约束 | 提供方 |
|---|---|---|---|---|
| 3.3.1 | `RUNTIME_MCP_URL` | P3C 业务工具 MCP URL | 必须 `/mcp-servers/` 路径；公网 HTTPS + 含 higress；私网豁免 | 平台运维 |
| 3.3.2 | `RUNTIME_MCP_TOKEN` | 业务工具 MCP Token | ≥16 字符 | 平台运维 |
| 3.3.3 | `MCP_SERVER_TOKEN` | Higress → Nest MCP 上游注入凭证 | ≥16 字符；≠ Worker 业务 Key | 平台运维 |
| 3.3.4 | `PIPELINE_CONTROL_TOKEN` | `/api/v1/pipeline/*` 控制面 Token | ≥16 字符 | 部署生成 |
| 3.3.5 | `BLUEPRINT_ADMIN_TOKEN` | Blueprint 管理面 Token | ≥16 字符；请求需附带 `X-Role: admin` + `X-Actor` | 部署生成 |
| 3.3.6 | `APPROVAL_SIGNING_SECRET` | 审批签名密钥 | ≥32 字符，HMAC-SHA256，15 分钟防重放 | 部署生成 |

### 3.4 各服务内部鉴权 Token (四件套互相调用)

| # | 变量名 | 说明 | 约束 |
|---|---|---|---|
| 3.4.1 | `RUNTIME_AUTH_TOKEN` | agent-runtime 业务 API Token | ≥16 字符 |
| 3.4.2 | `RUNTIME_ADMIN_TOKEN` | agent-runtime 管理 API Token | ≥16 字符，≠ 3.4.1 |
| 3.4.3 | `MANAGER_AUTH_TOKEN` | agent-manager 业务 API Token | ≥16 字符 |
| 3.4.4 | `MANAGER_ADMIN_TOKEN` | agent-manager 管理 API Token | ≥16 字符，≠ 3.4.3 |
| 3.4.5 | `WEB_AUTH_TOKEN` | DEMO Web / BFF 向导面 Token | ≥16 字符 |
| 3.4.6 | `WEB_AUTH_CLIENT_CODE` | Web 面默认租户编码 | 对应 catalogs/tenants/*.json 中存在的 tenant |

### 3.5 AgentTeams 外部平台对接信息

| # | 变量名 | 说明 | 提供方 |
|---|---|---|---|
| 3.5.1 | `AGENTTEAMS_CONTROLLER_URL` | AgentTeams Controller HTTP (编排) | AgentTeams 运维 |
| 3.5.2 | `AGENTTEAMS_AUTH_TOKEN` | Controller 鉴权 Token | AgentTeams 运维 |
| 3.5.3 | `AGENTTEAMS_MATRIX_URL` | Matrix Client-Server API 地址（当前生产/测试统一走 `:18080`） | AgentTeams 运维 |
| 3.5.4 | `AGENTTEAMS_MATRIX_USER_ID` | agent-manager Matrix 用户 ID | 推荐独立正式账号 `@chatflows-manager-ext:...` |
| 3.5.5 | `AGENTTEAMS_MATRIX_PASSWORD`（推荐）**或** `AGENTTEAMS_MATRIX_ACCESS_TOKEN` | Matrix 身份凭证 | 推荐 user+password；token 仅作可选补充 |
| 3.5.6 | `CHATFLOWS_TASK_FS_ENDPOINT` | 任务文件系统 (AgentTeams MinIO) 端点 | AgentTeams 运维 |
| 3.5.7 | `CHATFLOWS_TASK_FS_ACCESS_KEY` | 任务目录受限身份 Access Key | ≠ MinIO admin Key |
| 3.5.8 | `CHATFLOWS_TASK_FS_SECRET_KEY` | 任务目录受限身份 Secret Key | — |
| 3.5.9 | `CHATFLOWS_TASK_FS_BUCKET` | 任务文件存储桶 | 默认 `agentteams-storage` |
| 3.5.10 | `CHATFLOWS_TASK_FS_PREFIX` | 任务目录前缀 | 固定 `teams/chatflows-build-team/shared/tasks` |
| 3.5.11 | `AGENTTEAMS_LEADER_ROOM_ID` | `chatflows-build-team` 的 `teamRoomID` | Team 资源部署完成后回填 |

### 3.6 AgentTeams 身份与工作流配置

| # | 变量名 | 说明 | 示例 |
|---|---|---|---|
| 3.6.1 | `AGENTTEAMS_HUMAN_IDS` | 人工审批者 Matrix ID 列表 (逗号分隔) | `@admin:matrix-local.agentteams.io:18080` |
| 3.6.2 | `AGENTTEAMS_LEADER_IDS` | Team Leader Matrix ID | `@chatflows-leader:matrix-local.agentteams.io:18080` |
| 3.6.3 | `AGENTTEAMS_MANAGER_IDS` | 编排管理者 ID (必须包含 3.5.4) | `@chatflows-manager-ext:matrix-local.agentteams.io:18080` |
| 3.6.4 | `AGENTTEAMS_E2E_HUMAN_USER_ID` | E2E 验证用 Human ID | — |
| 3.6.5 | `AGENTTEAMS_RUN_CLIENT_CODE` | 默认识别的 client_code | `acme_beauty_missing_kb` |
| 3.6.6 | `AGENTTEAMS_RUN_TIMEOUT_SECONDS` | 单次 Run 超时 | 默认 3600 |

### 3.7 可观测性配置 (可选，OTLP 标准)

| # | 变量名 | 说明 | 约束 |
|---|---|---|---|
| 3.7.1 | `AGENTLOOP_EXPORTER` | AgentLoop 导出档位 | `off` / `stderr` / `on`；`on` 需 3.7.3 三要素齐 |
| 3.7.2 | `AGENTLOOP_SAMPLE_RATE` | 采样率 | `0.0` ~ `1.0` |
| 3.7.3 | `AGENTLOOP_PROTOCOL` | 导出协议 | 默认 `otlp`；旧版 `roa` |
| 3.7.4 | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | OTLP Traces 端点 | ARMS/阿里云可观测地址 |
| 3.7.5 | `OTEL_RESOURCE_ATTRIBUTES` | 资源标签 | 含 service.version / deployment.environment 等 |
| 3.7.6 | `ARMS_LICENSE_KEY` | ARMS License Key | 不写进 Git |
| 3.7.7 | `MANAGER_TELEMETRY` | Manager 遥测开关 | 设为 `otlp` 即走 OTel SDK，与 `AGENTLOOP_EXPORTER=on` 互斥 |

### 3.8 YunFlow 集成信息 (可选，仅 P3/P3B 导入用)

| # | 变量名 | 说明 |
|---|---|---|
| 3.8.1 | `FLOW_PLATFORM_MODE` | `local` 或 `production` |
| 3.8.2 | `YUNFLOW_BASE_URL` | YunFlow 服务地址 |
| 3.8.3 | `YUNFLOW_TOKEN` | YunFlow Token |
| 3.8.4 | `YUNFLOW_SPACE_ID` | YunFlow 空间 ID |
| 3.8.5 | `YUNFLOW_DRY_RUN_PATH` | 执行路径模板，含 `{workflow_id}` 占位 |

### 3.9 网络拓扑与暴露端口

| # | 配置项 | 说明 | 默认值 |
|---|---|---|---|
| 3.9.1 | `AGENT_CONSOLE_HOST_PORT` | 前端 Console 宿主机端口 | `15173` |
| 3.9.2 | `AGENT_MANAGER_HOST_PORT` | Manager HTTP 宿主机端口 | `18090` |
| 3.9.3 | `CHATFLOWS_MCP_HOST_PORT` | agent-core MCP 宿主机端口 | `13100` |
| 3.9.4 | 反代/HTTPS 终止方式 | Nginx / Traefik / Higress | 生产必须 HTTPS |

---

## 4. 部署步骤 (Ubuntu 生产)

### 4.1 目录准备

```bash
# 部署目录
sudo mkdir -p /opt/agentteams
sudo chown $USER:$USER /opt/agentteams
cd /opt/agentteams

# 拉取代码 (Git 方式，按实际仓库地址)
git clone <VibeSales 仓库地址> VibeSales
cd VibeSales

# 确认子仓 agent-core 存在
ls -la agent-core/ agent-manager/ agent-runtime/ agent-console/
```

### 4.2 准备 integration.env

```bash
cp prod-deploy/integration.env.example prod-deploy/integration.env
chmod 600 prod-deploy/integration.env
```

当前仓库中，`prod-deploy/integration.env` 是生产环境推荐的**唯一真源**。  
如需生成 `/etc/agentteams/agent-core.env`、`/etc/agentteams/agent-runtime.env`、`/etc/agentteams/agent-manager.env`，统一使用：

```bash
bash prod-deploy/split-integration-env.sh \
  --src prod-deploy/integration.env \
  --out /etc/agentteams
```

按第 3 节清单逐项填入。生产环境以下几项**必须特别注意**：

```env
# 1. 所有密码 URL-safe (无特殊字符 或已 percent-encode)
# 2. MCP_SERVER_TOKEN / PIPELINE_CONTROL_TOKEN / BLUEPRINT_ADMIN_TOKEN ≥16 字符且互不相同
# 3. APPROVAL_SIGNING_SECRET ≥32 字符
# 4. RUNTIME_AUTH_TOKEN ≠ RUNTIME_ADMIN_TOKEN；MANAGER_AUTH_TOKEN ≠ MANAGER_ADMIN_TOKEN
# 5. 生产关闭 DASHSCOPE_API_KEY (agent-core 生产模式必须为空)
# 6. MANAGER_TELEMETRY=otlp 与 AGENTLOOP_EXPORTER=on 互斥
```

### 4.3 预检查

```bash
cd /opt/agentteams/VibeSales

# 1. Docker 网络存在
docker network ls | grep agentteams-net

# 2. 基础服务可达 (以外部 PG/Redis/MinIO 为例)
nc -zv <POSTGRES_HOST> 5432
nc -zv <REDIS_HOST> 6379
curl -I <MINIO_ENDPOINT>/minio/health/ready

# 3. Compose 语法校验
docker compose --env-file deploy/agentteams/integration.env \
  -f deploy/agentteams/compose.yaml config --quiet
```

### 4.4 执行部署

```bash
cd /opt/agentteams/VibeSales
bash ./scripts/deploy-agentteams-stack.sh deploy/agentteams/integration.env
```

脚本执行顺序：
1. 构建 `agent-core` / `agent-core-bff` / `db-init` / `agent-runtime` / `agent-manager` / `agent-console`
2. 启动 PostgreSQL、Redis、artifact-bucket-init、db-init
3. 启动 agent-runtime、agent-core、agent-core-bff
4. 启动 agent-manager、agent-console
5. 等待健康检查 + MCP `/healthz` 通过
6. 执行 manager-runner check

### 4.5 部署后申报 AgentTeams 资源

```bash
cd /opt/agentteams/VibeSales
CHATFLOWS_MCP_BASE_URL=https://<HIGRESS_BASE_URL> \
HIGRESS_CONSUMER_TOKEN=<HIGRESS_CONSUMER_TOKEN> \
bash ./agentteams-apply.sh
```

该步骤：
- 渲染 `agentteams-resources/` 下的 Worker YAML
- 同步 `worker-packages/skills/*/SKILL.md` 到 AgentTeams

### 4.6 部署后验证

```bash
cd /opt/agentteams/VibeSales
ENV_FILE=deploy/agentteams/integration.env
COMPOSE_FILE=deploy/agentteams/compose.yaml

# 1. 容器状态
docker compose --env-file $ENV_FILE -f $COMPOSE_FILE ps

# 2. agent-core 健康检查 (MCP 面)
curl http://127.0.0.1:13100/healthz | jq
#   503 → 看 dependencies.postgres/minio/qwen_gateway/agent_runtime 定位缺失项

# 3. agent-manager 健康检查
MANAGER_AUTH_TOKEN=$(grep ^MANAGER_AUTH_TOKEN= $ENV_FILE | cut -d= -f2)
curl -H "Authorization: Bearer $MANAGER_AUTH_TOKEN" \
  http://127.0.0.1:18090/api/v1/health | jq

# 4. Console 可访问 (需反代 HTTPS)
curl -I http://127.0.0.1:15173/

# 5. 契约与数据库验证 (进入 agent-core)
cd agent-core
# npm install 已在构建阶段执行
# npm run test:postgres-contract   # 5 表 + RLS + 触发器 + 角色
# npm run test:mcp-production      # 生产装配不含 Wizard
# npm run test:control             # 控制面 + 审批签名
```

### 4.7 systemd 托管 (可选，Compose 开机自启)

```ini
# /etc/systemd/system/agentteams.service
[Unit]
Description=VibeSales Docker Compose Stack
Requires=docker.service
After=docker.service network.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/agentteams/VibeSales
EnvironmentFile=/opt/agentteams/VibeSales/deploy/agentteams/integration.env
ExecStart=/usr/bin/docker compose --env-file deploy/agentteams/integration.env -f deploy/agentteams/compose.yaml up -d
ExecStop=/usr/bin/docker compose --env-file deploy/agentteams/integration.env -f deploy/agentteams/compose.yaml down
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now agentteams
```

---

## 5. 反向代理与 HTTPS (生产强制)

推荐 Higress 或 Nginx。端口暴露矩阵：

| 后端 | 容器内端口 | 宿主机端口 (Compose) | 对外路径 | 鉴权域 |
|---|---|---|---|---|
| agent-console (nginx) | 8080 | 15173 | `/` | 无 (静态页面) |
| agent-core (BFF/Web) | 3101 | 不直接暴露 | `/api/**` | `WEB_AUTH_TOKEN` (WebAuth) |
| agent-core (MCP 生产) | 3100 | 13100 | `/mcp-servers/**` | `MCP_SERVER_TOKEN` (McpAuthGuard) |
| agent-core (MCP 生产) | 3100 | 13100 | `/api/v1/pipeline/**` | `PIPELINE_CONTROL_TOKEN` |
| agent-core (MCP 生产) | 3100 | 13100 | `/api/v1/blueprints/**` | `BLUEPRINT_ADMIN_TOKEN` + `X-Role: admin` |
| agent-runtime | 8088 | 不直接暴露 | `/runtime/**` | `RUNTIME_AUTH_TOKEN` |
| agent-manager | 8090 | 18090 | `/manager/**` | `MANAGER_AUTH_TOKEN` |

**安全注意**：
- DEMO Web 上 `/mcp-servers/**` 与 `/api/**` 是**两套独立鉴权域**，反代必须同时鉴权
- 生产建议 MCP 工具面只走 agent-core (3100/AppMcpModule)，BFF/Web 只走 3101/AppWebModule，两者解耦
- 所有对外端点必须 HTTPS；私网内部调用 (10.x / 172.16-31.x / 192.168.x / localhost) 可豁免 HTTP

---

## 6. 常见生产问题定位

| 现象 | 可能原因 | 排查命令 |
|---|---|---|
| agent-core healthz 503 | 缺 PG/MinIO/Qwen/Runtime | `curl /healthz \| jq .dependencies` |
| agent-core 启动直接退出 | `APPROVAL_SIGNING_SECRET <32` 或 Token 长度不足 | `docker logs agent-core 2>&1 \| head -50` |
| MCP 工具面 503 | `MCP_SERVER_TOKEN` 未配置 | 补 env 后重启；DEMO 场景允许保持 503 |
| MCP 工具面 401 | Token 已配但头不一致 | 用 `Authorization: Bearer <token>` 或 `X-MCP-Server-Token` |
| Console 页面能开但接口 401 | 检查 WebAuth vs MCP 两套域 | 对照 §3.3 / §3.4 的 Token 是否配齐 |
| agent-manager 启动失败 | Controller/Matrix/Task FS 不可达；或 MANAGER_TELEMETRY 与 AGENTLOOP_EXPORTER 冲突 | `docker logs agent-manager 2>&1 \| tail -100` |
| AgentTeams Worker 不启动 | 资源未申报；task FS 前缀不匹配 | 重新执行 `agentteams-apply.sh` |
| 审批接口 400/401 | 签名密钥不一致或时间漂移 15min+ | 检查 `APPROVAL_SIGNING_SECRET` 一致性 + NTP |

---

## 7. 附录：Token 生成参考

Ubuntu 上生成符合长度要求的随机 Token：

```bash
# ≥16 字符 (控制面 Token)
openssl rand -hex 16   # 32 字符 hex
openssl rand -base64 24 | tr -d '=/+' | head -c 24

# ≥32 字符 (审批签名密钥)
openssl rand -hex 32   # 64 字符 hex
```

---

## 8. 待确认项 (部署前请补齐)

以下为本次部署前必须由用户/运维侧提供的信息清单 (对应第 3 节)：

- [ ] 3.1 基础服务 (PG/Redis/MinIO) 连接信息与凭据
- [ ] 3.2 LLM 网关 (Higress) URL 与 Token (Nest 侧 + Runtime 侧各一套)
- [ ] 3.3 MCP 工具面 URL 与 Token
- [ ] 3.4 各服务内部鉴权 Token (或由部署方生成后登记)
- [ ] 3.5 AgentTeams 平台 (Controller + Matrix + Task FS) 对接信息
- [ ] 3.6 AgentTeams Human/Leader/Manager 身份清单
- [ ] 3.7 可观测性 OTLP 接入参数 (可选但生产推荐)
- [ ] 3.8 YunFlow 集成参数 (若启用 P3/P3B 导入)
- [ ] 3.9 HTTPS 反代方案与域名证书

---

## 9. 原生部署 (Ubuntu systemd / 无 Docker)

> 本节对应模式 C：**完全不用 Docker**，四件套 + Console 全部以 Node/Java/Nginx 原生进程托管到 systemd。所有服务直接监听 `127.0.0.1`，由 Nginx 统一对外暴露 + 反代。

### 9.1 与 Compose 模式的核心差异

| 维度 | Docker Compose | 原生 systemd |
|---|---|---|
| 进程隔离 | 容器 (PID/UTS/Net NS) | 普通用户进程 agentteams |
| 内部网络 | `chatflows` + `agentteams-net` external | 全走 `127.0.0.1` 回环 |
| 主机名 | `postgres` `agent-core` `agent-manager` 等 DNS | 统一 `127.0.0.1` + 不同端口 |
| 构建 | Dockerfile 多阶段 build | 本机 npm/mvn build → target/dist / dist/ |
| 启动编排 | `depends_on` + healthcheck | systemd `After=` + 启动脚本内轮询健康 |
| 日志 | `docker logs` | `journalctl -u agentteams-*` |
| 静态托管 | agent-console 容器内 nginx | 主机系统级 Nginx 统一托管 |
| MinIO/PG | 可随栈起容器 | 必须外部独立部署或本机 apt 安装 |

原生部署的端口分配（全部只绑 `127.0.0.1`，Nginx 对外）：

| 服务 | systemd 单元 | 端口 | 说明 |
|---|---|---|---|
| PostgreSQL (外部) | — | 5432 | 本机或远端 |
| Redis (外部) | — | 6379 | 本机或远端 |
| MinIO (外部) | — | 9000 | 本机或远端 |
| agent-runtime | `agentteams-agent-runtime.service` | 8088 | Java |
| agent-core (MCP 生产面) | `agentteams-agent-core-mcp.service` | 3100 | Node: dist/main-mcp.js |
| agent-core (Web/BFF) | `agentteams-agent-core-bff.service` | 3101 | Node: dist/main-web.js |
| agent-manager | `agentteams-agent-manager.service` | 8090 | Java |
| Nginx (Console + 反代) | `nginx.service` | 80 / 443 | 静态 + /api + /orchestration + /runtime + /mcp-servers |

### 9.2 代码拉取与目录布局

```bash
# 部署目录 (属主 agentteams)
sudo -u agentteams git clone <VibeSales 仓库地址> /opt/agentteams/VibeSales
cd /opt/agentteams/VibeSales

# 确认子仓完整
ls -la agent-core/dist agent-runtime/target agent-manager/target agent-console/dist 2>&1 || true
```

运行时目录约定：

```text
/opt/agentteams/
├── VibeSales/                    # 代码 + 构建产物
│   ├── agent-core/               # 构建后 dist/
│   ├── agent-runtime/            # 构建后 target/dist/
│   ├── agent-manager/            # 构建后 target/dist/
│   ├── agent-console/            # 构建后 dist/
│   ├── catalogs/ prompts/ flows/ # 资产目录 (agent-core CHATFLOWS_ROOT 指向此)
│   └── agentteams-resources/ worker-packages/ scripts/
├── logs/                         # 与 LOG_DIR 对应
├── orchestrator-state/           # manager ORCHESTRATOR_STATE_HOME
└── runtime-workspace/            # runtime AGENTSCOPE_WORKSPACE

/etc/agentteams/                   # EnvironmentFile 目录 (600)
├── agent-core.env                # agent-core 两个进程共用 (内容一致)
├── agent-runtime.env
└── agent-manager.env
```

```bash
sudo mkdir -p /etc/agentteams
sudo chown root:agentteams /etc/agentteams
sudo chmod 750 /etc/agentteams

sudo mkdir -p /opt/agentteams/logs /opt/agentteams/orchestrator-state /opt/agentteams/runtime-workspace
sudo chown agentteams:agentteams /opt/agentteams/logs /opt/agentteams/orchestrator-state /opt/agentteams/runtime-workspace
```

### 9.3 环境变量文件拆分

`prod-deploy/integration.env` 按服务拆成 3 份写到 `/etc/agentteams/`。每个文件权限 `root:agentteams 640`。  
当前仓库中的 `prod-deploy/{integration,agent-core,agent-runtime,agent-manager}.env.example` 已与生产真源字段对齐，推荐优先从这些模板生成。

#### 9.3.1 /etc/agentteams/agent-core.env (MCP 面 + BFF 面共用)

```bash
cat <<'EOF' | sudo tee /etc/agentteams/agent-core.env >/dev/null
# ---- 监听 ----
WEB_HOST=127.0.0.1
# 注意：MCP 面读 WEB_PORT=3100，BFF 面读 WEB_PORT=3101，分别在 systemd unit 的 Environment= 覆盖

# ---- 资产根 ----
CHATFLOWS_ROOT=/opt/agentteams/VibeSales

# ---- 存储 ----
ARTIFACT_STORE=postgres
DATABASE_URL=postgresql://chatflows_app_login:<CHATFLOWS_APP_DB_PASSWORD>@127.0.0.1:5432/chatflows
DATABASE_POOL_SIZE=10
DATABASE_SSL=0
MINIO_ENDPOINT=http://127.0.0.1:9000
MINIO_ACCESS_KEY=<MINIO_ACCESS_KEY>
MINIO_SECRET_KEY=<MINIO_SECRET_KEY>
MINIO_BUCKET=chatflows-artifacts

# ---- LLM 网关 (生产必须留空 DASHSCOPE_API_KEY) ----
DASHSCOPE_API_KEY=
QWEN_GATEWAY_TOKEN=<NEST_LLM_TOKEN>
QWEN_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
QWEN_MODEL=qwen3.7-plus

# ---- 控制面与鉴权 (全部 ≥16；APPROVAL ≥32) ----
MCP_SERVER_TOKEN=<MCP_SERVER_TOKEN>
PIPELINE_CONTROL_TOKEN=<PIPELINE_CONTROL_TOKEN>
BLUEPRINT_ADMIN_TOKEN=<BLUEPRINT_ADMIN_TOKEN>
PIPELINE_APPROVAL_SIGNING_SECRET=<APPROVAL_SIGNING_SECRET>
# P3C Business MCP URL
P3C_BUSINESS_MCP_URL=https://<HIGRESS_HOST>/mcp-servers/chatflows-p3c

# ---- BFF/Web 仅 BFF 进程需要，MCP 面进程读了也无害 ----
WEB_AUTH_TOKEN=<WEB_AUTH_TOKEN>
WEB_AUTH_CLIENT_CODE=<WEB_AUTH_CLIENT_CODE>
ORCHESTRATION_MODE=platform

# ---- 下游 ----
AGENT_RUNTIME_URL=http://127.0.0.1:8088
AGENT_RUNTIME_TOKEN=<RUNTIME_AUTH_TOKEN>

# ---- YunFlow (可选) ----
FLOW_PLATFORM_MODE=local
# YUNFLOW_BASE_URL=
# YUNFLOW_TOKEN=
# YUNFLOW_SPACE_ID=
# YUNFLOW_DRY_RUN_PATH=/api/workflow/{workflow_id}/run

# ---- 可观测 ----
AGENTLOOP_EXPORTER=off
AGENTLOOP_SAMPLE_RATE=1.0
AGENTLOOP_PROTOCOL=otlp
# OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=
# OTEL_RESOURCE_ATTRIBUTES=service.version=v0.1.0,...
# ARMS_LICENSE_KEY=
# OTEL_EXPORTER_OTLP_HEADERS=
LOG_STDERR=on
LOG_FILE=on
LOG_DIR=/opt/agentteams/logs
EOF
sudo chmod 640 /etc/agentteams/agent-core.env
sudo chown root:agentteams /etc/agentteams/agent-core.env
```

#### 9.3.2 /etc/agentteams/agent-runtime.env

```bash
cat <<'EOF' | sudo tee /etc/agentteams/agent-runtime.env >/dev/null
RUNTIME_MODE=production
RUNTIME_HOST=127.0.0.1
RUNTIME_PORT=8088
RUNTIME_AUTH_TOKEN=<RUNTIME_AUTH_TOKEN>
RUNTIME_ADMIN_TOKEN=<RUNTIME_ADMIN_TOKEN>
RUNTIME_MODEL=dashscope:qwen-plus
RUNTIME_LLM_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
RUNTIME_LLM_TOKEN=<RUNTIME_LLM_TOKEN>
AGENTSCOPE_WORKSPACE=/opt/agentteams/runtime-workspace
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/chatflows
DATABASE_USER=agent_runtime_login
DATABASE_PASSWORD=<AGENT_RUNTIME_DB_PASSWORD>
REDIS_URL=redis://127.0.0.1:6379/0
RUNTIME_MCP_URL=https://<HIGRESS_HOST>/mcp-servers/business-tools
RUNTIME_MCP_TOKEN=<RUNTIME_MCP_TOKEN>
BLUEPRINT_ADMIN_URL=http://127.0.0.1:3100
BLUEPRINT_ADMIN_TOKEN=<BLUEPRINT_ADMIN_TOKEN>
# ---- 可观测 ----
AGENTLOOP_EXPORTER=off
AGENTLOOP_SAMPLE_RATE=1.0
AGENTLOOP_PROTOCOL=otlp
OTEL_SERVICE_NAME=vibe-sales-runtime
# OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=
# OTEL_RESOURCE_ATTRIBUTES=...
# ARMS_LICENSE_KEY=
# OTEL_EXPORTER_OTLP_HEADERS=
# AGENTLOOP_ENDPOINT=
# AGENTLOOP_ACCESS_KEY=
# AGENTLOOP_ACCESS_SECRET=
EOF
sudo chmod 640 /etc/agentteams/agent-runtime.env
sudo chown root:agentteams /etc/agentteams/agent-runtime.env
```

#### 9.3.3 /etc/agentteams/agent-manager.env

```bash
cat <<'EOF' | sudo tee /etc/agentteams/agent-manager.env >/dev/null
# ---- AgentTeams 平台 ----
AGENTTEAMS_CONTROLLER_URL=http://<内网地址>:18090
AGENTTEAMS_AUTH_TOKEN=<AGENTTEAMS_AUTH_TOKEN>
AGENTTEAMS_MATRIX_URL=http://<内网地址>:18080
AGENTTEAMS_MATRIX_USER_ID=@chatflows-manager-ext:matrix-local.agentteams.io:18080
AGENTTEAMS_MATRIX_PASSWORD=<AGENTTEAMS_MATRIX_PASSWORD>
# AGENTTEAMS_MATRIX_ACCESS_TOKEN=<AGENTTEAMS_MATRIX_ACCESS_TOKEN>   # 可选；当前生产推荐使用 USER_ID + PASSWORD
# ---- 任务文件系统 ----
CHATFLOWS_TASK_FS_ENDPOINT=http://<内网地址>:19000
CHATFLOWS_TASK_FS_ACCESS_KEY=<CHATFLOWS_TASK_FS_ACCESS_KEY>
CHATFLOWS_TASK_FS_SECRET_KEY=<CHATFLOWS_TASK_FS_SECRET_KEY>
CHATFLOWS_TASK_FS_BUCKET=agentteams-storage
CHATFLOWS_TASK_FS_PREFIX=teams/chatflows-build-team/shared/tasks
# ---- 审批 ----
CHATFLOWS_APPROVAL_SIGNING_SECRET=<APPROVAL_SIGNING_SECRET>
# ---- 身份 ----
AGENTTEAMS_HUMAN_IDS=@admin:matrix-local.agentteams.io:18080
AGENTTEAMS_LEADER_IDS=@chatflows-leader:matrix-local.agentteams.io:18080
AGENTTEAMS_MANAGER_IDS=@chatflows-manager-ext:matrix-local.agentteams.io:18080
AGENTTEAMS_LEADER_ROOM_ID=<AGENTTEAMS_LEADER_ROOM_ID>
AGENTTEAMS_RUN_TIMEOUT_SECONDS=3600
AGENTTEAMS_TEAM_NAME=chatflows-build-team
AGENTTEAMS_LEADER_NAME=chatflows-leader
AGENTTEAMS_TEAM_FILE=/opt/agentteams/VibeSales/agentteams-resources/team.yaml
# ---- 下游 ----
CHATFLOWS_NEST_URL=http://127.0.0.1:3100
PIPELINE_CONTROL_TOKEN=<PIPELINE_CONTROL_TOKEN>
# ---- HTTP ----
MANAGER_AUTH_TOKEN=<MANAGER_AUTH_TOKEN>
MANAGER_ADMIN_TOKEN=<MANAGER_ADMIN_TOKEN>
MANAGER_HOST=127.0.0.1
MANAGER_PORT=8090
# ---- Orchestrator LLM ----
ORCHESTRATOR_LLM=off
# ORCHESTRATOR_MODEL=dashscope:qwen-plus
# ORCHESTRATOR_LLM_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
# HIGRESS_CONSUMER_TOKEN=
# ---- 状态 ----
ORCHESTRATOR_STATE_HOME=/opt/agentteams/orchestrator-state
# ---- 可观测 ----
AGENTLOOP_EXPORTER=off
AGENTLOOP_SAMPLE_RATE=1.0
AGENTLOOP_PROTOCOL=otlp
OTEL_SERVICE_NAME=vibe-sales-manager
# OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=
# OTEL_RESOURCE_ATTRIBUTES=...
# ARMS_LICENSE_KEY=
# OTEL_EXPORTER_OTLP_HEADERS=
# MANAGER_TELEMETRY=otlp   # 与 AGENTLOOP_EXPORTER=on 互斥
EOF
sudo chmod 640 /etc/agentteams/agent-manager.env
sudo chown root:agentteams /etc/agentteams/agent-manager.env
```

### 9.4 构建四件套 (原生 build)

> 全部以 `agentteams` 用户执行，产物落在各自目录内。

#### 9.4.1 agent-core (Node)

```bash
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-core
npm ci --no-audit --no-fund 2>&1 | tail -20
npm run build 2>&1 | tail -20
ls dist/main-mcp.js dist/main-web.js dist/main.js
'
```

构建完成后 `dist/` 下三份入口文件同时存在，分别启动即可。

#### 9.4.2 agent-runtime (Java 17)

```bash
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-runtime
# 首次构建需要联网下载依赖；build-dist.sh 默认 -o 离线，第一次去掉 -o
mvn -q clean compile dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dist/lib 2>&1 | tail -30
mkdir -p target/dist/classes target/dist/workspace
cp -R target/classes/. target/dist/classes/
[ -d workspace ] && cp -R workspace/. target/dist/workspace/ 2>/dev/null || true
test -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class && echo "agent-runtime build OK"
'
```

> 说明：`build-dist.sh` 脚本默认 `mvn -o` (offline)。首次构建前可先执行一次 `mvn dependency:go-offline` 预热本地仓库，或按上面去掉 `-o`。

#### 9.4.3 agent-manager (Java 17)

```bash
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-manager
mvn -q clean compile dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dist/lib 2>&1 | tail -30
mkdir -p target/dist/classes
cp -R target/classes/. target/dist/classes/
test -f target/dist/classes/com/yjiyun/chatflows/manager/ManagerApplication.class && echo "agent-manager build OK"
'
```

#### 9.4.4 agent-console (Vue → 静态 dist/)

```bash
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-console
npm ci --no-audit --no-fund 2>&1 | tail -20
ORCHESTRATION_MODE=platform npm run build 2>&1 | tail -20
ls dist/index.html
'
```

### 9.5 初始化步骤 (原生)

#### 9.5.1 MinIO Bucket 初始化

用 `mc` 或 curl 在外部 MinIO 创建业务桶（若已有则跳过）：

```bash
# 安装 minio client (若没有)
which mc >/dev/null 2>&1 || (curl -sSL https://dl.min.io/client/mc/release/linux-amd64/mc -o /usr/local/bin/mc && chmod +x /usr/local/bin/mc)

mc alias set local http://127.0.0.1:9000 <MINIO_ACCESS_KEY> <MINIO_SECRET_KEY>
mc ls local/chatflows-artifacts >/dev/null 2>&1 || mc mb local/chatflows-artifacts
```

#### 9.5.2 agent-core 数据库初始化

需要三个**一次性管理员变量**（≠ 普通业务连接）：

```bash
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-core
export DATABASE_ADMIN_URL="postgresql://postgres:<POSTGRES_ADMIN_PASSWORD>@127.0.0.1:5432/chatflows"
export CHATFLOWS_APP_DB_PASSWORD="<CHATFLOWS_APP_DB_PASSWORD>"
export AGENT_RUNTIME_DB_PASSWORD="<AGENT_RUNTIME_DB_PASSWORD>"
npm run db:init 2>&1 | tail -30
'
```

成功应包含：5 张表创建、2 个 login 创建、RLS/触发器/策略生效。

### 9.6 systemd unit 文件

每个服务一个独立 unit。`User=agentteams`、`Group=agentteams`、`Restart=always`。以下文件均放到 `/etc/systemd/system/`。

#### 9.6.1 agentteams-agent-runtime.service (先启动)

```ini
# /etc/systemd/system/agentteams-agent-runtime.service
[Unit]
Description=VibeSales agent-runtime (AgentScope Java)
After=network.target postgresql.service redis-server.service
Wants=network.target postgresql.service redis-server.service

[Service]
Type=simple
User=agentteams
Group=agentteams
WorkingDirectory=/opt/agentteams/VibeSales/agent-runtime
EnvironmentFile=/etc/agentteams/agent-runtime.env
# 定位 Java 17+；也可直接写 /usr/lib/jvm/java-17-openjdk-amd64/bin/java
ExecStart=/bin/sh -lc 'exec $(dirname $(readlink -f $(which java)))/java -cp "target/dist/classes:target/dist/lib/*" com.yjiyun.chatflows.runtime.RuntimeApplication'
Restart=always
RestartSec=5
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal
SyslogIdentifier=agentteams-runtime

[Install]
WantedBy=multi-user.target
```

#### 9.6.2 agentteams-agent-core-mcp.service (MCP 生产面 3100)

```ini
# /etc/systemd/system/agentteams-agent-core-mcp.service
[Unit]
Description=VibeSales agent-core MCP production plane (main-mcp)
After=network.target agentteams-agent-runtime.service
Requires=agentteams-agent-runtime.service

[Service]
Type=simple
User=agentteams
Group=agentteams
WorkingDirectory=/opt/agentteams/VibeSales/agent-core
EnvironmentFile=/etc/agentteams/agent-core.env
Environment=WEB_PORT=3100
Environment=OTEL_SERVICE_NAME=vibe-sales-nest-mcp
# 健康：等 runtime 起来再启；agent-core 自己 healthz 503 会触发重启
ExecStart=/usr/bin/npm run start:prod
Restart=always
RestartSec=5
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal
SyslogIdentifier=agentteams-core-mcp

[Install]
WantedBy=multi-user.target
```

#### 9.6.3 agentteams-agent-core-bff.service (Web/BFF 3101)

```ini
# /etc/systemd/system/agentteams-agent-core-bff.service
[Unit]
Description=VibeSales agent-core BFF / DEMO Web (main-web)
After=network.target agentteams-agent-runtime.service agentteams-agent-core-mcp.service
Requires=agentteams-agent-runtime.service

[Service]
Type=simple
User=agentteams
Group=agentteams
WorkingDirectory=/opt/agentteams/VibeSales/agent-core
EnvironmentFile=/etc/agentteams/agent-core.env
Environment=WEB_PORT=3101
Environment=WEB_STATIC_ROOT=/opt/agentteams/VibeSales/agent-console/dist
Environment=OTEL_SERVICE_NAME=vibe-sales-nest-bff
ExecStart=/usr/bin/npm run start:web
Restart=always
RestartSec=5
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal
SyslogIdentifier=agentteams-core-bff

[Install]
WantedBy=multi-user.target
```

#### 9.6.4 agentteams-agent-manager.service (编排 serve 8090)

```ini
# /etc/systemd/system/agentteams-agent-manager.service
[Unit]
Description=VibeSales agent-manager (Orchestrator Java)
After=network.target agentteams-agent-core-mcp.service agentteams-agent-core-bff.service
Requires=agentteams-agent-core-mcp.service agentteams-agent-core-bff.service

[Service]
Type=simple
User=agentteams
Group=agentteams
WorkingDirectory=/opt/agentteams/VibeSales/agent-manager
EnvironmentFile=/etc/agentteams/agent-manager.env
# ManagerApplication main 支持 args: serve | check | run ...
ExecStart=/bin/sh -lc 'exec $(dirname $(readlink -f $(which java)))/java -cp "target/dist/classes:target/dist/lib/*" com.yjiyun.chatflows.manager.ManagerApplication serve'
Restart=always
RestartSec=5
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal
SyslogIdentifier=agentteams-manager

[Install]
WantedBy=multi-user.target
```

写入后生效：

```bash
sudo systemctl daemon-reload
sudo systemctl enable \
  agentteams-agent-runtime \
  agentteams-agent-core-mcp \
  agentteams-agent-core-bff \
  agentteams-agent-manager
```

### 9.7 Nginx 统一配置 (Console 静态 + 反代)

创建 `/etc/nginx/sites-available/agentteams`：

```nginx
# /etc/nginx/sites-available/agentteams
# 同时托管 agent-console 静态 + 把四类请求反代到各自后端。
# 生产请在前面再加 TLS Termination (Let's Encrypt / Higress / ALB)。

server {
    listen 80;
    server_name agentteams.example.com;   # 改实际域名

    # ---------- 日志 ----------
    access_log /var/log/nginx/agentteams.access.log;
    error_log  /var/log/nginx/agentteams.error.log;
    client_max_body_size 100m;           # Blueprint zip/artifact 上传

    # ---------- agent-console 静态 (npm run build 产物) ----------
    root /opt/agentteams/VibeSales/agent-console/dist;
    index index.html;

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # ---------- BFF / Web 向导面 (WebAuth 域) ----------
    # agent-core-bff:3101  main-web.ts 监听
    location /api/ {
        proxy_pass         http://127.0.0.1:3101;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # ---------- 编排管理面 (MANAGER_AUTH) ----------
    # agent-manager:8090  前缀 /orchestration/ 重写后再传
    location /orchestration/ {
        rewrite ^/orchestration/(.*)$ /$1 break;
        proxy_pass         http://127.0.0.1:8090;
        proxy_http_version 1.1;
        proxy_buffering    off;                 # SSE / 长轮询
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # ---------- Runtime 面 (RUNTIME_AUTH) ----------
    # agent-runtime:8088  前缀 /runtime/ 重写
    location /runtime/ {
        rewrite ^/runtime/(.*)$ /$1 break;
        proxy_pass         http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_buffering    off;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # ---------- MCP 工具面 (MCP_SERVER_TOKEN, McpAuthGuard) ----------
    # agent-core-mcp:3100  main-mcp.ts 监听；生产建议独立域名只暴露这块
    location /mcp-servers/ {
        proxy_pass         http://127.0.0.1:3100;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # ---------- Pipeline + Blueprint 控制面 (agent-core-mcp:3100) ----------
    location /api/v1/pipeline/ {
        proxy_pass         http://127.0.0.1:3100;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
    }
    location /api/v1/blueprints/ {
        proxy_pass         http://127.0.0.1:3100;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
    }

    # ---------- 健康探针 (暴露给 LB/NLB) ----------
    location = /healthz {
        access_log off;
        # 串联检查核心面；单一挂整体标非健康
        content_by_lua_block {
            local http = require "resty.http"
            -- 简单点：直接代理 MCP 面的 /healthz
            ngx.exec("@core_mcp_healthz")
        }
    }
    location @core_mcp_healthz {
        internal;
        proxy_pass http://127.0.0.1:3100/healthz;
    }
}
```

启用：

```bash
sudo ln -sf /etc/nginx/sites-available/agentteams /etc/nginx/sites-enabled/agentteams
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

### 9.8 按顺序启动

```bash
# Step 1: runtime
sudo systemctl start agentteams-agent-runtime
sleep 10
sudo systemctl is-active agentteams-agent-runtime   # 输出 active
journalctl -u agentteams-agent-runtime --no-pager -n 20

# Step 2: agent-core MCP 面 (依赖 runtime)
sudo systemctl start agentteams-agent-core-mcp
sleep 10
curl -s http://127.0.0.1:3100/healthz | jq
# dependencies.postgres/minio/qwen_gateway/agent_runtime 全 OK

# Step 3: agent-core BFF 面
sudo systemctl start agentteams-agent-core-bff
sleep 8
# BFF 无公共 healthz，用 /api/health (需 WebAuth)
WEB_AUTH_TOKEN=$(sudo grep ^WEB_AUTH_TOKEN= /etc/agentteams/agent-core.env | cut -d= -f2)
curl -sSf -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $WEB_AUTH_TOKEN" \
  -H "X-Role: user" -H "X-Actor: deploy-probe" \
  http://127.0.0.1:3101/api/health
# 期望 200

# Step 4: manager
sudo systemctl start agentteams-agent-manager
sleep 15
MANAGER_AUTH_TOKEN=$(sudo grep ^MANAGER_AUTH_TOKEN= /etc/agentteams/agent-manager.env | cut -d= -f2)
curl -s -H "Authorization: Bearer $MANAGER_AUTH_TOKEN" \
  http://127.0.0.1:8090/api/v1/health | jq

# Step 5: 全部开机自启
sudo systemctl enable agentteams-agent-runtime agentteams-agent-core-mcp agentteams-agent-core-bff agentteams-agent-manager
```

### 9.9 部署后申报 AgentTeams 资源

与 Compose 模式一样，但直接跑 Node 脚本（不通过容器）：

```bash
cd /opt/agentteams/VibeSales
sudo -u agentteams env \
  CHATFLOWS_MCP_BASE_URL=https://<HIGRESS_BASE_URL> \
  HIGRESS_CONSUMER_TOKEN=<HIGRESS_CONSUMER_TOKEN> \
  bash ./agentteams-apply.sh
```

### 9.10 原生验证清单

```bash
# 1. systemd 整体状态
systemctl status agentteams-agent-{runtime,core-mcp,core-bff,manager} --no-pager

# 2. 各面健康 (与 9.8 相同)
curl -s http://127.0.0.1:3100/healthz | jq
curl -s -H "Authorization: Bearer $MANAGER_AUTH_TOKEN" http://127.0.0.1:8090/api/v1/health | jq
curl -sSf -o /dev/null -w "bff:%{http_code}\n" \
  -H "Authorization: Bearer $WEB_AUTH_TOKEN" -H "X-Role: user" -H "X-Actor: check" \
  http://127.0.0.1:3101/api/health

# 3. 契约测试 (进入 agent-core)
cd /opt/agentteams/VibeSales/agent-core
sudo -u agentteams npm run test:postgres-contract 2>&1 | tail -20
sudo -u agentteams npm run test:mcp-production  2>&1 | tail -20
sudo -u agentteams npm run test:control         2>&1 | tail -20
sudo -u agentteams npm run test:qwen-gateway    2>&1 | tail -20

# 4. 日志 (journalctl)
journalctl -u agentteams-agent-runtime --since "10 min ago" --no-pager
journalctl -u agentteams-agent-core-mcp  -f    # 实时跟随
journalctl -u agentteams-agent-manager  -n 50 --no-pager

# 5. agent-core 自身文件日志 (LOG_DIR=/opt/agentteams/logs)
sudo -u agentteams ls -la /opt/agentteams/logs
sudo tail -n 50 /opt/agentteams/logs/app.log
sudo tail -n 50 /opt/agentteams/logs/token.log
```

### 9.11 原生常见问题

| 现象 | 排查点 |
|---|---|
| `npm run start:prod` 启动就退出 | 检查 env 中 `APPROVAL_SIGNING_SECRET ≥32`、各控制 Token ≥16；`journalctl -u agentteams-agent-core-mcp` |
| healthz 503 指明 `dependencies.agent_runtime` | 8088 是否 listen：`ss -ltnp \| grep 8088`；Runtime env 是否把 host 设成了 127.0.0.1 但 manager/bff 去连别的地址 |
| Java 启动报 class version 61/65 | JDK 版本不够，必须 17+。`update-alternatives --config java` 选 17 或在 unit 里写绝对路径 |
| Maven 构建失败 `Could not resolve dependencies` | 首次需要联网。在构建机跑一次 `mvn -U dependency:resolve` 把依赖拉到 `~/.m2/repository/` |
| manager 报 Matrix 登录失败 / Task FS 403 | `AGENTTEAMS_MATRIX_*`、`CHATFLOWS_TASK_FS_*`（注意 Access/Secret ≠ MinIO admin 那套） |
| Console 打开但接口全 401 | Nginx `/api/` 没加正确反代；或浏览器发请求没带上 `X-Role: user` / `X-Actor`；WebAuth 与 McpAuth 是两套域 |
| Blueprint 写入后 Runtime 拉不到 | `BLUEPRINT_ADMIN_URL=http://127.0.0.1:3100` + `BLUEPRINT_ADMIN_TOKEN` 与 agent-core 侧一致；两者必须同字符串 |
| 审批签名失败 401 | `APPROVAL_SIGNING_SECRET` 在 manager.env 叫 `CHATFLOWS_APPROVAL_SIGNING_SECRET`，在 agent-core.env 叫 `PIPELINE_APPROVAL_SIGNING_SECRET`，二者必须同一个值 |

### 9.12 一键启停 (可选)

写成运维友好的 `/usr/local/sbin/agentteams`：

```bash
#!/usr/bin/env bash
set -euo pipefail
UNITS=(agentteams-agent-runtime agentteams-agent-core-mcp agentteams-agent-core-bff agentteams-agent-manager)
case "${1:-}" in
  start|stop|restart|status|enable|disable)
    exec sudo systemctl "$1" "${UNITS[@]}" ;;
  logs)
    shift || true
    exec journalctl -u agentteams-agent-runtime \
                    -u agentteams-agent-core-mcp \
                    -u agentteams-agent-core-bff \
                    -u agentteams-agent-manager -f "$@" ;;
  healthz)
    set -x
    curl -s http://127.0.0.1:3100/healthz | jq .status
    MANAGER_AUTH_TOKEN=$(sudo grep ^MANAGER_AUTH_TOKEN= /etc/agentteams/agent-manager.env | cut -d= -f2)
    curl -sH "Authorization: Bearer $MANAGER_AUTH_TOKEN" http://127.0.0.1:8090/api/v1/health | jq
    WEB_AUTH_TOKEN=$(sudo grep ^WEB_AUTH_TOKEN= /etc/agentteams/agent-core.env | cut -d= -f2)
    curl -sSo /dev/null -w "bff:%{http_code}\n" \
      -H "Authorization: Bearer $WEB_AUTH_TOKEN" -H "X-Role:user" -H "X-Actor:cli" \
      http://127.0.0.1:3101/api/health
    ;;
  *)
    echo "usage: $0 {start|stop|restart|status|enable|disable|logs [--since 1h]|healthz}" >&2; exit 2 ;;
esac
```

```bash
sudo install -m 0755 /dev/stdin /usr/local/sbin/agentteams <<'EOF'
# 粘贴上面脚本内容
EOF
```

---

## 10. 混合部署实操指南 (Docker 已装 + 外部 PostgreSQL/Redis)

> **适用前提**：目标机 **已预装 Docker（含 Compose v2）**，**已预装 PostgreSQL 16+**（本机 apt / 或远端），**已预装 Redis 7+**（本机 apt / 或远端）。
> 本模式 = 模式 B。业务四件套 + agent-console 走 Docker Compose 容器；PG/Redis 复用宿主机已安装实例；MinIO 可选 Docker 起一个桶容器或复用外部 S3/MinIO。

### 10.1 现状核对表 (在目标机先跑一遍)

```bash
# ---- Docker / Compose ----
docker --version      # >= 24
docker compose version   # v2
docker network ls    # 若无 agentteams-net，下文 §10.3 会创建

# ---- PostgreSQL (本机/远端二选一) ----
# 例：本机 apt 安装的 PG
pg_isready -h 127.0.0.1 -p 5432 -U postgres
# 能连上再问下版本
psql -h 127.0.0.1 -U postgres -c "SHOW server_version;"   # 期望 16.x
# 目标库 chatflows 可未创建，db-init 容器会以 DATABASE_ADMIN_URL 去建表

# ---- Redis ----
redis-cli -h 127.0.0.1 -p 6379 ping     # 期望 PONG；有密码则 -a <pass> --no-auth-warning
redis-server --version                   # 期望 7.x

# ---- 如果 MinIO 也要复用外部 ----
curl -I http://<MINIO_HOST>:9000/minio/health/ready
```

若以上任一项为 NO，要么先在目标机 apt 安装补齐，要么填到远端主机地址。

### 10.2 必须调整的 compose.yaml 依赖

原 [compose.yaml](file:///d:/Codes/yjy/AI-contest/github/VibeSales/deploy/agentteams/compose.yaml) 把 `postgres`、`redis` 定义为 service，且 `db-init` / `agent-runtime` / `verifier` 硬 `depends_on` 到这两个 service。**外部化后必须做两处调整**（先不改代码，先在下面方案里选 A 或 B 执行，再改文件）：

| 调整点 | 原 compose.yaml 行为 | 外部化后应做 |
|---|---|---|
| A. 去掉 `postgres:` / `redis:` 两个 service | 随 compose 起容器 | 删除/注释掉 (§10.4 给出 patch) |
| B. `depends_on: postgres: ...` / `redis: ...` | 等容器 healthy | 换成脚本里的 `pg_isready` / `redis-cli ping` 外部健康探测；或保留 depends_on 但替换为 external service 占位 |
| C. 所有 `DATABASE_URL` / `DATABASE_ADMIN_URL` 里的主机名 `postgres` | Docker 内 DNS → Compose postgres service | 替换成 **容器能触达的 PG 地址**（`host.docker.internal` / Docker 网关 / 私网 IP / 172.17.0.1） |
| D. `REDIS_URL=redis://redis:6379/0` 里的主机名 `redis` | 同上 | 同上替换成可达地址 |
| E. `agentteams` 网络 | external: true, name=agentteams-net | 必须先 `docker network create agentteams-net` |

**容器访问宿主机的三种写法**（Ubuntu 默认 bridge 网络）：

| 写法 | 可用性 | 推荐场景 |
|---|---|---|
| `host.docker.internal:5432` (需在 service 加 `extra_hosts:["host.docker.internal:host-gateway"]`) | Docker 20+，需 compose 加字段 | 跨平台最稳 |
| `172.17.0.1:5432` (docker0 网桥默认网关) | 默认 bridge；非默认网桥无效 | 简单快速 |
| 宿主机**物理网卡私网 IP** (如 `10.1.1.97:5432`) | 最可靠，跨所有网络 | ⭐ 推荐生产；但 PG/Redis 需 listen_addresses 对外放行 |

下文默认采用**物理网卡私网 IP** 方案（`HOST_PRIVATE_IP` 变量，下文替换），最稳。

### 10.3 预创建 + 连通性验证

```bash
# 1) external 网络（compose.yaml 里 agentteams 网络声明 external，必须先有）
docker network create agentteams-net 2>/dev/null || true
docker network ls | grep agentteams-net

# 2) 拿到目标机在 Docker 容器可达的私网 IP（容器里要连回宿主机 PG/Redis 用）
#    任选一个非 127.0.0.1 的 IP，且容器内 `nc -zv $HOST_PRIVATE_IP 5432` 通。
HOST_PRIVATE_IP=$(hostname -I | awk '{print $1}')
echo "宿主机容器可达 IP = $HOST_PRIVATE_IP"   # 例：10.1.1.97

# 3) 宿主机 PG 的 pg_hba.conf：放行 Docker 网段 (172.16/12 或容器桥 IP 段) 至少 md5
#    典型 Docker 默认桥：172.17.0.0/16。若用自定义网桥再补 172.x 对应段
sudo grep -n "host all all 172" /etc/postgresql/*/main/pg_hba.conf || \
  echo "需在 pg_hba.conf 追加:  host all all 172.16.0.0/12 md5"
# 然后 sudo systemctl reload postgresql

# 4) 宿主机 PG listen_addresses = '*'（或至少包含 HOST_PRIVATE_IP）
sudo grep -n listen_addresses /etc/postgresql/*/main/postgresql.conf
# 改完 sudo systemctl restart postgresql

# 5) 宿主机 Redis 放行 bind + requirepass
sudo grep -nE "^bind|^requirepass|^protected-mode" /etc/redis/redis.conf
#   bind 至少加 $HOST_PRIVATE_IP；protected-mode yes 时必须配 requirepass
#   改完 sudo systemctl restart redis-server

# 6) 从一个临时容器验证容器 → 宿主机 PG/Redis 真的通
docker run --rm --network agentteams-net alpine/sh -lc "
    apk add -q postgresql-client redis;
    echo '== PG ==';
    psql \"postgresql://postgres:<POSTGRES_ADMIN_PASSWORD>@${HOST_PRIVATE_IP}:5432/postgres\" -c 'SELECT 1;';
    echo '== Redis ==';
    redis-cli -h ${HOST_PRIVATE_IP} -p 6379${REDIS_PASS:+ -a $REDIS_PASS} ping
"
```

### 10.4 compose.yaml 外部化 patch (两种方式，二选一)

#### 方式 A：最小改动 — 保留 postgres/redis service 定义，加 `profiles: [unused]`（推荐）
**优点**：不必改一堆 `depends_on`，Compose 不带 profile 启动时不创建 PG/Redis 容器，但 `depends_on` 的目标名仍存在会报错 → 故必须同时把 `depends_on` 指向 `service_completed_successfully` 的内容去掉；更稳见方式 B。

#### 方式 B：创建 override 文件覆盖 (⭐ 推荐，不动主 compose.yaml)
新建 `deploy/agentteams/compose.override.yaml`（Docker Compose 自动读 `compose.override.yaml`）：

```yaml
# deploy/agentteams/compose.override.yaml
# 覆盖：1) 停掉 PG/Redis 自建容器；2) 主机名 postgres/redis → HOST_PRIVATE_IP；3) 补 extra_hosts
# 使用：在执行 docker compose 前，export HOST_PRIVATE_IP=...

# ---- 停用 compose.yaml 里的 postgres / redis 两个 service ----
services:
  postgres:
    profiles: ["__unused__"]        # 不带 profile 时永远不启动
  redis:
    profiles: ["__unused__"]

  # ---- artifact-bucket-init：仍可用容器去连外部 MinIO ----
  # 无需改；MINIO_ENDPOINT 在 integration.env 里直接写外部地址即可

  # ---- db-init：把 DATABASE_ADMIN_URL 主机名从 postgres 改成 $HOST_PRIVATE_IP ----
  db-init:
    depends_on:
      postgres:
        condition: service_completed_successfully   # 触发报错的根源；下面 profiles 不会起 service，这里必须删除。
    # 注意：profiles 后 service 不存在，仍不能 depends_on 它。
    # 所以正确做法是 **删掉 db-init / agent-runtime / verifier 对 postgres / redis 的 depends_on**
    # 详见 §10.5 关于 depends_on 的处理。

  # ---- 为所有需要连回宿主机的 service 注入 host 别名 ----
  agent-runtime:
    extra_hosts:
      - "host.docker.internal:host-gateway"
    # 覆盖写死的 URL 主机名（把 env 里写死的 postgres/redis 主机名替换）
    environment:
      # DATABASE_URL 原 compose.yaml 写死了 @postgres:5432；这里用 integration.env 重新赋值覆盖
      # REDIS_URL 也一样，在 integration.env 重写即可
      <<: {}
  agent-core:
    extra_hosts:
      - "host.docker.internal:host-gateway"
  agent-core-bff:
    extra_hosts:
      - "host.docker.internal:host-gateway"
  agent-manager:
    extra_hosts:
      - "host.docker.internal:host-gateway"
  verifier:
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

> ⚠️ 关键点：**Docker Compose `profiles` 隐藏服务后，任何 service 的 `depends_on: <hidden-service>` 都会在 `docker compose up` 直接报 `service "postgres" not found for depends_on`**。所以必须把 `postgres` / `redis` 相关 `depends_on` 从 `db-init`、`agent-runtime`、`verifier` 里删掉。
>
> 要完全不手改 `compose.yaml`，最佳做法是：**另起一个最小 patch 文件 `deploy/agentteams/compose.patch.yaml` 把这些 depends_on 置空**，Compose v2 支持多个 `-f` 叠加。

#### 方式 C (推荐生产)：多个 `-f` 显式合并 (不改任何提交版文件)

创建 `deploy/agentteams/compose.external-deps.yaml`：

```yaml
# deploy/agentteams/compose.external-deps.yaml
# 配合 compose.yaml 使用：
#   docker compose --env-file integration.env \
#     -f compose.yaml -f compose.external-deps.yaml ...
# 效果：停用内建 postgres/redis service；清空对它们的 depends_on；所有业务 service 加 extra_hosts。

services:
  postgres:
    image: alpine:3.20                 # 覆盖原 postgres:16 镜像 → 用最小镜像
    entrypoint: ["sleep", "infinity"]   # 假占位：不对外 listen，但 service 名存在，让 depends_on 不爆 not found
    healthcheck: { test: ["CMD", "true"], interval: 1s, timeout: 1s, retries: 1 }
    networks: [chatflows]
    profiles: ["__placeholder__"]        # 先 profile 隐藏，下面改用 extend

  redis:
    image: alpine:3.20
    entrypoint: ["sleep", "infinity"]
    healthcheck: { test: ["CMD", "true"], interval: 1s, timeout: 1s, retries: 1 }
    networks: [chatflows]
    profiles: ["__placeholder__"]

  # ---- 为所有连库/连缓存的 service 加 host 别名 ----
  agent-runtime:
    extra_hosts: &backport_hosts
      - "host.docker.internal:host-gateway"
  agent-core:
    extra_hosts: *backport_hosts
  agent-core-bff:
    extra_hosts: *backport_hosts
  agent-manager:
    extra_hosts: *backport_hosts
  verifier:
    extra_hosts: *backport_hosts
  db-init:
    extra_hosts: *backport_hosts
  artifact-bucket-init:
    extra_hosts: *backport_hosts
```

然后在 compose.yaml 里**已经**对 `postgres` / `redis` 写了 `depends_on: ... condition: service_healthy` 时，只要这两个占位 service **真的起来 healthy 了**，depends_on 就不会报错。但我们又不想真的启动它们 → 最终做法：**在执行命令时带 `--profile __placeholder__`**，让这两个轻量占位容器一起 up，业务容器就顺利通过 depends_on。

> 这是「零修改 compose.yaml 主文件」的最省事做法。生产嫌两个占位容器丑，可在启动完成后手动 `docker rm -f $(docker ps -qf name=chatflows-agentteams_postgres|redis)`；或后续选方式 A 改主 compose.yaml 删除 depends_on。

### 10.5 integration.env 差异对照 (外部 PG/Redis)

打开 `deploy/agentteams/integration.env`，以下变量**必须改**（与 example 默认不同）：

| # | 变量 | integration.env.example 默认 (Compose 内建 PG/Redis) | 外部 PG/Redis 模式应填 |
|---|---|---|---|
| 10.5.1 | `POSTGRES_ADMIN_PASSWORD` | 空 → 容器内建 postgres 用户 | **外部 PG postgres 用户的实际密码** |
| 10.5.2 | `CHATFLOWS_APP_DB_PASSWORD` | 空 | 生成一个 URL-safe ≥16 字符串 |
| 10.5.3 | `AGENT_RUNTIME_DB_PASSWORD` | 空 | 生成另一个 URL-safe ≥16 字符串 |
| 10.5.4 | 所有 compose.yaml 写死的 `DATABASE_URL=...@postgres:5432` | `postgres` 容器名 | 把 compose.yaml 里写死的 URL 主机部分改成 `$HOST_PRIVATE_IP`，方式有二：**(1) 改 compose.yaml 直接写主机名**；**(2) 用 override 在每个 service 重新声明 environment 覆盖变量 — 但 compose.yaml 里 DATABASE_URL 直接硬编码，最直接是在方式 C 再加一组覆盖**，见下方 patch。 |
| 10.5.5 | 所有写死的 `REDIS_URL=redis://redis:6379/0` | `redis` 容器名 | 同上：改成 `redis://[:<pass>@]$HOST_PRIVATE_IP:6379/0` |
| 10.5.6 | `MINIO_ENDPOINT` | `http://agentteams-minio:9000` (AgentTeams 另一个外部桶) | **两个独立的 MinIO 端点概念**：① agent-core 的 ARTIFACT/BLUEPRINT 大对象 → 可 Docker 再起一个 MinIO 容器；② AgentTeams 的任务目录 → 填 AgentTeams 实际 FS 端点。二者不是同一个 bucket。 |

为了不改 compose.yaml 内的硬编码，继续叠加第三个 `-f` 文件 `deploy/agentteams/compose.override-endpoints.yaml`：

```yaml
# deploy/agentteams/compose.override-endpoints.yaml
# 把 compose.yaml 里硬编码的 DATABASE_URL / REDIS_URL / DATABASE_ADMIN_URL 全部
# 改成走 $HOST_PRIVATE_IP（在 env 里再设）。
# 在 docker compose 里用 `${VAR}` 读 integration.env 已经注入过的变量。
# 下面只列出含硬编码的 service；其它 env 已在 integration.env 里定义。

x-env-host-db: &env_host_db
  DATABASE_ADMIN_URL: "postgresql://postgres:${POSTGRES_ADMIN_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"
  DATABASE_URL: "postgresql://chatflows_app_login:${CHATFLOWS_APP_DB_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"
  REDIS_URL: "redis://${REDIS_PASSWORD+:}$HOST_PRIVATE_IP:6379/0"
  # 上面 REDIS_URL: 如果 Redis 有密码，写 redis://:passwd@host:6379/0

services:
  db-init:
    environment:
      <<: *env_host_db

  agent-runtime:
    environment:
      # agent-runtime 用 JDBC URL + DATABASE_USER/DATABASE_PASSWORD 分别传
      DATABASE_URL: "jdbc:postgresql://${HOST_PRIVATE_IP:?required}:5432/chatflows"
      REDIS_URL: "redis://${REDIS_PASSWORD+:}$HOST_PRIVATE_IP:6379/0"

  agent-core:
    environment:
      DATABASE_URL: "postgresql://chatflows_app_login:${CHATFLOWS_APP_DB_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"

  agent-core-bff:
    environment:
      DATABASE_URL: "postgresql://chatflows_app_login:${CHATFLOWS_APP_DB_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"

  verifier:
    environment:
      <<: *env_host_db
      DATABASE_URL: "postgresql://chatflows_app_login:${CHATFLOWS_APP_DB_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"
      AGENT_RUNTIME_DATABASE_URL: "postgresql://agent_runtime_login:${AGENT_RUNTIME_DB_PASSWORD:?required}@${HOST_PRIVATE_IP:?required}:5432/chatflows"
      REDIS_URL: "redis://${REDIS_PASSWORD+:}$HOST_PRIVATE_IP:6379/0"
```

然后在 `deploy/agentteams/integration.env` 顶部**额外加一行**：

```env
# ---------- 宿主机私网 IP：容器连接回宿主机 PG/Redis 用 ----------
HOST_PRIVATE_IP=10.1.1.97     # ← 改实际 (hostname -I 首项或私网物理 IP)
# REDIS_PASSWORD=              # ← 如果 Redis requirepass 开启，填 (不加则留空)
```

### 10.6 MinIO 两种落地方式

agent-core 的 artifact 大对象和 AgentTeams 任务目录**是两套独立桶**，不要混。

| 桶用途 | integration.env 变量集 | 推荐方案 |
|---|---|---|
| ARTIFACT (agent-core) | `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET=chatflows-artifacts` | **Docker 单独起一个 MinIO 容器 + 持久卷**（同机简单，生产用外部 S3/MinIO） |
| AgentTeams 任务目录 | `CHATFLOWS_TASK_FS_ENDPOINT` / `CHATFLOWS_TASK_FS_ACCESS_KEY` / `..._SECRET` / `..._BUCKET=agentteams-storage` | 必须填**AgentTeams 运维**给的实际端点和受限身份（≠ artifact 那套 Key） |

用 Docker 起一个一次性 ARTIFACT 桶 MinIO（挂载本地卷，和 compose.yaml 其它 service 放同一 external 网）：

```bash
MINIO_ARTIFACT_DIR=/data/agentteams/minio-artifacts
sudo mkdir -p $MINIO_ARTIFACT_DIR
export ARTIFACT_MINIO_ACCESS_KEY=$(openssl rand -hex 10)
export ARTIFACT_MINIO_SECRET_KEY=$(openssl rand -hex 20)

docker run -d --restart unless-stopped \
  --name agentteams-minio-artifacts \
  --network agentteams-net \
  --network-alias agentteams-minio \
  -p 127.0.0.1:9010:9000 -p 127.0.0.1:9011:9001 \
  -v $MINIO_ARTIFACT_DIR:/data \
  -e "MINIO_ROOT_USER=$ARTIFACT_MINIO_ACCESS_KEY" \
  -e "MINIO_ROOT_PASSWORD=$ARTIFACT_MINIO_SECRET_KEY" \
  minio/minio:RELEASE.2025-05-21T01-59-54Z-cpuv1 server /data --console-address ":9001"

# 等健康 + 建桶
sleep 8
docker run --rm --network agentteams-net minio/mc:RELEASE.2025-05-21T01-59-54Z-cpuv1 \
  sh -lc "mc alias set a http://agentteams-minio:9000 $ARTIFACT_MINIO_ACCESS_KEY $ARTIFACT_MINIO_SECRET_KEY && mc mb a/chatflows-artifacts"

# 把这三行保存下来，填入 integration.env 对应项
echo "MINIO_ENDPOINT=http://agentteams-minio:9000"
echo "MINIO_ACCESS_KEY=$ARTIFACT_MINIO_ACCESS_KEY"
echo "MINIO_SECRET_KEY=$ARTIFACT_MINIO_SECRET_KEY"
```

然后在 integration.env 里填：

```env
MINIO_ENDPOINT=http://agentteams-minio:9000
MINIO_ACCESS_KEY=<上面输出的 ARTIFACT_MINIO_ACCESS_KEY>
MINIO_SECRET_KEY=<上面输出的 ARTIFACT_MINIO_SECRET_KEY>
MINIO_BUCKET=chatflows-artifacts
```

### 10.7 启动命令 (多 compose 文件合并)

```bash
cd /opt/agentteams/VibeSales
ENV_FILE=deploy/agentteams/integration.env
COMPOSE_DIR=deploy/agentteams

# ---- (推荐) 完整命令：主 compose + external-deps 占位 + override-endpoints ----
# 1) 先语法检查
docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml \
  -f $COMPOSE_DIR/compose.external-deps.yaml \
  -f $COMPOSE_DIR/compose.override-endpoints.yaml \
  --profile __placeholder__ config --quiet && echo "config OK"

# 2) 构建 (等价 deploy-agentteams-stack.sh 的第一步 build)
docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml \
  -f $COMPOSE_DIR/compose.external-deps.yaml \
  -f $COMPOSE_DIR/compose.override-endpoints.yaml \
  --profile __placeholder__ \
  build agent-core agent-core-bff db-init verifier agent-runtime agent-manager agent-console

# 3) 启动占位 + 基础 + 业务 (注意：如果 artifact-bucket-init 改外部 MinIO 要健康；本地 MinIO 桶已经 §10.6 创建过就跳过它)
docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml \
  -f $COMPOSE_DIR/compose.external-deps.yaml \
  -f $COMPOSE_DIR/compose.override-endpoints.yaml \
  --profile __placeholder__ \
  up -d --wait postgres redis db-init agent-runtime agent-core agent-core-bff agent-manager agent-console
# 注：artifact-bucket-init 不写进上面列表 — §10.6 桶已经建好；硬要跑也行，MINIO_ENDPOINT 对即可。

# 4) 运行 manager runner check + 健康探针
MCP_PORT=$(docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml -f $COMPOSE_DIR/compose.external-deps.yaml \
  port agent-core 3100 2>/dev/null | awk -F: '{print $2}' | head -1)
MCP_PORT=${MCP_PORT:-13100}
curl --fail --silent --show-error "http://127.0.0.1:$MCP_PORT/healthz" >/dev/null && echo "agent-core /healthz OK"

docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml \
  -f $COMPOSE_DIR/compose.external-deps.yaml \
  -f $COMPOSE_DIR/compose.override-endpoints.yaml \
  --profile __placeholder__ \
  run --rm agent-manager-runner check

# 5) ps
docker compose --env-file $ENV_FILE \
  -f $COMPOSE_DIR/compose.yaml \
  -f $COMPOSE_DIR/compose.external-deps.yaml \
  -f $COMPOSE_DIR/compose.override-endpoints.yaml \
  ps
```

### 10.8 systemd 包装整个 compose 栈 (可选)

对外暴露的端口参考 compose.yaml：
- Console: `AGENT_CONSOLE_HOST_PORT` 默认 15173
- Manager HTTP: `AGENT_MANAGER_HOST_PORT` 默认 18090
- MCP: `CHATFLOWS_MCP_HOST_PORT` 默认 13100

```ini
# /etc/systemd/system/agentteams-compose.service
[Unit]
Description=VibeSales Compose Stack (external PG/Redis)
Requires=docker.service postgresql.service redis-server.service
After=docker.service postgresql.service redis-server.service network.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/agentteams/VibeSales
EnvironmentFile=/opt/agentteams/VibeSales/deploy/agentteams/integration.env
# 多文件 + placeholder profile 外部 PG/Redis
ExecStart=/usr/bin/docker compose \
  --env-file deploy/agentteams/integration.env \
  -f deploy/agentteams/compose.yaml \
  -f deploy/agentteams/compose.external-deps.yaml \
  -f deploy/agentteams/compose.override-endpoints.yaml \
  --profile __placeholder__ \
  up -d --wait postgres redis db-init agent-runtime agent-core agent-core-bff agent-manager agent-console
# 停止时只停业务容器，保留外部 PG/Redis 状态
ExecStop=/usr/bin/docker compose \
  --env-file deploy/agentteams/integration.env \
  -f deploy/agentteams/compose.yaml \
  -f deploy/agentteams/compose.external-deps.yaml \
  -f deploy/agentteams/compose.override-endpoints.yaml \
  --profile __placeholder__ \
  stop agent-runtime agent-core agent-core-bff agent-manager agent-console postgres redis
TimeoutStartSec=900

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now agentteams-compose
```

### 10.9 本模式最小待提供信息 (相对 §3 精简版)

已确认目标机具备：✅ Docker + Compose v2、✅ PostgreSQL 16+、✅ Redis 7+。

剩余**必须提供**信息：

- [ ] (10.2) `HOST_PRIVATE_IP`：容器可达的宿主机/远端 PG/Redis 私网 IP（或等效 FQDN）
- [ ] (§3.1.3) `POSTGRES_ADMIN_PASSWORD`：外部 PG 管理员密码（URL-safe）
- [ ] (§3.1.4/§3.1.5) `CHATFLOWS_APP_DB_PASSWORD`、`AGENT_RUNTIME_DB_PASSWORD`：生成两个 ≥16 URL-safe 登录密码
- [ ] Redis 密码：有就填，没有就空
- [ ] MinIO/Artifact：选 §10.6 本地 Docker 起（全自动）还是**外部 S3**？外部需提供端点+Key+Secret+Bucket
- [ ] (§3.2) LLM 网关 URL + Token（Nest 侧与 Runtime 侧各一套）
- [ ] (§3.3) MCP 工具面 URL 与 Token；控制面 3 Token + 签名密钥 (≥32)
- [ ] (§3.4) Runtime/Manager/Web 的 Auth+Admin Token 共 6 个（互不相同）
- [ ] (§3.5) AgentTeams Controller/Matrix/Task FS 全套对接信息
- [ ] (§3.6) Human/Leader/Manager 身份矩阵
- [ ] (§3.9) 对外域名 + HTTPS 证书/反代方案（Nginx/Higress/ALB）
- [ ] (§3.7) 可观测 OTLP：开 or 关？开则接入参数 4 项

以上信息齐 = 可直接进入 §10.3 → §10.7 完整执行。

---

## 11. 全原生部署实操 (PG/Redis/MinIO/业务 全部原生，零 Docker)

> **适用前提**：目标机已装 ✅ Docker 但**不使用**；已装 ✅ PostgreSQL 16+；✅ Redis 7+；✅ MinIO（同机或远端均可）；业务四件套全部走 Node/JDK/Nginx 原生进程。
> 本节在 §9「模式 C 全拆分 systemd」的骨架上，**叠加外部 PG/Redis/MinIO 的连通性检查 + 一键建库建表 + 一键建桶**，直接给出 0→1 可执行顺序。

### 11.1 部署前现状核对 (目标机逐行执行)

```bash
# ---- Node/JDK/Maven/Nginx 版本 ----
node -v    # >= 22.14
java -version   # 17 / 21
mvn -version    # >= 3.9
nginx -v
# 缺任一：按 §2.2.3 先 apt 安装

# ---- PostgreSQL ----
PG_HOST=127.0.0.1     # 远端就改 IP/FQDN
PG_PORT=5432
pg_isready -h $PG_HOST -p $PG_PORT -U postgres
psql -h $PG_HOST -U postgres -c "SHOW server_version;"   # 期望 16.x
psql -h $PG_HOST -U postgres -c "SELECT 1;"              # 交互输密码或 PGPASSWORD 环境变量

# ---- Redis ----
R_HOST=127.0.0.1
R_PORT=6379
R_PASS=""   # 有密码就填
redis-cli -h $R_HOST -p $R_PORT ${R_PASS:+-a $R_PASS} --no-auth-warning ping   # PONG
redis-cli -h $R_HOST -p $R_PORT INFO server | grep redis_version                # 7.x

# ---- MinIO ----
MINIO_HOST=127.0.0.1
MINIO_PORT=9000
MINIO_ROOT_KEY="admin"        # 改成目标机实际
MINIO_ROOT_SECRET="ChangeMe123!"
curl -sSf -u "$MINIO_ROOT_KEY:$MINIO_ROOT_SECRET" \
  "http://$MINIO_HOST:$MINIO_PORT/minio/health/ready" >/dev/null && echo "MinIO OK"
# 安装 mc (没有就装一次，全局复用)
which mc >/dev/null 2>&1 || \
  (sudo curl -sSL https://dl.min.io/client/mc/release/linux-amd64/mc -o /usr/local/bin/mc && sudo chmod +x /usr/local/bin/mc)
mc alias set vibeminio http://$MINIO_HOST:$MINIO_PORT $MINIO_ROOT_KEY $MINIO_ROOT_SECRET
mc admin info vibeminio 2>&1 | head -10

# ---- 专用运行用户 agentteams + 目录 ----
id agentteams >/dev/null 2>&1 || sudo useradd -r -m -d /opt/agentteams -s /usr/sbin/nologin agentteams
sudo mkdir -p /opt/agentteams /var/log/agentteams /var/lib/agentteams \
  /etc/agentteams /opt/agentteams/logs /opt/agentteams/orchestrator-state /opt/agentteams/runtime-workspace
sudo chown -R agentteams:agentteams /opt/agentteams /var/log/agentteams /var/lib/agentteams
sudo chown root:agentteams /etc/agentteams && sudo chmod 750 /etc/agentteams

# ---- 代码拉取 (首次) ----
sudo -u agentteams test -d /opt/agentteams/VibeSales || \
  sudo -u agentteams git clone <VibeSales 仓库地址> /opt/agentteams/VibeSales
ls /opt/agentteams/VibeSales/agent-core/src/main.ts \
   /opt/agentteams/VibeSales/agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/RuntimeApplication.java \
   /opt/agentteams/VibeSales/agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerApplication.java \
   /opt/agentteams/VibeSales/agent-console/src/App.vue 2>&1
```

### 11.2 外部基础服务一次性准备

#### 11.2.1 PostgreSQL：建库 + 两个专用 login (最小权限)

**不建议**在生产里用 postgres 超级用户跑业务。下面脚本：
- 创建业务库 `chatflows`
- 业务登录 `chatflows_app_login`（agent-core 用）
- 业务登录 `agent_runtime_login`（agent-runtime 用）
- 预置 `CHATFLOWS_APP_DB_PASSWORD`、`AGENT_RUNTIME_DB_PASSWORD` 两个变量，URL-safe 且 ≥16 字符

```bash
export PGPASSWORD=<POSTGRES_ADMIN_PASSWORD>   # 管理员密码
export PG_HOST=127.0.0.1
export PG_PORT=5432
export CHATFLOWS_APP_DB_PASSWORD=$(openssl rand -base64 18 | tr -d '=/+' | head -c 20)
export AGENT_RUNTIME_DB_PASSWORD=$(openssl rand -base64 18 | tr -d '=/+' | head -c 20)
echo "保存以下两个密码："
echo "  CHATFLOWS_APP_DB_PASSWORD=$CHATFLOWS_APP_DB_PASSWORD"
echo "  AGENT_RUNTIME_DB_PASSWORD=$AGENT_RUNTIME_DB_PASSWORD"

psql -h $PG_HOST -U postgres -c "CREATE DATABASE chatflows;" 2>/dev/null || true
# 注意：CREATE ROLE 如果已存在会报错，忽略即可；用 ALTER USER 同步密码
psql -h $PG_HOST -U postgres <<EOSQL 2>&1 | tail -10
CREATE ROLE chatflows_app_login LOGIN PASSWORD '${CHATFLOWS_APP_DB_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE chatflows_app_login WITH PASSWORD '${CHATFLOWS_APP_DB_PASSWORD}';
CREATE ROLE agent_runtime_login LOGIN PASSWORD '${AGENT_RUNTIME_DB_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE agent_runtime_login WITH PASSWORD '${AGENT_RUNTIME_DB_PASSWORD}';
EOSQL

# 连通性自测 (agent-core 侧 / runtime 侧分别能否登录)
PGPASSWORD=$CHATFLOWS_APP_DB_PASSWORD  psql -h $PG_HOST -U chatflows_app_login  -d chatflows -c "SELECT current_user;"
PGPASSWORD=$AGENT_RUNTIME_DB_PASSWORD psql -h $PG_HOST -U agent_runtime_login -d chatflows -c "SELECT current_user;"
unset PGPASSWORD

# ---- 权限红线 1：chatflows_tenant_lookup 必须 NOLOGIN，且不得授给任何 LOGIN 用户 ----
psql -h $PG_HOST -U postgres -d chatflows <<'EOSQL'
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_roles r WHERE rolname = 'chatflows_tenant_lookup' AND rolcanlogin = TRUE
  ) THEN RAISE EXCEPTION 'chatflows_tenant_lookup 必须是 NOLOGIN BYPASSRLS 角色，禁止登录';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
    WHERE r.rolname IN ('chatflows_app_login','agent_runtime_login')
      AND EXISTS (SELECT 1 FROM pg_roles r2 WHERE r2.oid = m.roleid AND r2.rolname = 'chatflows_tenant_lookup')
  ) THEN RAISE EXCEPTION '严禁把 chatflows_tenant_lookup 角色授给任何登录用户 (lookup_run_client 函数专用 owner)';
  END IF;
END $$;
EOSQL
```

> 后续 `agent-core` 的 `npm run db:init` 会在 `chatflows` 库内创建 5 张表 + RLS + 触发器 + 策略，并把对象属主切换到上面两个 login。

#### 11.2.2 MinIO：两个独立桶 (Artifact + Task FS 分离)

| 桶 | 目的 | 推荐最小权限账号 |
|---|---|---|
| `chatflows-artifacts` | agent-core 存 Blueprint zip / 大对象 Artifact | 专用账号 `agentteams-artifact-rw`，只放行此桶读写 |
| `agentteams-storage` | AgentTeams 任务目录（通常由 AgentTeams 运维提供，这里只是目标机也装了 MinIO 时顺带建） | 用 AgentTeams 运维给的受限身份，不要用 root |

```bash
MINIO_HOST=127.0.0.1
MINIO_PORT=9000
MINIO_ROOT_KEY="admin"
MINIO_ROOT_SECRET="ChangeMe123!"
mc alias set vibeminio http://$MINIO_HOST:$MINIO_PORT $MINIO_ROOT_KEY $MINIO_ROOT_SECRET

# --- 生成业务专用 Artifact 读写账号 ---
ARTIFACT_ACCESS_KEY=vb-art-$(openssl rand -hex 4)
ARTIFACT_SECRET_KEY=$(openssl rand -base64 30 | tr -d '=/+' | head -c 32)
mc admin user add vibeminio $ARTIFACT_ACCESS_KEY $ARTIFACT_SECRET_KEY

# --- 建桶 + 只允许这个账号读写本桶 ---
mc ls vibeminio/chatflows-artifacts >/dev/null 2>&1 || mc mb vibeminio/chatflows-artifacts
mc admin policy create vibeminio agentteams-artifact-rw-policy /dev/stdin <<'EOPOL'
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::chatflows-artifacts"]},
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::chatflows-artifacts/*"]}
]}
EOPOL
mc admin policy attach vibeminio agentteams-artifact-rw-policy --user $ARTIFACT_ACCESS_KEY
echo "Artifact 桶凭证 (填 integration.env 对应 MINIO_*)："
echo "  MINIO_ENDPOINT=http://$MINIO_HOST:$MINIO_PORT"
echo "  MINIO_ACCESS_KEY=$ARTIFACT_ACCESS_KEY"
echo "  MINIO_SECRET_KEY=$ARTIFACT_SECRET_KEY"
echo "  MINIO_BUCKET=chatflows-artifacts"

# --- 若目标机 MinIO 同时承担 AgentTeams Task FS 角色 (可选) ---
# 注意：大部分生产是 AgentTeams 运维独立提供。仅当本次目标机也装了 MinIO 想复用才建：
# mc ls vibeminio/agentteams-storage >/dev/null 2>&1 || mc mb vibeminio/agentteams-storage
# 然后把对应 Access/Secret 给 AgentTeams 运维，让他们写 CHATFLOWS_TASK_FS_*
```

### 11.3 生成三份 EnvironmentFile (一次到位)

根据 §9.3，三份文件 `/etc/agentteams/{agent-core,agent-runtime,agent-manager}.env`。下面用变量模板直接生成，避免遗漏差异。

```bash
# =============== 开始填写 ==================
# ---- 基础服务 (目标机实际值) ----
PG_HOST=127.0.0.1; PG_PORT=5432
R_HOST=127.0.0.1;  R_PORT=6379
R_PASS=""                             # Redis 无密码就留空
MINIO_ENDPOINT=http://127.0.0.1:9000
MINIO_ACCESS_KEY=___REPLACE_ME___     # §11.2.2 生成的 $ARTIFACT_ACCESS_KEY
MINIO_SECRET_KEY=___REPLACE_ME___     # §11.2.2 生成的 $ARTIFACT_SECRET_KEY
MINIO_BUCKET=chatflows-artifacts

# ---- 两个业务 DB 密码 (§11.2.1 输出) ----
CHATFLOWS_APP_DB_PASSWORD=___REPLACE_ME___
AGENT_RUNTIME_DB_PASSWORD=___REPLACE_ME___

# ---- 控制面 Token (全 ≥16；签名 ≥32) ----
MCP_SERVER_TOKEN=$(openssl rand -hex 16)
PIPELINE_CONTROL_TOKEN=$(openssl rand -hex 16)
BLUEPRINT_ADMIN_TOKEN=$(openssl rand -hex 16)
APPROVAL_SIGNING_SECRET=$(openssl rand -hex 32)

# ---- LLM 网关 (Higress/OpenAI 兼容) ----
NEST_LLM_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
NEST_LLM_TOKEN=___REPLACE_ME___
RUNTIME_LLM_BASE_URL=https://<HIGRESS_HOST>/compatible-mode/v1
RUNTIME_LLM_TOKEN=___REPLACE_ME___

# ---- BFF Web ----
WEB_AUTH_TOKEN=$(openssl rand -hex 16)
WEB_AUTH_CLIENT_CODE=acme_beauty_missing_kb   # 对应 catalogs/tenants/*.json 存在的 tenant

# ---- Runtime 内部 ----
RUNTIME_AUTH_TOKEN=$(openssl rand -hex 16)
RUNTIME_ADMIN_TOKEN=$(openssl rand -hex 16)
P3C_MCP_URL=https://<HIGRESS_HOST>/mcp-servers/chatflows-p3c   # 业务 MCP 工具
RUNTIME_MCP_URL=https://<HIGRESS_HOST>/mcp-servers/business-tools
RUNTIME_MCP_TOKEN=___REPLACE_ME___

# ---- Manager 内部 ----
MANAGER_AUTH_TOKEN=$(openssl rand -hex 16)
MANAGER_ADMIN_TOKEN=$(openssl rand -hex 16)
# ---- AgentTeams 对接 (严格按现网映射端口：宿主 18090/19000/18080，容器内网名仅同网容器互访时用) ----
AGENTTEAMS_CONTROLLER_URL=http://<HOST>:18090           # 宿主映射端口 18090 → Controller 容器 8090；原生 Manager 进程走宿主映射端口
AGENTTEAMS_AUTH_TOKEN=___REPLACE_ME___                  # SSH <HOST>: docker exec agentteams-controller cat /var/run/agentteams/cli-token
AGENTTEAMS_MATRIX_URL=http://<HOST>:18080               # Matrix CS API 走 18080；不要用 6167
AGENTTEAMS_MATRIX_USER_ID=@chatflows-manager-ext:matrix-local.agentteams.io:18080
AGENTTEAMS_MATRIX_PASSWORD=___REPLACE_ME___             # 当前生产推荐：独立正式账号 chatflows-manager-ext，使用 user+password 方式
# AGENTTEAMS_MATRIX_ACCESS_TOKEN=___REPLACE_ME___       # 可选；若使用 token，必须是非 guest token，且不得复用旧 @manager guest token
CHATFLOWS_TASK_FS_ENDPOINT=http://<HOST>:19000          # 宿主 19000 → MinIO 容器 9000
CHATFLOWS_TASK_FS_ACCESS_KEY=___REPLACE_ME___           # 推荐 AgentTeams 运维给的受限身份；禁止直接用 Artifact 桶 IAM
CHATFLOWS_TASK_FS_SECRET_KEY=___REPLACE_ME___           # ≠ MINIO_SECRET_KEY；推荐由 configure-chatflows-task-storage*.{js,sh} 自动生成/回写
CHATFLOWS_TASK_FS_BUCKET=agentteams-storage             # 现网实际桶名；旧值 agentteams 会 403
CHATFLOWS_TASK_FS_PREFIX=teams/chatflows-build-team/shared/tasks
AGENTTEAMS_HUMAN_IDS=@admin:matrix-local.agentteams.io:18080
AGENTTEAMS_LEADER_IDS=@chatflows-leader:matrix-local.agentteams.io:18080   # 若已在 Controller 侧声明过也可留空自动发现
AGENTTEAMS_MANAGER_IDS=@chatflows-manager-ext:matrix-local.agentteams.io:18080   # 必须与上方 AGENTTEAMS_MATRIX_USER_ID 同账号
AGENTTEAMS_LEADER_ROOM_ID=___REPLACE_ME___             # 通过 Team 资源创建后，从 teamRoomID 回填
AGENTTEAMS_RUN_TIMEOUT_SECONDS=3600
# ---- Higress 网关 Bearer 约束：同一 Authorization 头被原样转发给 Nest，故必须 === MCP_SERVER_TOKEN ----
HIGRESS_CONSUMER_TOKEN=${MCP_SERVER_TOKEN}               # 若以后要分离，必须先在 Higress 加上游头改写/注入，不能直接改
# ---- E2E 验收前置：agent-console 同一次向导导出的平台验收 JSON；run-agentteams-e2e.sh 会在任何 apply 前校验 gate=PASS ----
AGENTTEAMS_PHASE1_RESULT_FILE=/opt/agentteams/agent-console-phase1-result.json

# ---- 可观测 (关 or on) ----
AGENTLOOP_EXPORTER=off
AGENTLOOP_SAMPLE_RATE=1.0
AGENTLOOP_PROTOCOL=otlp
OTEL_SERVICE_NAME_PREFIX=vibe-sales
# =============== 停止填写 ==================

# ---- 生成 agent-core.env ----
sudo tee /etc/agentteams/agent-core.env >/dev/null <<EOF
WEB_HOST=0.0.0.0                            # 给 Higress/Worker 当 MCP 上游时必须；仅本机 BFF 单跑可回 127.0.0.1
CHATFLOWS_ROOT=/opt/agentteams/VibeSales
ARTIFACT_STORE=postgres
DATABASE_URL=postgresql://chatflows_app_login:${CHATFLOWS_APP_DB_PASSWORD}@${PG_HOST}:${PG_PORT}/chatflows
DATABASE_POOL_SIZE=10
DATABASE_SSL=0
MINIO_ENDPOINT=${MINIO_ENDPOINT}
MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY}
MINIO_SECRET_KEY=${MINIO_SECRET_KEY}
MINIO_BUCKET=${MINIO_BUCKET}
DASHSCOPE_API_KEY=
QWEN_GATEWAY_TOKEN=${NEST_LLM_TOKEN}
QWEN_BASE_URL=${NEST_LLM_BASE_URL}
QWEN_MODEL=qwen3.7-plus
MCP_SERVER_TOKEN=${MCP_SERVER_TOKEN}
PIPELINE_CONTROL_TOKEN=${PIPELINE_CONTROL_TOKEN}
BLUEPRINT_ADMIN_TOKEN=${BLUEPRINT_ADMIN_TOKEN}
PIPELINE_APPROVAL_SIGNING_SECRET=${APPROVAL_SIGNING_SECRET}
P3C_BUSINESS_MCP_URL=${P3C_MCP_URL}
WEB_AUTH_TOKEN=${WEB_AUTH_TOKEN}
WEB_AUTH_CLIENT_CODE=${WEB_AUTH_CLIENT_CODE}
ORCHESTRATION_MODE=platform
AGENT_RUNTIME_URL=http://127.0.0.1:8088
AGENT_RUNTIME_TOKEN=${RUNTIME_AUTH_TOKEN}
FLOW_PLATFORM_MODE=local
AGENTLOOP_EXPORTER=${AGENTLOOP_EXPORTER}
AGENTLOOP_SAMPLE_RATE=${AGENTLOOP_SAMPLE_RATE}
AGENTLOOP_PROTOCOL=${AGENTLOOP_PROTOCOL}
OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME_PREFIX}-nest-mcp
LOG_STDERR=on
LOG_FILE=on
LOG_DIR=/opt/agentteams/logs
EOF

# ---- 生成 agent-runtime.env ----
sudo tee /etc/agentteams/agent-runtime.env >/dev/null <<EOF
RUNTIME_MODE=production
RUNTIME_HOST=127.0.0.1
RUNTIME_PORT=8088
RUNTIME_AUTH_TOKEN=${RUNTIME_AUTH_TOKEN}
RUNTIME_ADMIN_TOKEN=${RUNTIME_ADMIN_TOKEN}
RUNTIME_MODEL=dashscope:qwen-plus
RUNTIME_LLM_BASE_URL=${RUNTIME_LLM_BASE_URL}
RUNTIME_LLM_TOKEN=${RUNTIME_LLM_TOKEN}
AGENTSCOPE_WORKSPACE=/opt/agentteams/runtime-workspace
DATABASE_URL=jdbc:postgresql://${PG_HOST}:${PG_PORT}/chatflows
DATABASE_USER=agent_runtime_login
DATABASE_PASSWORD=${AGENT_RUNTIME_DB_PASSWORD}
REDIS_URL=redis://${R_PASS:+:$R_PASS@}${R_HOST}:${R_PORT}/0
RUNTIME_MCP_URL=${RUNTIME_MCP_URL}
RUNTIME_MCP_TOKEN=${RUNTIME_MCP_TOKEN}
BLUEPRINT_ADMIN_URL=http://127.0.0.1:3100
BLUEPRINT_ADMIN_TOKEN=${BLUEPRINT_ADMIN_TOKEN}
AGENTLOOP_EXPORTER=${AGENTLOOP_EXPORTER}
AGENTLOOP_SAMPLE_RATE=${AGENTLOOP_SAMPLE_RATE}
AGENTLOOP_PROTOCOL=${AGENTLOOP_PROTOCOL}
OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME_PREFIX}-runtime
EOF

# ---- 生成 agent-manager.env ----
sudo tee /etc/agentteams/agent-manager.env >/dev/null <<EOF
AGENTTEAMS_CONTROLLER_URL=${AGENTTEAMS_CONTROLLER_URL}
AGENTTEAMS_AUTH_TOKEN=${AGENTTEAMS_AUTH_TOKEN}
AGENTTEAMS_MATRIX_URL=${AGENTTEAMS_MATRIX_URL}
AGENTTEAMS_MATRIX_USER_ID=${AGENTTEAMS_MATRIX_USER_ID}
AGENTTEAMS_MATRIX_PASSWORD=${AGENTTEAMS_MATRIX_PASSWORD}
# AGENTTEAMS_MATRIX_ACCESS_TOKEN=${AGENTTEAMS_MATRIX_ACCESS_TOKEN}
CHATFLOWS_TASK_FS_ENDPOINT=${CHATFLOWS_TASK_FS_ENDPOINT}
CHATFLOWS_TASK_FS_ACCESS_KEY=${CHATFLOWS_TASK_FS_ACCESS_KEY}
CHATFLOWS_TASK_FS_SECRET_KEY=${CHATFLOWS_TASK_FS_SECRET_KEY}
CHATFLOWS_TASK_FS_BUCKET=${CHATFLOWS_TASK_FS_BUCKET}
CHATFLOWS_TASK_FS_PREFIX=${CHATFLOWS_TASK_FS_PREFIX}
CHATFLOWS_APPROVAL_SIGNING_SECRET=${APPROVAL_SIGNING_SECRET}
AGENTTEAMS_HUMAN_IDS=${AGENTTEAMS_HUMAN_IDS}
AGENTTEAMS_LEADER_IDS=${AGENTTEAMS_LEADER_IDS}
AGENTTEAMS_MANAGER_IDS=${AGENTTEAMS_MANAGER_IDS}
AGENTTEAMS_LEADER_ROOM_ID=${AGENTTEAMS_LEADER_ROOM_ID}
AGENTTEAMS_RUN_TIMEOUT_SECONDS=${AGENTTEAMS_RUN_TIMEOUT_SECONDS}
AGENTTEAMS_TEAM_NAME=chatflows-build-team
AGENTTEAMS_LEADER_NAME=chatflows-leader
AGENTTEAMS_TEAM_FILE=/opt/agentteams/VibeSales/agentteams-resources/team.yaml
CHATFLOWS_NEST_URL=http://127.0.0.1:3100
PIPELINE_CONTROL_TOKEN=${PIPELINE_CONTROL_TOKEN}
MANAGER_AUTH_TOKEN=${MANAGER_AUTH_TOKEN}
MANAGER_ADMIN_TOKEN=${MANAGER_ADMIN_TOKEN}
MANAGER_HOST=127.0.0.1
MANAGER_PORT=8090
ORCHESTRATOR_LLM=off
ORCHESTRATOR_STATE_HOME=/opt/agentteams/orchestrator-state
AGENTLOOP_EXPORTER=${AGENTLOOP_EXPORTER}
AGENTLOOP_SAMPLE_RATE=${AGENTLOOP_SAMPLE_RATE}
AGENTLOOP_PROTOCOL=${AGENTLOOP_PROTOCOL}
OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME_PREFIX}-manager
EOF

# 权限收紧
sudo chown root:agentteams /etc/agentteams/*.env
sudo chmod 640 /etc/agentteams/*.env
ls -la /etc/agentteams/
```

### 11.4 构建 + 初始化 (一步一步)

```bash
cd /opt/agentteams/VibeSales

# ---- (1) agent-core Node 构建 ----
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-core && \
npm ci --no-audit --no-fund --loglevel=error 2>&1 | tail -5 && \
npm run build --if-present 2>&1 | tail -5 && \
ls dist/main-mcp.js dist/main-web.js dist/main.js
'

# ---- (2) 数据库：5 表 + RLS + 触发器 (agent-core 的 db:init) ----
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-core
set -a; source /etc/agentteams/agent-core.env; set +a
# db:init 需要一次性管理员变量 (不是业务 DATABASE_URL)
export DATABASE_ADMIN_URL="postgresql://postgres:<POSTGRES_ADMIN_PASSWORD>@'${PG_HOST}':'${PG_PORT}'/chatflows"
export CHATFLOWS_APP_DB_PASSWORD="'${CHATFLOWS_APP_DB_PASSWORD}'"
export AGENT_RUNTIME_DB_PASSWORD="'${AGENT_RUNTIME_DB_PASSWORD}'"
npm run db:init 2>&1 | tail -30
'
# 预期输出：CREATE TABLE ... ×5；CREATE POLICY；ALTER DEFAULT PRIVILEGES；exit 0

# ---- (3) Java 两组件构建 (首次联网拉依赖，之后可 -o) ----
sudo -u agentteams bash -lc '
set -e
# 3a. agent-runtime
cd /opt/agentteams/VibeSales/agent-runtime
mvn -q clean compile dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory=target/dist/lib
mkdir -p target/dist/classes target/dist/workspace
cp -R target/classes/. target/dist/classes/
[ -d workspace ] && cp -R workspace/. target/dist/workspace/ 2>/dev/null || true
test -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class && echo "[OK] agent-runtime build"

# 3b. agent-manager
cd /opt/agentteams/VibeSales/agent-manager
mvn -q clean compile dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory=target/dist/lib
mkdir -p target/dist/classes
cp -R target/classes/. target/dist/classes/
test -f target/dist/classes/com/yjiyun/chatflows/manager/ManagerApplication.class && echo "[OK] agent-manager build"
'

# ---- (4) agent-console Vue 构建 ----
sudo -u agentteams bash -lc '
cd /opt/agentteams/VibeSales/agent-console
npm ci --no-audit --no-fund --loglevel=error 2>&1 | tail -5
ORCHESTRATION_MODE=platform npm run build 2>&1 | tail -5
ls dist/index.html
'
```

### 11.5 安装 4 个 systemd unit + Nginx 配置

复用 §9.6 四个 unit。如果已经写入 `/etc/systemd/system/` 只需确保 `EnvironmentFile` 路径正确；否则执行：

```bash
# ---- 写入 4 个 unit (同 §9.6) ----
# 若之前已写，只需要 daemon-reload：
sudo systemctl daemon-reload

# ---- Nginx 站点 (同 §9.7) ----
# 若 /etc/nginx/sites-available/agentteams 已写过并软链到 sites-enabled：
sudo nginx -t
```

> Nginx 里 `root` 必须指向实际构建产物：
> `root /opt/agentteams/VibeSales/agent-console/dist;`

### 11.6 按顺序启动 + 健康探针

```bash
SVC=(agentteams-agent-runtime agentteams-agent-core-mcp agentteams-agent-core-bff agentteams-agent-manager)

# Step 1 Runtime
sudo systemctl start agentteams-agent-runtime
sleep 12
sudo systemctl is-active agentteams-agent-runtime   # active
# Runtime 健康：必须 POST /api/v1/dryrun；GET 会 405。未鉴权期望 401（= 监听成功）
RT_HTTP=$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
  -H "Content-Type: application/json" -d '{"input":"probe"}' \
  http://127.0.0.1:8088/api/v1/dryrun)
echo "runtime probe HTTP=$RT_HTTP (expect 401 or 2xx)"
# 有 Admin Token 时再试一次 actuator/info 或 2xx 路由
RUNTIME_ADMIN_TOKEN=$(sudo grep ^RUNTIME_ADMIN_TOKEN= /etc/agentteams/agent-runtime.env | cut -d= -f2)
[ -n "$RUNTIME_ADMIN_TOKEN" ] && curl -s -H "Authorization: Bearer $RUNTIME_ADMIN_TOKEN" \
  http://127.0.0.1:8088/actuator/info 2>/dev/null | jq . || true

# Step 2 agent-core MCP 面
sudo systemctl start agentteams-agent-core-mcp
sleep 12
curl -s http://127.0.0.1:3100/healthz | jq
# 期望 dependencies.postgres/minio/qwen_gateway/agent_runtime 全 ok

# Step 3 agent-core BFF 面
sudo systemctl start agentteams-agent-core-bff
sleep 8
WEB_AUTH_TOKEN=$(sudo grep ^WEB_AUTH_TOKEN= /etc/agentteams/agent-core.env | cut -d= -f2)
curl -sSo /dev/null -w "bff:%{http_code}\n" \
  -H "Authorization: Bearer $WEB_AUTH_TOKEN" -H "X-Role:user" -H "X-Actor:deploy-check" \
  http://127.0.0.1:3101/api/health
# 期望 200 (401 则是 WEB_AUTH_TOKEN 对不上)

# Step 4 Manager (编排)
sudo systemctl start agentteams-agent-manager
sleep 18
MANAGER_AUTH_TOKEN=$(sudo grep ^MANAGER_AUTH_TOKEN= /etc/agentteams/agent-manager.env | cut -d= -f2)
curl -s -H "Authorization: Bearer $MANAGER_AUTH_TOKEN" http://127.0.0.1:8090/api/v1/health | jq
# 期望 {ok:true}

# Step 5 Nginx reload + 对外探测
sudo nginx -t && sudo systemctl reload nginx
curl -sS -o /dev/null -w "nginx home:%{http_code}\n" -H "Host: agentteams.example.com" http://127.0.0.1/
curl -sSo /dev/null -w "nginx /healthz:%{http_code}\n" -H "Host: agentteams.example.com" http://127.0.0.1/healthz

# 开机自启
sudo systemctl enable "${SVC[@]}"
```

### 11.7 一键运维脚本 /usr/local/sbin/agentteams (最终版，纯原生)

```bash
sudo tee /usr/local/sbin/agentteams >/dev/null <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
UNITS=(agentteams-agent-runtime agentteams-agent-core-mcp agentteams-agent-core-bff agentteams-agent-manager)
healthz(){
  echo "-- agent-core MCP plane --"
  curl -sS --max-time 5 http://127.0.0.1:3100/healthz | jq '.status, .dependencies'
  echo "-- agent-runtime --"
  RAT=$(sudo grep ^RUNTIME_ADMIN_TOKEN= /etc/agentteams/agent-runtime.env | cut -d= -f2)
  # §12.4：Runtime /api/v1/dryrun 必须 POST；未鉴权 401 算 OK；鉴权过 2xx 算 OK
  RT_HTTP=$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" -X POST \
    -H "Content-Type: application/json" -d '{"input":"healthz-probe"}' \
    ${RAT:+-H "Authorization: Bearer $RAT"} \
    http://127.0.0.1:8088/api/v1/dryrun)
  case "$RT_HTTP" in
    2??|401) echo "runtime_alive=yes http=$RT_HTTP" ;;
    *)     echo "runtime_alive=no http=$RT_HTTP (expected 2xx/401, see §12.4)" ;;
  esac
  echo "-- agent-core BFF --"
  WAT=$(sudo grep ^WEB_AUTH_TOKEN= /etc/agentteams/agent-core.env | cut -d= -f2)
  curl -sSo /dev/null -w "bff:%{http_code}\n" --max-time 5 \
    -H "Authorization: Bearer $WAT" -H "X-Role:user" -H "X-Actor:cli" \
    http://127.0.0.1:3101/api/health
  echo "-- agent-manager --"
  MAT=$(sudo grep ^MANAGER_AUTH_TOKEN= /etc/agentteams/agent-manager.env | cut -d= -f2)
  curl -sS --max-time 5 -H "Authorization: Bearer $MAT" http://127.0.0.1:8090/api/v1/health | jq
}
case "${1:-}" in
  start|stop|restart|status|enable|disable) exec sudo systemctl "$1" "${UNITS[@]}" ;;
  logs) shift; exec journalctl "${UNITS[@]/#/-u}" -f "$@" ;;
  healthz) healthz ;;
  *) echo "usage: $0 {start|stop|restart|status|enable|disable|logs [--since 1h]|healthz}" >&2; exit 2;;
esac
BASH
sudo chmod +x /usr/local/sbin/agentteams
```

使用：`agentteams start` → `agentteams healthz` → `agentteams logs --since 1h`。

### 11.8 最小信息清单 (纯原生 + 外部 PG/Redis/MinIO)

✅ 已具备：Docker（但不用）、PostgreSQL 16+、Redis 7+、MinIO。

| 分类 | 项目 | 状态 | 备注 |
|---|---|---|---|
| 基础连接 | `PG_HOST/POSTGRES_ADMIN_PASSWORD` | ⬜ | 11.2.1 需要一次管理员权限建 login；密码 URL-safe |
| 基础连接 | `R_HOST/R_PASS` (Redis) | ⬜ | 无密码就空；有密码须能从 127.0.0.1 或部署机 IP AUTH |
| 基础连接 | MinIO 管理员账号 (建桶+最小权限用户) | ⬜ | 11.2.2 产出 `MINIO_ACCESS_KEY/SECRET_KEY` 两字段 |
| LLM 网关 | `NEST_LLM_BASE_URL` / `NEST_LLM_TOKEN` | ⬜ | agent-core (Nest) 侧 qwen_gateway 用 |
| LLM 网关 | `RUNTIME_LLM_BASE_URL` / `RUNTIME_LLM_TOKEN` | ⬜ | agent-runtime 侧 Java LLM 调用用 |
| 业务 MCP | `P3C_MCP_URL` (Nest sidecar) + `RUNTIME_MCP_URL/TOKEN` (Java sidecar) | ⬜ | 公网 HTTPS 含 higress；私网 IP 可 HTTP |
| AgentTeams | Controller URL + Token | ⬜ | 3.5.1/3.5.2 |
| AgentTeams | Matrix URL + User + Access Token | ⬜ | 3.5.3-3.5.5 |
| AgentTeams | Task FS 4 件套 (endpoint/AK/SK/bucket/prefix) | ⬜ | 3.5.6-3.5.10 |
| AgentTeams | Human/Leader/Manager Matrix ID 清单 + run 默认 client_code + timeout | ⬜ | 3.6 |
| 鉴权 Token | 脚本已自动生成 11 个随机 Token (可替换) | ⚠️ 可替换 | 控制面 3 个 + 签名 1 个 + Runtime/Web/Manager Auth/Admin 6 个 + MCP 1 个 |
| 可观测 | OTLP/ARMS 开关与接入参数 | ⬜ 默认关 | 3.7 |
| 公网/HTTPS | 域名、证书、TLS 终止 (Nginx/Higress) | ⬜ | 3.9 |

**拿到 `⬜` 项值后 → 把它们填入 §11.3 的「开始填写」区，再执行下面脚本即可一键把三份 env 写好，后续 11.4~11.6 全是纯执行步骤。**

---

## 12. 测试环境部署经验同步 (来自启动说明.md 的已验证流程)

> 数据来源：`AgenticAssistantBase` 原项目（本项目 VibeSales 的未脱敏副本）下 [启动说明.md](file:///d:/Codes/yjy/AI-contest/AgenticAssistantBase/docs/agentteams/%E5%90%AF%E5%8A%A8%E8%AF%B4%E6%98%8E.md)。原文档在 **测试环境 10.0.0.1** 上**已完整跑通「真平台四件套」：Nest 3100 + agent-runtime 8088 + agent-manager 8090 + agent-console 5174**。本节把那里经过真实踩坑验证过的结论同步到生产部署文档，避免在 Ubuntu 生产上二次踩坑。
>
> 命名映射（下文沿用）：
> - `Nest / Nest BFF` = **agent-core** （Node），三份入口 main-mcp.js / main-web.js / main.js
> - `Console / agent-console` = **agent-console** （Vue → 静态 dist）
> - `Runtime / agent-runtime` = **agent-runtime** （Java 17，Spring Boot / AgentScope）
> - `Manager / agent-manager` = **agent-manager** （Java 17，编排 + AgentTeams 对接）
> - `start-local-manual-stack.sh` = 三件套，local 模式，**不接**外部平台（没 manager）
> - `run-agentteams-local-dev.sh all` = 四件套，真平台模式，**必须**接外部 Controller / Matrix / MinIO + PG / Redis + LLM 网关

### 12.1 两条启动路径，不要混

| 场景 | 用哪条 | 服务数 | 端口 | 外部依赖 | 本机能否单独跑 |
|---|---|---|---|---|---|
| 纯本机点 UI / 做向导验证 | `scripts/start-local-manual-stack.sh` | 3 (无 Manager) | Nest 23401 / Runtime 28288 / Console 25273 | **零**（ARTIFACT_STORE=file） | ✅ |
| 对接测试环境 Controller / Matrix / Task FS | `scripts/run-agentteams-local-dev.sh all` | 4 (全) | Nest 3100 / Runtime 8088 / Manager 8090 / Console 5174 | 6 项必齐（见 12.2） | ❌ 缺任一项 preflight 直接 exit |
| 混合栈（真模型但不跑编排） | `START_MANAGER=0 ./scripts/run-agentteams-local-dev.sh` | 3 (无 Manager) | 同四件套端口 | 需要 PG + Redis + LLM + MCP，**但**不需要 Controller/Matrix/Task FS | ⚠️ 看 PG/Redis 是否本机可连 |
| Ubuntu 生产部署（原生 systemd，本文 §9/§11） | systemd 四 unit + Nginx | 4（全） | 3100 / 3101 / 8088 / 8090 | 外部全依赖 + Nginx 对外 | ⭐ 本文目标 |

> 关键记忆：**端口不同 = 运行时参数不同（Token / env 不同），不要把一套 token 粘到另一套的 Console 连接凭证抽屉里**（见原启动说明 §5 端口错开表）。

### 12.2 已验证的 preflight 清单（启动四件套前全部要通过）

对应原文档 `preflight_nest / preflight_manager / preflight_runtime` 三段代码（`scripts/run-agentteams-local-dev.sh` 的 3 个函数）。**生产部署（§9/§11）也应按这个顺序先跑一次 preflight，再上 systemd。** 一条不通过就不要启动 unit，否则起了也会被 preflight 代码里的 `exit 1` 自动拖垮（触发 cleanup 把同伴都杀了）。

```bash
# ---------- helper：纯 TCP 探测（绕开业务鉴权，一次性脚本） ----------
probe_tcp_url(){
  # 用法: probe_tcp_url "redis://host:6379/0" Redis 6379
  #       probe_tcp_url "http://host:5432" PostgreSQL 5432
  #       probe_tcp_url "postgres://user:pass@host:5432/db" PostgreSQL 5432
  local url="$1" name="$2" default_port="$3"
  local host port
  host=$(echo "$url" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:@]+).*$#\1#;t;s#^.*@([^:/?#]+).*#\1#')
  port=$(echo "$url" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#:]*:([0-9]+).*$#\1#;t;s#^.*$#'"$default_port"'#')
  nc -z -w 3 "$host" "$port" || { echo "[PREFLIGHT FAIL] $name unreachable ${host}:${port} from url=$url" >&2; exit 1; }
}

# (1) Nest preflight —— 只要数据库端口通就行（真正的业务鉴权在 db:init 做）
set -a; source /etc/agentteams/agent-core.env; set +a
probe_tcp_url "$DATABASE_URL" PostgreSQL 5432
# 加测 MinIO L7（S3 ListBucketV2）
curl -sSf -u "${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}" \
  "${MINIO_ENDPOINT}/${MINIO_BUCKET}?list-type=2&max-keys=1" >/dev/null \
  || { echo "[PREFLIGHT FAIL] MinIO bucket=${MINIO_BUCKET} endpoint=${MINIO_ENDPOINT} LIST 失败 (没建桶？)" >&2; exit 1; }

# (2) Runtime preflight —— PG(jdbc) + Redis 端口通 + Nest(3100) 端口通
set -a; source /etc/agentteams/agent-runtime.env; set +a
probe_tcp_url "${DATABASE_URL#jdbc:}" PostgreSQL 5432
probe_tcp_url "$REDIS_URL" Redis 6379
# 注意：这里只能 TCP 探 Nest 端口，不能 HTTP 探 /api/health —— 该路由要求 Bearer，401 也会被 curl -f 当失败
while ! ( probe_tcp_url "http://127.0.0.1:3100" Nest 3100 ) >/dev/null 2>&1; do sleep 1; done  # 子 shell 包 probe 防 exit 1

# (3) Manager preflight —— 三样外部平台依赖必须都探到
set -a; source /etc/agentteams/agent-manager.env; set +a
probe_tcp_url "$AGENTTEAMS_CONTROLLER_URL" AgentTeams_Controller 8090
probe_tcp_url "$AGENTTEAMS_MATRIX_URL"     AgentTeams_Matrix     6167
probe_tcp_url "$CHATFLOWS_TASK_FS_ENDPOINT" Task_FS_MinIO        9000
# Task FS AK/SK 要单独验证（且必须 ≠ Artifact MinIO 的 root/业务 key）
curl -sSf -u "${CHATFLOWS_TASK_FS_ACCESS_KEY}:${CHATFLOWS_TASK_FS_SECRET_KEY}" \
  "${CHATFLOWS_TASK_FS_ENDPOINT}/${CHATFLOWS_TASK_FS_BUCKET}?list-type=2&max-keys=1&prefix=${CHATFLOWS_TASK_FS_PREFIX}" >/dev/null \
  || { echo "[PREFLIGHT FAIL] Task FS prefix 不可列: bucket=$CHATFLOWS_TASK_FS_BUCKET prefix=$CHATFLOWS_TASK_FS_PREFIX" >&2; exit 1; }

echo "[PREFLIGHT OK] 全量通过，可以启动 systemd unit 了。"
```

### 12.3 启动时序竞态（真·必踩坑）

原文档 §4.4 花了两轮才修对。结论直接抄，生产要照着做：

| 顺序 | 服务 | 启动前置 | 理由 |
|---|---|---|---|
| ① | agent-runtime (8088) | 无；但要等 PG/Redis TCP 通了再启 | Runtime LLM 调用和 RLS 查数据都要这俩，不通时初始化可能失败 |
| ② | agent-core MCP 面 (3100) | 等 Runtime 健康（POST `/api/v1/dryrun` 401 就算 OK，见 §12.4） | Nest healthz 里 `dependencies.agent_runtime` 会真的打它；Runtime 没起来 Nest 直接 503 |
| ③ | agent-core BFF 面 (3101) | 可与 MCP 面同时；但强依赖 Runtime/MCP 都健康 | UI 向导在 3101 |
| ④ | agent-manager (8090) | **必须在 Nest 3100 TCP 已 LISTEN 之后**；HTTP 鉴权路由不能用作前置条件 | `ManagerApplication.serve` 启动时**只探测一次** Nest `/api/v1/pipeline/health`；Nest（ts-node/Node）通常比 JVM 慢 2–3 倍；**先起 Manager 会拿 Connection refused → Manager 抛异常 → cleanup trap 把另外三个也杀了**（原文档 4.4 原景复现） |

**Ubuntu systemd 的落实方式**：把 `agentteams-agent-manager.service` 的 `After=` 串成链，再加一个 `ExecStartPre=` 用 TCP 轮询 Nest，**禁止用 HTTP 轮询**（401 也会被当失败）：

```ini
[Unit]
After=network.target agentteams-agent-runtime.service agentteams-agent-core-mcp.service agentteams-agent-core-bff.service
Requires=agentteams-agent-runtime.service agentteams-agent-core-mcp.service agentteams-agent-core-bff.service

[Service]
# ...
# ⚠️ 必须子 shell 包 probe_tcp_url：该脚本一旦失败会直接 exit 1 当前 shell，外层 systemd 不会重试
ExecStartPre=/bin/bash -euxc '
  while ! ( probe_tcp_url "http://127.0.0.1:3100" Nest 3100 ) >/dev/null 2>&1; do
    waited=$((waited+1)); [[ $waited -lt 90 ]] || { echo "Nest 3100 did not listen within 90s" >&2; exit 1; }; sleep 1
  done
'
ExecStart=/bin/sh -lc 'exec $(dirname $(readlink -f $(which java)))/java -cp "target/dist/classes:target/dist/lib/*" com.yjiyun.chatflows.manager.ManagerApplication serve'
```

### 12.4 健康探针要用的方法（GET 会 405/401 的地方别用 GET）

来自原文档 §4.2 + 多个脚本的 `wait_http` 参数（`start-local-manual-stack.sh:106,122,136`、`run-console-ui-evidence.sh:105,119,130`、`preflight-agentteams-integration.js:284`）：

| 服务 | 健康探针 | 方法 | 期望状态码 | 说明 |
|---|---|---|---|---|
| agent-core (MCP 3100) | `/healthz` | GET | 200 | 公开；可看 `dependencies.*`，用于排障 |
| agent-core (BFF 3101) | `/api/health` | GET | 401 | 需要 Bearer + `X-Role: user` + `X-Actor: <uid>`；生产里拿不到 Token 就用 401 判 alive（**401 ≠ 失败**） |
| agent-runtime (8088) | `/api/v1/dryrun` | **POST** | 401 | ⚠️ **GET 永远 405**（Controller 先判 method 再鉴权）。原文档 §4.2 明确写了，不要写反 |
| agent-manager (8090) | `/api/v1/health` | GET | 401/200 | 需要 `MANAGER_AUTH_TOKEN`；拿不到 Token 时以 `401 ≠ Connection refused` 判 alive |
| agent-console (80/443) | `/index.html` | GET | 200 | 静态页面；Nginx 健康探针用这个 |
| Nginx 综合健康 | `/healthz` | GET | 200 | 代理到 agent-core /healthz（见 §9.7 / §11.5 Nginx 配置） |

**对应修正**：§9.6、§11.6、§11.7 所有 runtime 健康探针里用 GET 打路由的地方，全部改成 POST `/api/v1/dryrun` 并接受 401 作为「OK」。

### 12.5 进程树退出残留（systemd 停服务必须连根拔）

原文档 §4.3：`( cd X; env A=B exec app ) &` 时，`$!` 是子 shell PID，真实监听端口的是子 shell fork 的 Node/JVM PID。只 kill 子 shell → 监听进程甩给 init 占端口 → 下次启动 `Address already in use`。

**systemd 落实方式**（4 个 unit 都加）：

```ini
[Service]
# 用 cgroup v2 freezer 杀干净一整颗进程树（含 Node 的 worker / JVM 的 fork）
KillMode=mixed
KillSignal=SIGTERM
TimeoutStopSec=30s
# 兜底：ExecStop 之后 systemd 会再走 cgroup 清理
# 仍不放心的话（比如某些老内核 cgroup v1）可以加这一行显式遍历：
ExecStop=/bin/bash -c 'for pid in $(pgrep -P $MAINPID 2>/dev/null; echo $MAINPID); do kill -TERM $pid 2>/dev/null || true; done; sleep 2; pgrep -U agentteams | xargs -r kill -9 2>/dev/null || true'
```

同时保留原文档的手工查残留命令（换成生产端口）：
```bash
for p in 3100 3101 8088 8090 80 443; do ss -ltnp "sport = :$p" 2>/dev/null | tail -n +2; done
```

### 12.6 pipeline 接口真实必填字段（curl 直打要记牢）

来自原文档 §6 最后一段（报错才挖到的根因）：`POST /api/v1/pipeline/start` 必须一次传齐四个字段，缺一不可：

```bash
PIPELINE_CONTROL_TOKEN=$(sudo grep ^PIPELINE_CONTROL_TOKEN= /etc/agentteams/agent-core.env | cut -d= -f2)
curl -sSf -X POST http://127.0.0.1:3100/api/v1/pipeline/start \
  -H "Authorization: Bearer $PIPELINE_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "client_code":"acme_beauty_missing_kb",
    "channel":"wecom",
    "industry_id":"1001",
    "goal_ids":["g001","g002"]
  }' | jq
# 期望: { "run_id":"...","gate":"ASK", ... }
# 缺 channel/industry_id/goal_ids 任何一个：HTTP 400 + message
```

### 12.7 LLM 模型三种运行档（不要互串）

来自原文档 §8 / §8.2。生产部署只会用真模型，但要知道另外两种档用来排障，**本地/测试栈才会用**：

| `RUNTIME_MODEL` 档位 | 作用 | 在哪里写 | 输出里会包含 |
|---|---|---|---|
| **production 真实档** | 打真 LLM 网关（Higress） | agent-runtime.env `RUNTIME_MODEL=dashscope:qwen-plus` | 正常客服质量的文字 |
| `deterministic-test` | 链路烟测 stub，回声 | 手工栈 `RUNTIME_MODEL=deterministic-test ./start-local-manual-stack.sh` | `DRY_RUN_OK: <原文>` + 「链路探针」字样 |
| `blueprint-aware-test` | 产物探针（验证 Blueprint/Soul/Skills 绑定） | 手工栈默认档（= 没写 `deterministic-test`） | `BLUEPRINT_OK runtimeAgentId=... soul=... skills=...` + 「产物探针」字样 |

**生产常见误排障**：
- 真测试「聊的是回声不是客服」→ 看看 agent-runtime.env 是不是被人改成了 `deterministic-test`
- 打 Higress 返回 `insufficient_quota` / 429 → 不是自动化问题，是消费者配额；确认 `RUNTIME_LLM_TOKEN` 没被错误地换成了 `RUNTIME_MCP_TOKEN` 或 `HIGRESS_CONSUMER_TOKEN`（原文档 §8.3 最后一段真实踩坑）

### 12.8 生产部署与原测试脚本的对照

把本文 §9/§11 与原仓库脚本做一个最终映射，方便后续遇到报错时去翻原脚本找对应代码位置：

| 本文位置 | 对应原脚本 | 作用 |
|---|---|---|
| §9.3 + §11.3 三份 EnvironmentFile | `docs/agentteams/local-development.env.local`（模板 `local-development.env.example`） | 四件套配置源 |
| §12.2 preflight 清单 | `scripts/run-agentteams-local-dev.sh` 中 `preflight_nest / preflight_runtime / preflight_manager` | 启动前 TCP + L7 探活 |
| §11.4 `npm run db:init` | `scripts/preflight-agentteams-integration.js` 前后置初始化 | 5 表+RLS+策略 |
| §12.3 Manager 抢跑修法 / §12.5 进程树清理 | 原文档 §4.4 `wait_for_nest` + §4.3 `stop_tree()` (pgrep -P) | 时序与退出 |
| §12.4 POST `/api/v1/dryrun` 401 探活 | `start-local-manual-stack.sh:122` / `preflight-agentteams-integration.js:284` POST 401 | Runtime Liveness |
| §9.7 / §11.5 Nginx 反代路径规则 | `agent-console/nginx.conf` (容器内原 nginx.conf，同名) | 路由分域 |
| §11.7 `/usr/local/sbin/agentteams` | `start-local-manual-stack.sh` 的前台常驻 + trap cleanup 的思路 | 运维入口 |

### 12.9 本轮同步对 §9/§11 已做的修正

1. **Runtime 健康探针改 POST**：所有 GET `/actuator/info` / GET `/api/v1/dryrun` 写法全部调整为 POST `/api/v1/dryrun`（见 §12.4 表），并把「401 = 通」写死在探针期望里。
2. **Manager unit 加 `After=` 强链 + ExecStartPre TCP 轮询 Nest 3100**：明确禁止用 HTTP 探 `/api/health` 当作 ready（401 会把循环卡死到超时）。TCP 轮询必须包在子 shell，防止 `probe_tcp_url` 自己的 exit 1 把 systemd 的 ExecStartPre 判为非重试失败。
3. **4 个 unit 补 `KillMode=mixed` + 兜底 ExecStop**：解决 §4.3 所述「子 shell 杀死但端口监听进程残留」问题。
4. **新增 §12.2 统一 preflight**：在启动任何 systemd unit 之前先跑一版 TCP+L7 的清单，**一条不通过就不要启动**。这跟 `run-agentteams-local-dev.sh` 的 preflight→运行 两段式设计一致。
5. **§11.6 的 `agentteams healthz` 运维脚本（§11.7）**：把 Runtime 分支从 `actuator/info`（Java 可能没开 Actuator）统一改成 POST `/api/v1/dryrun`，并以 `200/401` 皆判 OK。
6. **补充 pipeline 四必填 + 三种 RUNTIME_MODEL**：在运维排障阶段可以直接用 curl 验证接口，不会把缺字段报 400 当作服务故障（原文档 §6 真实踩坑）。

---

## 13. 测试环境部署步骤 & 注意事项 (基于集成测试环境依赖清单)

> 数据来源：未脱敏原项目 `AgenticAssistantBase/docs/agentteams/集成测试环境依赖清单.md`（真实测试环境 `10.0.0.1` 的已验证现网文档，其中明确写了：`10.0.0.1` 上 Controller / MinIO / Higress / Element / Dashboard 六件套**已安装并端口映射到宿主**，Nest MCP / Runtime / Manager 仍未部署，PG/Redis 宿主原生版已装但对笔记本仍为 `ECONNREFUSED`，且有真实踩坑的「Docker 网络漂移」导致 11 个 Worker stopped 的问题**。本节把这份文档中与「Ubuntu 生产部署」强相关的规则、端口矩阵、鉴权陷阱、验收阻塞顺序**逐条同步**过来，避免生产重复踩坑。部署目标从「10.0.0.1」换成你的 Ubuntu 生产机 IP（下文记作 `<HOST>`）。
>
> 注：该原始文档 §7.1–§7.5 就是「集成测试目标态 = 全内网宿主部署」，**正好对齐本文 §9 / §11 的全原生 systemd 方案**；而 §7.6 是「笔记本混合跑法」，只在开发期用，生产不采用。

### 13.1 核心工程原则 (违反一定出问题)

| 编号 | 原则 | 原始依据 |
|---|---|---|
| P1 | **状态类资源 (PG / Redis / AgentTeams 平台 / Docker Engine / MinIO 平台桶) 绝对不能放笔记本** | §1 / §4 / §7.1；原：Nest / Java 可以开发期放笔记本，集成测试必须回 `<HOST>` |
| P2 | **两套网络/端口视图严格分开**：①「宿主/笔记本访问」用映射端口 `:18090/:19000/:18080/:13000/:18088`；②「同机进程/容器互访」用容器名/内网 IP + 默认端口 `agentteams-controller:8090` / `agentteams-minio:9000` / `postgres:5432` / `redis:6379` / `agentteams-matrix:6167` | §2 / §3 / §7.4；原：默认值 `localhost:8090/6167/9000` 是本机 Docker 视角，不要照抄 |
| P3 | **绝对不要共用 Matrix 账号**：Human = `@admin` / Manager = `@manager` / Worker = 各自账号；`agent-manager` 进程用了 admin token 直接触发预检拒启动 | §3.2 / §3.3 步 4 / §7.6 身份表；原：`whoami ∈ AGENTTEAMS_MANAGER_IDS` 是强制项 |
| P4 | **两套 MinIO 桶严格分 IAM 身份**：① 平台桶 `agentteams-storage` (AgentTeams 用，≠ `agentteams`) 用 **AgentTeams 运维给的受限身份**；② Artifact 桶 `chatflows-artifacts` (VibeSales 用) 用 VibeSales 独立 IAM 用户，**绝不能用 root/admin** | §3.1 AGENTTEAMS_FS_BUCKET / §11.2.2 分离设计；原：现网实际桶是 `agentteams-storage`，example 里 `agentteams` 是旧值，写错直接 403 |
| P5 | **Higress Bearer Key Auth 转发机制导致：生产联调阶段 `HIGRESS_CONSUMER_TOKEN = MCP_SERVER_TOKEN` 必须**，否则 Nest 上游收到两个 Bearer。若以后要分离必须在 Higress 侧加「上游头改写/注入」规则 | §7.6 Higress 段；原：两值均只放 `*.env.local`，不入库 |
| P6 | **先打通平台，再建库，最后启 Java** (阻塞序)：Controller+MinIO → Manager Matrix 身份+Room/Leader → Worker 网络修复 → PG/Redis → Runtime/持久化。反过来做，库建了白建、进程起了白死 | §6 阻塞顺序 1→4；原：当前开发阶段 `ARTIFACT_STORE=file START_RUNTIME=0` 可以跳过库 |
| P7 | **AgentTeams 平台 Docker 网络一致性（高危）**：如果生产上装 AgentTeams 平台，必须保证 `AGENTTEAMS_DOCKER_NETWORK`（创建 Worker 用）与 Controller 容器自身所在的 Docker 网络**完全同名且是 user-defined network**，否则 Worker 起后在 `mirror_all` 阶段解析不到 `agentteams-controller:9000` → 全 stopped，`leaderReady=false readyWorkers=0/N` | §4 依赖表最后一行 + §6 步 3；原：10.0.0.1 上就踩了这个坑，必须授权重建 Controller/Worker 网络 |

### 13.2 部署拓扑 (Ubuntu 生产单主机 = <HOST>)

完全对应原文档 §7.1 目标态，只是把 `10.0.0.1` 换成你的 `<HOST>`：

```
笔记本/浏览器 (只做客户端)
   │13000 Dashboard   18001 Higress Console  18088 Element  18080 Higress/Matrix CS API
   └─────── HTTPS反代 ────────►
                              Ubuntu <HOST> (唯一运行时宿主)
                              ├ AgentTeams 平台容器
                              │  ├ agentteams-controller:8090  <-> 宿主 :18090
                              │  ├ agentteams-minio:9000       <-> 宿主 :19000  平台桶 agentteams-storage
                              │  ├ Higress  :18080 (LLM/MCP/Matrix CS API)  ;  Console :18001
                              │  ├ Element  :18088  ;  Dashboard :13000
                              │  └ Workers (Chatflows 11 Worker)
                              ├ 原生 PostgreSQL 16 (127.0.0.1:5432 + <HOST>:5432 内网)
                              ├ 原生 Redis 7     (127.0.0.1:6379 + <HOST>:6379 内网)
                              ├ 原生进程 agent-core MCP 面  0.0.0.0:3100  ← Higress 反代当上游
                              ├ 原生进程 agent-core BFF 面  127.0.0.1:3101
                              ├ 原生进程 agent-runtime      127.0.0.1:8088
                              ├ 原生进程 agent-manager      127.0.0.1:8090
                              ├ Nginx 对外 80/443 → 代理到上面四件套 (§9.7)
                              └ 可选：原生 MinIO Artifact 桶 chatflows-artifacts  (0.0.0.0:9000, IAM 专用账号)
```

关键端口对照表（**严格与原文档 §2 现网对齐**，端口错 = 鉴权边界错 = 全链路 401）：

| 组件 | 容器/进程内部端口 | 对外暴露 (人从笔记本访问) | 同机进程互访 / 容器互访 | 备注 |
|---|---|---|---|---|
| AgentTeams Dashboard | 容器内 `:3000` 或 `:80` | `<HOST>:13000` | 同 Docker 网桥内部名 | 只给人看；**不是** Controller REST |
| Higress Console | 容器内 `:8080` | `<HOST>:18001` | — | 配置 MCP / LLM 消费者 |
| Higress 网关 + Matrix CS API | 容器内 `:8080` + Matrix app-service | `<HOST>:18080` | 容器名 `agentteams-matrix:6167` | **MCP 工具调用 / Matrix Client-Server 统一走这里** |
| Element Web (Human HITL) | 容器内 `:80` | `<HOST>:18088` | — | admin 密码登录；Dashboard 同一套 admin |
| Controller REST | 容器内 `:8090` | `<HOST>:18090` | 容器名 `agentteams-controller:8090` | agent-manager 用；Dashboard 13000 不是这个！ |
| MinIO 平台桶 FS | 容器内 `:9000` | `<HOST>:19000` | 容器名 `agentteams-minio:9000` | 桶 = **`agentteams-storage`**；Key=admin，Secret=Dashboard admin 密码 |
| PostgreSQL | **原生** `5432` | 只绑 `<HOST>:5432` (内网)，不 0.0.0.0 | `127.0.0.1:5432` / `<HOST>:5432` | 不要用笔记本 ECONNREFUSED 版本 |
| Redis | **原生** `6379` | 只绑 `<HOST>:6379` (内网) | `127.0.0.1:6379` / `<HOST>:6379` | 同上 |
| agent-core MCP (Nest) | **原生** `3100` | `<HOST>:3100` (给 Higress 当上游) | `127.0.0.1:3100` | **必须监听 0.0.0.0 或 `<HOST>`**，不能 127.0.0.1 (否则 Higress 连不到) |
| agent-core BFF (Nest) | **原生** `3101` | 通过 Nginx 443 | `127.0.0.1:3101` | 只本机能连即可 |
| agent-runtime | **原生** `8088` | 通过 Nginx 或 `<HOST>:8088` 调试 | `127.0.0.1:8088` | 同上 |
| agent-manager | **原生** `8090` | 与 Controller 宿主端口**同号但不同网卡**（这里是 Java 原生，Controller 是容器映射 18090），务必分清楚 | `127.0.0.1:8090` | 不要与 `Controller 宿主映射端口 18090` 搞混，两者同号在不同 IP/网卡上！AgentTeams Controller = 18090；Java Manager = 原生 8090 (只本机或内网 Nginx 反代) |

### 13.3 部署按阻塞序分 6 阶段（照搬原文档 §6 阻塞序）

**任何一个阶段未通过，直接停，不要往下走。**

| 阶段 | 做什么 | 验收判据 (对齐原文档 §3.3 6 步) | 失败后怎么办 |
|---|---|---|---|
| **① Controller + MinIO 打通** | SSH 上 `<HOST>`，抽 `AGENTTEAMS_AUTH_TOKEN` (`docker exec agentteams-controller cat /var/run/agentteams/cli-token`)；读 `~/agentteams-manager.env` 备用；宿主映射 `:18090`/:19000` 存在；MinIO 桶存在 | (a) `curl -H "Bearer <cli-token>" http://<HOST>:18090/api/v1/workers` → 200 JSON (b) mc 签名 ls 可见 `agentteams-storage` (c) 写/读/删 临时 `shared/tasks/task-preflight-*/spec.md` 无遗留 | Controller 没起来 → 先重启平台容器；MinIO 桶名错 → 用 `agentteams-storage` 不要 `agentteams` |
| **② Matrix Manager 独立身份 + Room/Leader** | 用**独立正式账号** `@chatflows-manager-ext:matrix-local.agentteams.io:18080`；优先使用 `AGENTTEAMS_MATRIX_USER_ID + AGENTTEAMS_MATRIX_PASSWORD`，由 `agent-manager` 自行登录/刷新；不要复用容器内 `@manager` 的 guest token。Human 仍用 `@admin:…` 只做审批；`AGENTTEAMS_LEADER_ROOM_ID` 明确回填 `chatflows-build-team.teamRoomID` | (a) `whoami` 返回 `@chatflows-manager-ext:...` 且不为 guest；(b) 能 `join` Team Room 200；(c) `AGENTTEAMS_MANAGER_IDS` 与 `AGENTTEAMS_MATRIX_USER_ID` 同值；(d) `AGENTTEAMS_LEADER_ROOM_ID` 已回填 | 若 `whoami` 是 guest 或 join 403 → 说明误用了 `@manager` guest token；切回独立 Human/正式账号方案，并重新回填密码 |
| **③ 修复 Worker 容器 Docker 网络漂移 (P7)** | SSH `<HOST>`：`docker inspect agentteams-controller | jq .[0].NetworkSettings.Networks`；对比 Controller 启动参数 `AGENTTEAMS_DOCKER_NETWORK`；若不一致 → 重建 Controller 容器或重建 Worker 到同一 user-defined 网络，再触发协调 | 11 Worker `containerState=running`；Controller `/api/v1/workers` 查出来 `leaderReady=true` `readyWorkers=10/10`；Team Active | 生产环境若不装 AgentTeams 平台（直接对接外部平台运维），**此阶段跳过**，由平台方输出 readyWorkers 报告 |
| **④ PG + Redis 开放 + 权限** | 原生 PG `listen_addresses` 至少含 `<HOST>`；`pg_hba.conf` 放行 Docker 网桥 `172.16/12` + `<HOST>` 私网段；Redis `bind` 同样含 `<HOST>` + `requirepass`；建库 `chatflows`；建两个 LOGIN `chatflows_app_login` / `agent_runtime_login`；**`chatflows_tenant_lookup` NOLOGIN BYPASSRLS 严禁授给任何登录用户**；跑 `agent-core/sql/001_agentteams.sql` + `npm run db:init` | (a) 笔记本或 `<HOST>` 连 PG/Redis 都不再 ECONNREFUSED；(b) `psql -U chatflows_app_login -d chatflows -c 'SELECT current_user'`；(c) 5 表+RLS+策略 0 报错；(d) `chatflows_tenant_lookup` \du 显示 Cannot login | LOGIN 角色错授了 BYPASSRLS 或 chatflows_tenant_lookup → rollback 重建 |
| **⑤ 部署 VibeSales 四件套 + Higress 上游注册** | 走 §9 / §11 的全原生 systemd 方案（4 个 unit + Nginx）；特别注意 `WEB_HOST=0.0.0.0`（否则 Higress 打不到）；Higress Console 18001 注册 `chatflows-p1~p4/p3b/p3c` 六个 MCP 的**上游基路径** = `http://<HOST>:3100/mcp-servers/<name>`；**Direct Route path 末位不要加 `/mcp`**（否则会收到重复 `/mcp/mcp`）；鉴权：`HIGRESS_CONSUMER_TOKEN = MCP_SERVER_TOKEN`（P5） | (a) Nest `/healthz` dependencies.* 全 ok；(b) Higress `mcp-servers/<name>/mcp` 正确 token → 200 JSON，缺/错 → 401；(c) Worker 容器内 `mcporter list chatflows-p1` ≈ 3 工具，不是笔记本 curl 算；(d) 四件套 `agentteams healthz` 全绿 | Web/Host 用了 127.0.0.1 导致 Higress 502 → 改 `WEB_HOST=0.0.0.0` 重启 MCP unit；Direct Route 加了 `/mcp` → 删掉； |
| **⑥ 端到端验收 (E2E)** | (1) `node scripts/audit-agentteams-platform-readonly.js` 只读检查；(2) agent-console 同一次向导 → 导出「平台验收 JSON」，文件路径写进 `AGENTTEAMS_PHASE1_RESULT_FILE`；(3) `AGENTTEAMS_CONFIRM_APPLY=chatflows-only ./scripts/run-agentteams-e2e.sh docs/agentteams/integration-test.env.local` | (a) phase1 result `gate=PASS` 且 `client_code` == `AGENTTEAMS_RUN_CLIENT_CODE`；(b) 脚本在 apply 前先验 phase1；(c) 最终 P4 终态 + Blueprint 发布 + Runtime `done` SSE + Nest `run`/`artifact` 齐全 + Matrix 同一 run_id 时间线一致；(d) 不 delete/prune 任何 CR | 任何验收入口都要求 phase1 result 是向导真实产物（不能固定 spec.md 代替）；缺少 → 回到 agent-console 重新跑向导 |

### 13.4 环境变量 / 端口 / IAM 对照 (核心修正)

以下字段是现 PROD-DEPLOY.md §11.3 中**与现网验证结果不一致**的地方，**直接替换 §11.3 模板里对应值**（重要）：

| 原 §11.3 模板值（旧默认） | 现网验证正确值 (生产填这个) | 依据（集成测试环境依赖清单.md） |
|---|---|---|
| AGENTTEAMS_MATRIX_USER_ID=`@agent-manager:localhost` | `@chatflows-manager-ext:matrix-local.agentteams.io:18080` | 当前测试/生产收敛结论：VibeSales manager 使用独立正式 Matrix 账号 |
| AGENTTEAMS_HUMAN_IDS=`@admin:localhost` | `@admin:matrix-local.agentteams.io:18080` | §3.6 Matrix Human token 行 + §3.3 步 4 allowlist |
| AGENTTEAMS_LEADER_IDS=`@chatflows-leader:localhost` | `@chatflows-leader:matrix-local.agentteams.io:18080` (或以 Controller 自动发现结果为准，留空让 Manager 查询) | §3.3 步 5 "Room/Leader 自动发现已通过" |
| AGENTTEAMS_MANAGER_IDS=`@agent-manager:localhost` | `@chatflows-manager-ext:matrix-local.agentteams.io:18080` | 必须与 `AGENTTEAMS_MATRIX_USER_ID` 保持同账号 |
| AGENTTEAMS_CONTROLLER_URL（全原生同机互访） | `http://127.0.0.1:18090` 或 `http://<HOST>:18090` (宿主映射端口)；若 Manager 进 Docker 网用容器名 `http://agentteams-controller:8090` | §2 + §3.1 区分笔记本(18090) vs 同机容器(8090)；**若 Manager 为全原生 systemd 进程：走宿主 18090** |
| CHATFLOWS_TASK_FS_ENDPOINT / BUCKET | `http://<HOST>:19000` + `agentteams-storage`；同机进程可用 `http://127.0.0.1:19000`；旧 `agentteams` 桶名作废 | §2 `10.0.0.1:19000`；§3.1 AGENTTEAMS_FS_BUCKET = **`agentteams-storage`**（明确写了现网实际桶≠旧鉴权文档） |
| AGENTTEAMS_MATRIX_URL | `http://<HOST>:18080` 或同机 `http://127.0.0.1:18080`；**不要用 `:6167`** | §2 `:6167 连不上`；§3.1 / §3.2 方式 C；§7.6 表格 |
| agent-core.env `WEB_HOST` (MCP 面) | `0.0.0.0`（给 Higress/Worker 当上游必须）；若仅本机调试可用 `127.0.0.1` | §7.3 步 4；§7.6 表格 Nest 给 Worker 上游条件；原：Nest 要 Worker 容器可及就不能 127.0.0.1 |
| 新增变量 `HIGRESS_CONSUMER_TOKEN` | 无默认；但生产联调 **必须 == MCP_SERVER_TOKEN**，写进 `.env.local`/宿主 env，否则触发 P5 冲突 | §7.6 Higress 段 2 段文字；两个 Bearer 问题 |
| 新增变量 `AGENTTEAMS_PHASE1_RESULT_FILE` | 指向 agent-console "导出平台验收 JSON" 的绝对路径 (在 `<HOST>` 本地文件系统) | §7.6 末段 2 段；E2E 验收入口前置校验条件 |
| AgentTeams AUTH_TOKEN 实际获取方式 | SSH `<HOST>`: `docker exec agentteams-controller cat /var/run/agentteams/cli-token` | §6 阻塞序 1 + §3.2 §2.2 行；不要从 Dashboard 取 |
| MinIO FS (Task) Secret 获取方式 | `<HOST>` / Dashboard admin 同一套 `AGENTTEAMS_ADMIN_PASSWORD`；AK=admin | §3.2 §2.4 行；§3.6 MinIO root 行；不要用 Artifact IAM |

### 13.5 两个 IAM/权限红线 (必须硬检查)

**红线 1：PG `chatflows_tenant_lookup` 角色禁止授给任何 LOGIN 账号**
原文档 §5 说明：`chatflows_tenant_lookup` 是 SQL 函数 `lookup_run_client` 专用的 NOLOGIN BYPASSRLS owner，只能读 `run.client_code`，**严禁授给任何登录用户**。在 §11.2.1 建完 LOGIN 后补一条：

```bash
psql -h $PG_HOST -U postgres -d chatflows <<'EOSQL'
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_roles r WHERE rolname = 'chatflows_tenant_lookup' AND rolcanlogin = TRUE
  ) THEN RAISE EXCEPTION 'chatflows_tenant_lookup 必须是 NOLOGIN';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
    WHERE r.rolname IN ('chatflows_app_login','agent_runtime_login')
      AND EXISTS (SELECT 1 FROM pg_roles r2 WHERE r2.oid = m.roleid AND r2.rolname = 'chatflows_tenant_lookup')
  ) THEN RAISE EXCEPTION '严禁把 chatflows_tenant_lookup 授给任何登录用户';
  END IF;
END $$;
EOSQL
```

**红线 2：Task FS MinIO 不要与 Artifact MinIO 共用同一 AK/SK，更不要 root/admin**
§11.2.2 已经做了 Artifact 独立 IAM；Task FS（`agentteams-storage`）必须由 AgentTeams 运维给你受限身份，不要顺手把 root/admin 贴到 VibeSales 的 env 里。即使是同一台物理 MinIO，也必须分桶+分 IAM。

### 13.6 Higress MCP 注册脚本用法 (幂等)

原文档提供了 `configure-higress-chatflows-mcp.mjs`（幂等，先 dry-run 再 apply），不要手动去 Higress Console 点点点。**先配置上游基路径**：把所有占位 `higress.example` 替换成 `http://<HOST>:18080/mcp-servers/<name>/mcp`，上游 Nest 端点用 `http://<HOST>:3100`。

```bash
# 先只读 dry-run（不会改 Higress）
set -a
source docs/agentteams/integration-test.env.local
set +a
node scripts/configure-higress-chatflows-mcp.mjs
# 确认输出「七类对象新增/更新范围 = Chatflows 静态源 + 专用 Consumer + 6 MCP」
# 再执行真实写入（硬防误触开关）
node scripts/configure-higress-chatflows-mcp.mjs \
  --apply --confirm chatflows-mcp-only
# 回滚（也必须是 完全匹配脚本期望）
node scripts/configure-higress-chatflows-mcp.mjs \
  --rollback --confirm chatflows-mcp-only
```

**Direct Route 路径陷阱**：脚本里上游 Direct Route 的 `path` 必须是 `/mcp-servers/<name>`，**不要加尾部 `/mcp`**。Higress 会在对外入口自动追加 transport 后缀，因此最终用户看到的对外 URL 仍是 `/mcp-servers/<name>/mcp`。手动加了会导致上游收到 `POST /mcp-servers/<name>/mcp/mcp` → 404。

### 13.7 验收时只打 Manager，不直打 Nest CLI

§7.6 末尾明确：真实验收**只调用 Manager HTTP（POST `/api/v1/pipeline/runs` 得唯一 `run_id`）**，脚本**不自行生成 UUID**，也不走旧 `run/resume` CLI。最终验收要核对 6 个面的 `run_id` 一致性：
1. Controller `/api/v1/workers` / Team Room timeline (Matrix)
2. Nest `GET /api/v1/pipeline/runs/:run_id` + artifacts
3. Manager 端 Human 审批流
4. P4 终态 + Blueprint 已发布
5. Runtime `message` 与 `done` SSE
6. AgentLoop 写入端点存在且 OK；若只有写入端没查询端，脚本会明确 `pending`，不能伪报

### 13.8 最小凭据清单 (精简到不能再删，与 §11.8 合并去重)

✅ 已具备：Ubuntu 生产机 Docker、PG、Redis、MinIO（或 AgentTeams 平台 MinIO）。
⬜ 待提供（直接照原文档 §3.6 账号表 + §6 阻塞序整理）：

| # | 名称 | 怎么取 | 填到哪个变量 | 校验 |
|---|---|---|---|---|
| ① | `<HOST>` 公/私 IP | ifconfig/云控制台 | 所有 URL 里的 host | SSH 能上去且 docker ps 有 agentteams-controller |
| ② | AGENTTEAMS_AUTH_TOKEN (cli-token) | `<HOST>` SSH: `docker exec agentteams-controller cat /var/run/agentteams/cli-token` | `AGENTTEAMS_AUTH_TOKEN` | `curl -H "Bearer $T" http://<HOST>:18090/api/v1/workers` 200 |
| ③ | AGENTTEAMS_ADMIN_PASSWORD | 平台运维给 | `AGENTTEAMS_FS_SECRET_KEY` + Dashboard / Matrix 登录 | Element 18088 admin 登录 200；MinIO root 登录 200 |
| ④ | Matrix Manager 独立正式账号凭据 | 推荐通过 Human 机制创建 `chatflows-manager-ext` 后回填密码；或平台运维提供 | `AGENTTEAMS_MATRIX_USER_ID` + `AGENTTEAMS_MATRIX_PASSWORD`（推荐） | `whoami` 返回 user_id == `@chatflows-manager-ext:…`，且不为 guest；`join teamRoomID` 返回 200 |
| ⑤ | Matrix Human admin Token (可选，只用于审批验收) | 方式 C 密码登录 admin 签发 | 仅 `run-agentteams-e2e.sh` 运行期用 | whoami == @admin:… 且 ≠ ④ |
| ⑥ | Task FS 4 件套 (桶=agentteams-storage) | 同 ③，host=<HOST>:19000 | `CHATFLOWS_TASK_FS_ENDPOINT`/4 字段 | SDK 签名写读删临时 spec.md 齐全；不要用 Artifact IAM |
| ⑦ | Nest MCP Server Token (≥16) | 自己生成 | `MCP_SERVER_TOKEN` 同时 = `HIGRESS_CONSUMER_TOKEN` (P5) | Higress 正反例 200/401 各通一次 |
| ⑧ | 其它控制/鉴权 Token 共 11 项 | §11.3 openssl rand 自动生成 | PIPELINE/BLUEPRINT/APPROVAL_SIGNING/WEB/RUNTIME/MANAGER 的 Auth+Admin | 成对 Admin ≠ Auth；签名密钥 ≥32 |
| ⑨ | LLM 网关 Token×2 (Nest 侧 + Runtime 侧) | Higress 侧 18001 里申请 | §11.3 NEST_LLM_* / RUNTIME_LLM_* | 各自模型 chat completion 能通，无 429/insufficient_quota |
| ⑩ | 业务 MCP URL 共 2 条 | Higress 18001 已有配置 → `<HOST>:18080/mcp-servers/<name>/mcp` | `P3C_BUSINESS_MCP_URL` / `RUNTIME_MCP_URL` + `RUNTIME_MCP_TOKEN` | 正确 Bearer → 200，错/缺 → 401 |
| ⑪ | Higress上游Nest基路径 + PHASE1_RESULT_FILE | 向导导出产物 + 手动注册/脚本注册 | Higress Console 上游 `<HOST>:3100`；`AGENTTEAMS_PHASE1_RESULT_FILE` | Worker 容器内 `mcporter list chatflows-p1` ≥3；phase1 result `gate=PASS` client_code 对 |
| ⑫ | 公网域名+HTTPS证书/反代方案 | 运维标准 | Nginx 80/443 + cert | 浏览器 443 打开 AgentConsole |
| ⑬ | (装 AgentTeams 平台时必须) Docker 网络名一致 | `<HOST>` SSH 比对 Controller 所在网与 `AGENTTEAMS_DOCKER_NETWORK` | Controller 启动参数 | 11 Worker running / leaderReady=true |

### 13.9 本次同步对 §9 / §11 / §12 的修正清单

1. **§11.3 agent-core.env `WEB_HOST`**：从 `127.0.0.1` 改为 `0.0.0.0`（否则 Higress/Woker 反代不通 MCP 上游 3100，Nest 只监听 lo 就会 502，§13.4 第 8 行）。**同时说明：仅本机 BFF 面仍建议 127.0.0.1**；两份 main 入口分别绑各自网卡最佳。
2. **§11.3 Manager Matrix 身份四条变量**：`AGENTTEAMS_MATRIX_USER_ID` / `AGENTTEAMS_HUMAN_IDS` / `AGENTTEAMS_LEADER_IDS` / `AGENTTEAMS_MANAGER_IDS` 的默认 `@xxx:localhost` 全部替换为 `@xxx:matrix-local.agentteams.io:18080` 格式 (§13.4 第 1–4 行)，否则 Manager 预检 `whoami ∈ allowlist` 失败 → 直接退出。
3. **§11.3 AgentTeams 三大 URL + 桶**：Controller URL → 宿主映射 `http://<HOST>:18090`（全原生 systemd 场景，不用容器内网名）；Matrix URL → 不要用 6167，改 `<HOST>:18080`；Task FS Endpoint → `<HOST>:19000`，BUCKET = **`agentteams-storage`**（不是 example 里的旧 `agentteams`）。
4. **§11.2.1 建 LOGIN 后增加红线 1 DO 块**：确保 `chatflows_tenant_lookup` 是 NOLOGIN 且未被授给任何登录账号，避免 RLS 被绕过。
5. **§12.2 preflight 清单补充 2 条**：① Manager 侧 `whoami` 校验身份白名单；② MinIO Task FS 桶名 **不得** 是 `agentteams`，必须 `agentteams-storage`；否则不通过就 exit 1。
6. **§12.3 Manager 启动 ExecStartPre 再加一条**：阻塞在 Controller `GET /api/v1/workers` readyWorkers>0 才启动，避免 Worker 还在 stopped 就跑 Manager 造成 409。
7. **新增 §13.7 验收 6 面 run_id 一致性**：端到端验收入口**只走 Manager HTTP**，禁止直打 Nest CLI 或伪造 UUID；必须有 `AGENTTEAMS_PHASE1_RESULT_FILE` 来自 agent-console 向导真实产物。
8. **补充 P7 Docker 网络漂移检查**：生产若装 AgentTeams 平台，在阶段 ③ 做 `docker inspect` 对比，保证 Controller 与 Worker 同一个 user-defined 网络；不满足则拒绝继续阶段 ④–⑥。

### 13.10 2026-08-18 收敛结论（配置项类型 + 脚本语义）

#### 13.10.1 integration.env 中今日确认的配置项类型

| 配置项 | 类型 | 说明 |
|---|---|---|
| `AGENTTEAMS_ADMIN_USER` / `AGENTTEAMS_ADMIN_PASSWORD` | **手工填写（依赖外部 AgentTeams / Higress 环境）** | 用于登录 Higress Console；当前生产已填 `admin` / `admind2189f3aafc8` |
| `MCP_SERVER_TOKEN` / `HIGRESS_CONSUMER_TOKEN` | **手工生成，且两边必须同值** | 共享 Bearer；Higress 转发给 Nest MCP，现网已确认 `MCP_SERVER_TOKEN == HIGRESS_CONSUMER_TOKEN` |
| `PIPELINE_APPROVAL_SIGNING_SECRET` / `CHATFLOWS_APPROVAL_SIGNING_SECRET` | **手工生成，且两边必须同值** | 审批签名密钥，长度应 ≥ 32 |
| `AGENTTEAMS_MATRIX_USER_ID` / `AGENTTEAMS_MANAGER_IDS` | **手工填写，且两边必须同账号** | 当前生产收敛为 `@chatflows-manager-ext:matrix-local.agentteams.io:18080` |
| `AGENTTEAMS_MATRIX_PASSWORD` | **脚本后手工回填** | 推荐方式；由 Human/独立正式 Matrix 账号创建后回填，`agent-manager` 用 user+password 自行登录与刷新 |
| `AGENTTEAMS_MATRIX_ACCESS_TOKEN` | **可选，不推荐作为长期真源** | 若填写，必须是非 guest token；不要再固化旧 `@manager` guest token |
| `AGENTTEAMS_WORKER_MATRIX_TOKEN` | **外部真实 token，建议运行脚本时临时注入** | 不是自定义伪值；也不是当前仓库里会自动刷新出来的 token。建议优先使用 `chatflows-leader` 或专用只读观察账号的 Matrix token，执行 `configure-agentteams-worker-mcp.js` 时临时传入 |
| `CHATFLOWS_TASK_FS_SECRET_KEY` | **脚本生成 / 脚本维护** | `configure-chatflows-task-storage.js --apply` 或 `configure-chatflows-task-storage-new.sh apply` 可生成/回写；不建议手工频繁改 |
| `HIGRESS_CHATFLOWS_UPSTREAM` | **手工填写（依赖当前网络拓扑）** | 当前原生部署场景固定为 `10.1.1.42:3100`；**不要**使用脚本默认值 `127.0.0.1:13104`，因为 Higress 容器/Pod 视角下 127.0.0.1 是自身 loopback |

#### 13.10.2 共享 consumer 模式 = 当前测试/生产应收敛的目标态

经测试环境实际查询 `GET /v1/mcpServer/{chatflows-p1..p4}` 的 `consumerAuthInfo.allowedConsumers`，6 条 Chatflows MCP route 当前全部是：

```json
["chatflows-mcp-local"]
```

因此，**测试环境实际运行态 = 共享 consumer 模式**。生产环境应与测试环境保持一致，统一采用：

1. Higress 6 条 MCP route 只绑定共享 consumer：`chatflows-mcp-local`（或 `HIGRESS_CHATFLOWS_CONSUMER_NAME` 显式指定的同义名）。
2. Worker 访问 Higress MCP 网关时，统一使用 `HIGRESS_CONSUMER_TOKEN`。
3. 允许 Higress Console 中额外存在 `worker-*` consumers，但**不要求**把这些名字挂到 `allowedConsumers` 里作为当前 Chatflows 生产链路的目标态。

这与 `configure-higress-chatflows-mcp.mjs`、`configure-agentteams-worker-mcp.js`、`fix-agentteams-worker-mcp-token.js`、`watch-agentteams-worker-mcp-token.js` 的当前实现保持一致。

#### 13.10.3 今日新增 / 修正脚本

1. **新增** `scripts/configure-chatflows-task-storage-new.sh`
   - 用途：生产环境下替代直接裸跑 `configure-chatflows-task-storage.js` 的排障/兼容入口。
   - 解决的问题：
     - 先把宿主 `integration.env` 复制到容器 `/envFile`；
     - 强制用 `bash`，避免 `sh/dash` 的 `set -o pipefail` 兼容性问题；
     - `mc admin policy create` 改为显式使用临时 policy 文件，而不是 stdin；
     - 保留原始 stderr，便于定位 MinIO / mc 报错。
   - 当前生产已验证：`check` / `apply` / 原 `configure-chatflows-task-storage.js --check` 均可通过。

2. **新增** `scripts/init-agentteams-worker-consumers.js`
   - 用途：初始化创建 10 个**执行 Worker** 对应的 Higress consumers（不含 `worker-chatflows-leader`）。
   - 语义：`plan` 只看缺失项；`--apply --confirm worker-consumers-only` 只补建缺失项；若同名对象已存在且是合法 `key-auth + BEARER` 结构，则保持不动。
   - 注意：此脚本创建的是 **Higress consumer 对象**，不是 AgentTeams Worker CR。

3. **修正** `scripts/configure-agentteams-higress.js`
   - 旧语义：要求 `manager + worker-*` 独立 consumer 模式，并把每条 route 的 `allowedConsumers` 改为各自的细粒度列表。
   - 新语义：与测试环境实际运行态、`configure-higress-chatflows-mcp.mjs` 以及 Worker MCP 修复脚本统一，改为**共享 consumer 模式**：
     - 只要求共享 consumer（默认 `chatflows-mcp-local`）存在；
     - 把 6 条 MCP route 收敛到 `allowedConsumers=[sharedConsumer]`；
     - 继续负责为 `mcp-server-*.internal` Ingress 注入 `Authorization Bearer <MCP_SERVER_TOKEN>` 的 header-control，并继续做带/不带 token 的探活。
   - 对 `run-agentteams-stack-e2e.sh` 的影响：**有明确影响**，它不再验证 `worker-*` 是否在 route allowlist 中，而是收敛到当前测试环境已验证通过的共享 consumer 模式。

#### 13.10.4 生产执行顺序（按今日收敛后的最小闭环）

1. `node scripts/configure-higress-chatflows-mcp.mjs`（先 plan）
2. `node scripts/configure-higress-chatflows-mcp.mjs --apply --confirm chatflows-mcp-only`
3. `node scripts/init-agentteams-worker-consumers.js prod-deploy/integration.env`（可选 plan；仅当要补齐 `worker-*` consumer 对象时使用）
4. `node scripts/init-agentteams-worker-consumers.js prod-deploy/integration.env --apply --confirm worker-consumers-only`（可选 apply）
5. `node scripts/configure-agentteams-higress.js prod-deploy/integration.env`
6. `node scripts/configure-agentteams-leader-tools.js`
7. 执行 `configure-agentteams-worker-mcp.js` 前，如需查询 manager-free Team Room，则临时注入 `AGENTTEAMS_WORKER_MATRIX_TOKEN='<真实 Matrix token>'`

#### 13.10.5 一个常见误区（务必避免）

`integration.env` 是**统一真源文件**，当前仓库中推荐使用 `prod-deploy/integration.env`，主要供：

- 直接读取文件内容的脚本（如 `configure-chatflows-task-storage.js` / `configure-agentteams-higress.js`）
- 或拆分脚本 `split-integration-env.sh`

使用。

它**不保证天然可被 `source` 成为干净 shell env**。若文件中存在历史反引号 / CRLF，应先做净化，再 `set -a; . <(sed 's/\r$//; s/`//g' prod-deploy/integration.env); set +a`。对只接受 `<integration.env>` 路径参数的脚本，优先直接传文件路径，不要强依赖 `source`。
