#!/usr/bin/env bash
set -euo pipefail

host="${CHATFLOWS_SSH_HOST:-10.0.0.1}"
port="${CHATFLOWS_SSH_PORT:-22}"
user="${CHATFLOWS_SSH_USER:-pad}"
remote_root="${CHATFLOWS_REMOTE_ROOT:-/home/pad/chatflows}"
env_file="${CHATFLOWS_REMOTE_ENV_FILE:-/home/pad/chatflows/docs/agentteams/local-development.env.local}"
java_home="${CHATFLOWS_REMOTE_JAVA_HOME:-/home/pad/zulu17.60.17-ca-jdk17.0.16-linux_x64}"
node_bin="${CHATFLOWS_REMOTE_NODE_BIN:-/home/pad/node20/bin}"
maven_bin="${CHATFLOWS_REMOTE_MAVEN_BIN:-/home/pad/maven/bin}"
ssh_opts=(
  -tt
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -p "$port"
)

if ! command -v ssh >/dev/null 2>&1; then
  echo "ssh is required" >&2
  exit 2
fi

prompt_branch() {
  local label="$1"
  local repo_path="$2"
  local value=""
  local prompt_text="请输入 ${label} 的分支（${repo_path}，留空使用服务器当前分支）: "
  if [[ -t 0 ]]; then
    read -r -p "$prompt_text" value
  elif [[ -r /dev/tty ]]; then
    read -r -p "$prompt_text" value </dev/tty
  else
    echo "[WARN] 当前没有可交互终端，${label} 默认使用服务器当前分支" >&2
  fi
  printf '%s' "$value"
}

agent_core_branch="$(prompt_branch 'agent-core' '/home/pad/chatflows/agent-core')"
chatflows_branch="$(prompt_branch 'chatflows 根目录' '/home/pad/chatflows')"

echo "[INFO] restart chatflows host services on ${user}@${host}:${port}"
echo "[INFO] remote root: ${remote_root}"
echo "[INFO] remote env : ${env_file}"
echo "[INFO] agent-core branch input: ${agent_core_branch:-<current>}"
echo "[INFO] chatflows branch input : ${chatflows_branch:-<current>}"
echo "[INFO] next step may ask for the SSH password of ${user}@${host}; password input will not echo"

ssh "${ssh_opts[@]}" "${user}@${host}" bash -s -- \
  "$remote_root" "$env_file" "$java_home" "$node_bin" "$maven_bin" \
  "$agent_core_branch" "$chatflows_branch" <<'REMOTE'
set -euo pipefail

remote_root="$1"
env_file="$2"
java_home="$3"
node_bin="$4"
maven_bin="$5"
agent_core_branch_input="${6-}"
chatflows_branch_input="${7-}"
logs_dir="$remote_root/run-logs"
remote_host_label="$(hostname)"
agent_core_actual_branch=""
agent_core_actual_commit=""
chatflows_actual_branch=""
chatflows_actual_commit=""
summary_webhook_urls=(
  "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=4833f27f-c232-42df-9d63-a318c5ba1210"
  "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=88068a1f-e8a9-4cb5-95b2-f08eda450847"
)

info() { echo "[INFO] $*"; }
pass() { echo "[PASS] $*"; }
fail() { echo "[FAIL] $*" >&2; exit 1; }

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "required file not found: $path"
}

require_dir() {
  local path="$1"
  [[ -d "$path" ]] || fail "required directory not found: $path"
}

run_if_present() {
  local dir="$1"
  shift
  [[ -d "$dir" ]] || return 0
  (
    cd "$dir"
    "$@"
  )
}

wait_for_port() {
  local port="$1" name="$2" log_file="${3:-}" waited=0
  while ! ss -lntp 2>/dev/null | grep -q ":${port}\\b"; do
    waited=$((waited + 1))
    if [[ "$waited" -ge 90 ]]; then
      [[ -n "$log_file" ]] && show_tail "$log_file"
      fail "${name} did not listen on :${port} within 90s"
    fi
    sleep 1
  done
  pass "${name} is listening on :${port}"
}

