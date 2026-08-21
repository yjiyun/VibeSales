#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

mode="${1:-start}" # start | stop | restart
env_file="${MCP_ENV_FILE:-$root_dir/.env}"
pid_file="${MCP_PID_FILE:-$root_dir/agent-core-mcp.pid}"
log_dir="${MCP_LOG_DIR:-$root_dir/logs}"
log_file="${MCP_LOG_FILE:-$log_dir/agent-core-mcp.out}"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage:
  agent-core/run_mcp_linux.sh [start|stop|restart]

defaults:
  env file : agent-core/.env
  pid file : agent-core/agent-core-mcp.pid
  log file : agent-core/logs/agent-core-mcp.out

notes:
  - this script is for Ubuntu/Linux
  - it prefers built output dist/main-mcp.js
  - if dist/main-mcp.js is missing, it falls back to npm run mcp
  - it reads agent-core/.env by default
  - it uses WEB_PORT/WEB_HOST from env, with 3100/127.0.0.1 as fallback
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

mcp_health() {
  local port="${WEB_PORT:-3100}"
  local code
  code="$(curl -sS -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}/healthz" 2>/dev/null || true)"
  [[ "$code" == "200" || "$code" == "503" ]]
}

start_mcp() {
  require_command npm
  require_command node
  [[ -f "$env_file" ]] || fail "env file not found: $env_file"
  [[ -d "node_modules" ]] || fail "node_modules missing under $root_dir; run npm install first"

  if is_running; then
    local existing_pid
    existing_pid="$(cat "$pid_file")"
    fail "agent-core mcp is already running with pid=$existing_pid"
  fi

  mkdir -p "$log_dir"

  log "Using env file: $env_file"
  log "PID file: $pid_file"
  log "Log file: $log_file"

  load_env_file_literal "$env_file"

  export WEB_PORT="${WEB_PORT:-3100}"
  export WEB_HOST="${WEB_HOST:-127.0.0.1}"

  local command
  if [[ -f "dist/main-mcp.js" ]]; then
    command=(npm run start:prod)
  else
    command=(npm run mcp)
    warn "dist/main-mcp.js not found; falling back to ts-node via npm run mcp"
  fi

  log "Starting agent-core mcp on ${WEB_HOST}:${WEB_PORT}"
  nohup "${command[@]}" >>"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" > "$pid_file"
  sleep 3

  if ! kill -0 "$pid" 2>/dev/null; then
    warn "agent-core mcp exited early; last log lines:"
    tail -n 60 "$log_file" 2>/dev/null || true
    rm -f "$pid_file"
    fail "agent-core mcp failed to stay alive after start"
  fi

  if mcp_health; then
    pass "agent-core mcp started successfully with pid=$pid"
    return 0
  fi

  warn "process is alive but /healthz is not ready yet; last log lines:"
  tail -n 60 "$log_file" 2>/dev/null || true
  pass "agent-core mcp started in background with pid=$pid"
}

stop_mcp() {
  if ! is_running; then
    warn "agent-core mcp is not running"
    rm -f "$pid_file"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  log "Stopping agent-core mcp pid=$pid"
  kill "$pid" 2>/dev/null || true

  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      pass "agent-core mcp stopped"
      return 0
    fi
    sleep 1
  done

  warn "process did not exit after graceful stop; sending SIGKILL"
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
  pass "agent-core mcp stopped with SIGKILL"
}

case "$mode" in
  start)
    start_mcp
    ;;
  stop)
    stop_mcp
    ;;
  restart)
    stop_mcp
    start_mcp
    ;;
esac
