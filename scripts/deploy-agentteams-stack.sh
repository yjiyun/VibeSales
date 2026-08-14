#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${1:-$root_dir/deploy/agentteams/integration.env}"
compose="$root_dir/deploy/agentteams/compose.yaml"
if [[ ! -f "$env_file" ]]; then echo "env file not found: $env_file" >&2; exit 2; fi
docker compose --env-file "$env_file" -f "$compose" config --quiet
docker compose --env-file "$env_file" -f "$compose" build agent-core agent-core-bff db-init verifier agent-runtime agent-manager agent-console
docker compose --env-file "$env_file" -f "$compose" up -d --wait postgres redis artifact-bucket-init db-init agent-runtime agent-core agent-core-bff agent-manager agent-console
mcp_address="$(docker compose --env-file "$env_file" -f "$compose" port agent-core 3100)"
curl --fail --silent --show-error "http://$mcp_address/healthz" >/dev/null
docker compose --env-file "$env_file" -f "$compose" run --rm agent-manager-runner check
docker compose --env-file "$env_file" -f "$compose" ps
echo "[PASS] chatflows PostgreSQL/Redis/MinIO/Nest/runtime and AgentTeams manager dependencies are healthy"
echo "Apply Workers: CHATFLOWS_MCP_BASE_URL=<gateway> HIGRESS_CONSUMER_TOKEN=<token> ./agentteams-apply.sh"
console_port="$(sed -n 's/^AGENT_CONSOLE_HOST_PORT=//p' "$env_file" | tail -1)"
echo "Console: http://127.0.0.1:${console_port:-5173}; manager HTTP is running persistently"
echo "CLI fallback: docker compose --env-file '$env_file' -f '$compose' run --rm agent-manager-runner run <room> <run_id> <client> /specs/e2e-spec.md"
