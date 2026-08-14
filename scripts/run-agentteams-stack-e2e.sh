#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${1:-$root_dir/deploy/agentteams/integration.env}"
compose="$root_dir/deploy/agentteams/compose.yaml"
"$root_dir/scripts/configure-chatflows-task-storage.js" "$env_file" --check
"$root_dir/scripts/deploy-agentteams-stack.sh" "$env_file"
dc=(docker compose --env-file "$env_file" -f "$compose")
"${dc[@]}" run --rm verifier scripts/preflight-agentteams-integration.js --infra-only
"${dc[@]}" run --rm verifier scripts/render-agentteams-bundle.js /rendered
"${dc[@]}" run --rm agent-manager-runner apply-manifest /rendered/manifest.json
"$root_dir/scripts/configure-agentteams-higress.js" "$env_file"
# The Leader must not merely be prompted away from task-room orchestration: its
# runtime driver exposes only fixed-Team messaging and shared-file sync.
"$root_dir/scripts/configure-agentteams-leader-tools.js"
# Applying a Worker can race the initial Higress allow-list and QwenPaw's
# default driver policy is "ask". Reload each bound card after Higress is
# ready, deny by default, allow only the manager-free Team Room on that stage
# client, and prove real tool discovery.
"$root_dir/scripts/configure-agentteams-worker-mcp.js"
# Stage Workers report authoritative MCP artifacts in the fixed Team Room;
# only the Leader may publish the top-level result.md after P4. Enforce that
# boundary in QwenPaw's ResourceGovernor before any E2E run is created.
"$root_dir/scripts/configure-agentteams-worker-filesystem-policy.js"
rooms="$("${dc[@]}" run -T --rm verifier scripts/discover-agentteams-resources.js)"
leader_room_id="$(node -e 'const x=JSON.parse(process.argv[1]);process.stdout.write(x.leaderRoomId)' "$rooms")"
team_room_id="$(node -e 'const x=JSON.parse(process.argv[1]);process.stdout.write(x.teamRoomId)' "$rooms")"
if [[ "$leader_room_id" != '!'* || "$team_room_id" != '!'* || "$leader_room_id" == "$team_room_id" ]]; then echo "Leader/Team Room discovery failed" >&2; exit 6; fi
echo "[PASS] discovered distinct Leader and Team Rooms from Controller"
"${dc[@]}" run --rm verifier scripts/preflight-agentteams-integration.js
"${dc[@]}" run --rm --entrypoint npm verifier --prefix /app/agent-core run test:postgres-contract
"${dc[@]}" run --rm verifier scripts/reset-agentteams-worker-sessions.js
"${dc[@]}" run --rm -e AGENTTEAMS_LEADER_ROOM_ID="$leader_room_id" verifier scripts/reset-agentteams-leader-session.js
run_id="$("${dc[@]}" run -T --rm -e AGENTTEAMS_LEADER_ROOM_ID="$leader_room_id" -e AGENTTEAMS_RUN_SPEC=/app/docs/agentteams/e2e-spec.md verifier scripts/e2e-manager-run.js start)"
if [[ ! "$run_id" =~ ^[0-9a-fA-F-]{36}$ ]]; then echo "Manager did not issue a valid run_id" >&2; exit 7; fi
echo "[RUN] Manager/Nest issued run_id=$run_id"
approver_pid=""
cleanup_approver(){ if [[ -n "$approver_pid" ]] && kill -0 "$approver_pid" 2>/dev/null; then kill "$approver_pid" 2>/dev/null || true; wait "$approver_pid" 2>/dev/null || true; fi; }
trap cleanup_approver EXIT
"${dc[@]}" run --rm -e AGENTTEAMS_RUN_ID="$run_id" verifier scripts/e2e-human-approver.js &
approver_pid=$!
"${dc[@]}" run --rm -e AGENTTEAMS_RUN_ID="$run_id" verifier scripts/e2e-manager-run.js wait
wait "$approver_pid"
approver_pid=""
trap - EXIT
echo "[PASS] deployed stack completed autonomous AgentTeams P1-P4"
