#!/usr/bin/env bash
# 极飞向导手工路径（test-console-jifei-manual.mjs 宿主）。
# 对齐 docs/agentteams/测试用例/test2-jifei-rag-manual.md：
# 关 LLM 接待员，点下「开始生成」即停，不等确认发布 / 试聊。
#
# 默认：隔离栈 Console :25193 / Nest :23321，租户 acme_agri，不起 runtime。
# 挂混合栈：CONSOLE_JIFEI_ATTACH=1 ./scripts/run-console-jifei-manual.sh
# 可视化请用 Cursor 右侧打开混合栈 http://127.0.0.1:5173/ ，不要 CONSOLE_JIFEI_HEADED=1。
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
t0="$(date +%s)"
elapsed() { echo "[JIFEI-MANUAL] $* (+$(( $(date +%s) - t0 ))s)"; }

export CONSOLE_JIFEI_FIXTURE="${CONSOLE_JIFEI_FIXTURE:-$root_dir/agent-core/fixtures/wizard-e2e/jifei-manual.yaml}"
export CONSOLE_JIFEI_SHOTS="${CONSOLE_JIFEI_SHOTS:-$root_dir/agent-core/tmp/wizard-jifei-manual}"
mkdir -p "$CONSOLE_JIFEI_SHOTS"

wait_http_url() {
  local url="$1" expect="$2" name="$3" method="${4:-GET}"
  local i status=""
  for i in $(seq 1 240); do
    status="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 0.15
  done
  echo "$name did not become ready (last HTTP $status, want $expect): $url" >&2
  return 1
}

if [[ "${CONSOLE_JIFEI_ATTACH:-}" == "1" ]]; then
  env_file="${AGENTTEAMS_LOCAL_ENV:-$root_dir/docs/agentteams/local-development.env.local}"
  if [[ ! -f "$env_file" ]]; then
    echo "CONSOLE_JIFEI_ATTACH=1 needs $env_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
  nest_port="${WEB_PORT:-3100}"
  console_port="${CONSOLE_PORT:-5173}"
  export CONSOLE_UI_WIZARD_TOKEN="${WEB_AUTH_TOKEN:?WEB_AUTH_TOKEN missing in env.local}"
  export CONSOLE_UI_PIPELINE_TOKEN="${PIPELINE_CONTROL_TOKEN:?PIPELINE_CONTROL_TOKEN missing in env.local}"
  export CONSOLE_UI_RUNTIME_TOKEN="${RUNTIME_AUTH_TOKEN:-}"
  export CONSOLE_UI_RUNTIME_ADMIN_TOKEN="${RUNTIME_ADMIN_TOKEN:-}"
  export CONSOLE_UI_MANAGER_TOKEN="${MANAGER_AUTH_TOKEN:-}"
  export CONSOLE_UI_MANAGER_ADMIN_TOKEN="${MANAGER_ADMIN_TOKEN:-}"
  export CONSOLE_UI_ACTOR="${CONSOLE_UI_ACTOR:-@developer:local}"
  export CONSOLE_UI_BASE="${CONSOLE_UI_BASE:-http://127.0.0.1:$console_port}"
  elapsed "attach mixed stack Console=$CONSOLE_UI_BASE"
  wait_http_url "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF"
  wait_http_url "$CONSOLE_UI_BASE/" 200 "agent-console"
  cd "$root_dir/agent-console"
  npm run test:jifei-manual
  elapsed "PASS evidence under $CONSOLE_JIFEI_SHOTS"
  echo "[PASS] jifei-manual evidence under $CONSOLE_JIFEI_SHOTS"
  exit 0
fi

nest_port="${CONSOLE_JIFEI_NEST_PORT:-23321}"
console_port="${CONSOLE_JIFEI_CONSOLE_PORT:-25193}"

export CONSOLE_UI_WIZARD_TOKEN="jifei-manual-wizard-token-0123456789"
export CONSOLE_UI_PIPELINE_TOKEN="jifei-manual-pipeline-token-0123456789"
export CONSOLE_UI_RUNTIME_TOKEN="jifei-manual-runtime-token-0123456789"
export CONSOLE_UI_ACTOR="@developer:local"
export CONSOLE_UI_BASE="http://127.0.0.1:$console_port"

