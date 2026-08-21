#!/usr/bin/env bash
# 本地 Docker 真模型栈 — 分步手动启动脚本
# 用法见末尾 help；环境文件默认 deploy/local/.env.local
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${CHATFLOWS_LOCAL_DOCKER_ENV:-$root_dir/deploy/local/.env.local}"
compose_file="$root_dir/deploy/local/compose.yaml"
cmd="${1:-help}"

load_env() {
  [[ -f "$env_file" ]] || { echo "missing env: $env_file" >&2; exit 1; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "${line:0:1}" == "#" || "$line" != *=* ]] && continue
    export "$line"
  done < "$env_file"
}

compose() {
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
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
  return 1
}

build_host_artifacts() {
  (
    load_env
    cd "$root_dir/agent-core" && npm run build
    cd "$root_dir/agent-console" && npm run build
  )
}

print_urls() {
  load_env
  cat <<INFO

Console:         http://127.0.0.1:${LOCAL_CONSOLE_PORT}
Nest BFF:        http://127.0.0.1:${LOCAL_NEST_BFF_PORT}
Nest MCP:        http://127.0.0.1:${LOCAL_NEST_MCP_PORT}
Runtime:         http://127.0.0.1:${LOCAL_RUNTIME_PORT}
Higress Gateway: http://127.0.0.1:${LOCAL_HIGRESS_GATEWAY_PORT}
Higress Console: http://127.0.0.1:${LOCAL_HIGRESS_CONSOLE_PORT}
模型:            ${QWEN_MODEL:-unknown}  (Runtime: ${RUNTIME_MODEL:-unknown})
Console 凭证:    WEB_AUTH_TOKEN（见 $env_file）

INFO
}

case "$cmd" in
  stop|down)
    load_env
    compose down
    echo "[OK] stopped"
    ;;
  status)
    load_env
    compose ps
    ;;
  build)
    build_host_artifacts
    compose build agent-core agent-core-bff db-init agent-runtime agent-console
    echo "[OK] host artifacts + images built"
    ;;
  1-infra)
    load_env
    compose up -d --wait postgres redis minio higress local-business-mcp
    echo "[OK] infra ready (postgres redis minio higress local-business-mcp)"
    ;;
  2-init)
    load_env
    compose up -d minio-init db-init
    wait_service_exit_zero minio-init
    wait_service_exit_zero db-init
    echo "[OK] minio-init + db-init completed"
    ;;
  3-higress)
    load_env
    node "$root_dir/scripts/configure-local-higress.mjs" "$env_file"
    echo "[OK] higress configured"
    ;;
  4-apps)
    load_env
    compose up -d --wait agent-runtime agent-core agent-core-bff agent-console
    echo "[OK] application services started"
    print_urls
    ;;
  all)
    build_host_artifacts
    compose build agent-core agent-core-bff db-init agent-runtime agent-console
    "$0" 1-infra
    "$0" 2-init
    "$0" 3-higress
    "$0" 4-apps
    "$root_dir/scripts/run-local-docker-stack.sh" verify
    ;;
  verify)
    "$root_dir/scripts/run-local-docker-stack.sh" verify
    ;;
  logs)
    load_env
    shift || true
    compose logs -f "$@"
    ;;
  rotate-key)
    [[ -f "$env_file" ]] || { echo "missing env: $env_file" >&2; exit 1; }
    shift || true
    node "$root_dir/scripts/rotate-higress-llm-key.mjs" --target local --env "$env_file" "$@"
    ;;
  probe-model)
    load_env
    node - <<NODE
const model = process.env.QWEN_MODEL || 'deepseek-v4-flash';
const resp = await fetch(\`http://127.0.0.1:\${process.env.LOCAL_HIGRESS_GATEWAY_PORT}/v1/chat/completions\`, {
  method: 'POST',
  headers: {
    authorization: \`Bearer \${process.env.HIGRESS_CONSUMER_TOKEN}\`,
    'content-type': 'application/json',
  },
  body: JSON.stringify({ model, messages: [{ role: 'user', content: 'ping' }], max_tokens: 32 }),
});
console.log('model:', model, 'HTTP', resp.status);
console.log((await resp.text()).slice(0, 400));
NODE
    ;;
  help|*)
    cat <<'HELP'
本地 Docker 真模型栈 — 分步手动启动

  ./scripts/start-local-docker-stack-manual.sh stop          # 停止全部容器
  ./scripts/start-local-docker-stack-manual.sh status      # 查看状态
  ./scripts/start-local-docker-stack-manual.sh build       # 宿主机编译 + 构建镜像
  ./scripts/start-local-docker-stack-manual.sh 1-infra     # 起 PG/Redis/MinIO/Higress/MCP
  ./scripts/start-local-docker-stack-manual.sh 2-init      # 跑 db-init / minio-init
  ./scripts/start-local-docker-stack-manual.sh 3-higress   # 首次配置 Higress（consumer/route/MCP）
  ./scripts/start-local-docker-stack-manual.sh rotate-key  # 只换厂商 Key/端点（dry-run；真正写入加 --apply --confirm rotate-llm-key）
  ./scripts/start-local-docker-stack-manual.sh 4-apps      # 起 Runtime/Nest/Console
  ./scripts/start-local-docker-stack-manual.sh all         # 以上全部 + verify
  ./scripts/start-local-docker-stack-manual.sh verify      # 健康检查
  ./scripts/start-local-docker-stack-manual.sh probe-model # 经 Higress 探测当前 QWEN_MODEL
  ./scripts/start-local-docker-stack-manual.sh logs [svc]  # 跟踪日志

只换 Key / 供应商 URL：
  HIGRESS_LLM_API_KEY=sk-新Key ./scripts/start-local-docker-stack-manual.sh rotate-key --apply --confirm rotate-llm-key
  HIGRESS_LLM_API_KEY=sk-新Key ./scripts/start-local-docker-stack-manual.sh rotate-key \
    --url https://xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 \
    --apply --confirm rotate-llm-key

改模型名：编辑 deploy/local/.env.local 的 QWEN_MODEL / RUNTIME_MODEL，然后：
  ./scripts/start-local-docker-stack-manual.sh 3-higress
  docker compose --env-file deploy/local/.env.local -f deploy/local/compose.yaml up -d --force-recreate agent-runtime agent-core agent-core-bff

HELP
    ;;
esac
