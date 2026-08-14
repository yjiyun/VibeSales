# VibeSales

`VibeSales` 是 Agentic Assistant 的平台层仓库，负责控制台、平台编排、运行时、AgentTeams 资源与技能包，并集成业务逻辑子仓 `agent-core/`。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](./LICENSE)

## 项目概览

当前仓库由两层组成：

- 平台层：`agent-console`、`agent-manager`、`agent-runtime`、`agentteams-resources`、`worker-packages`
- 业务核心层：`agent-core/`，它是一个独立子仓库

本文中与 AgenticAssistant 业务核心的交互统一使用占位符 `VIBE_SALES_CORE` 指代。在当前工作区里，`VIBE_SALES_CORE` 的实际本地承载目录就是 `agent-core/`。

## 文档导航

建议按下面顺序阅读：

- [README.md](./README.md)：项目总览、模块定位、快速开始
- [ARCHITECTURE.MD](./ARCHITECTURE.MD)：核心模块、交互链路、运行模式
- [DEPLOY.MD](./DEPLOY.MD)：环境要求、按操作系统区分的部署方式、验证步骤

## `agent-core` 业务核心定位

仓库里新增的 `agent-core/` 已经承接原先业务核心仓的职责，包括：

- P1-P4 业务管线
- NestJS Web / MCP / CLI 三个入口
- Blueprint 管理与产物存储
- PostgreSQL / MinIO / YunFlow / agent-runtime 集成

## 总体架构

```text
agent-console
   |-- /api ----------> agent-core (VIBE_SALES_CORE BFF / Web)
   |-- /orchestration -> agent-manager
   `-- /runtime ------> agent-runtime

agent-manager
   |-- Controller REST -> AgentTeams
   |-- Matrix ---------> Leader / Human / Worker
   `-- MinIO ----------> shared/tasks/task-{run_id}/

agent-core
   |-- CLI ------------> P1 / P2 / 回归验证
   |-- Web ------------> 向导 / BFF / 控制面
   `-- MCP ------------> P1-P4 工具面 / Blueprint 管理

agent-runtime
   |-- PostgreSQL -----> Blueprint / Binding / Skills
   |-- Redis ----------> 分布式状态
   `-- Higress/LLM ----> 运行时模型与 MCP
```

更详细的系统说明见 [ARCHITECTURE.MD](./ARCHITECTURE.MD)。

## 仓库结构

```text
VibeSales/
├── agent-console/           # Vue 3 控制台
├── agent-manager/           # Java 平台编排器
├── agent-runtime/           # Java 运行时
├── agent-core/              # 独立子仓：业务逻辑核心（VIBE_SALES_CORE）
├── agentteams-resources/    # Team / Worker / Human 声明
├── worker-packages/         # Worker 技能包
├── catalogs/                # 词表真源
├── prompts/                 # 提示词模板
├── flows/                   # 工作流与模板资产
├── deploy/                  # Docker Compose 与集成配置
├── scripts/                 # 启动、部署、验证脚本
└── docs/                    # 设计、工程、AgentTeams 文档
```

## 核心组件

| 组件 | 技术栈 | 作用 |
|------|--------|------|
| `agent-console` | Vue 3 + Vite + Element Plus | 向导、编排看板、试聊入口 |
| `agent-manager` | Java 17 + AgentScope | 平台外侧编排、审批流、任务跟踪 |
| `agent-runtime` | Java 17 + AgentScope Harness | Blueprint 投影、SSE 对话 |
| `agent-core` | NestJS + TypeScript + PostgreSQL + MinIO | P1-P4 业务核心、MCP 工具面、Web/BFF、Blueprint 管理 |
| `agentteams-resources` | YAML | AgentTeams 平台资源定义 |
| `worker-packages` | Markdown Skill 包 | Worker 执行规范与能力边界 |

## `agent-core` 提供的业务能力

`agent-core` 是整套系统的业务中枢，负责以下能力：

