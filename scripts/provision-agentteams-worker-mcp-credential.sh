#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "usage: printf '%s' \"\$HIGRESS_CONSUMER_TOKEN\" | $0 <worker-name> <mcp-server-name>" >&2
  exit 2
fi
worker="$1"
server="$2"
if [[ ! "$worker" =~ ^[a-z0-9][a-z0-9-]*$ || ! "$server" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  echo "worker and MCP server names must match [a-z0-9][a-z0-9-]*" >&2
  exit 2
fi
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
container="agentteams-worker-$worker"
helper="/tmp/put-qwenpaw-mcp-credential.py"
credentials="/root/agentteams-fs/agents/$worker/.qwenpaw/workspaces/default/credentials.yaml"

if ! docker inspect "$container" >/dev/null 2>&1; then
  echo "AgentTeams Worker container not found: $container" >&2
  exit 4
fi

# A failed MCP startup may leave the container stopped. Start it only to make
# docker exec available; the Controller remains the authority for readiness.
if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" != "true" ]]; then
  docker start "$container" >/dev/null
fi
docker cp "$root_dir/scripts/put-qwenpaw-mcp-credential.py" "$container:$helper"
# Read the dedicated Chatflows MCP Consumer token only from stdin. It is never
# placed in argv, the Worker CR, environment variables, or command output.
docker exec -i "$container" /opt/venv/qwenpaw/bin/python \
  "$helper" --credentials "$credentials" --server "$server"

# Restart only after the encrypted credential has been verified. The secret is
# persisted in the Worker private storage and is never placed in the CR/YAML.
docker restart "$container" >/dev/null
echo "[CREDENTIAL] restarted Worker/$worker"
