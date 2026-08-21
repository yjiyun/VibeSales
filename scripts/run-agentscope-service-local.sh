#!/usr/bin/env bash
# 本机启动 AgentScope Service（文章：https://mp.weixin.qq.com/s/GJ3SNaTwRUwmEbb5qbIWMQ）
# 默认端口 8080/8081/5432 已被 nginx / Higress / AgentTeams 占用，这里改绑到 28xxx。
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
service_root="${AGENTSCOPE_JAVA_ROOT:-$root_dir/../agentscope-java}/agentscope-service"
env_file="${AGENTSCOPE_SERVICE_ENV:-$root_dir/docs/agentteams/agentscope-service.env.local}"
source_env="$root_dir/docs/agentteams/local-development.env.local"
fallback_env="$root_dir/agent-core/.env"
cmd="${1:-up}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -d "$service_root" ]]; then
  echo "missing AgentScope Service source: $service_root" >&2
  echo "clone https://github.com/agentscope-ai/agentscope-java.git next to chatflows" >&2
  exit 1
fi

load_key() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v k="$key" '$1==k {print substr($0, index($0,"=")+1); exit}' "$file"
}

ensure_env() {
  mkdir -p "$(dirname "$env_file")"
  if [[ -f "$env_file" ]]; then
    return 0
  fi
  local key
  key="$(load_key "$source_env" DASHSCOPE_API_KEY)"
  [[ -n "$key" ]] || key="$(load_key "$fallback_env" DASHSCOPE_API_KEY)"
  [[ -n "$key" ]] || key="$(load_key "$root_dir/deploy/local/.env.local" HIGRESS_DASHSCOPE_API_KEY)"
  if [[ -z "$key" ]]; then
    echo "DASHSCOPE_API_KEY not found in local-development.env.local / agent-core/.env / deploy/local/.env.local" >&2
    exit 1
  fi
  cat >"$env_file" <<EOF
DASHSCOPE_API_KEY=$key
BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
BUILDER_JWT_SECRET=builder-default-dev-secret-change-in-production-32chars
BUILDER_GATEWAY_PORT=28080
BUILDER_CONTROL_PORT=28081
BUILDER_DATA_PORT=28082
BUILDER_SCHEDULER_PORT=28083
BUILDER_PG_PORT=25432
AISTIO_CONTROL_PLANE_HTTP=http://127.0.0.1:28081
AISTIO_CONTROL_HTTP=http://127.0.0.1:28081
AISTIO_AGENT_NAME=chatflows-agent-runtime
AISTIO_NAMESPACE=chatflows
AISTIO_CONTRACT_HTTP_PORT=28191
EOF
}

ensure_env
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

export AISTIO_ENABLE_KUBERNETES=false

case "$cmd" in
  down)
    (cd "$service_root" && scripts/dev-down.sh)
    ;;
  up|restart)
    if [[ "$cmd" == restart ]]; then
      (cd "$service_root" && scripts/dev-down.sh) || true
    fi
    (cd "$service_root" && BUILDER_REBUILD="${BUILDER_REBUILD:-1}" scripts/dev-up.sh)
    echo "Console: http://127.0.0.1:${BUILDER_GATEWAY_PORT:-28080}/  (admin / admin)"
    echo "Control plane: ${AISTIO_CONTROL_PLANE_HTTP}"
    echo "chatflows BYO env file: $env_file"
    ;;
  *)
    echo "usage: $0 [up|down|restart]" >&2
    exit 1
    ;;
esac
