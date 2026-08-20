#!/usr/bin/env bash
# 刷新 platform Console（默认 http://127.0.0.1:15173/）。
#
# 为什么不在 Docker 里 npm ci：本机 registry 会 E401，镜像会停在几天前的 dist。
# 做法与 deploy/local 相同——宿主机 vite build（读 integration.env，把
# ORCHESTRATION_MODE / Bearer / Human actor 打进包），再 COPY dist 进 nginx 镜像。
# 只重建 agent-console，不动 Nest / runtime / manager。
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${1:-$root_dir/deploy/agentteams/integration.env}"
compose="$root_dir/deploy/agentteams/compose.yaml"
image_name="chatflows-agentteams-agent-console"
chatflows_net="chatflows-agentteams_chatflows"

if [[ ! -f "$env_file" ]]; then
  echo "env file not found: $env_file" >&2
  exit 2
fi

load_env() {
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "${line:0:1}" == "#" || "$line" != *=* ]] && continue
    export "$line"
  done < "$env_file"
}

console_port() {
  local port
  port="$(sed -n 's/^AGENT_CONSOLE_HOST_PORT=//p' "$env_file" | tail -1)"
  echo "${port:-5173}"
}

dns_has() {
  local host="$1" c
  for c in chatflows-agentteams-postgres-1 chatflows-agentteams-agent-runtime-1 chatflows-agentteams-redis-1; do
    docker exec "$c" getent hosts "$host" >/dev/null 2>&1 && return 0
  done
  return 1
}

ensure_bff_dns() {
  local bff_old
  if dns_has agent-core-bff; then
    return 0
  fi
  bff_old="$(docker ps -q -f name=chatflows-agentteams-code-project-bff-1)"
  if [[ -z "$bff_old" ]]; then
    echo "[WARN] nginx 需要 agent-core-bff，但该主机名解析不到，也没有 code-project-bff 可补别名" >&2
    return 0
  fi
  echo "[3/4] BFF 仍叫 code-project-bff，补 DNS 别名 agent-core-bff"
  docker network disconnect "$chatflows_net" chatflows-agentteams-code-project-bff-1
  docker network connect --alias code-project-bff --alias agent-core-bff \
    "$chatflows_net" chatflows-agentteams-code-project-bff-1
}

wait_console() {
  local port="$1" url status body i
  url="http://127.0.0.1:${port}/"
  for i in $(seq 1 40); do
    status="$(curl -sS -o /tmp/agentteams-console-index.html -w '%{http_code}' "$url" || true)"
    if [[ "$status" == "200" ]]; then
      body="$(cat /tmp/agentteams-console-index.html)"
      if echo "$body" | grep -q 'VibeSales Harness'; then
        return 0
      fi
      echo "[FAIL] $url 仍是旧包（没有 VibeSales Harness）。强制刷新浏览器，或检查 dist 是否写进镜像。" >&2
      echo "$body" | head -c 240 >&2
      echo >&2
      return 1
    fi
    sleep 1
  done
  echo "[FAIL] $url 未在 40s 内变为 HTTP 200（got ${status:-none}）" >&2
  docker logs --tail 40 chatflows-agentteams-agent-console-1 >&2 || true
  return 1
}

load_env
if [[ "${ORCHESTRATION_MODE:-}" != "platform" ]]; then
  echo "[FAIL] $env_file 的 ORCHESTRATION_MODE 必须是 platform，当前=${ORCHESTRATION_MODE:-empty}" >&2
  exit 2
fi
: "${WEB_AUTH_TOKEN:?WEB_AUTH_TOKEN missing in $env_file}"

# 本机 platform 要看右侧「专家团 / 产物」：生产 vite 默认把观察闸打成 off。
export ARTIFACT_INSPECTOR="${ARTIFACT_INSPECTOR:-on}"

echo "[1/4] 宿主机 vite build（platform + compose token + ARTIFACT_INSPECTOR=${ARTIFACT_INSPECTOR}）"
(
  cd "$root_dir/agent-console"
  npm run build
)
stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "built_at=${stamp}"
  echo "orchestration_mode=${ORCHESTRATION_MODE}"
} > "$root_dir/agent-console/dist/build-stamp.txt"

echo "[2/4] 构建 nginx 镜像 ${image_name}（只 COPY dist，不跑 npm ci）"
docker build -f "$root_dir/deploy/agentteams/agent-console.Dockerfile" -t "$image_name" "$root_dir"

echo "[3/4] 检查 BFF 主机名（nginx 反代 agent-core-bff:3101）"
ensure_bff_dns

echo "[4/4] 只重建 agent-console"
docker compose --env-file "$env_file" -f "$compose" up -d --no-deps --no-build --force-recreate agent-console

port="$(console_port)"
wait_console "$port"
echo "[PASS] Console http://127.0.0.1:${port}/  stamp=${stamp}"
curl -sS "http://127.0.0.1:${port}/build-stamp.txt" || true
echo
echo "浏览器若仍显示「产物对话」/ Agent Console：对该地址强制刷新（Cmd+Shift+R）。"
echo "不要用 :5173 / :15174，那是 local 栈。"
