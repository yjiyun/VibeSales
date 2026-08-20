#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

mode="${1:-start}" # start | stop | restart
env_file="${RUNTIME_ENV_FILE:-$root_dir/agent-runtime.env}"
pid_file="${RUNTIME_PID_FILE:-$root_dir/agent-runtime.pid}"
log_dir="${RUNTIME_LOG_DIR:-$root_dir/logs}"
log_file="${RUNTIME_LOG_FILE:-$log_dir/agent-runtime.out}"
main_class="com.yjiyun.chatflows.runtime.RuntimeApplication"
classpath="target/dist/classes:target/dist/lib/*"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage:
  agent-runtime/run_linux.sh [start|stop|restart]

defaults:
  env file : agent-runtime/agent-runtime.env
  pid file : agent-runtime/agent-runtime.pid
  log file : agent-runtime/logs/agent-runtime.out

notes:
  - this script is for Ubuntu/Linux
  - it uses java from PATH
  - it starts RuntimeApplication with nohup in the background
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

runtime_health() {
  curl -fsS "http://127.0.0.1:${RUNTIME_PORT:-8088}/healthz" 2>/dev/null || return 1
}

start_runtime() {
  require_command java
  [[ -f "$env_file" ]] || fail "env file not found: $env_file"
  [[ -f "target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class" ]] || fail "missing target/dist/classes RuntimeApplication.class; upload build-dist output first"
  [[ -d "target/dist/lib" ]] || fail "missing target/dist/lib; upload build-dist runtime dependencies first"

  if is_running; then
    local existing_pid
    existing_pid="$(cat "$pid_file")"
    fail "agent-runtime is already running with pid=$existing_pid"
  fi

  mkdir -p "$log_dir"

  log "Using env file: $env_file"
  log "PID file: $pid_file"
  log "Log file: $log_file"

  load_env_file_literal "$env_file"

  : "${RUNTIME_MODE:=production}"
  : "${RUNTIME_HOST:=127.0.0.1}"
  : "${RUNTIME_PORT:=8088}"

  log "Starting agent-runtime on ${RUNTIME_HOST}:${RUNTIME_PORT}"
  nohup java -cp "$classpath" "$main_class" >>"$log_file" 2>&1 &
  local pid=$!
  echo "$pid" > "$pid_file"
  sleep 2

  if ! kill -0 "$pid" 2>/dev/null; then
    warn "agent-runtime exited early; last log lines:"
    tail -n 40 "$log_file" 2>/dev/null || true
    rm -f "$pid_file"
    fail "agent-runtime failed to stay alive after start"
  fi

  if runtime_health >/dev/null; then
    pass "agent-runtime started successfully with pid=$pid"
    runtime_health || true
    return 0
  fi

  warn "process is alive but /healthz is not ready yet; last log lines:"
  tail -n 40 "$log_file" 2>/dev/null || true
  pass "agent-runtime started in background with pid=$pid"
}

stop_runtime() {
  if ! is_running; then
    warn "agent-runtime is not running"
    rm -f "$pid_file"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  log "Stopping agent-runtime pid=$pid"
  kill "$pid" 2>/dev/null || true

  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      pass "agent-runtime stopped"
      return 0
    fi
    sleep 1
  done

  warn "process did not exit after graceful stop; sending SIGKILL"
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
  pass "agent-runtime stopped with SIGKILL"
}

case "$mode" in
  start)
    start_runtime
    ;;
  stop)
    stop_runtime
    ;;
  restart)
    stop_runtime
    start_runtime
    ;;
esac
