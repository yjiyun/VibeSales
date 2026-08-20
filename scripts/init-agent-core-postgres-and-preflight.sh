#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
agent_core_dir="${AGENT_CORE_DIR:-$root_dir/agent-core}"
mode="${1:-all}" # all | precheck | db-init
env_file="${2:-$root_dir/prod-deploy/agent-core.env}"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }
step(){ echo; echo "[STEP] $*"; }

usage() {
  cat <<'EOF'
usage:
  scripts/init-agent-core-postgres-and-preflight.sh [all|precheck|db-init] [agent-core.env]

examples:
  export DATABASE_ADMIN_URL='postgresql://postgres:***@10.1.1.42:5432/chatflows'
  scripts/init-agent-core-postgres-and-preflight.sh all prod-deploy/agent-core.env

notes:
  - default mode is "all"
  - DATABASE_ADMIN_URL is required for "db-init" and "all"
  - CHATFLOWS_APP_DB_PASSWORD and AGENT_RUNTIME_DB_PASSWORD must be exported explicitly
EOF
}

case "$mode" in
  all|precheck|db-init) ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    fail "unknown mode: $mode"
    ;;
esac

[[ -f "$env_file" ]] || fail "env file not found: $env_file"
[[ -d "$agent_core_dir" ]] || fail "agent-core directory not found: $agent_core_dir"

