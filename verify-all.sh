#!/usr/bin/env bash
# v4 并集回归：worktree(v3) 与主线(v1/v2) 两侧的门禁缺一即失败（§5.6）。
# 真集群入口（run-agentteams-stack-e2e.sh / run-agentteams-platform-e2e.js）不在此处，见 verify-contracts.sh 与文档。
set -euo pipefail
root_dir="$(cd "$(dirname "$0")" && pwd)"
export ARTIFACT_STORE=file
export FLOW_PLATFORM_MODE=local
export DEMO_TRACE=0
export LOG_STDERR=off
export ORCHESTRATOR_LLM="${ORCHESTRATOR_LLM:-off}"   # A24：默认不打真模型
blueprint_file="$(mktemp -t chatflows-blueprint.XXXXXX.json)"
runtime_log="$(mktemp -t chatflows-runtime.XXXXXX.log)"
runtime_pid=""
runtime_token="local-runtime-token-0123456789"
runtime_admin_token="local-admin-token-01234567890"
runtime_port="28088"
runtime_temp="$(mktemp -d -t chatflows-runtime.XXXXXX)"
e2e_temp="$(mktemp -d -t chatflows-e2e.XXXXXX)"
smoke_temp="$(mktemp -d -t chatflows-p3c-smokes.XXXXXX)"
approval_proof_file="$(mktemp -t chatflows-approval-proof.XXXXXX)"
manager_agentloop_file="$(mktemp -t chatflows-manager-agentloop.XXXXXX.json)"
runtime_agentloop_file="$(mktemp -t chatflows-runtime-agentloop.XXXXXX.json)"
approval_proof_secret="approval-cross-language-secret-0123456789"
export ARTIFACT_STORE_FILE="$e2e_temp/agentteams-store.json"
export FLOW_PROJECT_ROOT="$e2e_temp/flow-projects"
cleanup() {
  # 异常退出时也要连子孙一起收，否则 java 会继续占用 runtime_port。
  if [[ -n "$runtime_pid" ]]; then
    for child in $(pgrep -P "$runtime_pid" 2>/dev/null || true); do kill "$child" 2>/dev/null || true; done
    if kill -0 "$runtime_pid" 2>/dev/null; then kill "$runtime_pid" 2>/dev/null || true; wait "$runtime_pid" 2>/dev/null || true; fi
  fi
  rm -f "$blueprint_file" "$runtime_log" "$approval_proof_file" "$manager_agentloop_file" "$runtime_agentloop_file"
  rm -rf "$runtime_temp"
  rm -rf "$e2e_temp"
  rm -rf "$smoke_temp"
}
trap cleanup EXIT

start_runtime() {
  local seed="$1"
  : > "$runtime_log"
  local existing_status
  existing_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$runtime_port/api/v1/dryrun" || true)"
  if [[ "$existing_status" != "000" ]]; then
    echo "agent-runtime port $runtime_port is already in use (HTTP $existing_status)" >&2
    return 1
  fi
  (
    cd "$root_dir/agent-runtime"
    RUNTIME_AUTH_TOKEN="$runtime_token" RUNTIME_ADMIN_TOKEN="$runtime_admin_token" RUNTIME_MODEL=deterministic-test RUNTIME_MODE=local RUNTIME_PORT="$runtime_port" RUNTIME_LOCAL_BLUEPRINT="$seed" AGENTSCOPE_STATE_HOME="$runtime_temp/state" AGENTSCOPE_WORKSPACE="$runtime_temp/workspace" \
      ./run.sh
  ) >"$runtime_log" 2>&1 &
  runtime_pid=$!
  for _ in $(seq 1 120); do
    if ! kill -0 "$runtime_pid" 2>/dev/null; then cat "$runtime_log" >&2; return 1; fi
    local status
    status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$runtime_port/api/v1/dryrun" || true)"
    if [[ "$status" == "401" ]]; then return 0; fi
    sleep 0.25
  done
  cat "$runtime_log" >&2
  echo "agent-runtime did not become ready" >&2
  return 1
}

