#!/usr/bin/env bash
# 本机手工验证用的一键起停：Nest BFF + agent-runtime + agent-console（vite dev）。
#
# 与相邻两支脚本的分工：
# - run-agentteams-local-dev.sh：连**集成环境**（要 Controller / Matrix / MinIO / PG / Redis），
#   preflight 全过才起，四件套齐全 —— 本机没有那些依赖时它会在 preflight 就退出。
# - run-console-ui-evidence.sh：起完就跑 Playwright，跑完立刻拆掉，人来不及点。
# - 本脚本：只起「本机自足」的三件（agent-manager 需真 Controller/Matrix/MinIO，故不起，
#   Console 以 ORCHESTRATION_MODE=local 运行，编排走 Nest 的 pipeline 平面），
#   起好后**前台常驻**，等人手工点完 UI 再 Ctrl-C 一次性收干净。
#
# 端口与两支门禁脚本刻意错开（23401 / 28288 / 25273 vs 23301 / 28188 / 25173），
# 所以手工验证期间仍可并行跑 verify-all.sh。
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"

nest_port="${MANUAL_NEST_PORT:-23401}"
runtime_port="${MANUAL_RUNTIME_PORT:-28288}"
console_port="${MANUAL_CONSOLE_PORT:-25273}"
# 本机固定串：只喂给 127.0.0.1 上的临时进程，不是任何环境的真凭证。
# 三本 Wizard Bearer 同时注入 Nest 与 Console，欢迎页会显示账号下拉。
wizard_token="manual-dev-wizard-token-0123456789"
wizard_agri_token="manual-dev-agri-token-0123456789"
wizard_edu_token="manual-dev-edu-token-0123456789"
wizard_credentials="[{\"token\":\"$wizard_token\",\"client_code\":\"acme_beauty\",\"roles\":[\"user\",\"admin\"]},{\"token\":\"$wizard_agri_token\",\"client_code\":\"acme_agri\",\"roles\":[\"user\",\"admin\"]},{\"token\":\"$wizard_edu_token\",\"client_code\":\"acme_edu\",\"roles\":[\"user\",\"admin\"]}]"
pipeline_token="manual-dev-pipeline-token-0123456789"
runtime_token="manual-dev-runtime-token-0123456789"
runtime_admin_token="manual-dev-runtime-admin-0123456789"
approval_secret="manual-dev-approval-secret-at-least-32-characters"

work_dir="${MANUAL_STACK_DIR:-/tmp/chatflows-manual-stack}"
mkdir -p "$work_dir"
nest_pid=""; runtime_pid=""; console_pid=""

# run.sh / vite 会 exec 或 fork 出真正监听端口的子进程，只 kill 父进程会把它甩给 init
# 并继续占端口（与 verify-all.sh stop_runtime / test-bff-http.sh 同口径）。
stop_tree() {
  local pid="$1"
  [[ -z "$pid" ]] && return 0
  local descendants; descendants="$(pgrep -P "$pid" 2>/dev/null || true)"
  if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  local child
  for child in $descendants; do stop_tree "$child"; done
}
cleanup() {
  echo
  echo "[STOP] shutting down console / runtime / nest"
  stop_tree "$console_pid"; stop_tree "$runtime_pid"; stop_tree "$nest_pid"
}
trap cleanup EXIT INT TERM

require_free() {
  local port="$1" name="$2"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "$name port $port is already in use" >&2; return 1
  fi
}
# 第 6 个参数是 HTTP 方法。agent-runtime 的控制器**先判方法再鉴权**（非 POST 直接 405），
# 探它必须用 POST 才能拿到 401。
wait_http() {
  local url="$1" expect="$2" name="$3" pid="$4" log="$5" method="${6:-GET}"
  local i status
  for i in $(seq 1 240); do
    if ! kill -0 "$pid" 2>/dev/null; then tail -40 "$log" >&2; echo "$name exited early (full log: $log)" >&2; return 1; fi
    status="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 0.25
  done
  tail -40 "$log" >&2; echo "$name did not become ready (last HTTP $status, want $expect; full log: $log)" >&2; return 1
}

require_free "$nest_port" "Nest BFF"
require_free "$runtime_port" "agent-runtime"
require_free "$console_port" "agent-console"

