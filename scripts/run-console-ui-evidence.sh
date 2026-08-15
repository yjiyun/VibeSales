#!/usr/bin/env bash
# Console 浏览器级 UI 验收的一键起停（agent-console/scripts/test-console-ui.mjs 的宿主）。
#
# 为什么单独一个脚本：那条用例要**真**后端（Nest BFF + agent-runtime）+ 真 vite dev server，
# 三个进程的起、健康探测、拆，以及 P3C Blueprint 的 seed，都不该塞进 Playwright 脚本里。
# 脚本只准备环境并把连接参数经 env 交给用例，用例本身可在任何已就绪环境上单独重放。
#
# 与 verify-all.sh 的 runtime 段错开端口（28088 vs 28188），因此两者可并行。
# 截图落在 CONSOLE_UI_SHOTS（默认 /tmp/chatflows-ui-evidence/shots），连同 manifest.json
# 作为 `agentTeams架构改造v3-设计结构.md` §8.1 #17/#18 的运行证据。
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"

nest_port="${CONSOLE_UI_NEST_PORT:-23301}"
runtime_port="${CONSOLE_UI_RUNTIME_PORT:-28188}"
console_port="${CONSOLE_UI_CONSOLE_PORT:-25173}"
export CONSOLE_UI_SHOTS="${CONSOLE_UI_SHOTS:-/tmp/chatflows-ui-evidence/shots}"

# 本机开发用固定串：只喂给 127.0.0.1 上的临时进程，不是任何环境的真凭证。
export CONSOLE_UI_WIZARD_TOKEN="ui-evidence-wizard-token-0123456789"
export CONSOLE_UI_PIPELINE_TOKEN="ui-evidence-pipeline-token-0123456789"
export CONSOLE_UI_RUNTIME_TOKEN="ui-evidence-runtime-token-0123456789"
export CONSOLE_UI_ACTOR="@developer:local"
export CONSOLE_UI_BASE="http://127.0.0.1:$console_port"
runtime_admin_token="ui-evidence-runtime-admin-0123456789"
approval_secret="ui-evidence-approval-secret-at-least-32-characters"

temp_dir="$(mktemp -d -t chatflows-console-ui.XXXXXX)"
nest_pid=""; runtime_pid=""; console_pid=""

# run.sh / vite 都会 exec 或 fork 出真正监听端口的子进程，只 kill 父进程会把它甩给 init
# 并继续占端口，所以先抓子孙 pid，再自下而上收（与 verify-all.sh stop_runtime 同口径）。
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
# 第 6 个参数是 HTTP 方法。agent-runtime 的控制器**先判方法再鉴权**（非 POST 直接 405），
# 所以探它必须用 POST 才能拿到 401；用 GET 只会永远收 405。与 verify-all.sh 的探测同口径。
wait_http() {
  local url="$1" expect="$2" name="$3" pid="$4" log="$5" method="${6:-GET}"
  local i status
  for i in $(seq 1 240); do
    if ! kill -0 "$pid" 2>/dev/null; then cat "$log" >&2; echo "$name exited early" >&2; return 1; fi
    status="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 0.25
  done
  cat "$log" >&2; echo "$name did not become ready (last HTTP $status, want $expect)" >&2; return 1
}

require_free "$nest_port" "Nest BFF"
require_free "$runtime_port" "agent-runtime"
require_free "$console_port" "agent-console"

echo "[UI] build Nest BFF and export a published P3C Blueprint seed"
cd "$root_dir/agent-core"
npm run build >"$temp_dir/nest-build.log" 2>&1 || { cat "$temp_dir/nest-build.log" >&2; exit 1; }
ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$temp_dir/seed-store.json" FLOW_PLATFORM_MODE=local \
  DEMO_TRACE=0 LOG_STDERR=off BLUEPRINT_SMOKE_DIR="$temp_dir/blueprints" \
  npm run test:p3c-industries >"$temp_dir/blueprints.log" 2>&1 || { cat "$temp_dir/blueprints.log" >&2; exit 1; }