http_code() {
  local url="$1"
  shift
  curl -s -o /tmp/chatflows-http-body.$$ -w '%{http_code}' "$@" "$url"
}

show_tail() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  echo "----- tail: ${file} -----"
  tail -n 20 "$file" || true
  echo "----- end tail -----"
}

push_group_summary() {
  local webhook_url="$1" summary_text="$2" response result http_status errcode errmsg body
  response="$(WEBHOOK_URL="$webhook_url" SUMMARY_TEXT="$summary_text" python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

payload = json.dumps(
    {
        "msgtype": "text",
        "text": {"content": os.environ["SUMMARY_TEXT"]},
    },
    ensure_ascii=False,
).encode("utf-8")
request = urllib.request.Request(
    os.environ["WEBHOOK_URL"],
    data=payload,
    headers={"Content-Type": "application/json"},
)
try:
    with urllib.request.urlopen(request, timeout=15) as resp:
        status = getattr(resp, "status", 200)
        raw_body = resp.read().decode("utf-8", errors="replace")
except urllib.error.HTTPError as exc:
    status = exc.code
    raw_body = exc.read().decode("utf-8", errors="replace")
    result = "error"
except Exception as exc:
    print("RESULT=error")
    print("HTTP_STATUS=0")
    print("ERRCODE=transport_error")
    print(f"ERRMSG={exc}")
    print("BODY=")
    sys.exit(4)
else:
    result = "success"

errcode = ""
errmsg = ""
try:
    data = json.loads(raw_body)
except Exception:
    data = None

if isinstance(data, dict):
    errcode = str(data.get("errcode", ""))
    errmsg = str(data.get("errmsg", ""))
    if errcode not in ("", "0"):
        result = "error"

print(f"RESULT={result}")
print(f"HTTP_STATUS={status}")
print(f"ERRCODE={errcode}")
print(f"ERRMSG={errmsg}")
print(f"BODY={raw_body}")

if result != "success":
    sys.exit(3)
PY
)" || {
    result="$(printf '%s\n' "$response" | sed -n 's/^RESULT=//p' | head -1)"
    http_status="$(printf '%s\n' "$response" | sed -n 's/^HTTP_STATUS=//p' | head -1)"
    errcode="$(printf '%s\n' "$response" | sed -n 's/^ERRCODE=//p' | head -1)"
    errmsg="$(printf '%s\n' "$response" | sed -n 's/^ERRMSG=//p' | head -1)"
    body="$(printf '%s\n' "$response" | sed -n 's/^BODY=//p' | head -1)"
    echo "[WARN] group summary webhook push failed" >&2
    echo "[WARN] webhook result : ${result:-error}" >&2
    echo "[WARN] webhook status : ${http_status:-unknown}" >&2
    echo "[WARN] webhook errcode: ${errcode:-<empty>}" >&2
    echo "[WARN] webhook errmsg : ${errmsg:-<empty>}" >&2
    [[ -n "${body:-}" ]] && echo "[WARN] webhook body   : $body" >&2
    return 0
  }
  result="$(printf '%s\n' "$response" | sed -n 's/^RESULT=//p' | head -1)"
  http_status="$(printf '%s\n' "$response" | sed -n 's/^HTTP_STATUS=//p' | head -1)"
  errcode="$(printf '%s\n' "$response" | sed -n 's/^ERRCODE=//p' | head -1)"
  errmsg="$(printf '%s\n' "$response" | sed -n 's/^ERRMSG=//p' | head -1)"
  body="$(printf '%s\n' "$response" | sed -n 's/^BODY=//p' | head -1)"
  pass "group summary pushed via webhook"
  echo "[INFO] webhook result : ${result:-success}"
  echo "[INFO] webhook status : ${http_status:-200}"
  echo "[INFO] webhook errcode: ${errcode:-0}"
  echo "[INFO] webhook errmsg : ${errmsg:-ok}"
  [[ -n "${body:-}" ]] && echo "[INFO] webhook body   : $body"
}

