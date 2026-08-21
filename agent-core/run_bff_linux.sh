#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

mode="${1:-start}" # start | stop | restart
env_file="${BFF_ENV_FILE:-$root_dir/.env.forWeb}"
pid_file="${BFF_PID_FILE:-$root_dir/agent-core-bff.pid}"
log_dir="${BFF_LOG_DIR:-$root_dir/logs}"
log_file="${BFF_LOG_FILE:-$log_dir/agent-core-bff.out}"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage:
  agent-core/run_bff_linux.sh [start|stop|restart]

defaults:
  env file : agent-core/.env.forWeb
  pid file : agent-core/agent-core-bff.pid
  log file : agent-core/logs/agent-core-bff.out

notes:
  - this script is for Ubuntu/Linux
  - it prefers built output dist/main-web.js
  - if dist/main-web.js is missing, it falls back to npm run web
  - it reads agent-core/.env.forWeb by default
  - it forces WEB_PORT=3101 by default for the BFF/Web process
EOF
}

case "$mode" in
  start|stop|restart) ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    fail "unknown mode: $mode"
    ;;
esac

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

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

load_env_file_literal() {
  local file="$1"
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    raw="${raw%$'\r'}"
    [[ -z "${raw//[[:space:]]/}" ]] && continue
    [[ "$raw" =~ ^[[:space:]]*# ]] && continue
    [[ "$raw" == *"="* ]] || continue

    local key="${raw%%=*}"
    local value="${raw#*=}"
    key="$(trim_ws "$key")"
    value="$(normalize_env_value "$value")"

    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    export "$key=$value"
  done < "$file"
}

is_running() {
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

bff_health() {
  local port="${WEB_PORT:-3101}"
  local code
  code="$(curl -sS -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}/api/health" 2>/dev/null || true)"
  [[ "$code" == "200" || "$code" == "401" || "$code" == "403" ]]
}

start_bff() {
  require_command npm
  require_command node
  [[ -f "$env_file" ]] || fail "env file not found: $env_file"
  [[ -d "node_modules" ]] || fail "node_modules missing under $root_dir; run npm install first"

  if is_running; then
    local existing_pid
    existing_pid="$(cat "$pid_file")"
    fail "agent-core bff is already running with pid=$existing_pid"
  fi

  mkdir -p "$log_dir"

  log "Using env file: $env_file"
  log "PID file: $pid_file"
  log "Log file: $log_file"

  load_env_file_literal "$env_file"

  export WEB_PORT="${WEB_PORT:-3101}"
  export WEB_HOST="${WEB_HOST:-127.0.0.1}"

  local command
  if [[ -f "dist/main-web.js" ]]; then
    command=(npm run start:web)
  else
    command=(npm run web)
    warn "dist/main-web.js not found; falling back to ts-node via npm run web"
  fi

  log "Starting agent-core bff on ${WEB_HOST}:${WEB_PORT}"
  nohup "${command[@]}" >>"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" > "$pid_file"
  sleep 3

  if ! kill -0 "$pid" 2>/dev/null; then
    warn "agent-core bff exited early; last log lines:"
    tail -n 60 "$log_file" 2>/dev/null || true
    rm -f "$pid_file"
    fail "agent-core bff failed to stay alive after start"
  fi

  if bff_health; then
    pass "agent-core bff started successfully with pid=$pid"
    return 0
  fi

  warn "process is alive but /api/health is not ready yet; last log lines:"
  tail -n 60 "$log_file" 2>/dev/null || true
  pass "agent-core bff started in background with pid=$pid"
}

stop_bff() {
  if ! is_running; then
    warn "agent-core bff is not running"
    rm -f "$pid_file"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  log "Stopping agent-core bff pid=$pid"
  kill "$pid" 2>/dev/null || true

  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      pass "agent-core bff stopped"
      return 0
    fi
    sleep 1
  done

  warn "process did not exit after graceful stop; sending SIGKILL"
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
  pass "agent-core bff stopped with SIGKILL"
}

case "$mode" in
  start)
    start_bff
    ;;
  stop)
    stop_bff
    ;;
  restart)
    stop_bff
    start_bff
    ;;
esac
