#!/usr/bin/env bash
# Console 浏览器级 P3C 全流程（test-console-p3c-e2e.mjs 宿主）。
#
# 与 run-console-ui-evidence.sh 的分工：
# - evidence：向导烟测（通常落 P3）+ seed Blueprint 对话
# - 本脚本：fixture 驱动，强制命中 P3C，审批后 ingest 本次 Blueprint 再对话（含附录 B 必测说法）
#
# 两种跑法（不要混端口）：
# - 默认：隔离栈 :25183 / :23311 / :28198 + deterministic-test，只强制 7.1
# - CONSOLE_P3C_ATTACH=1：挂到混合栈 :5173 / :3100 / :8088（读 env.local 凭证，不启停进程）
#   默认再开 CONSOLE_P3C_AGENT_CHAT=1，跑 7.2–7.6 智能体必测
#
# 加速（相对旧版每次 tsc + 4 行业 seed）：
# - dist 已存在则跳过 Nest rebuild（CONSOLE_P3C_REBUILD=1 强制）
# - runtime 启动不再依赖 seed Blueprint（确认发布会 ingest）
# - Nest / runtime / console 并行拉起
#
# 端口与 evidence / 手工栈错开，可并行。可视化请用 Cursor 右侧打开混合栈
# http://127.0.0.1:5173/ ，不要 CONSOLE_P3C_HEADED=1（会弹出系统 Chrome）。
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
t0="$(date +%s)"
elapsed() { echo "[P3C-E2E] $* (+$(( $(date +%s) - t0 ))s)"; }

pick_wizard_token() {
  local want="$1"
  if [[ -n "${WEB_AUTH_CREDENTIALS:-}" ]]; then
    WANT="$want" node -e '
      const list = JSON.parse(process.env.WEB_AUTH_CREDENTIALS || "[]");
      const hit = list.find((item) => item && item.client_code === process.env.WANT && item.token);
      if (!hit) process.exit(2);
      process.stdout.write(String(hit.token));
    ' && return 0
    echo "WEB_AUTH_CREDENTIALS has no token for $want" >&2
    exit 1
  fi
  if [[ -n "${WEB_AUTH_TOKEN:-}" && "${WEB_AUTH_CLIENT_CODE:-}" == "$want" ]]; then
    printf '%s' "$WEB_AUTH_TOKEN"
    return 0
  fi
  echo "no wizard token for $want: set WEB_AUTH_CREDENTIALS or match WEB_AUTH_CLIENT_CODE" >&2
  exit 1
}

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

fixture="${CONSOLE_P3C_FIXTURE:-$root_dir/agent-core/fixtures/wizard-e2e/p3c-guyu-wecom.yaml}"
export CONSOLE_P3C_FIXTURE="$fixture"
if [[ -z "${CONSOLE_P3C_CLIENT_CODE:-}" ]]; then
  case "$fixture" in
    *jifei*) CONSOLE_P3C_CLIENT_CODE=acme_agri ;;
    *) CONSOLE_P3C_CLIENT_CODE=acme_beauty ;;
  esac
fi
export CONSOLE_P3C_CLIENT_CODE
if [[ -z "${CONSOLE_P3C_SHOTS:-}" ]]; then
  case "$fixture" in
    *jifei*) CONSOLE_P3C_SHOTS="$root_dir/agent-core/tmp/wizard-p3c-jifei-e2e" ;;
    *) CONSOLE_P3C_SHOTS="$root_dir/agent-core/tmp/wizard-p3c-e2e" ;;
  esac
fi
export CONSOLE_P3C_SHOTS
export CONSOLE_UI_SHOTS="$CONSOLE_P3C_SHOTS"
mkdir -p "$CONSOLE_P3C_SHOTS"

