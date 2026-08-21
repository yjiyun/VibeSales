# sales-customer-agent

当前目录不再只被视为“销售客服正式项目骨架”，而被重新定义为：

- 销售客服场景下的首个 Agent 资产化示例工程
- 未来 Agent 生成向导生成结果的参考样板
- 用于验证 `Skill / Tool / Rule / Knowledge` 资产边界是否能在 AgentScope 下稳定落地的示例项目

这份 README 主要帮助第一次打开项目时，快速看懂：

- 这个目录为什么重要
- 它和未来生成向导是什么关系
- 当前做到哪一步
- 怎么运行和继续扩展

## 当前阶段

当前已经进入“示例工程先行验证”阶段：

- 正式项目目录已创建
- Maven 工程已初始化
- 基础目录结构已落位
- 配置文件骨架已创建
- 启动类可编译、可执行
- 业务校准层文档骨架已创建
- `/api/chat` 已支持 `input_data` 结构化上下文
- 验证页已支持模拟 `agent-connector -> sales-customer-agent` 接入协议
- 已支持本地 `.env` 轻量加载
- 已新增 `/api/health/knowledge` 百炼知识库健康检查接口

当前阶段的验证重点不是继续把业务逻辑堆在主 Agent 中，而是逐步验证：

- 主 Agent 能否保持薄编排壳
- Skill 能否承接业务流程骨架
- Tool 能否承接业务动作边界
- Rule 能否从主 Agent 中独立出来
- Knowledge 能否作为独立资产治理

## 目录说明

```text
sales-customer-agent/
├── docs/
├── src/
├── scripts/
├── .agentscope/workspace/
├── .env.example
├── .gitignore
├── pom.xml
└── README.md
```

## 运行方式

先加载本机环境变量：

```bash
source ~/.zshrc
```

编译：

```bash
mvn -q -DskipTests compile
```

运行启动类：

```bash
mvn -q -DskipTests exec:java
```

如果需要挂载 AgentLoop 可观测探针（验证链路数据能否上报），用 `scripts/run-with-agent.sh` 代替上面这条命令，前提是 `.env` 里配置了 `AGENT_JAVA_AGENT_JAR`/`AGENT_OTEL_LICENSE_KEY`/`AGENT_OTEL_CMS_WORKSPACE`（详见 [14-AgentLoop.md](../docs-Final/14-AgentLoop.md)）：

```bash
./scripts/run-with-agent.sh
```

访问验证页：

```text
http://localhost:18080/
```

访问知识库健康检查：

```text
http://localhost:18080/api/health/knowledge?q=补水方案
```

## 运行时注意事项（踩坑清单）

### 1. 读「已发布蓝图」必须单独配 `AGENT_BLUEPRINT_JDBC_*`

runtime 里有**三套互不相通**的数据库/蓝图配置，名字不同、用途不同，配错不会报错、只会静默降级：

| 变量 | 谁读 | 用途 |
|------|------|------|
| `AGENT_BLUEPRINT_SOURCE` + `AGENT_BLUEPRINT_JDBC_URL` / `_USERNAME` / `_PASSWORD` | `AppConfig` → `BlueprintSourceFactory` | **读 PG 里 `PUBLISHED` 蓝图**（试聊要用的就是这条） |
| `AGENT_RUNTIME_DATABASE_URL` + `DATABASE_USER` / `DATABASE_PASSWORD` | `RuntimeApplication` | 启动期 schema/role 健康检查（`/healthz`） |
| `BLUEPRINT_STORE` | Nest（`agent-core`），**不是 runtime** | Nest 侧产物仓选择 |

只配了后两个（很容易，因为它们名字更「显眼」）时：`blueprintJdbcConfigured()` 恒为 `false`，`JdbcPublishedBlueprintSource` 根本不会被构造，唯一蓝图来源退化成 classpath 里的几个示例（`yjiyuncom` / `hanmei_apparel`）。**任何真实租户都查不到，试聊回复变成 `prompts/fallback/` 里的通用文案（品牌名固定是 VibeSales），与实际发布内容无关。**

之所以是「静默」：`AGENT_BLUEPRINT_FALLBACK_TO_CLASSPATH` 默认 `true`，查不到就回落，不抛异常。

排查第一步用调试接口，不要靠猜：

```bash
curl -s "http://127.0.0.1:8088/api/debug/agent-blueprint?clientCode=<租户>&runtimeAgentId=<agentId>" \
  -H "authorization: Bearer $RUNTIME_AUTH_TOKEN" | python3 -m json.tool
```

- `status=ok` 且 `sourceType=jdbc_published` → 蓝图链路正常。
- `status=blueprint_not_found` 且 `scopes` 里只有 `yjiyuncom` / `hanmei_apparel` 这类示例 → JDBC 源没生效，回头查上表第一行。

### 2. 业务表有 RLS，查询必须带上真实 `clientCode`

`agent_blueprint` / `agent_binding` 都是 `FORCE ROW LEVEL SECURITY`，策略是 `client_code = current_setting('app.client_code', true)`（见 `agent-core/sql/001_agentteams.sql`）。`agent_runtime_login` **没有** `BYPASSRLS`。

