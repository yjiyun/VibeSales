#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${CHATFLOWS_LOCAL_DOCKER_ENV:-$root_dir/deploy/local/.env.local}"
example_file="$root_dir/deploy/local/.env.example"
compose_file="$root_dir/deploy/local/compose.yaml"
source_env="$root_dir/docs/agentteams/local-development.env.local"
fallback_env="$root_dir/agent-core/.env"
cmd="${1:-up}"

ensure_env() {
  if [[ -f "$env_file" ]]; then
    return 0
  fi
  if [[ ! -f "$example_file" ]]; then
    echo "missing template: $example_file" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$env_file")"
  EXAMPLE_FILE="$example_file" SOURCE_ENV="$source_env" FALLBACK_ENV="$fallback_env" TARGET_ENV="$env_file" node <<'NODE'
const fs = require('node:fs');
const crypto = require('node:crypto');
const parse = file => {
  if (!fs.existsSync(file)) return {};
  return Object.fromEntries(
    fs.readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(line => line && !line.startsWith('#') && line.includes('='))
      .map(line => {
        const at = line.indexOf('=');
        return [line.slice(0, at), line.slice(at + 1)];
      }),
  );
};
const example = parse(process.env.EXAMPLE_FILE);
const source = parse(process.env.SOURCE_ENV);
const fallback = parse(process.env.FALLBACK_ENV);
const rand = bytes => crypto.randomBytes(bytes).toString('hex');
const sharedGatewayToken = rand(16);
const credential = (token, client_code) => JSON.stringify({ token, client_code, roles: ['user', 'admin'] });
const beautyToken = rand(16);
const agriToken = rand(16);
const eduToken = rand(16);
const merged = {
  ...example,
  POSTGRES_ADMIN_PASSWORD: rand(16),
  CHATFLOWS_APP_DB_PASSWORD: rand(16),
  AGENT_RUNTIME_DB_PASSWORD: rand(16),
  MINIO_ROOT_PASSWORD: rand(16),
  HIGRESS_ADMIN_PASSWORD: rand(16),
  HIGRESS_CONSUMER_TOKEN: sharedGatewayToken,
  WEB_AUTH_TOKEN: beautyToken,
  PIPELINE_CONTROL_TOKEN: rand(16),
  PIPELINE_APPROVAL_SIGNING_SECRET: rand(24),
  RUNTIME_AUTH_TOKEN: rand(16),
  RUNTIME_ADMIN_TOKEN: rand(16),
  BLUEPRINT_ADMIN_TOKEN: rand(16),
  BUSINESS_MCP_BACKEND_TOKEN: sharedGatewayToken,
  HIGRESS_DASHSCOPE_API_KEY: source.DASHSCOPE_API_KEY || fallback.DASHSCOPE_API_KEY || '',
  WEB_AUTH_CREDENTIALS: `[${[
    credential(beautyToken, 'acme_beauty'),
    credential(agriToken, 'acme_agri'),
    credential(eduToken, 'acme_edu'),
  ].join(',')}]`,
  LOG_STDERR: 'on',
};
if (!merged.HIGRESS_DASHSCOPE_API_KEY) {
  throw new Error('Cannot bootstrap local Docker env: DASHSCOPE_API_KEY missing in docs/agentteams/local-development.env.local and agent-core/.env');
}
const lines = fs.readFileSync(process.env.EXAMPLE_FILE, 'utf8')
  .split(/\r?\n/)
  .map(line => {
    if (!line || line.trim().startsWith('#') || !line.includes('=')) return line;
    const at = line.indexOf('=');
    const key = line.slice(0, at);
    return `${key}=${merged[key] ?? line.slice(at + 1)}`;
  });
fs.writeFileSync(process.env.TARGET_ENV, `${lines.join('\n').replace(/\n+$/, '')}\n`);
NODE
  echo "created local env: $env_file"
}

compose() {
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
}

build_host_artifacts() {
  (
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "${line:0:1}" == "#" || "$line" != *=* ]] && continue
      export "$line"
    done < "$env_file"
    cd "$root_dir/agent-core" && npm run build
    cd "$root_dir/agent-console" && npm run build
  )
}

wait_url() {
  local url="$1" expect="${2:-200}" name="$3"
  local status
  for _ in $(seq 1 120); do
    status="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    [[ "$status" == "$expect" ]] && return 0
    sleep 2
  done
  echo "$name did not become ready: $url (want HTTP $expect, got $status)" >&2
  return 1
}