if [[ "${CONSOLE_P3C_ATTACH:-}" == "1" ]]; then
  env_file="${AGENTTEAMS_LOCAL_ENV:-$root_dir/docs/agentteams/local-development.env.local}"
  if [[ ! -f "$env_file" ]]; then
    echo "CONSOLE_P3C_ATTACH=1 needs $env_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
  nest_port="${WEB_PORT:-3100}"
  runtime_port="${RUNTIME_PORT:-8088}"
  console_port="${CONSOLE_PORT:-5173}"
  export CONSOLE_UI_WIZARD_TOKEN="$(pick_wizard_token "$CONSOLE_P3C_CLIENT_CODE")"
  export CONSOLE_UI_PIPELINE_TOKEN="${PIPELINE_CONTROL_TOKEN:?PIPELINE_CONTROL_TOKEN missing in env.local}"
  export CONSOLE_UI_RUNTIME_TOKEN="${RUNTIME_AUTH_TOKEN:?RUNTIME_AUTH_TOKEN missing in env.local}"
  export CONSOLE_UI_RUNTIME_ADMIN_TOKEN="${RUNTIME_ADMIN_TOKEN:-}"
  export CONSOLE_P3C_STORE_FILE="${ARTIFACT_STORE_FILE:-}"
  export CONSOLE_UI_ACTOR="${CONSOLE_UI_ACTOR:-@developer:local}"
  export CONSOLE_UI_BASE="${CONSOLE_UI_BASE:-http://127.0.0.1:$console_port}"
  export CONSOLE_UI_NEST_BASE="${CONSOLE_UI_NEST_BASE:-http://127.0.0.1:$nest_port}"
  export CONSOLE_P3C_AGENT_CHAT="${CONSOLE_P3C_AGENT_CHAT:-1}"
  elapsed "attach mixed stack Console=$CONSOLE_UI_BASE Nest=$CONSOLE_UI_NEST_BASE agent-chat=$CONSOLE_P3C_AGENT_CHAT"
  wait_http_url "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF"
  wait_http_url "http://127.0.0.1:$runtime_port/api/v1/dryrun" 401 "agent-runtime" POST
  wait_http_url "$CONSOLE_UI_BASE/" 200 "agent-console"
  elapsed "mixed stack reachable (tokens from env.local, not printed)"
  if [[ "${CONSOLE_P3C_STACK_ONLY:-}" == "1" ]]; then
    elapsed "attach stack-only; nothing to start"
    exit 0
  fi
  elapsed "drive browser through $(basename "$CONSOLE_P3C_FIXTURE") (attached, tenant=$CONSOLE_P3C_CLIENT_CODE)"
  cd "$root_dir/agent-console"
  npm run test:p3c-e2e
  elapsed "PASS evidence under $CONSOLE_P3C_SHOTS"
  echo "[PASS] P3C e2e evidence under $CONSOLE_P3C_SHOTS"
  exit 0
fi

nest_port="${CONSOLE_P3C_NEST_PORT:-23311}"
runtime_port="${CONSOLE_P3C_RUNTIME_PORT:-28198}"
console_port="${CONSOLE_P3C_CONSOLE_PORT:-25183}"

export CONSOLE_UI_WIZARD_TOKEN="p3c-e2e-wizard-token-0123456789"
export CONSOLE_UI_PIPELINE_TOKEN="p3c-e2e-pipeline-token-0123456789"
export CONSOLE_UI_RUNTIME_TOKEN="p3c-e2e-runtime-token-0123456789"
export CONSOLE_UI_ACTOR="@developer:local"
export CONSOLE_UI_BASE="http://127.0.0.1:$console_port"
export CONSOLE_UI_NEST_BASE="http://127.0.0.1:$nest_port"
runtime_admin_token="p3c-e2e-runtime-admin-0123456789"
approval_secret="p3c-e2e-approval-secret-at-least-32-characters"

temp_dir="$(mktemp -d -t chatflows-console-p3c.XXXXXX)"
export ARTIFACT_STORE_FILE="$temp_dir/agentteams-store.json"
export CONSOLE_P3C_STORE_FILE="$temp_dir/agentteams-store.json"
export CONSOLE_UI_RUNTIME_ADMIN_TOKEN="$runtime_admin_token"
nest_pid=""; runtime_pid=""; console_pid=""

stop_tree() {
  local pid="$1"
  [[ -z "$pid" ]] && return 0
  local descendants; descendants="$(pgrep -P "$pid" 2>/dev/null || true)"
  if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  local child
  for child in $descendants; do stop_tree "$child"; done
}
cleanup() {
  stop_tree "$console_pid"; stop_tree "$runtime_pid"; stop_tree "$nest_pid"
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
  local url="$1" expect="$2" name="$3" pid="$4" log="$5" method="${6:-GET}"
  local i status
  for i in $(seq 1 240); do
    if ! kill -0 "$pid" 2>/dev/null; then cat "$log" >&2; echo "$name exited early" >&2; return 1; fi
    status="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 0.15
  done
  cat "$log" >&2; echo "$name did not become ready (last HTTP $status, want $expect)" >&2; return 1
}

require_free "$nest_port" "Nest BFF"
require_free "$runtime_port" "agent-runtime"
require_free "$console_port" "agent-console"

