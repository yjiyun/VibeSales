#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

mode="${1:-start}" # start | stop | restart
env_file="${MANAGER_ENV_FILE:-$root_dir/agent-manager.env}"
pid_file="${MANAGER_PID_FILE:-$root_dir/agent-manager.pid}"
log_dir="${MANAGER_LOG_DIR:-$root_dir/logs}"
log_file="${MANAGER_LOG_FILE:-$log_dir/agent-manager.out}"
main_class="com.yjiyun.chatflows.manager.ManagerApplication"
classpath="target/dist/classes:target/dist/lib/*"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage:
  agent-manager/run_linux.sh [start|stop|restart]

defaults:
  env file : agent-manager/agent-manager.env
  pid file : agent-manager/agent-manager.pid
  log file : agent-manager/logs/agent-manager.out

notes:
  - this script is for Ubuntu/Linux
  - it uses java from PATH
  - it starts ManagerApplication serve with nohup in the background
  - required packaged files are target/dist/classes and target/dist/lib
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

manager_health() {
  local token="${MANAGER_AUTH_TOKEN:-}"
  [[ -n "$token" ]] || return 1
  local port="${MANAGER_PORT:-8090}"
  local code
  code="$(curl -sS -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $token" \
    -H "X-Role: user" \
    "http://127.0.0.1:${port}/api/v1/health" 2>/dev/null || true)"
  [[ "$code" == "200" ]]
}

start_manager() {
  require_command java
  [[ -f "$env_file" ]] || fail "env file not found: $env_file"
  [[ -f "target/dist/classes/com/yjiyun/chatflows/manager/ManagerApplication.class" ]] || fail "missing target/dist/classes ManagerApplication.class; run build-dist-linux.sh first"
  [[ -d "target/dist/lib" ]] || fail "missing target/dist/lib; run build-dist-linux.sh first"

  if is_running; then
    local existing_pid
    existing_pid="$(cat "$pid_file")"
    fail "agent-manager is already running with pid=$existing_pid"
  fi

  mkdir -p "$log_dir"

  log "Using env file: $env_file"
  log "PID file: $pid_file"
  log "Log file: $log_file"

  load_env_file_literal "$env_file"

  : "${MANAGER_HOST:=127.0.0.1}"
  : "${MANAGER_PORT:=8090}"

  log "Starting agent-manager on ${MANAGER_HOST}:${MANAGER_PORT}"
  nohup java -cp "$classpath" "$main_class" serve >>"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" > "$pid_file"
  sleep 2

  if ! kill -0 "$pid" 2>/dev/null; then
    warn "agent-manager exited early; last log lines:"
    tail -n 60 "$log_file" 2>/dev/null || true
    rm -f "$pid_file"
    fail "agent-manager failed to stay alive after start"
  fi

  if manager_health; then
    pass "agent-manager started successfully with pid=$pid"
    return 0
  fi

  warn "process is alive but /api/v1/health is not ready yet; last log lines:"
  tail -n 60 "$log_file" 2>/dev/null || true
  pass "agent-manager started in background with pid=$pid"
}

stop_manager() {
  if ! is_running; then
    warn "agent-manager is not running"
    rm -f "$pid_file"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  log "Stopping agent-manager pid=$pid"
  kill "$pid" 2>/dev/null || true

  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      pass "agent-manager stopped"
      return 0
    fi
    sleep 1
  done

  warn "process did not exit after graceful stop; sending SIGKILL"
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
  pass "agent-manager stopped with SIGKILL"
}

case "$mode" in
  start)
    start_manager
    ;;
  stop)
    stop_manager
    ;;
  restart)
    stop_manager
    start_manager
    ;;
esac