echo "[1/4] export a published P3C Blueprint seed (agent-runtime needs one to serve /api/v1/chat)"
cd "$root_dir/agent-core"
# 手工栈跑的是 node dist/main-web.js；源码比 dist 新时必须重编，否则会继续端出旧 S1 分组（美妆/个护）。
if [[ ! -f dist/main-web.js ]] || [[ -n "$(find src -name '*.ts' -newer dist/main-web.js -print -quit 2>/dev/null)" ]]; then
  echo "building agent-core dist (missing or older than src)"
  npm run build
fi
ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$work_dir/seed-store.json" FLOW_PLATFORM_MODE=local \
  DEMO_TRACE=0 LOG_STDERR=off BLUEPRINT_SMOKE_DIR="$work_dir/blueprints" \
  npm run test:p3c-industries >"$work_dir/blueprints.log" 2>&1 || { tail -40 "$work_dir/blueprints.log" >&2; exit 1; }
blueprint="$work_dir/blueprints/beauty.json"
[[ -f "$blueprint" ]] || { echo "beauty blueprint missing under $work_dir/blueprints" >&2; exit 1; }
runtime_client_code="$(BLUEPRINT_FILE="$blueprint" node -e 'const fs=require("node:fs");process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE,"utf8")).clientCode)')"
runtime_agent_id="$(BLUEPRINT_FILE="$blueprint" node -e 'const fs=require("node:fs");process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE,"utf8")).runtimeAgentId)')"

echo "[2/4] start Nest BFF on http://127.0.0.1:$nest_port (ORCHESTRATION_MODE=local)"
mkdir -p "$work_dir/static" "$work_dir/flow-projects"
(
  cd "$root_dir/agent-core"
  ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$work_dir/agentteams-store.json" \
    FLOW_PLATFORM_MODE=local FLOW_PROJECT_ROOT="$work_dir/flow-projects" ORCHESTRATION_MODE=local \
    ARTIFACT_INSPECTOR=on \
    AGENT_RUNTIME_URL="http://127.0.0.1:$runtime_port" \
    AGENT_RUNTIME_TOKEN="$runtime_token" \
    AGENT_RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    LOG_STDERR=on LOG_FILE=off DEMO_TRACE=0 \
    WEB_HOST=127.0.0.1 WEB_PORT="$nest_port" WEB_STATIC_ROOT="$work_dir/static" \
    WEB_AUTH_CREDENTIALS="$wizard_credentials" \
    WEB_AUTH_TOKEN="$wizard_token" WEB_AUTH_CLIENT_CODE=acme_beauty \
    PIPELINE_CONTROL_TOKEN="$pipeline_token" \
    PIPELINE_APPROVAL_SIGNING_SECRET="$approval_secret" \
    BLUEPRINT_ADMIN_TOKEN="manual-dev-blueprint-token-0123456789" \
    MCP_SERVER_TOKEN="manual-dev-mcp-token-0123456789" \
    node dist/main-web.js
) >"$work_dir/nest.log" 2>&1 &
nest_pid=$!
# 匿名请求必须被拒：401 既是「起好了」也是「鉴权没被绕过」。
# AGENT_RUNTIME_* 指向即将启动的 runtime 端口；P4 时才真正打，Nest 可先于 runtime 起。
wait_http "http://127.0.0.1:$nest_port/api/health" 401 "Nest BFF" "$nest_pid" "$work_dir/nest.log"

# 本机无 Higress 时用产物探针（证明聊的是这份 Blueprint）；真模型走 run-agentteams-local-dev.sh。
runtime_model="${RUNTIME_MODEL:-blueprint-aware-test}"
echo "[3/4] start agent-runtime on http://127.0.0.1:$runtime_port (RUNTIME_MODEL=$runtime_model)"
mkdir -p "$work_dir/runtime/state" "$work_dir/runtime/workspace"
(
  cd "$root_dir/agent-runtime"
  RUNTIME_AUTH_TOKEN="$runtime_token" RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    RUNTIME_MODEL="$runtime_model" RUNTIME_MODE=local ARTIFACT_INSPECTOR=on \
    RUNTIME_HOST=127.0.0.1 RUNTIME_PORT="$runtime_port" RUNTIME_LOCAL_BLUEPRINT="$blueprint" \
    AGENTSCOPE_STATE_HOME="$work_dir/runtime/state" AGENTSCOPE_WORKSPACE="$work_dir/runtime/workspace" \
    AGENTLOOP_EXPORTER=off \
    ./run.sh
) >"$work_dir/runtime.log" 2>&1 &
runtime_pid=$!
wait_http "http://127.0.0.1:$runtime_port/api/v1/dryrun" 401 "agent-runtime" "$runtime_pid" "$work_dir/runtime.log" POST