mkdir -p "$CONSOLE_P3C_SHOTS" "$temp_dir/static" "$temp_dir/flow-projects" \
  "$temp_dir/runtime/state" "$temp_dir/runtime/workspace"

cd "$root_dir/agent-core"
if [[ "${CONSOLE_P3C_REBUILD:-}" == "1" || ! -f dist/main-web.js ]]; then
  elapsed "build Nest BFF"
  npm run build >"$temp_dir/nest-build.log" 2>&1 || { cat "$temp_dir/nest-build.log" >&2; exit 1; }
else
  elapsed "reuse Nest dist (set CONSOLE_P3C_REBUILD=1 to force tsc)"
fi

elapsed "start Nest + runtime + console in parallel"
(
  cd "$root_dir/agent-core"
  ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$temp_dir/agentteams-store.json" \
    FLOW_PLATFORM_MODE=local FLOW_PROJECT_ROOT="$temp_dir/flow-projects" ORCHESTRATION_MODE=local \
    ARTIFACT_INSPECTOR=on \
    AGENT_RUNTIME_URL="http://127.0.0.1:$runtime_port" \
    AGENT_RUNTIME_TOKEN="$CONSOLE_UI_RUNTIME_TOKEN" \
    AGENT_RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    LOG_STDERR=off LOG_FILE=off DEMO_TRACE=0 \
    WEB_HOST=127.0.0.1 WEB_PORT="$nest_port" WEB_STATIC_ROOT="$temp_dir/static" \
    WEB_AUTH_TOKEN="$CONSOLE_UI_WIZARD_TOKEN" WEB_AUTH_CLIENT_CODE="$CONSOLE_P3C_CLIENT_CODE" \
    PIPELINE_CONTROL_TOKEN="$CONSOLE_UI_PIPELINE_TOKEN" \
    PIPELINE_APPROVAL_SIGNING_SECRET="$approval_secret" \
    BLUEPRINT_ADMIN_TOKEN="p3c-e2e-blueprint-token-0123456789" \
    MCP_SERVER_TOKEN="p3c-e2e-mcp-token-0123456789" \
    node dist/main-web.js
) >"$temp_dir/nest.log" 2>&1 &
nest_pid=$!

(
  cd "$root_dir/agent-runtime"
  RUNTIME_AUTH_TOKEN="$CONSOLE_UI_RUNTIME_TOKEN" RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    RUNTIME_MODEL=deterministic-test RUNTIME_MODE=local ARTIFACT_INSPECTOR=on \
    RUNTIME_HOST=127.0.0.1 RUNTIME_PORT="$runtime_port" \
    AGENTSCOPE_STATE_HOME="$temp_dir/runtime/state" AGENTSCOPE_WORKSPACE="$temp_dir/runtime/workspace" \
    AGENTLOOP_EXPORTER=off \
    ./run.sh
) >"$temp_dir/runtime.log" 2>&1 &
runtime_pid=$!

(
  cd "$root_dir/agent-console"
  NEST_API="http://127.0.0.1:$nest_port" RUNTIME_API="http://127.0.0.1:$runtime_port" \
    MANAGER_API="http://127.0.0.1:28091" ORCHESTRATION_MODE=local ARTIFACT_INSPECTOR=on \
    RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    CONSOLE_HOST=127.0.0.1 CONSOLE_PORT="$console_port" \
    npm run dev
) >"$temp_dir/console.log" 2>&1 &
console_pid=$!

wait_http "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF" "$nest_pid" "$temp_dir/nest.log"
wait_http "http://127.0.0.1:$runtime_port/api/v1/dryrun" 401 "agent-runtime" "$runtime_pid" "$temp_dir/runtime.log" POST
wait_http "$CONSOLE_UI_BASE/" 200 "agent-console" "$console_pid" "$temp_dir/console.log"
elapsed "stack ready"

if [[ "${CONSOLE_P3C_STACK_ONLY:-}" == "1" ]]; then
  elapsed "stack-only mode; console $CONSOLE_UI_BASE (Ctrl-C to stop)"
  wait
fi

elapsed "drive browser through $(basename "$CONSOLE_P3C_FIXTURE") (tenant=$CONSOLE_P3C_CLIENT_CODE)"
cd "$root_dir/agent-console"
npm run test:p3c-e2e

elapsed "PASS evidence under $CONSOLE_P3C_SHOTS"
echo "[PASS] P3C e2e evidence under $CONSOLE_P3C_SHOTS"
