#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage:
  agent-runtime/build-dist-linux.sh

notes:
  - intended for Ubuntu/Linux
  - tries offline Maven build first
  - falls back to online plugin/dependency resolution if offline build fails
  - outputs runtime distribution into target/dist/
EOF
}

case "${1:-}" in
  "" ) ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    fail "unexpected argument: $1"
    ;;
esac

command -v mvn >/dev/null 2>&1 || fail "mvn not found in PATH"
command -v java >/dev/null 2>&1 || fail "java not found in PATH"

run_build() {
  local offline_flag="$1"
  mvn ${offline_flag} -q clean compile org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/dist/lib
}

log "Building agent-runtime distribution under $root_dir"
if run_build "-o"; then
  pass "offline Maven build completed"
else
  warn "offline Maven build failed; retrying with online dependency/plugin resolution"
  run_build "" || fail "online Maven build failed"
  pass "online Maven build completed"
fi

mkdir -p target/dist/classes target/dist/workspace
cp -R target/classes/. target/dist/classes/

if [[ -d workspace ]]; then
  cp -R workspace/. target/dist/workspace/
else
  warn "workspace directory not found; target/dist/workspace will be empty"
fi

[[ -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class ]] || fail "RuntimeApplication.class missing after build"

log "Output directories:"
log "  classes: $root_dir/target/dist/classes"
log "  libs   : $root_dir/target/dist/lib"
log "  ws     : $root_dir/target/dist/workspace"
pass "agent-runtime Linux build distribution is ready"
