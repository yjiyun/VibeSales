#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
port="${BFF_SMOKE_PORT:-23101}"
token="bff-smoke-token-0123456789"
# mktemp 会预建空文件，而 ArtifactStore 对空内容 JSON.parse 会启动即失败，所以用目录 + 未存在的文件名。
store_dir="$(mktemp -d -t chatflows-bff-store.XXXXXX)"
store="$store_dir/agentteams-store.json"
static_dir="$(mktemp -d -t chatflows-bff-static.XXXXXX)"
log="$(mktemp -t chatflows-bff.XXXXXX.log)"
pid=""
# 下面起服务用的是子 shell（要先 cd 再带一长串 env），所以 $! 拿到的是**子 shell**，
# 真正监听端口的 node 是它的子进程。只 kill 子 shell 会把 node 甩给 init 并继续占
# 23101，下一次 verify-all 就会撞端口。先抓子孙再自下而上收（与 verify-all.sh
# stop_runtime / run-console-ui-evidence.sh stop_tree 同口径）。
stop_tree(){
  local target="$1"
  [[ -z "$target" ]] && return 0
  local descendants; descendants="$(pgrep -P "$target" 2>/dev/null || true)"
  if kill -0 "$target" 2>/dev/null; then kill "$target" 2>/dev/null || true; wait "$target" 2>/dev/null || true; fi
  local child
  for child in $descendants; do stop_tree "$child"; done
}
cleanup(){ stop_tree "$pid"; rm -f "$log"; rm -rf "$static_dir" "$store_dir"; }
trap cleanup EXIT
(
 cd "$root_dir/agent-core"
 ARTIFACT_STORE=file ARTIFACT_STORE_FILE="$store" FLOW_PLATFORM_MODE=local LOG_STDERR=off LOG_FILE=off WEB_HOST=127.0.0.1 WEB_PORT="$port" WEB_STATIC_ROOT="$static_dir" WEB_AUTH_TOKEN="$token" WEB_AUTH_CLIENT_CODE=acme_beauty PIPELINE_CONTROL_TOKEN=pipeline-smoke-token-0123456789 PIPELINE_APPROVAL_SIGNING_SECRET=approval-smoke-secret-at-least-32-characters BLUEPRINT_ADMIN_TOKEN=blueprint-smoke-token-0123456789 MCP_SERVER_TOKEN=mcp-smoke-token-0123456789 node dist/main-web.js
) >"$log" 2>&1 &
pid=$!
code=""
for _ in $(seq 1 80); do code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/health" || true)"; [[ "$code" == 401 ]] && break; if ! kill -0 "$pid" 2>/dev/null; then cat "$log" >&2; exit 1; fi; sleep 0.1; done
[[ "$code" == 401 ]]
auth=(-H "Authorization: Bearer $token" -H 'X-Role: user' -H 'X-Actor: @smoke:local')
curl -fsS "${auth[@]}" "http://127.0.0.1:$port/api/health" | grep -q '"ok":true'
session="$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -d '{"client_code":"acme_edu","llm":false}' "http://127.0.0.1:$port/api/wizard/sessions")"
node -e 'const x=JSON.parse(process.argv[1]);if(x.client_code!=="acme_beauty"||!x.session_id)process.exit(1)' "$session"
echo '[PASS] BFF HTTP rejects anonymous, authenticates and binds session to credential tenant'