echo "[4/4] start agent-console dev server on http://127.0.0.1:$console_port"
(
  cd "$root_dir/agent-console"
  NEST_API="http://127.0.0.1:$nest_port" RUNTIME_API="http://127.0.0.1:$runtime_port" \
    MANAGER_API="http://127.0.0.1:28090" ORCHESTRATION_MODE=local ARTIFACT_INSPECTOR=on \
    RUNTIME_ADMIN_TOKEN="$runtime_admin_token" \
    WEB_AUTH_CREDENTIALS="$wizard_credentials" \
    WEB_AUTH_TOKEN="$wizard_token" PIPELINE_CONTROL_TOKEN="$pipeline_token" \
    RUNTIME_AUTH_TOKEN="$runtime_token" \
    CONSOLE_HOST=127.0.0.1 CONSOLE_PORT="$console_port" \
    npm run dev
) >"$work_dir/console.log" 2>&1 &
console_pid=$!
wait_http "http://127.0.0.1:$console_port/" 200 "agent-console" "$console_pid" "$work_dir/console.log"

cat <<INFO

────────────────────────────────────────────────────────────────
  UI 入口   http://127.0.0.1:$console_port
────────────────────────────────────────────────────────────────

打开即可用：连接凭证已预填本机开发串，一般不用再打开抽屉。若要核对或改写：

  Wizard Bearer（谷雨）    $wizard_token
  Wizard Bearer（极飞）    $wizard_agri_token
  Wizard Bearer（教育）    $wizard_edu_token
  Pipeline Control Bearer  $pipeline_token
  Runtime Bearer           $runtime_token
  Runtime Admin Bearer     $runtime_admin_token
  Manager Bearer / Admin   留空（agent-manager 未起，见下）
  X-Role                   admin
  X-Actor                  @developer:local

向导右侧「产物」「专家团」tab 已打开（ARTIFACT_INSPECTOR=on）。生产关掉：三件进程都设 ARTIFACT_INSPECTOR=off 后重启。
CLI：./scripts/inspect-run.sh --store $work_dir/agentteams-store.json
local 下「专家团」显示空态是预期（不派 Matrix）。

「产物对话」不要填启动脚本的 seed。先在「搭建向导」里点「开始生成」→「确认发布」，再点「去试聊」。
本页会自动绑定本次发布的 clientCode / runtimeAgentId。

本机 runtime 默认是产物探针（RUNTIME_MODEL=blueprint-aware-test）：回包带 BLUEPRINT_OK
与本次 runtimeAgentId / soul / Skill，Console 标「产物探针」。要谷雨客服质量请改走
scripts/run-agentteams-local-dev.sh（Higress + RUNTIME_LLM_*）。链路烟测仍用
RUNTIME_MODEL=deterministic-test。

「编排看板」是排障页，local 主路径不必打开。platform 模式 CTA 在本脚本下会失败
（未起 agent-manager），与 verify-all.sh 的 UI 证据段是同一限制。

后端直连（不经 vite 代理）：
  Nest BFF        http://127.0.0.1:$nest_port/api/health
  agent-runtime   http://127.0.0.1:$runtime_port/api/v1/chat

日志：
  tail -f $work_dir/nest.log
  tail -f $work_dir/runtime.log
  tail -f $work_dir/console.log

Ctrl-C 停止全部三个进程。
INFO

# 前台常驻，等人手工点完。任一进程先挂掉就退出并触发 cleanup。
# 不能用 `wait -n`：macOS 自带的是 bash 3.2，没有这个选项（3.2 下会直接
# 报 invalid option 并让脚本起完就退、把刚起好的三个进程又收掉）。
# 用 kill -0 轮询，语义等价且 3.2 可用。
while true; do
  for entry in "nest:$nest_pid" "runtime:$runtime_pid" "console:$console_pid"; do
    if ! kill -0 "${entry#*:}" 2>/dev/null; then
      echo "[WARN] ${entry%%:*} exited; see logs under $work_dir" >&2
      exit 1
    fi
  done
  sleep 2
done