stop_runtime() {
  # run.sh exec 成 java，是后台子 shell 的子进程。只 kill 子 shell 会把 java 甩给 init
  # 并继续占着端口，所以先在父进程还活着时抓下整棵子孙 pid，再自下而上收掉。
  local descendants=""
  if [[ -n "$runtime_pid" ]]; then descendants="$(pgrep -P "$runtime_pid" 2>/dev/null || true)"; fi
  if [[ -n "$runtime_pid" ]] && kill -0 "$runtime_pid" 2>/dev/null; then kill "$runtime_pid" 2>/dev/null || true; wait "$runtime_pid" 2>/dev/null || true; fi
  local child
  for child in $descendants; do
    if kill -0 "$child" 2>/dev/null; then kill "$child" 2>/dev/null || true; fi
  done
  runtime_pid=""
  for _ in $(seq 1 40); do
    local stopped_status
    stopped_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$runtime_port/api/v1/dryrun" || true)"
    [[ "$stopped_status" == "000" ]] && return 0
    sleep 0.05
  done
  echo "agent-runtime did not release port $runtime_port" >&2
  return 1
}

assert_sse() {
  local output="$1"
  local failed=0
  grep -Eq '^event: message\r?$' <<<"$output" || failed=1
  grep -Eq '^event: done\r?$' <<<"$output" || failed=1
  grep -q 'DRY_RUN_OK' <<<"$output" || failed=1
  if [[ "$failed" -ne 0 ]]; then
    echo "agent-runtime returned an invalid SSE stream:" >&2
    printf '%s\n' "$output" >&2
    echo "agent-runtime log:" >&2
    cat "$runtime_log" >&2
    return 1
  fi
}

manager_test() {
  mvn -o -q exec:java -Dexec.mainClass="com.yjiyun.chatflows.manager.$1" -Dexec.classpathScope=test "${@:2}"
}

runtime_test() {
  mvn -o -q exec:java -Dexec.mainClass="com.yjiyun.chatflows.runtime.$1" -Dexec.classpathScope=test "${@:2}"
}

echo "[VERIFY] Node build, resources, MCP and P1 regression"
node "$root_dir/scripts/test-agentteams-local-dev.mjs"
node "$root_dir/scripts/test-agentteams-platform-e2e.mjs"
node "$root_dir/scripts/test-higress-chatflows-mcp-config.mjs"
node "$root_dir/scripts/test-agentteams-preflight.mjs"
( cd "$root_dir/agent-console" && npm run build )
cd "$root_dir/agent-core"
npm run build
"$root_dir/scripts/test-bff-http.sh"
npm run test:mcp-startup
npm run test:web-auth
npm run test:qwen-gateway
npm run test:resources
"$root_dir/scripts/test-agentteams-apply.sh"
npm run test:mcp
npm run test:mcp-production
npm run test:control
npm run test:p1
BLUEPRINT_SMOKE_DIR="$smoke_temp" npm run test:p3c-industries

echo "[VERIFY] Real P1→P2→P3/P3B/P3C→approval→P4 and Blueprint export"
start_runtime ""
AGENT_RUNTIME_URL="http://127.0.0.1:$runtime_port" AGENT_RUNTIME_TOKEN="$runtime_token" npm run test:worker-mcp
AGENT_RUNTIME_URL="http://127.0.0.1:$runtime_port" AGENT_RUNTIME_TOKEN="$runtime_token" BLUEPRINT_EXPORT_PATH="$blueprint_file" npm run test:agentteams
stop_runtime

echo "[VERIFY] Java external orchestrator"
cd "$root_dir/agent-manager"
./build-dist.sh
mvn -o -q test-compile
manager_test ManagerSelfTest
manager_test ManagerConfigSelfTest
manager_test ManagerAuthSelfTest
manager_test ManagerHttpSelfTest
manager_test RunSupervisorSelfTest
manager_test CompletionGateSelfTest
manager_test ManifestApplySelfTest
manager_test MatrixPasswordLoginSelfTest
manager_test AgentLoopContractSelfTest
manager_test matrix.RestMatrixClientSelfTest
manager_test matrix.RoomTimelineSelfTest
manager_test agent.OrchestrationStoreSelfTest
manager_test agent.OrchestrationPlannerSuspendSelfTest
manager_test agent.OrchestrationPlannerFallbackSelfTest
manager_test observability.AgentLoopExporterSelfTest -Dexec.args="$manager_agentloop_file"
manager_test observability.ManagerSpanAliasesSelfTest
manager_test ApprovalProofExport -Dexec.args="$approval_proof_file $approval_proof_secret"
cd "$root_dir/agent-core"
APPROVAL_PROOF_FILE="$approval_proof_file" APPROVAL_PROOF_SECRET="$approval_proof_secret" npm run test:approval-proof