所以 `JdbcPublishedBlueprintSource.PostgresRowProvider` 每条连接都要先 `set_config('app.client_code', <真实 clientCode>, true)`，否则查询**返回 0 行而不是报错**——表现同样是「蓝图明明已发布，试聊却查不到」。手工用 psql 复核时也要先设这个 GUC，裸 `select` 恒 0 行，很容易误判成「数据没写进去」。

跨租户的 `loadAllPublished()`（`/api/debug/agent-blueprint` 不带 `clientCode` 时用）与这套隔离设计天然冲突，当前保持原状：该角色列不出跨租户结果，不要据此判断「库里没数据」。

### 3. `sceneCode` 与 `runtimeAgentId` 是两个维度，不能互相顶替

- `runtimeAgentId`：形如 `<场景>-<租户>`（如 `beauty_wecom_cs-acme_agri`），P4 `bindProject` 生成。
- `sceneCode`：要对上 Blueprint 的 `meta.scenarios` 元素（如 `beauty_wecom_cs`）。

`JdbcPublishedBlueprintSource.sceneRank` 对非空 `sceneCode` 做**精确匹配**，对不上就把候选整个判掉。所以任何往 `sceneCode` 里塞 `runtimeAgentId`（或塞 `"sales-service"` 这类兜底占位值）的写法，都会凭空造出一个永远匹配不上的过滤条件，静默落回 fallback。试聊入口不传 `sceneCode` 时就该**留空**（表示不限定场景），交给 `runtimeAgentId` 单独定位。

同理，`CustomerContext.normalizedSceneCode()` 的默认值 `"sales-service"` 只适合日志/展示，**不要**拿它做蓝图匹配入参。

### 4. 改完代码必须 `build-dist`，`mvn compile` 不算

`run.sh` / `run_linux.sh` 跑的是 `target/dist/classes`，`mvn compile` 只更新 `target/classes`。改完代码只 compile 就重启，行为仍是旧的。核对：

```bash
./build-dist-linux.sh     # Linux；macOS 用 ./build-dist.sh
javap -p -cp target/dist/classes com.vibesales.salesagent.blueprint.JdbcPublishedBlueprintSource | grep resolve
```

### 5. Linux 上起 runtime 要注意脚本与 PATH

- `run.sh` 的 `pick_java_home()` 探测顺序是 `JAVA_HOME` → macOS Homebrew 路径 → `mvn -version` 推出的 JDK。这台 Linux 上若 `JAVA_HOME` 未设且 `mvn` 不在 `PATH`，会报一句 macOS 风格的 `agent-runtime needs JDK 17+`，实际是环境变量缺失，不是 JDK 没装。`run_linux.sh` 不看 `JAVA_HOME`，直接用 `PATH` 里的 `java`。
- 通过 `scripts/run-agentteams-local-dev.sh all` 起栈时，它是**进程守护**：单个组件退出会自动 respawn，但 respawn 只重新 `eval` 启动函数，**不会重读磁盘上的 env 文件**。所以改完 env 必须重启这个守护脚本本身，光杀 runtime 子进程没用。
- 同一个守护脚本里 `agent-manager/run.sh` 依赖 `mvn` 在 `PATH` 上（它用 `mvn -version` 定位 JDK）；缺了会 `status=127` 反复重启，超过 5 次会把**整栈**拉下来（含 runtime）。

## 设计原则

- 薄 `Agent Shell`
- `Skill-first`
- `Tool-as-Boundary`
- `Rule-as-Asset`
- 百炼知识库作为 `Knowledge` 层
- 业务真值继续走现有后端接口

## 与生成向导的关系

当前项目不是生成向导本体，而是未来生成向导生成结果的示例样板。

这意味着：

- 当前目录结构要尽量可模板化
- 当前 Skill / Tool / Rule / Knowledge 设计要尽量可复用
- 当前主 Agent 不应继续承载大量行业业务逻辑
- 当前文档要同时服务于“示例工程落地”和“未来模板抽象”

## 当前接入口径

当前项目按新的系统边界推进：

- `agent-connector` 继续负责外部消息接入
- `sales-customer-agent` 负责主编排、会话治理和业务 Tool 调用
- `conversationId` 在本项目内作为内部持久会话 ID 使用
- `robotKey`、`robotConversationId`、`chatUser` 继续作为外部会话定位锚点保留

`/api/chat` 当前兼容两种方式：

- 早期最小闭环使用的扁平字段
- 推荐使用的 `input_data` 结构化上下文

推荐请求形态示意：

```json
{
  "message": "你好，我想继续看适合油皮的推荐。",
  "tenantId": "pending-tenant",
  "scrmBindingId": "pending-binding",
  "sceneCode": "sales-service",
  "input_data": {
    "conversationId": "sca-conversation-001",
    "robotConversationId": "S:1688855002789689_7881300016900726",
    "robotKey": "robot-demo-key",
    "chatUser": "7881300016900726",
    "user_id": "7881300016900726",
    "user_name": "李豪72",
    "conversationName": "李豪72",
    "messageId": "merged_1782125626238_1pskvzq2iuyk",
    "sendTime": "2026-08-12 11:30:00",
    "addMsgCount": "0"
  }
}
```