blueprint="$temp_dir/blueprints/beauty.json"
[[ -f "$blueprint" ]] || { ls -la "$temp_dir/blueprints" >&2; echo "beauty blueprint smoke missing" >&2; exit 1; }
export CONSOLE_UI_RUNTIME_CLIENT_CODE
export CONSOLE_UI_RUNTIME_AGENT_ID
CONSOLE_UI_RUNTIME_CLIENT_CODE="$(BLUEPRINT_FILE="$blueprint" node -e 'const fs=require("node:fs");process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE,"utf8")).clientCode)')"
CONSOLE_UI_RUNTIME_AGENT_ID="$(BLUEPRINT_FILE="$blueprint" node -e 'const fs=require("node:fs");process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE,"utf8")).runtimeAgentId)')"

echo "[UI] start Nest BFF on 127.0.0.1:$nest_port (ORCHESTRATION_MODE=local)"
mkdir -p "$temp_dir/static" "$temp_dir/flow-projects"
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
    WEB_AUTH_TOKEN="$CONSOLE_UI_WIZARD_TOKEN" WEB_AUTH_CLIENT_CODE=acme_beauty \
    PIPELINE_CONTROL_TOKEN="$CONSOLE_UI_PIPELINE_TOKEN" \
    PIPELINE_APPROVAL_SIGNING_SECRET="$approval_secret" \
    BLUEPRINT_ADMIN_TOKEN="ui-evidence-blueprint-token-0123456789" \
    MCP_SERVER_TOKEN="ui-evidence-mcp-token-0123456789" \
    node dist/main-web.js
) >"$temp_dir/nest.log" 2>&1 &
nest_pid=$!
# 匿名请求必须被拒：401 既是「起好了」也是「鉴权没被绕过」。
wait_http "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF" "$nest_pid" "$temp_dir/nest.log"

echo "[UI] start agent-runtime on 127.0.0.1:$runtime_port with the published Blueprint"
mkdir -p "$temp_dir/runtime/state" "$temp_dir/runtime/workspace"
(
  cd "$root_dir/agent-runtime"
  RUNTIME_AUTH_TOKEN="$CONSOLE_UI_RUNTIME_TOKEN" RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    RUNTIME_MODEL=deterministic-test RUNTIME_MODE=local ARTIFACT_INSPECTOR=on \
    RUNTIME_HOST=127.0.0.1 RUNTIME_PORT="$runtime_port" RUNTIME_LOCAL_BLUEPRINT="$blueprint" \
    AGENTSCOPE_STATE_HOME="$temp_dir/runtime/state" AGENTSCOPE_WORKSPACE="$temp_dir/runtime/workspace" \
    AGENTLOOP_EXPORTER=off \
    ./run.sh
) >"$temp_dir/runtime.log" 2>&1 &
runtime_pid=$!
wait_http "http://127.0.0.1:$runtime_port/api/v1/dryrun" 401 "agent-runtime" "$runtime_pid" "$temp_dir/runtime.log" POST

echo "[UI] start agent-console dev server on $CONSOLE_UI_BASE"
(
  cd "$root_dir/agent-console"
  NEST_API="http://127.0.0.1:$nest_port" RUNTIME_API="http://127.0.0.1:$runtime_port" \
    MANAGER_API="http://127.0.0.1:28090" ORCHESTRATION_MODE=local ARTIFACT_INSPECTOR=on \
    CONSOLE_HOST=127.0.0.1 CONSOLE_PORT="$console_port" \
    npm run dev
) >"$temp_dir/console.log" 2>&1 &
console_pid=$!
wait_http "$CONSOLE_UI_BASE/" 200 "agent-console" "$console_pid" "$temp_dir/console.log"

echo "[UI] drive the browser through wizard → local orchestration approval → runtime SSE"
cd "$root_dir/agent-console"
npm run test:ui

echo "[PASS] Console UI evidence captured under $CONSOLE_UI_SHOTS"
