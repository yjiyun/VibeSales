#!/usr/bin/env bash
# 挂载 AgentLoop javaagent 探针启动本项目。
#
# 普通开发用 `mvn -q -DskipTests exec:java` 就够了（不挂探针）。这个脚本只在需要验证
# AgentLoop 可观测数据能否真正上报时使用——`-javaagent` 是 JVM 启动参数，只能在创建新进程时
# 生效，`exec:java`（exec-maven-plugin 的 java 目标）不 fork 新进程，所以这里改用
# `java -javaagent:... -cp ...` 直接起一个新的 JVM 进程。
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  source .env
  set +a
fi

MISSING=()
[ -z "${AGENT_JAVA_AGENT_JAR:-}" ] && MISSING+=("AGENT_JAVA_AGENT_JAR")
[ -z "${AGENT_OTEL_LICENSE_KEY:-}" ] && MISSING+=("AGENT_OTEL_LICENSE_KEY")
[ -z "${AGENT_OTEL_CMS_WORKSPACE:-}" ] && MISSING+=("AGENT_OTEL_CMS_WORKSPACE")

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "缺少以下环境变量，无法挂载探针启动：${MISSING[*]}" >&2
  echo "请在 .env 里补全后重试（参考 .env.example 的 Observability 段落）。" >&2
  exit 1
fi

if [ ! -f "$AGENT_JAVA_AGENT_JAR" ]; then
  echo "AGENT_JAVA_AGENT_JAR 指向的文件不存在：$AGENT_JAVA_AGENT_JAR" >&2
  exit 1
fi

APP_NAME="${AGENT_APP_NAME:-sales-customer-agent}"
MAIN_CLASS="com.agentteams.salesagent.app.SalesCustomerAgentApplication"

echo "编译项目..."
mvn -q -DskipTests compile

echo "构建 classpath..."
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

CLASSPATH="target/classes:$(cat target/classpath.txt)"

echo "挂载探针启动（appName=${APP_NAME}, workspace=${AGENT_OTEL_CMS_WORKSPACE}）..."
exec java \
  -javaagent:"${AGENT_JAVA_AGENT_JAR}" \
  -Darms.licenseKey="${AGENT_OTEL_LICENSE_KEY}" \
  -Darms.appName="${APP_NAME}" \
  -Darms.workspace="${AGENT_OTEL_CMS_WORKSPACE}" \
  -cp "${CLASSPATH}" \
  "${MAIN_CLASS}"
