#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
fake_bin="$(mktemp -d -t chatflows-fake-agt.XXXXXX)"
capture="$(mktemp -d -t chatflows-agt-capture.XXXXXX)"
cleanup(){ rm -rf "$fake_bin" "$capture"; }
trap cleanup EXIT

cat > "$fake_bin/agt" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == apply && "$2" == -f ]]
count="$(find "$AGT_CAPTURE" -type f | wc -l | tr -d ' ')"
cp "$3" "$AGT_CAPTURE/$(printf '%03d' "$count")-$(basename "$3")"
SH
chmod +x "$fake_bin/agt"
PATH="$fake_bin:$PATH" AGT_CAPTURE="$capture" AGENTTEAMS_APPLY_TRANSPORT=agt CHATFLOWS_MCP_BASE_URL=https://gateway.test "$root_dir/agentteams-apply.sh"

[[ "$(find "$capture" -type f | wc -l | tr -d ' ')" == 13 ]]
[[ "$(rg -l '^kind: Worker$' "$capture" | wc -l | tr -d ' ')" == 11 ]]
! rg -q 'higress\.(example|local)' "$capture"
rg -q 'https://gateway.test/mcp-servers/chatflows-p1"' "$capture/001-10-wizard-intent.yaml"
rg -q 'Bundled Skill contract: p1-wizard-gate' "$capture/001-10-wizard-intent.yaml"
rg -q 'chatflows-p1.ask、revise、buildPhase1Result' "$capture/001-10-wizard-intent.yaml"
rg -q 'Bundled Skill contract: leader-route' "$capture/000-00-team-leader.yaml"
rg -q '^kind: Team$' "$capture/011-team.yaml"
rg -q '^kind: Human$' "$capture/012-humans.yaml"
# 无 HIGRESS_CONSUMER_TOKEN 时不注入 headers（向后兼容），非空 mcpServers 的 Worker 保持原状。
! rg -q 'headers: \{ Authorization:' "$capture/005-32-blueprint-compose.yaml"

# qwenpaw_worker 的 update.py:_apply_mcp_servers 只在 mcpServers[].headers 缺 Authorization
# 时才用容器 env 的错误 gateway key 兜底覆盖（driver_not_found 根因，见 docs/agentteams/todo.md
# §5）。设置 HIGRESS_CONSUMER_TOKEN 后必须让渲染产物显式带上正确 headers，永久压住这条兜底。
token_capture="$(mktemp -d -t chatflows-agt-capture-token.XXXXXX)"
PATH="$fake_bin:$PATH" AGT_CAPTURE="$token_capture" AGENTTEAMS_APPLY_TRANSPORT=agt CHATFLOWS_MCP_BASE_URL=https://gateway.test HIGRESS_CONSUMER_TOKEN=test-mcp-token-0123456789 "$root_dir/agentteams-apply.sh"
rg -q 'headers: \{ Authorization: "Bearer test-mcp-token-0123456789" \}' "$token_capture/001-10-wizard-intent.yaml"
rg -q 'headers: \{ Authorization: "Bearer test-mcp-token-0123456789" \}' "$token_capture/005-32-blueprint-compose.yaml"
rg -q '^  mcpServers: \[\]$' "$token_capture/000-00-team-leader.yaml"
rm -rf "$token_capture"

if PATH="$fake_bin:$PATH" AGT_CAPTURE="$capture" AGENTTEAMS_APPLY_TRANSPORT=agt CHATFLOWS_MCP_BASE_URL=http://public.example "$root_dir/agentteams-apply.sh" >/dev/null 2>&1; then echo 'public HTTP gateway accepted' >&2; exit 1; fi
node "$root_dir/scripts/test-agentteams-rest-apply.mjs"
echo '[PASS] agentteams-apply bundles Skill contracts, renders gateway URL, injects MCP Authorization headers, applies 11 Workers → Team → Human'