- P1：向导与意图分诊
- P2：模板筛选、排序与裁决
- P3：模板个性化
- P3B：从零生成工作流
- P3C：组装 Agent Blueprint
- P4：导入、绑定、dry-run、人工审批

它有三个入口：

| 入口 | 命令 | 用途 |
|------|------|------|
| CLI | `npm run cli -- <command>` | 本地验证、回归测试 |
| Web / BFF | `npm run web` / `npm run start:web` | 向导、前端联调 |
| MCP Plane | `npm run mcp` / `npm run start:prod` | 生产工具面、审批、Blueprint 管理 |

## 运行模式

### 1. `agent-core` 本地最小验证

在 `agent-core/` 内可直接跑业务核心：

```bash
cd agent-core
cp .env.example .env
npm install
npm run cli -- p1 --client-code <CLIENT_CODE> --triage fixtures/p1/beauty/pass-full.json
```

### 2. 根仓本机最小验证

适合快速点通 UI：

```bash
./scripts/start-local-manual-stack.sh
```

默认会启动：

- `agent-core` Web / BFF
- `agent-runtime`
- `agent-console`

### 3. 本机接真实平台联调

```bash
./scripts/run-agentteams-local-dev.sh all
```

### 4. Docker Compose 完整部署

```bash
./scripts/deploy-agentteams-stack.sh deploy/agentteams/integration.env
```

完整步骤见 [DEPLOY.MD](./DEPLOY.MD)。

## 外部依赖

完整部署通常依赖以下服务：

- PostgreSQL
- Redis
- MinIO / S3
- AgentTeams Controller
- Matrix
- Higress 网关
- OpenAI-compatible LLM 网关
- YunFlow

其中 `agent-core` 还负责与：

- Blueprint Admin
- MCP Tools
- 审批控制面
- 业务产物对象存储

这些能力的对外接口见 [ARCHITECTURE.MD](./ARCHITECTURE.MD)。

## 开源许可证说明

本仓库整体采用 [Apache License 2.0](./LICENSE) 发布。

其中：

- `agent-manager/` 基于 AgentScope Java 的 `agentscope-core` 构建
- `agent-runtime/` 基于 AgentScope Java 的 `agentscope-harness` 构建
- AgentScope Java 开源项目地址：`https://github.com/agentscope-ai/agentscope-java`
- 相关第三方归属说明见 [NOTICE](./NOTICE)

如果后续继续直接引入或改写 `agentscope-java` 源码文件，建议在对应源码文件中保留原有 Apache 2.0 版权头。

## 关键脚本

| 脚本 | 说明 |
|------|------|
| `scripts/start-local-manual-stack.sh` | 本机最小三件套 |
| `scripts/run-agentteams-local-dev.sh` | 本机四件套联调 |
| `scripts/deploy-agentteams-stack.sh` | Docker Compose 完整部署 |
| `agentteams-apply.sh` | 向 AgentTeams 平台应用资源 |
| `sanitize-for-github.js` | 复跑式 GitHub 脱敏脚本 |
| `verify-all.sh` | 全量验证 |
| `verify-contracts.sh` | 契约验证 |

## 文档入口

- [ARCHITECTURE.MD](./ARCHITECTURE.MD)：系统架构说明，含 `agent-core` 业务架构
- [DEPLOY.MD](./DEPLOY.MD)：根仓部署说明，含环境要求、OS 差异与验证步骤

说明：`docs/`、`flows/`、`PPT/` 在当前 GitHub 脱敏提交流程里属于例外目录，默认不作为公开提交内容。

## 快速开始

```bash
git clone <VibeSales 仓库地址>
cd VibeSales

# agent-core 是独立子仓
git clone <VIBE_SALES_CORE 仓库地址> agent-core
```

如果只是先看业务核心，推荐直接进入 `agent-core/` 做独立验证；如果要走完整平台链路，再回到根仓跑 `agent-console + agent-manager + agent-runtime + agent-core` 联调。

## License

本仓库采用 [Apache License 2.0](./LICENSE)，第三方归属说明见 [NOTICE](./NOTICE)。
