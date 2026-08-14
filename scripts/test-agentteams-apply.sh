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
rg -q 'https://gateway.test/mcp-servers/chatflows-p1/mcp' "$capture/001-10-wizard-intent.yaml"
rg -q 'Bundled Skill contract: p1-wizard-gate' "$capture/001-10-wizard-intent.yaml"
rg -q 'chatflows-p1.ask、revise、buildPhase1Result' "$capture/001-10-wizard-intent.yaml"
rg -q 'Bundled Skill contract: leader-route' "$capture/000-00-team-leader.yaml"
rg -q '^kind: Team$' "$capture/011-team.yaml"
rg -q '^kind: Human$' "$capture/012-humans.yaml"
if PATH="$fake_bin:$PATH" AGT_CAPTURE="$capture" AGENTTEAMS_APPLY_TRANSPORT=agt CHATFLOWS_MCP_BASE_URL=http://public.example "$root_dir/agentteams-apply.sh" >/dev/null 2>&1; then echo 'public HTTP gateway accepted' >&2; exit 1; fi
node "$root_dir/scripts/test-agentteams-rest-apply.mjs"
echo '[PASS] agentteams-apply bundles Skill contracts, renders gateway URL, applies 11 Workers → Team → Human'
