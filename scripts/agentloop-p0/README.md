# AgentLoop P0 探针

在改任何业务进程代码前，先跑这个探针验证 [AgentLoop 接入设计方案-最终.md](../../docs/agentteams/agentLoop/abutment/AgentLoop接入设计方案-最终.md) 的 **P0 全局阀门**：OTLP 直发通路 + AI Agent 面板识别是否成立。它**不碰任何业务代码**，零依赖（只用 Node 内置 `crypto` + `fetch`）。

## 用法

```bash
ARMS_LICENSE_KEY='<你的 LicenseKey>' node scripts/agentloop-p0/otlp-probe.mjs
```

可选环境变量覆盖（默认值取自方案 §1）：

| 变量 | 默认 |
|------|------|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 广州 SLS OTLP endpoint |
| `OTEL_SERVICE_NAME` | `vibe-sales-p0-probe` |
| `AGENTLOOP_WORKSPACE` | `agentloop-7f371f9a844483cf38ba3e84bc46add5` |
| `AGENTLOOP_PROJECT` | `proj-xtrace-...-cn-guangzhou` |

`ARMS_LICENSE_KEY` **只从环境注入**，脚本不打印、不硬编码。

## 它做什么

手拼一条标准 **OTLP/HTTP JSON** payload，发一棵最小 GenAI span 树：

```
invoke_agent (根, gen_ai.session.id = run_id)
└── chat      (子, gen_ai.usage.input/output_tokens)
```

带齐面板识别所需的属性：`gen_ai.operation.name` ∈ {invoke_agent, chat}、`gen_ai.session.id`、`gen_ai.input/output.messages`、Resource 的 `acs.arms.service.feature=genai_app`。

## 判读结果

- **HTTP 2xx** → P0-6 通过（连通/鉴权/参数 OK），OTLP JSON 通路成立。
- 然后**人工**登 AgentLoop 控制台 → **AI Agent 可观测**，按脚本打印的 `trace-id` / `session.id` 查，确认 **P0-1**：
  1. 应用列表出现 `vibe-sales-p0-probe`；
  2. 能解析出 **Agent(invoke_agent) + Chat(chat)** 节点且有父子层级；
  3. Input/Output 非空、token 可见。
- 三点全成立 = **P0-1 通过，OTLP 路线坐实**，可按方案 §7/§8 改三个进程代码。
- 若"收到 trace 但认不出 Agent/Chat 结构" = 触发方案 §7 协议重估开关。

## 非 2xx 排查

| 现象 | 可能原因 |
|------|----------|
| 连接失败 | 网络不可达；先 `curl` 该 endpoint 应回 `405` |
| 401/403 | LicenseKey 无效或与 workspace/project 不匹配 |
| 415 | 端点只收 OTLP/protobuf 不收 JSON（本探针实测 200，一般不会） |
| 4xx + 参数报错 | 检查 `genai_app` 拼写、workspace/project 值 |

## 清理

探针用独立 `service.name=vibe-sales-p0-probe`，与三个业务进程区分——验证完可在控制台按此名或 trace-id 忽略/清理这些测试数据，不会混入业务链路。
