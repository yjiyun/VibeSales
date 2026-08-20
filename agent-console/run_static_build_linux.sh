#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
agent_core_env="${AGENT_CORE_WEB_ENV_FILE:-$root_dir/../agent-core/.env.forWeb}"
integration_env="${INTEGRATION_ENV_FILE:-$root_dir/../deploy/agentteams/integration.env}"

log() { echo "[INFO] $*"; }
step() { echo; echo "[STEP] $*"; }
pass() { echo "[PASS] $*"; }
fail() { echo "[FAIL] $*" >&2; exit 1; }

trim_ws() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

normalize_env_value() {
  local value
  value="$(trim_ws "$1")"
  if [[ ${#value} -ge 2 ]]; then
    local first="${value:0:1}"
    local last="${value: -1}"
    if [[ ("$first" == '"' && "$last" == '"') || ("$first" == "'" && "$last" == "'") || ("$first" == '`' && "$last" == '`') ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s' "$value"
}

read_env_value() {
  local file="$1" key="$2" raw line value
  [[ -f "$file" ]] || fail "env file not found: $file"
  raw="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -n "$raw" ]] || fail "${key} is missing in $file"
  line="${raw#*=}"
  value="$(normalize_env_value "$line")"
  [[ -n "$value" ]] || fail "${key} is empty in $file"
  printf '%s' "$value"
}

read_env_value_optional() {
  local file="$1" key="$2" raw line value
  [[ -f "$file" ]] || fail "env file not found: $file"
  raw="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -n "$raw" ]] || return 1
  line="${raw#*=}"
  value="$(normalize_env_value "$line")"
  [[ -n "$value" ]] || return 1
  printf '%s' "$value"
}

extract_default_wizard_credential() {
  local json="$1"
  node -e "const raw=process.argv[1];
try{
  const parsed=JSON.parse(raw);
  if(!Array.isArray(parsed)||parsed.length===0) throw new Error('WEB_AUTH_CREDENTIALS must be a non-empty JSON array');
  const first=parsed[0];
  if(!first || !String(first.token||'').trim() || !String(first.client_code||'').trim()){
    throw new Error('WEB_AUTH_CREDENTIALS[0] must include token and client_code');
  }
  console.log(String(first.token).trim());
  console.log(String(first.client_code).trim());
} catch (error) {
  console.error(String(error.message||error));
  process.exit(1);
}" "$json"
}

step "Check dependencies"
command -v npm >/dev/null 2>&1 || fail "npm is required"
command -v node >/dev/null 2>&1 || fail "node is required"
[[ -f "$root_dir/package.json" ]] || fail "package.json not found under $root_dir"
[[ -d "$root_dir/node_modules" ]] || fail "node_modules missing under $root_dir; run npm install first"
[[ -f "$agent_core_env" ]] || fail "agent-core web env file not found: $agent_core_env"
[[ -f "$integration_env" ]] || fail "integration env file not found: $integration_env"
pass "npm, node, and env files are available"

step "Load build-time auth variables"
WEB_AUTH_CREDENTIALS="$(read_env_value_optional "$agent_core_env" "WEB_AUTH_CREDENTIALS" || true)"
if [[ -n "$WEB_AUTH_CREDENTIALS" ]]; then
  log "Using WEB_AUTH_CREDENTIALS from $agent_core_env"
  mapfile -t wizard_default < <(extract_default_wizard_credential "$WEB_AUTH_CREDENTIALS") || fail "failed to parse WEB_AUTH_CREDENTIALS from $agent_core_env"
  WEB_AUTH_TOKEN="${wizard_default[0]:-}"
  WEB_AUTH_CLIENT_CODE="${wizard_default[1]:-}"
  [[ -n "$WEB_AUTH_TOKEN" ]] || fail "default WEB_AUTH_TOKEN derived from WEB_AUTH_CREDENTIALS is empty"
  [[ -n "$WEB_AUTH_CLIENT_CODE" ]] || fail "default WEB_AUTH_CLIENT_CODE derived from WEB_AUTH_CREDENTIALS is empty"
  log "Derived default wizard credential from WEB_AUTH_CREDENTIALS: client_code=$WEB_AUTH_CLIENT_CODE"
else
  WEB_AUTH_TOKEN="$(read_env_value "$agent_core_env" "WEB_AUTH_TOKEN")"
  WEB_AUTH_CLIENT_CODE="$(read_env_value "$agent_core_env" "WEB_AUTH_CLIENT_CODE")"
  log "WEB_AUTH_CREDENTIALS not found; falling back to WEB_AUTH_TOKEN + WEB_AUTH_CLIENT_CODE"
fi
PIPELINE_CONTROL_TOKEN="$(read_env_value "$integration_env" "PIPELINE_CONTROL_TOKEN")"
RUNTIME_AUTH_TOKEN="$(read_env_value "$integration_env" "RUNTIME_AUTH_TOKEN")"
RUNTIME_ADMIN_TOKEN="$(read_env_value "$integration_env" "RUNTIME_ADMIN_TOKEN")"
MANAGER_AUTH_TOKEN="$(read_env_value "$integration_env" "MANAGER_AUTH_TOKEN")"
MANAGER_ADMIN_TOKEN="$(read_env_value "$integration_env" "MANAGER_ADMIN_TOKEN")"
AGENTTEAMS_HUMAN_IDS="$(read_env_value "$integration_env" "AGENTTEAMS_HUMAN_IDS")"
pass "required build-time variables loaded"

step "Run static build"
log "Using agent-core env: $agent_core_env"
log "Using integration env: $integration_env"
ORCHESTRATION_MODE=platform \
WEB_AUTH_CREDENTIALS="$WEB_AUTH_CREDENTIALS" \
WEB_AUTH_TOKEN="$WEB_AUTH_TOKEN" \
WEB_AUTH_CLIENT_CODE="$WEB_AUTH_CLIENT_CODE" \
PIPELINE_CONTROL_TOKEN="$PIPELINE_CONTROL_TOKEN" \
RUNTIME_AUTH_TOKEN="$RUNTIME_AUTH_TOKEN" \
RUNTIME_ADMIN_TOKEN="$RUNTIME_ADMIN_TOKEN" \
MANAGER_AUTH_TOKEN="$MANAGER_AUTH_TOKEN" \
MANAGER_ADMIN_TOKEN="$MANAGER_ADMIN_TOKEN" \
AGENTTEAMS_HUMAN_IDS="$AGENTTEAMS_HUMAN_IDS" \
npm run build

pass "agent-console static build completed"
log "Build output: $root_dir/dist"
