#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${1:-$root_dir/docs/agentteams/integration-test.env.local}"
if [[ ! -f "$env_file" ]]; then echo "env file not found: $env_file" >&2; exit 2; fi
confirm_apply="${AGENTTEAMS_CONFIRM_APPLY:-}"
set -a
source "$env_file"
set +a
if [[ -n "$confirm_apply" ]]; then AGENTTEAMS_CONFIRM_APPLY="$confirm_apply"; export AGENTTEAMS_CONFIRM_APPLY; fi
: "${CHATFLOWS_MCP_BASE_URL:?required}"
: "${AGENTTEAMS_RUN_CLIENT_CODE:?required}"
: "${AGENTTEAMS_PHASE1_RESULT_FILE:?export the completed Wizard Phase1Result JSON from agent-console first}"
node "$root_dir/scripts/run-agentteams-platform-e2e.js" "$env_file" --validate-phase1-only
if [[ "${AGENTTEAMS_CONFIRM_APPLY:-}" != "chatflows-only" ]]; then
  echo "refusing shared-platform apply: set AGENTTEAMS_CONFIRM_APPLY=chatflows-only after confirming 11 Workers + 1 Team + 1 Human; no delete/prune" >&2
  exit 3
fi
manager_pid=""
manager_log=""
cleanup() {
  if [[ -n "$manager_pid" ]]; then kill "$manager_pid" 2>/dev/null || true; wait "$manager_pid" 2>/dev/null || true; fi
  if [[ -n "$manager_log" ]]; then rm -f "$manager_log"; fi
}
trap cleanup EXIT INT TERM
manager_health() {
  curl -fsS --connect-timeout 2 --max-time 8 \
    -H "Authorization: Bearer $MANAGER_AUTH_TOKEN" -H "X-Role: orchestrator" \
    "${MANAGER_API%/}/api/v1/health" >/dev/null 2>&1
}
ensure_manager() {
  : "${MANAGER_API:?required}"
  : "${MANAGER_AUTH_TOKEN:?required}"
  if manager_health; then echo "[PASS] existing agent-manager is healthy"; return; fi
  local mode="${AGENTTEAMS_START_MANAGER:-auto}" local_api
  local_api="$(MANAGER_URL="$MANAGER_API" node -e 'const u=new URL(process.env.MANAGER_URL);process.stdout.write(["127.0.0.1","localhost","::1"].includes(u.hostname)?"1":"0")')"
  if [[ "$mode" == 0 || "$mode" == off || "$local_api" != 1 ]]; then
    echo "agent-manager is unreachable at $MANAGER_API; start it first or use a local MANAGER_API with AGENTTEAMS_START_MANAGER=auto" >&2
    exit 4
  fi
  manager_log="$(mktemp -t agentteams-manager.XXXXXX.log)"
  (cd "$root_dir/agent-manager" && exec ./run.sh serve) >"$manager_log" 2>&1 &
  manager_pid="$!"
  for _ in {1..30}; do
    if manager_health; then echo "[PASS] temporary local agent-manager is healthy"; return; fi
    if ! kill -0 "$manager_pid" 2>/dev/null; then break; fi
    sleep 1
  done
  echo "agent-manager failed to become healthy; sanitized log follows" >&2
  sed -E 's/(Bearer |token[=: ]+|secret[=: ]+)[^[:space:]]+/\1<redacted>/Ig' "$manager_log" >&2
  exit 4
}
node "$root_dir/scripts/preflight-agentteams-integration.js" "$env_file" --infra-only
(cd "$root_dir/agent-core" && npm run test:postgres-contract)
CHATFLOWS_MCP_BASE_URL="$CHATFLOWS_MCP_BASE_URL" "$root_dir/agentteams-apply.sh"
discovered="$(node "$root_dir/scripts/discover-agentteams-runtime.js")"
if [[ -z "${AGENTTEAMS_LEADER_IDS:-}" ]]; then AGENTTEAMS_LEADER_IDS="$(DISCOVERED="$discovered" node -e 'process.stdout.write(JSON.parse(process.env.DISCOVERED).AGENTTEAMS_LEADER_IDS)')"; export AGENTTEAMS_LEADER_IDS; fi
if [[ -z "${AGENTTEAMS_LEADER_ROOM_ID:-}" ]]; then AGENTTEAMS_LEADER_ROOM_ID="$(DISCOVERED="$discovered" node -e 'process.stdout.write(JSON.parse(process.env.DISCOVERED).AGENTTEAMS_LEADER_ROOM_ID)')"; export AGENTTEAMS_LEADER_ROOM_ID; fi
node "$root_dir/scripts/preflight-agentteams-integration.js" "$env_file"
ensure_manager
node "$root_dir/scripts/run-agentteams-platform-e2e.js" "$env_file"