push_group_summary_with_fallback() {
  local summary_text="$1" webhook_url idx=0 total
  total="${#summary_webhook_urls[@]}"
  for webhook_url in "${summary_webhook_urls[@]}"; do
    idx=$((idx + 1))
    info "trying webhook[$idx/$total]"
    if push_group_summary "$webhook_url" "$summary_text"; then
      pass "group summary delivered via webhook[$idx]"
      return 0
    fi
  done
  echo "[WARN] all webhook candidates failed; summary was printed locally only" >&2
  return 0
}

checkout_branch() {
  local repo_dir="$1" target_branch="$2"
  if git -C "$repo_dir" show-ref --verify --quiet "refs/heads/${target_branch}"; then
    git -C "$repo_dir" checkout "$target_branch"
    return 0
  fi
  if git -C "$repo_dir" show-ref --verify --quiet "refs/remotes/origin/${target_branch}"; then
    git -C "$repo_dir" checkout -b "$target_branch" --track "origin/${target_branch}"
    return 0
  fi
  fail "branch not found locally or on origin: ${target_branch}"
}

update_repo_branch() {
  local repo_dir="$1" requested_branch="$2" label="$3"
  local current_branch target_branch upstream_branch actual_branch actual_commit
  require_dir "$repo_dir"
  git -C "$repo_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "${label} is not a git repository: ${repo_dir}"
  current_branch="$(git -C "$repo_dir" rev-parse --abbrev-ref HEAD)"
  if [[ "$current_branch" == "HEAD" && -z "$requested_branch" ]]; then
    fail "${label} is in detached HEAD; please rerun and specify a branch explicitly"
  fi
  target_branch="${requested_branch:-$current_branch}"
  info "${label} current branch: ${current_branch}"
  info "${label} target branch : ${target_branch}"
  git -C "$repo_dir" fetch --all --prune
  checkout_branch "$repo_dir" "$target_branch"
  if upstream_branch="$(git -C "$repo_dir" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)"; then
    info "${label} pulling from ${upstream_branch}"
    git -C "$repo_dir" pull --ff-only
  elif git -C "$repo_dir" show-ref --verify --quiet "refs/remotes/origin/${target_branch}"; then
    git -C "$repo_dir" branch --set-upstream-to="origin/${target_branch}" "$target_branch"
    info "${label} pulling from origin/${target_branch}"
    git -C "$repo_dir" pull --ff-only
  else
    fail "${label} has no upstream and origin/${target_branch} does not exist"
  fi
  actual_branch="$(git -C "$repo_dir" rev-parse --abbrev-ref HEAD)"
  actual_commit="$(git -C "$repo_dir" rev-parse --short HEAD)"
  case "$label" in
    agent-core)
      agent_core_actual_branch="$actual_branch"
      agent_core_actual_commit="$actual_commit"
      ;;
    chatflows)
      chatflows_actual_branch="$actual_branch"
      chatflows_actual_commit="$actual_commit"
      ;;
  esac
  pass "${label} updated on branch ${actual_branch} (${actual_commit})"
}

check_ok_json() {
  local name="$1" url="$2" token="$3"
  local code
  code="$(http_code "$url" -H "Authorization: Bearer $token" -H 'x-role: admin' -H 'x-actor: restart-script')"
  [[ "$code" == 200 ]] || {
    cat /tmp/chatflows-http-body.$$ >&2 || true
    fail "${name} returned HTTP ${code}"
  }
  grep -q '"ok":true' /tmp/chatflows-http-body.$$ || {
    cat /tmp/chatflows-http-body.$$ >&2 || true
    fail "${name} did not return ok=true"
  }
  pass "${name} returned 200 and ok=true"
}