wait_service_exit_zero() {
  local service="$1"
  local cid exit_code
  for _ in $(seq 1 120); do
    cid="$(compose ps -aq "$service" | tail -n 1)"
    [[ -n "$cid" ]] && break
    sleep 1
  done
  [[ -n "${cid:-}" ]] || { echo "service has no container id: $service" >&2; return 1; }
  for _ in $(seq 1 120); do
    exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null || true)"
    if [[ "$exit_code" =~ ^[0-9]+$ ]]; then
      [[ "$exit_code" == "0" ]] && return 0
      echo "$service exited with code $exit_code" >&2
      compose logs "$service" >&2
      return 1
    fi
    sleep 2
  done
  echo "$service did not exit within timeout" >&2
  compose logs "$service" >&2
  return 1
}

verify_stack() {
  set -a
  source "$env_file"
  set +a
  wait_url "http://127.0.0.1:${LOCAL_HIGRESS_CONSOLE_PORT}/" 200 "Higress console"
  wait_url "http://127.0.0.1:${LOCAL_MINIO_API_PORT}/minio/health/live" 200 "MinIO"
  wait_url "http://127.0.0.1:${LOCAL_CONSOLE_PORT}/" 200 "Console"
  local nest_status runtime_status
  nest_status="$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${WEB_AUTH_TOKEN}" -H "X-Role: admin" -H "X-Actor: local-check" \
    "http://127.0.0.1:${LOCAL_NEST_BFF_PORT}/api/health" || true)"
  runtime_status="$(curl -s -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${LOCAL_RUNTIME_PORT}/healthz" || true)"
  [[ "$nest_status" == "200" ]] || { echo "Nest BFF health failed: HTTP $nest_status" >&2; exit 1; }
  [[ "$runtime_status" == "200" ]] || {
    echo "Runtime health failed: HTTP $runtime_status" >&2; exit 1;
  }
  node "$root_dir/scripts/configure-local-higress.mjs" "$env_file" >/dev/null
  if rg -n "10\\.1\\.2\\.3" "$env_file" "$compose_file" "$root_dir/scripts/configure-local-higress.mjs" "$root_dir/scripts/local-business-mcp.mjs" >/dev/null; then
    echo "found forbidden remote address 10.0.0.1 in local stack files" >&2
    exit 1
  fi
  echo "[PASS] local Docker stack is healthy"
}

case "$cmd" in
  up)
    ensure_env
    build_host_artifacts
    compose config --quiet
    compose build agent-core agent-core-bff db-init agent-runtime agent-console
    compose up -d --wait postgres redis minio higress local-business-mcp
    compose up -d minio-init db-init
    wait_service_exit_zero minio-init
    wait_service_exit_zero db-init
    node "$root_dir/scripts/configure-local-higress.mjs" "$env_file"
    compose up -d --wait agent-runtime agent-core agent-core-bff agent-console
    verify_stack
    set -a
    source "$env_file"
    set +a
    cat <<INFO
Console:        http://127.0.0.1:${LOCAL_CONSOLE_PORT}
Nest BFF:       http://127.0.0.1:${LOCAL_NEST_BFF_PORT}
Nest MCP:       http://127.0.0.1:${LOCAL_NEST_MCP_PORT}
Runtime:        http://127.0.0.1:${LOCAL_RUNTIME_PORT}
Higress Console:http://127.0.0.1:${LOCAL_HIGRESS_CONSOLE_PORT}
Higress Gateway:http://127.0.0.1:${LOCAL_HIGRESS_GATEWAY_PORT}
MinIO API:      http://127.0.0.1:${LOCAL_MINIO_API_PORT}
MinIO Console:  http://127.0.0.1:${LOCAL_MINIO_CONSOLE_PORT}
INFO
    ;;
  down)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    compose down
    ;;
  reset)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    compose down -v --remove-orphans
    ;;
  status)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    compose ps
    ;;
  logs)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    shift || true
    compose logs -f "$@"
    ;;
  verify)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    verify_stack
    ;;
  configure-higress)
    ensure_env
    node "$root_dir/scripts/configure-local-higress.mjs" "$env_file"
    ;;
  rotate-key)
    [[ -f "$env_file" ]] || { echo "env file not found: $env_file" >&2; exit 1; }
    shift || true
    node "$root_dir/scripts/rotate-higress-llm-key.mjs" --target local --env "$env_file" "$@"
    ;;
  *)
    echo "usage: $0 {up|down|reset|status|logs [service...]|verify|configure-higress|rotate-key}" >&2
    exit 2
    ;;
esac