echo "[VERIFY] Java singleton runtime and cross-language Blueprint consumption"
cd "$root_dir/agent-runtime"
./build-dist.sh
mvn -o -q test-compile
runtime_test RuntimeSelfTest
runtime_test InspectSelfTest
runtime_test RuntimeGatewaySelfTest
runtime_test RuntimeGatewayModelSelfTest
runtime_test RuntimeAgentLoopSelfTest
runtime_test RuntimeDistributedSelfTest
runtime_test observability.AgentLoopExporterSelfTest -Dexec.args="$runtime_agentloop_file"
runtime_test observability.SpanAliasesSelfTest
runtime_test CrossLanguageSelfTest -Dexec.args="$blueprint_file"
runtime_test IndustrySmokeSelfTest -Dexec.args="$smoke_temp"
cd "$root_dir/agent-core"
MANAGER_AGENTLOOP_ENVELOPE="$manager_agentloop_file" RUNTIME_AGENTLOOP_ENVELOPE="$runtime_agentloop_file" npm run test:agentloop-aggregation

echo "[VERIFY] Runtime HTTP loads published Blueprint and streams real SSE events"
start_runtime "$blueprint_file"
client_code="$(BLUEPRINT_FILE="$blueprint_file" node -e 'const fs = require("node:fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE, "utf8")).clientCode)')"
runtime_agent_id="$(BLUEPRINT_FILE="$blueprint_file" node -e 'const fs = require("node:fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.env.BLUEPRINT_FILE, "utf8")).runtimeAgentId)')"
sse_output="$(curl -sS -N -X POST -H "Authorization: Bearer $runtime_token" --data-binary 'SSE health check' "http://127.0.0.1:$runtime_port/api/v1/chat?clientCode=$client_code&userId=sse-user&sessionId=sse-session&runtimeAgentId=$runtime_agent_id")"
assert_sse "$sse_output"
stop_runtime
echo "[PASS] real HTTP P3C dry-run and SSE message/done stream"

echo "[VERIFY] VibeSales Harness production build"
cd "$root_dir/agent-console"
npm run test:contract
npm run build
echo "[VERIFY] artifact inspect-run CLI"
node "$root_dir/scripts/inspect-run.mjs" --self-test

# 浏览器级 UI 验收（§8.1 #17/#18 的运行证据）。默认 auto：装了 Playwright 浏览器就跑，
# 没装就跳过并说明原因 —— 门禁不该因为一台机器没下过 chromium 就红。
# CONSOLE_UI_EVIDENCE=on 强制要求它必须跑（CI 上用），=off 跳过。
console_ui_evidence="${CONSOLE_UI_EVIDENCE:-auto}"
if [[ "$console_ui_evidence" != "off" ]]; then
  chromium_path="$(cd "$root_dir/agent-console" && node -e 'import("playwright").then(m=>process.stdout.write(m.chromium.executablePath())).catch(()=>process.exit(3))' 2>/dev/null || true)"
  if [[ -n "$chromium_path" && -x "$chromium_path" ]]; then
    echo "[VERIFY] Console browser UI evidence (wizard → local approval → runtime SSE)"
    "$root_dir/scripts/run-console-ui-evidence.sh"
    echo "[VERIFY] Console P3C e2e (wizard fixture → P3C → approval → runtime SSE)"
    "$root_dir/scripts/run-console-p3c-e2e.sh"
  elif [[ "$console_ui_evidence" == "on" ]]; then
    echo "CONSOLE_UI_EVIDENCE=on but no Playwright browser found; run 'cd agent-console && npx playwright install chromium'" >&2
    exit 1
  else
    echo "[SKIP] Console browser UI evidence: no Playwright browser installed (npx playwright install chromium)"
  fi
fi

# 无浏览器时仍跑 P3C 信号规则回归
echo "[VERIFY] P3C wizard signal inference"
cd "$root_dir/agent-core"
ARTIFACT_STORE=file DEMO_TRACE=0 npm run test:p3c-signals

echo "[PASS] verify-all: P1-P4, Human approval, AgentTeams resources, MCP, Java manager/runtime, Node→Java Blueprint"