temp_dir="$(mktemp -d -t chatflows-jifei-manual.XXXXXX)"
nest_pid=""; console_pid=""

stop_tree() {
  local pid="$1"
  [[ -z "$pid" ]] && return 0
  local descendants; descendants="$(pgrep -P "$pid" 2>/dev/null || true)"
  if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  local child
  for child in $descendants; do stop_tree "$child"; done
}
cleanup() {
  stop_tree "$console_pid"; stop_tree "$nest_pid"
  rm -rf "$temp_dir"
}
trap cleanup EXIT

require_free() {
  local port="$1" name="$2"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "$name port $port is already in use" >&2; return 1
  fi
}
wait_http() {
  local url="$1" expect="$2" name="$3" pid="$4" log="$5"
  local i status
  for i in $(seq 1 240); do
    if ! kill -0 "$pid" 2>/dev/null; then cat "$log" >&2; echo "$name exited early" >&2; return 1; fi
    status="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 0.15
  done
  cat "$log" >&2; echo "$name did not become ready (last HTTP $status, want $expect)" >&2; return 1
}

require_free "$nest_port" "Nest BFF"
require_free "$console_port" "agent-console"
mkdir -p "$CONSOLE_JIFEI_SHOTS" "$temp_dir/static" "$temp_dir/flow-projects"

cd "$root_dir/agent-core"
if [[ "${CONSOLE_JIFEI_REBUILD:-}" == "1" || ! -f dist/main-web.js ]]; then
  elapsed "build Nest BFF"
  npm run build >"$temp_dir/nest-build.log" 2>&1 || { cat "$temp_dir/nest-build.log" >&2; exit 1; }
else
  elapsed "reuse Nest dist (set CONSOLE_JIFEI_REBUILD=1 to force tsc)"
fi

elapsed "start Nest + console"
(
  cd "$root_dir/agent-core"
  # 隔离栈必须抠掉本机 env.local 里的千问 Key，否则 S4「先看看效果」直串 P2 会打真模型并卡住。
  env -u QWEN_GATEWAY_TOKEN -u QWEN_BASE_URL -u DASHSCOPE_API_KEY \
    ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$temp_dir/agentteams-store.json" \
    FLOW_PLATFORM_MODE=local FLOW_PROJECT_ROOT="$temp_dir/flow-projects" ORCHESTRATION_MODE=local \
    ARTIFACT_INSPECTOR=off \
    LOG_STDERR=off LOG_FILE=off DEMO_TRACE=0 \
    WEB_HOST=127.0.0.1 WEB_PORT="$nest_port" WEB_STATIC_ROOT="$temp_dir/static" \
    WEB_AUTH_TOKEN="$CONSOLE_UI_WIZARD_TOKEN" WEB_AUTH_CLIENT_CODE=acme_agri \
    PIPELINE_CONTROL_TOKEN="$CONSOLE_UI_PIPELINE_TOKEN" \
    PIPELINE_APPROVAL_SIGNING_SECRET="jifei-manual-approval-secret-at-least-32-chars" \
    BLUEPRINT_ADMIN_TOKEN="jifei-manual-blueprint-token-0123456789" \
    MCP_SERVER_TOKEN="jifei-manual-mcp-token-0123456789" \
    node dist/main-web.js
) >"$temp_dir/nest.log" 2>&1 &
nest_pid=$!

(
  cd "$root_dir/agent-console"
  NEST_API="http://127.0.0.1:$nest_port" \
    MANAGER_API="http://127.0.0.1:28091" ORCHESTRATION_MODE=local ARTIFACT_INSPECTOR=off \
    CONSOLE_HOST=127.0.0.1 CONSOLE_PORT="$console_port" \
    npm run dev
) >"$temp_dir/console.log" 2>&1 &
console_pid=$!

wait_http "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF" "$nest_pid" "$temp_dir/nest.log"
wait_http "$CONSOLE_UI_BASE/" 200 "agent-console" "$console_pid" "$temp_dir/console.log"
elapsed "stack ready"

cd "$root_dir/agent-console"
npm run test:jifei-manual

elapsed "PASS evidence under $CONSOLE_JIFEI_SHOTS"
echo "[PASS] jifei-manual evidence under $CONSOLE_JIFEI_SHOTS"