env_get() {
  local key="$1"
  local line value
  line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
  [[ -n "$line" ]] || return 1
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "${value:0:1}" == '`' && "${value: -1}" == '`' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

is_placeholder() {
  local value="${1:-}"
  [[ -z "$value" || "$value" == __REPLACE_ME__* || "$value" == __GENERATE__* || "$value" == "<"*">" ]]
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

check_required_value() {
  local key="$1" min_len="${2:-0}" value
  value="$(env_get "$key" || true)"
  [[ -n "$value" ]] || fail "$key is missing in $env_file"
  is_placeholder "$value" && fail "$key still contains a placeholder: $value"
  if (( min_len > 0 )) && (( ${#value} < min_len )); then
    fail "$key length ${#value} is too short; expected >= $min_len"
  fi
}

check_optional_placeholder_warn() {
  local key="$1" value
  value="$(env_get "$key" || true)"
  if [[ -z "$value" ]] || is_placeholder "$value"; then
    warn "$key is not finalized yet; related feature will not be production-ready"
  fi
}

url_field() {
  local url="$1" field="$2"
  node -e "const u=new URL(process.argv[1]); const field=process.argv[2]; const map={username:u.username,password:u.password,hostname:u.hostname,port:u.port||'',pathname:u.pathname.replace(/^\\//,'')}; process.stdout.write(String(map[field]??''));" "$url" "$field"
}

precheck() {
  step "Check dependencies"
  require_command node
  require_command npm
  require_command psql
  pass "node / npm / psql are available"

  step "Validate critical env values"
  check_required_value QWEN_BASE_URL 12
  check_required_value QWEN_GATEWAY_TOKEN 16
  check_required_value CHATFLOWS_ROOT 5
  check_required_value DATABASE_URL 12
  check_required_value MINIO_ENDPOINT 8
  check_required_value MINIO_ACCESS_KEY 1
  check_required_value MINIO_SECRET_KEY 1
  check_required_value MCP_SERVER_TOKEN 16
  check_required_value PIPELINE_CONTROL_TOKEN 16
  check_required_value BLUEPRINT_ADMIN_TOKEN 16
  check_required_value PIPELINE_APPROVAL_SIGNING_SECRET 32
  check_required_value WEB_AUTH_TOKEN 16
  check_required_value WEB_AUTH_CLIENT_CODE 3
  check_required_value AGENT_RUNTIME_TOKEN 16

  local artifact_store dashscope_key qwen_base qwen_token
  artifact_store="$(env_get ARTIFACT_STORE || true)"
  dashscope_key="$(env_get DASHSCOPE_API_KEY || true)"
  qwen_base="$(env_get QWEN_BASE_URL || true)"
  qwen_token="$(env_get QWEN_GATEWAY_TOKEN || true)"

  if [[ "${artifact_store,,}" == "postgres" && -n "$dashscope_key" ]]; then
    fail "DASHSCOPE_API_KEY must not be injected when ARTIFACT_STORE=postgres"
  fi

  node -e "const url=new URL(process.argv[1]); const token=process.argv[2]; const host=url.hostname.toLowerCase(); const privateHost=host==='localhost'||host==='127.0.0.1'||host==='::1'||host.startsWith('10.')||host.startsWith('192.168.')||/^172\\.(1[6-9]|2\\d|3[01])\\./.test(host)||!host.includes('.'); const openAiPath=url.pathname.replace(/\\/+$/,'')==='/v1'||url.pathname.includes('compatible'); if(token.length<16) throw new Error('QWEN_GATEWAY_TOKEN too short'); if(!(url.protocol==='https:'||(url.protocol==='http:'&&privateHost))) throw new Error('QWEN_BASE_URL protocol/host is invalid'); if(!privateHost && !host.includes('higress')) throw new Error('QWEN_BASE_URL should target a Higress-style host in production'); if(!openAiPath) throw new Error('QWEN_BASE_URL must be OpenAI-compatible');" "$qwen_base" "$qwen_token" || fail "QWEN gateway validation failed"
  pass "critical startup env values look valid"

  step "Check runtime paths"
  local chatflows_root log_dir
  chatflows_root="$(env_get CHATFLOWS_ROOT)"
  log_dir="$(env_get LOG_DIR || true)"
  [[ -d "$chatflows_root" ]] || fail "CHATFLOWS_ROOT does not exist: $chatflows_root"
  [[ -d "$chatflows_root/catalogs" ]] || fail "missing catalogs directory under CHATFLOWS_ROOT: $chatflows_root/catalogs"
  [[ -d "$chatflows_root/prompts" ]] || fail "missing prompts directory under CHATFLOWS_ROOT: $chatflows_root/prompts"
  [[ -d "$chatflows_root/agent-core" ]] || warn "CHATFLOWS_ROOT/agent-core not found: $chatflows_root/agent-core"
  if [[ -n "$log_dir" ]]; then
    mkdir -p "$log_dir" || fail "failed to create/access LOG_DIR: $log_dir"
  fi
  pass "CHATFLOWS_ROOT and LOG_DIR are usable"

  step "Warn about non-startup placeholders"
  check_optional_placeholder_warn P3C_BUSINESS_MCP_URL
  check_optional_placeholder_warn YUNFLOW_BASE_URL
  check_optional_placeholder_warn YUNFLOW_TOKEN
  check_optional_placeholder_warn YUNFLOW_SPACE_ID
  pass "precheck completed"
}

db_init() {
  step "Prepare PostgreSQL bootstrap context"
  require_command node
  require_command npm
  require_command psql

  local database_admin_url target_db bootstrap_url app_db_password runtime_db_password pg_host pg_user
  database_admin_url="${DATABASE_ADMIN_URL:-}"
  app_db_password="${CHATFLOWS_APP_DB_PASSWORD:-}"
  runtime_db_password="${AGENT_RUNTIME_DB_PASSWORD:-}"
  [[ -n "$database_admin_url" ]] || fail "DATABASE_ADMIN_URL is required for db-init/all"
  [[ -n "$app_db_password" ]] || fail "CHATFLOWS_APP_DB_PASSWORD is required for db-init/all"
  [[ -n "$runtime_db_password" ]] || fail "AGENT_RUNTIME_DB_PASSWORD is required for db-init/all"

  target_db="$(url_field "$database_admin_url" pathname)"
  [[ -n "$target_db" ]] || fail "unable to parse target database name from DATABASE_ADMIN_URL"
  bootstrap_url="$(node -e "const u=new URL(process.argv[1]); u.pathname='/postgres'; process.stdout.write(u.toString());" "$database_admin_url")"
  pg_host="$(url_field "$database_admin_url" hostname)"
  pg_user="$(url_field "$database_admin_url" username)"

  log "PostgreSQL admin user: ${pg_user}@${pg_host}"
  log "Target database: $target_db"
  log "Will initialize LOGIN roles: chatflows_app_login / agent_runtime_login"

  step "Ensure database exists"
  if psql "$bootstrap_url" -Atqc "select 1 from pg_database where datname='${target_db}'" | grep -q '^1$'; then
    pass "database already exists: $target_db"
  else
    log "database not found; creating: $target_db"
    psql "$bootstrap_url" -v ON_ERROR_STOP=1 -c "create database \"${target_db}\"" || fail "failed to create database $target_db"
    pass "created database: $target_db"
  fi

  step "Run schema and role initialization"
  [[ -d "$agent_core_dir/node_modules" ]] || fail "node_modules missing under $agent_core_dir; run npm install first"
  DOTENV_CONFIG_PATH="$env_file" \
  DATABASE_ADMIN_URL="$database_admin_url" \
  CHATFLOWS_APP_DB_PASSWORD="$app_db_password" \
  AGENT_RUNTIME_DB_PASSWORD="$runtime_db_password" \
  DATABASE_SSL="$(env_get DATABASE_SSL || printf '0')" \
  npm --prefix "$agent_core_dir" run db:init || fail "npm run db:init failed"
  pass "db:init finished"

  step "Verify schema and role grants"
  psql "$database_admin_url" -Atqc "select to_regclass('public.run'), to_regclass('public.artifact'), to_regclass('public.agent_blueprint'), to_regclass('public.agent_binding'), to_regclass('public.agentscope_skills')" || fail "failed to query core tables"
  psql "$database_admin_url" -Atqc "select pg_has_role('chatflows_app_login','chatflows_app','member'), pg_has_role('agent_runtime_login','agent_runtime','member')" | grep -q '^t|t$' || fail "login-role membership verification failed"
  pass "schema tables and login-role grants are present"
}

case "$mode" in
  precheck)
    precheck
    ;;
  db-init)
    db_init
    ;;
  all)
    precheck
    db_init
    ;;
esac

echo
pass "mode=$mode completed successfully"
