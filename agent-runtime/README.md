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