preflight_component() {
  local component="$1" log_file="$2"
  info "preflight ${component}"
  if ! env AGENTTEAMS_LOCAL_ENV="$env_file" AGENTTEAMS_PREFLIGHT_ONLY=1 \
      bash "$remote_root/scripts/run-agentteams-local-dev.sh" "$component" \
      >"$log_file" 2>&1; then
    show_tail "$log_file"
    fail "${component} preflight failed"
  fi
  pass "${component} preflight passed"
}

require_file "$env_file"
require_file "$remote_root/scripts/run-agentteams-local-dev.sh"
require_file "$remote_root/agent-runtime/build-dist.sh"
require_file "$remote_root/agent-manager/build-dist.sh"
require_dir "$remote_root/agent-core"

export JAVA_HOME="$java_home"
export PATH="$node_bin:$maven_bin:$JAVA_HOME/bin:$PATH"

info "using JAVA_HOME=$JAVA_HOME"
info "using PATH prefix=$node_bin:$maven_bin"

mkdir -p "$logs_dir"

info "updating git branches before rebuild"
update_repo_branch "$remote_root/agent-core" "$agent_core_branch_input" "agent-core"
update_repo_branch "$remote_root" "$chatflows_branch_input" "chatflows"

info "installing Node dependencies when package.json is present"
run_if_present "$remote_root/agent-core" npm install --no-audit --no-fund
run_if_present "$remote_root/agent-console" npm install --no-audit --no-fund

if [[ -d "$remote_root/agent-console" ]]; then
  info "building agent-console"
  (
    cd "$remote_root/agent-console"
    npm run build
    test -f dist/index.html
  )
  pass "agent-console dist/index.html generated"
fi

info "rebuilding agent-runtime"
(
  cd "$remote_root/agent-runtime"
  ./build-dist.sh
)
pass "agent-runtime build-dist completed"

info "rebuilding agent-manager"
(
  cd "$remote_root/agent-manager"
  ./build-dist.sh
)
pass "agent-manager build-dist completed"

info "stopping old processes"
pkill -f 'bash ./scripts/run-agentteams-local-dev.sh all' || true
pkill -f 'com.yjiyun.chatflows.manager.ManagerApplication' || true
pkill -f 'com.yjiyun.chatflows.runtime.RuntimeApplication' || true
pkill -f 'ts-node -r dotenv/config src/main-web.ts' || true
sleep 3
pass "old processes stopped"

info "starting agent-core"
preflight_component nest "$logs_dir/agent-core.preflight.log"
nohup env AGENTTEAMS_LOCAL_ENV="$env_file" \
  bash "$remote_root/scripts/run-agentteams-local-dev.sh" nest \
  >"$logs_dir/agent-core.log" 2>&1 &
wait_for_port 3100 agent-core "$logs_dir/agent-core.log"

info "starting agent-runtime"
preflight_component runtime "$logs_dir/agent-runtime.preflight.log"
nohup env AGENTTEAMS_LOCAL_ENV="$env_file" \
  bash "$remote_root/scripts/run-agentteams-local-dev.sh" runtime \
  >"$logs_dir/agent-runtime.log" 2>&1 &
wait_for_port 8088 agent-runtime "$logs_dir/agent-runtime.log"

info "starting agent-manager"
preflight_component manager "$logs_dir/agent-manager.preflight.log"
nohup env AGENTTEAMS_LOCAL_ENV="$env_file" \
  bash "$remote_root/scripts/run-agentteams-local-dev.sh" manager \
  >"$logs_dir/agent-manager.log" 2>&1 &
wait_for_port 8090 agent-manager "$logs_dir/agent-manager.log"

info "checking homepage"
root_code="$(http_code 'http://127.0.0.1:3100/')"
[[ "$root_code" == 200 ]] || {
  cat /tmp/chatflows-http-body.$$ >&2 || true
  show_tail "$logs_dir/agent-core.log"
  fail "homepage returned HTTP ${root_code}"
}
grep -q '<title>' /tmp/chatflows-http-body.$$ || {
  cat /tmp/chatflows-http-body.$$ >&2 || true
  fail "homepage did not include a title tag"
}
pass "homepage returned 200"

web_token="$(grep '^WEB_AUTH_TOKEN=' "$env_file" | cut -d= -f2-)"
pipeline_token="$(grep '^PIPELINE_CONTROL_TOKEN=' "$env_file" | cut -d= -f2-)"
[[ -n "$web_token" ]] || fail "WEB_AUTH_TOKEN is empty in $env_file"
[[ -n "$pipeline_token" ]] || fail "PIPELINE_CONTROL_TOKEN is empty in $env_file"

info "checking auth rejection for /api/health"
health_unauth_code="$(http_code 'http://127.0.0.1:3100/api/health')"
[[ "$health_unauth_code" == 401 ]] || {
  cat /tmp/chatflows-http-body.$$ >&2 || true
  fail "/api/health without token returned HTTP ${health_unauth_code}, expected 401"
}
pass "/api/health rejects anonymous access"

check_ok_json "api/health" "http://127.0.0.1:3100/api/health" "$web_token"
check_ok_json "pipeline/health" "http://127.0.0.1:3100/api/v1/pipeline/health" "$pipeline_token"

info "checking process list"
pgrep -af 'src/main-web.ts|RuntimeApplication|ManagerApplication' || fail "expected processes were not found"
ss -lntp | grep -E ':3100|:8088|:8090' || fail "expected ports were not found"
pass "processes and ports look healthy"

show_tail "$logs_dir/agent-core.log"
show_tail "$logs_dir/agent-runtime.log"
show_tail "$logs_dir/agent-manager.log"

rm -f /tmp/chatflows-http-body.$$

echo
pass "chatflows remote restart finished successfully"
echo "[INFO] homepage      : http://127.0.0.1:3100/"
echo "[INFO] core log      : $logs_dir/agent-core.log"
echo "[INFO] runtime log   : $logs_dir/agent-runtime.log"
echo "[INFO] manager log   : $logs_dir/agent-manager.log"
echo
echo "========== 启动成功摘要 =========="
echo "[SUMMARY] host              : ${remote_host_label}"
echo "[SUMMARY] chatflows branch  : ${chatflows_actual_branch} (${chatflows_actual_commit})"
echo "[SUMMARY] agent-core branch : ${agent_core_actual_branch} (${agent_core_actual_commit})"
echo "[SUMMARY] services          : agent-core=:3100, agent-runtime=:8088, agent-manager=:8090"
echo "[SUMMARY] checks            : homepage=200, api/health(anon)=401, api/health(auth)=200, pipeline/health=200"
echo "[SUMMARY] logs              : $logs_dir/agent-core.log | $logs_dir/agent-runtime.log | $logs_dir/agent-manager.log"
echo
echo "========== 可贴群摘要 =========="
group_summary="$(printf '%s\n' \
  '【chatflows 重启结果】' \
  "- 机器：${remote_host_label}" \
  "- chatflows 分支：${chatflows_actual_branch} (${chatflows_actual_commit})" \
  "- agent-core 分支：${agent_core_actual_branch} (${agent_core_actual_commit})" \
  '- 构建结果：agent-console build、agent-runtime build-dist、agent-manager build-dist 已完成' \
  '- 服务状态：agent-core(3100)、agent-runtime(8088)、agent-manager(8090) 已启动' \
  '- 校验结果：首页 200；/api/health 匿名 401；/api/health 鉴权 200；/api/v1/pipeline/health 200' \
  "- 日志位置：$logs_dir/agent-core.log、$logs_dir/agent-runtime.log、$logs_dir/agent-manager.log")"
printf '%s\n' "$group_summary"
push_group_summary_with_fallback "$group_summary"
REMOTE
