#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${AGENTTEAMS_LOCAL_ENV:-$root_dir/docs/agentteams/local-development.env.local}"
component="${1:-all}"
if [[ ! -f "$env_file" ]]; then
  echo "missing $env_file; copy local-development.env.example and fill credentials" >&2
  exit 1
fi
set -a
source "$env_file"
set +a
export ARTIFACT_INSPECTOR="${ARTIFACT_INSPECTOR:-on}"
# 本机默认 /usr/libexec/java_home 常是 Java 8；runtime 字节码是 61，必须用 17+。
# 不设的话 runtime 立刻崩，all 模式 trap 会把刚起来的 Nest 一并 SIGTERM，Console 点「开始」就是 Failed to fetch。
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version >/dev/null 2>&1 \
  || ! "${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "(1[7-9]|2[0-9])'; then
  for java_home in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  do
    if [[ -x "$java_home/bin/java" ]]; then
      export JAVA_HOME="$java_home"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
require() { local key="$1"; [[ -n "${!key:-}" ]] || { echo "$key is required" >&2; exit 1; }; }
require_any() { local first="$1" second="$2"; [[ -n "${!first:-}" || -n "${!second:-}" ]] || { echo "$first or $second is required" >&2; exit 1; }; }
probe() { local url="$1" name="$2"; curl -fsS --connect-timeout 2 "$url" >/dev/null || { echo "$name is unreachable: $url" >&2; exit 1; }; }
probe_bearer() { local url="$1" name="$2" token="$3"; curl -fsS --connect-timeout 2 -H "Authorization: Bearer $token" "$url" >/dev/null || { echo "$name is unreachable or unauthorized: $url" >&2; exit 1; }; }
# CHATFLOWS_TASK_FS_* 身份的策略按 prefix 收窄（见 001_agentteams.sql /
# configure-chatflows-task-storage.js），不持有裸 s3:GetBucketLocation+ListBucket
# 全桶权限，所以不能用 bucketExists()（HeadBucket 不带 prefix，会被策略拒绝）。
# 改用带 prefix 的 listObjectsV2 探活，语义等价于「这个身份能不能读到自己的任务目录」。
probe_minio_bucket() { local endpoint="$1" access="$2" secret="$3" bucket="$4" prefix="$5"; MINIO_PROBE_ENDPOINT="$endpoint" MINIO_PROBE_ACCESS="$access" MINIO_PROBE_SECRET="$secret" MINIO_PROBE_BUCKET="$bucket" MINIO_PROBE_PREFIX="$prefix" MINIO_PROBE_ROOT="$root_dir" node -e 'const path=require("node:path"),Minio=require(path.join(process.env.MINIO_PROBE_ROOT,"agent-core/node_modules/minio")),u=new URL(process.env.MINIO_PROBE_ENDPOINT),client=new Minio.Client({endPoint:u.hostname,port:Number(u.port||(u.protocol==="https:"?443:80)),useSSL:u.protocol==="https:",accessKey:process.env.MINIO_PROBE_ACCESS,secretKey:process.env.MINIO_PROBE_SECRET});const stream=client.listObjectsV2(process.env.MINIO_PROBE_BUCKET,process.env.MINIO_PROBE_PREFIX,true);stream.on("data",()=>{});stream.on("end",()=>process.exit(0));stream.on("error",error=>{console.error("MinIO bucket check failed: "+error.message);process.exit(1);});' || exit 1; }
probe_tcp_url() { local url="$1" name="$2" default_port="$3"; PROBE_URL="$url" PROBE_NAME="$name" PROBE_DEFAULT_PORT="$default_port" node -e 'const net=require("node:net"),u=new URL(process.env.PROBE_URL),port=Number(u.port||process.env.PROBE_DEFAULT_PORT);if(!u.hostname||!port)throw new Error(process.env.PROBE_NAME+" URL is invalid");const socket=net.createConnection({host:u.hostname,port});const timer=setTimeout(()=>socket.destroy(new Error("timeout")),2000);socket.once("connect",()=>{clearTimeout(timer);socket.end();});socket.once("close",hadError=>process.exit(hadError?1:0));socket.once("error",error=>{console.error(process.env.PROBE_NAME+" is unreachable: "+u.hostname+":"+port+" ("+error.message+")");process.exit(1);});' || exit 1; }
# A21：生产必须经 Higress HTTPS，容器私网允许 HTTP。判定与 RuntimeApplication.validatedHigress /
# OrchestrationPlanner.validateGateway / validateQwenProductionGateway 三处 Java/TS 实现保持同一口径。
require_gateway_url() { local key="$1"; require "$key"; URL_VALUE="${!key}" URL_KEY="$key" node -e 'const u=new URL(process.env.URL_VALUE),key=process.env.URL_KEY,host=u.hostname.toLowerCase();const priv=host==="localhost"||host==="127.0.0.1"||host==="::1"||host==="[::1]"||host.startsWith("10.")||host.startsWith("192.168.")||/^172\.(1[6-9]|2\d|3[01])\./.test(host)||!host.includes(".");const secure=u.protocol==="https:"||(u.protocol==="http:"&&priv);if(!secure||(!priv&&!host.includes("higress")))throw new Error(key+" must target Higress over HTTPS or private-network HTTP");if(!u.pathname||u.pathname==="/")throw new Error(key+" must include the gateway route path");' || exit 1; }
# A23：普通凭证与管理凭证必须分离且都 >=16 字符，否则 AuthService 构造器会在 serve 时抛错。
require_token_pair() { local regular="$1" admin="$2"; require "$regular"; require "$admin"; local regular_value="${!regular}" admin_value="${!admin}"; [[ "${#regular_value}" -ge 16 ]] || { echo "$regular must be at least 16 characters" >&2; exit 1; }; [[ "${#admin_value}" -ge 16 ]] || { echo "$admin must be at least 16 characters" >&2; exit 1; }; [[ "$regular_value" != "$admin_value" ]] || { echo "$regular and $admin must differ" >&2; exit 1; }; }
discover_team_runtime() {
  if [[ -n "${AGENTTEAMS_LEADER_IDS:-}" && -n "${AGENTTEAMS_LEADER_ROOM_ID:-}" ]]; then return; fi
  local discovered
  discovered="$(node "$root_dir/scripts/discover-agentteams-runtime.js")" || exit 1
  if [[ -z "${AGENTTEAMS_LEADER_IDS:-}" ]]; then AGENTTEAMS_LEADER_IDS="$(DISCOVERED="$discovered" node -e 'process.stdout.write(JSON.parse(process.env.DISCOVERED).AGENTTEAMS_LEADER_IDS)' )"; export AGENTTEAMS_LEADER_IDS; fi
  if [[ -z "${AGENTTEAMS_LEADER_ROOM_ID:-}" ]]; then AGENTTEAMS_LEADER_ROOM_ID="$(DISCOVERED="$discovered" node -e 'process.stdout.write(JSON.parse(process.env.DISCOVERED).AGENTTEAMS_LEADER_ROOM_ID)' )"; export AGENTTEAMS_LEADER_ROOM_ID; fi
}
require_disjoint_matrix_ids() { MATRIX_HUMANS="$AGENTTEAMS_HUMAN_IDS" MATRIX_LEADERS="$AGENTTEAMS_LEADER_IDS" MATRIX_MANAGERS="$AGENTTEAMS_MANAGER_IDS" MATRIX_USER="${AGENTTEAMS_MATRIX_USER_ID:-}" node -e 'const ids=value=>new Set(value.split(",").map(x=>x.trim()).filter(Boolean)),human=ids(process.env.MATRIX_HUMANS),leader=ids(process.env.MATRIX_LEADERS),manager=ids(process.env.MATRIX_MANAGERS);if(process.env.MATRIX_USER&&!manager.has(process.env.MATRIX_USER))throw new Error("AGENTTEAMS_MATRIX_USER_ID must be in AGENTTEAMS_MANAGER_IDS");for(const id of manager)if(human.has(id)||leader.has(id))throw new Error("Manager identity overlaps Human/Leader: "+id);for(const id of human)if(leader.has(id))throw new Error("Human identity overlaps Leader: "+id);' || exit 1; }
preflight_nest() {
  require WEB_AUTH_TOKEN; require WEB_AUTH_CLIENT_CODE; require PIPELINE_CONTROL_TOKEN; require PIPELINE_APPROVAL_SIGNING_SECRET
  # production runtime 读 PG 的 PUBLISHED 绑定；file 仓的确认发布对试聊不可见。
  if [[ "${RUNTIME_MODE:-}" == production ]]; then
    if [[ "${ARTIFACT_STORE:-}" != postgres && "${BLUEPRINT_STORE:-}" != postgres ]]; then
      echo "production runtime reads PG PUBLISHED bindings; set BLUEPRINT_STORE=postgres (keep ARTIFACT_STORE=file) or ARTIFACT_STORE=postgres" >&2
      exit 1
    fi
    require DATABASE_URL
    probe_tcp_url "$DATABASE_URL" PostgreSQL 5432
  fi
  # QwenService 用 ARTIFACT_STORE=postgres 触发生产网关校验（无 QWEN_MODE 开关），这里必须同口径。
  if [[ "${ARTIFACT_STORE:-}" == postgres ]]; then
    require DATABASE_URL; require MINIO_ENDPOINT; require MINIO_ACCESS_KEY; require MINIO_SECRET_KEY; require MINIO_BUCKET
    probe_tcp_url "$DATABASE_URL" PostgreSQL 5432
    probe "${MINIO_ENDPOINT%/}/minio/health/live" MinIO
    require QWEN_GATEWAY_TOKEN
    [[ "${#QWEN_GATEWAY_TOKEN}" -ge 16 ]] || { echo "QWEN_GATEWAY_TOKEN must be at least 16 characters" >&2; exit 1; }
    [[ -z "${DASHSCOPE_API_KEY:-}" ]] || { echo "production Nest must not receive DASHSCOPE_API_KEY; use QWEN_GATEWAY_TOKEN" >&2; exit 1; }
    require_gateway_url QWEN_BASE_URL
    QWEN_URL="$QWEN_BASE_URL" node -e 'const u=new URL(process.env.QWEN_URL);if(!(u.pathname.replace(/\/+$/,"")==="/v1"||u.pathname.includes("compatible")))throw new Error("QWEN_BASE_URL must target a Higress OpenAI-compatible route");' || exit 1
  fi
}
preflight_manager() {
  require AGENTTEAMS_CONTROLLER_URL; require AGENTTEAMS_AUTH_TOKEN; require AGENTTEAMS_MATRIX_URL; require_any AGENTTEAMS_MATRIX_ACCESS_TOKEN AGENTTEAMS_MATRIX_PASSWORD
  # Manager 任务目录用 prefix 受限的专属 MinIO 身份（ManagerConfig 的 CHATFLOWS_TASK_FS_*），
  # 不是 REST apply 用的平台 admin 身份 AGENTTEAMS_FS_*，两套凭证不可互换。
  require CHATFLOWS_TASK_FS_ENDPOINT; require CHATFLOWS_TASK_FS_ACCESS_KEY; require CHATFLOWS_TASK_FS_SECRET_KEY; require CHATFLOWS_TASK_FS_BUCKET; require CHATFLOWS_TASK_FS_PREFIX
  require AGENTTEAMS_HUMAN_IDS; require AGENTTEAMS_MANAGER_IDS; discover_team_runtime; require AGENTTEAMS_LEADER_IDS; require AGENTTEAMS_LEADER_ROOM_ID
  require CHATFLOWS_APPROVAL_SIGNING_SECRET; require PIPELINE_CONTROL_TOKEN; require CHATFLOWS_NEST_URL; require_token_pair MANAGER_AUTH_TOKEN MANAGER_ADMIN_TOKEN
  [[ "$CHATFLOWS_APPROVAL_SIGNING_SECRET" == "${PIPELINE_APPROVAL_SIGNING_SECRET:-}" ]] || { echo "CHATFLOWS_APPROVAL_SIGNING_SECRET and PIPELINE_APPROVAL_SIGNING_SECRET must match" >&2; exit 1; }
  require_disjoint_matrix_ids
  probe "${AGENTTEAMS_MATRIX_URL%/}/_matrix/client/versions" Matrix
  node "$root_dir/scripts/check-matrix-manager-identity.js"
  probe_bearer "${AGENTTEAMS_CONTROLLER_URL%/}/api/v1/workers" Controller "$AGENTTEAMS_AUTH_TOKEN"
  probe_minio_bucket "$CHATFLOWS_TASK_FS_ENDPOINT" "$CHATFLOWS_TASK_FS_ACCESS_KEY" "$CHATFLOWS_TASK_FS_SECRET_KEY" "$CHATFLOWS_TASK_FS_BUCKET" "$CHATFLOWS_TASK_FS_PREFIX"
  if [[ "${ORCHESTRATOR_LLM:-off}" == on ]]; then require_gateway_url ORCHESTRATOR_LLM_BASE_URL; require HIGRESS_CONSUMER_TOKEN; fi
}
preflight_runtime() {
  # compose 给 runtime 注入的 DATABASE_URL 是 jdbc: 形式（RuntimeApplication 直接交给
  # DriverManager，无归一化），给 Nest 注入的同名变量是 postgresql:// 形式。本机四进程共用
  # 一份 env，所以 env 文件里用 AGENT_RUNTIME_DATABASE_URL 承载 runtime 侧连接串，
  # 在这里归一化成 jdbc: 再 export 成 DATABASE_URL，避免与 Nest 段互相覆盖。
  require AGENT_RUNTIME_DATABASE_URL; require DATABASE_USER; require DATABASE_PASSWORD; require REDIS_URL
  local runtime_database_url="$AGENT_RUNTIME_DATABASE_URL"
  case "$runtime_database_url" in
    jdbc:postgresql://*) ;;
    postgresql://*|postgres://*) runtime_database_url="jdbc:postgresql://${runtime_database_url#*://}" ;;
    *) echo "AGENT_RUNTIME_DATABASE_URL must be a postgresql:// or jdbc:postgresql:// URL" >&2; exit 1 ;;
  esac
  # pgjdbc 不支持 authority 里带 user:pass，凭证只能走 DATABASE_USER / DATABASE_PASSWORD。
  case "${runtime_database_url#jdbc:postgresql://}" in *@*) echo "AGENT_RUNTIME_DATABASE_URL must not embed credentials; use DATABASE_USER and DATABASE_PASSWORD" >&2; exit 1 ;; esac
  DATABASE_URL="$runtime_database_url"; export DATABASE_URL
  require_token_pair RUNTIME_AUTH_TOKEN RUNTIME_ADMIN_TOKEN
  # validateMcpGateway 额外要求 /mcp-servers/ 前缀，网关对外地址带 /mcp 后缀。
  require_gateway_url RUNTIME_MCP_URL; require RUNTIME_MCP_TOKEN; require BLUEPRINT_ADMIN_URL; require BLUEPRINT_ADMIN_TOKEN
  MCP_URL="$RUNTIME_MCP_URL" node -e 'const u=new URL(process.env.MCP_URL);if(!u.pathname.startsWith("/mcp-servers/"))throw new Error("RUNTIME_MCP_URL must use a Higress /mcp-servers/ path");' || exit 1
  require_gateway_url RUNTIME_LLM_BASE_URL; require RUNTIME_LLM_TOKEN
  [[ "${#RUNTIME_LLM_TOKEN}" -ge 16 ]] || { echo "RUNTIME_LLM_TOKEN must be at least 16 characters" >&2; exit 1; }
  probe_tcp_url "${DATABASE_URL#jdbc:}" PostgreSQL 5432
  probe_tcp_url "$REDIS_URL" Redis 6379
}
bridge_runtime_model_env() {
  # runtime 底下混用了两套配置名：AgentTeams 这一层主写 RUNTIME_*，
  # salesagent 子应用实际读取 AGENT_MODEL_*。这里做一次兼容桥接，
  # 避免 env 已配置了 Higress consumer token，但子应用仍报缺 AGENT_MODEL_API_KEY。
  export AGENT_MODEL_BASE_URL="${AGENT_MODEL_BASE_URL:-${RUNTIME_LLM_BASE_URL:-}}"
  export AGENT_MODEL_API_KEY="${AGENT_MODEL_API_KEY:-${RUNTIME_LLM_TOKEN:-}}"
  if [[ -z "${AGENT_MODEL_NAME:-}" && -n "${RUNTIME_MODEL:-}" ]]; then
    export AGENT_MODEL_NAME="${RUNTIME_MODEL#dashscope:}"
  fi
  [[ -n "${AGENT_MODEL_BASE_URL:-}" ]] || { echo "AGENT_MODEL_BASE_URL/RUNTIME_LLM_BASE_URL is required" >&2; exit 1; }
  [[ -n "${AGENT_MODEL_API_KEY:-}" ]] || { echo "AGENT_MODEL_API_KEY/RUNTIME_LLM_TOKEN is required" >&2; exit 1; }
}
run_nest() {
  preflight_nest
  export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-vibe-sales-nest}"
  # Nest P4 dry-run/ingest 读 AGENT_RUNTIME_*；env 文件主写的是 RUNTIME_AUTH_TOKEN / RUNTIME_ADMIN_TOKEN。
  export AGENT_RUNTIME_TOKEN="${AGENT_RUNTIME_TOKEN:-$RUNTIME_AUTH_TOKEN}"
  export AGENT_RUNTIME_ADMIN_TOKEN="${AGENT_RUNTIME_ADMIN_TOKEN:-$RUNTIME_ADMIN_TOKEN}"
  [[ "${AGENTTEAMS_PREFLIGHT_ONLY:-0}" == 1 ]] && return
  cd "$root_dir/agent-core"
  exec npm run web
}
# platform 编排要求 Leader/Worker 工具面锁定：Leader 只有 health/message/filesync（禁止
# create_task_room/taskflow），阶段 Worker 只有 health/filesync 且 MCP policy deny + 仅放行
# 无 Manager 的 Team Room。qwenpaw/容器一重载就打回默认（ask、taskflow 复现），run 会静默
# 停在 DISPATCHED。混合栈启动路径原先不跑这两个脚本，这里在起 Manager 前补上，失败即退出，
# 避免拉起一个注定空等 WAITING_HUMAN 的栈。仅 platform 生效；AGENTTEAMS_SKIP_TOOLFACE=1 可跳过。
configure_toolface() {
  [[ "${ORCHESTRATION_MODE:-local}" == platform ]] || return 0
  [[ "${AGENTTEAMS_SKIP_TOOLFACE:-0}" == 1 ]] && { echo "[toolface] skipped via AGENTTEAMS_SKIP_TOOLFACE=1" >&2; return 0; }
  [[ "${AGENTTEAMS_PREFLIGHT_ONLY:-0}" == 1 ]] && return 0
  echo "[toolface] locking Leader/Worker tools on remote AgentTeams (platform mode)" >&2
  node "$root_dir/scripts/configure-agentteams-leader-tools.js"
  node "$root_dir/scripts/configure-agentteams-worker-mcp.js"
}
run_manager() { preflight_manager; export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-vibe-sales-manager}"; [[ "${AGENTTEAMS_PREFLIGHT_ONLY:-0}" == 1 ]] && return; configure_toolface; cd "$root_dir/agent-manager"; exec ./run.sh serve; }
run_runtime() { preflight_runtime; bridge_runtime_model_env; export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-vibe-sales-runtime}"; [[ "${AGENTTEAMS_PREFLIGHT_ONLY:-0}" == 1 ]] && return; cd "$root_dir/agent-runtime"; exec ./run.sh; }
run_console() { [[ "${AGENTTEAMS_PREFLIGHT_ONLY:-0}" == 1 ]] && return; cd "$root_dir/agent-console"; exec npm run dev; }
# ManagerApplication.checkHttp() 启动时探测一次 Nest /api/v1/pipeline/health，不重试；
# ts-node 编译+起 Nest 比 JVM 慢，all 模式并行起四个进程时 Manager 常常探测过早拿到
# Connection refused 而直接退出，进而拖累 cleanup trap 杀掉其余三个进程。这里在拉起
# Manager 前等 Nest 端口先起来，不改动 Java 生产代码的单次探测语义。
# 用 TCP 连接而非 /api/health：该路由要求 Bearer 鉴权，未带凭证会 401，
# 用 curl -f 判活会一直判定失败直到轮询超时。
wait_for_nest() {
  local waited=0
  # probe_tcp_url 内部失败时 exit 1（为一次性预检设计），不包一层子 shell 的话，
  # 第一次探测不通就会把当前 shell 整个杀掉，这个 while 循环根本没机会重试。
  while ! (probe_tcp_url "$CHATFLOWS_NEST_URL" Nest 3100) >/dev/null 2>&1; do
    waited=$((waited + 1))
    [[ "$waited" -lt 60 ]] || { echo "Nest did not become ready within 60s: $CHATFLOWS_NEST_URL" >&2; return 1; }
    sleep 1
  done
}
# 栈起完后核验每个组件的端口真的在监听。少一个服务时，症状是前端某条 vite 代理路径
# 502（/orchestration/* -> manager 尤其容易被忽略，因为要点到「开始生成」才触发），
# 排查时容易误判成端口配错或代码问题。这里在启动阶段就把缺失喊出来。
verify_stack_ports() {
  local waited=0 missing
  sleep 5
  while :; do
    missing=""
    (probe_tcp_url "$CHATFLOWS_NEST_URL" Nest 3100) >/dev/null 2>&1 || missing="$missing nest($CHATFLOWS_NEST_URL)"
    if [[ "${START_MANAGER:-1}" == 1 ]]; then
      (probe_tcp_url "${MANAGER_API:-http://127.0.0.1:8090}" Manager 8090) >/dev/null 2>&1 || missing="$missing manager(${MANAGER_API:-http://127.0.0.1:8090})"
    fi
    if [[ "${START_RUNTIME:-1}" == 1 ]]; then
      (probe_tcp_url "${AGENT_RUNTIME_URL:-http://127.0.0.1:8088}" Runtime 8088) >/dev/null 2>&1 || missing="$missing runtime(${AGENT_RUNTIME_URL:-http://127.0.0.1:8088})"
    fi
    if [[ -z "$missing" ]]; then
      echo "[stack] all components listening (nest + console + manager/runtime as enabled)" >&2
      return 0
    fi
    waited=$((waited + 1))
    if [[ "$waited" -ge 90 ]]; then
      echo "[stack] WARNING components not listening after 90s:$missing" >&2
      return 0
    fi
    sleep 1
  done
}
case "$component" in
  nest) run_nest ;;
  manager) run_manager ;;
  runtime) run_runtime ;;
  console) run_console ;;
  all)
    # 进程表：name -> 启动函数。名字用于日志和自愈时重启对应组件。
    names=(); pids=(); starters=()
    spawn() { # spawn <name> <starter-shell-snippet>
      local name="$1" starter="$2"
      ( eval "$starter" ) & local pid=$!
      names+=("$name"); pids+=("$pid"); starters+=("$starter")
      echo "[stack] $name started pid=$pid" >&2
    }
    respawn() { # respawn <index>
      local i="$1"
      ( eval "${starters[$i]}" ) & pids[$i]=$!
      echo "[stack] ${names[$i]} restarted pid=${pids[$i]}" >&2
    }
    cleanup(){ for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
    trap cleanup EXIT INT TERM
    spawn nest 'run_nest'
    spawn console 'run_console'
    # 用 if 而非 `[[ ]] && spawn`：后者在条件为假时整条命令返回非零，set -e 会直接终止脚本。
    if [[ "${START_MANAGER:-1}" == 1 ]]; then spawn manager 'wait_for_nest && run_manager'; fi
    if [[ "${START_RUNTIME:-1}" == 1 ]]; then spawn runtime 'run_runtime'; fi
    (verify_stack_ports) &
    # 单个组件挂掉（自身崩溃，或被手工 kill 误伤——四个进程同属一个 trap，
    # kill 其中一个会连带触发 cleanup）默认自动拉起，而不是整栈退出：否则栈会静默
    # 少一个服务继续跑，表现成前端某条代理路径 502，排查成本远高于直接重启。
    # STACK_SUPERVISE=0 回到旧语义（任一进程退出即整栈退出），供 CI 用。
    # macOS 系统自带 bash 3.2（wait -n 要 4.3+），故用轮询而非 wait -n。
    supervise="${STACK_SUPERVISE:-1}"
    declare -a restarts; for i in "${!pids[@]}"; do restarts[$i]=0; done
    while :; do
      for i in "${!pids[@]}"; do
        if kill -0 "${pids[$i]}" 2>/dev/null; then continue; fi
        # `wait` 对非零退出的子进程返回该状态码；set -e 下不吞掉的话，脚本会在这一行
        # 直接终止（连 restarting 日志都来不及打），supervisor 自己就消失了。
        status=0; wait "${pids[$i]}" || status=$?
        if [[ "$supervise" != 1 ]]; then
          echo "[stack] ${names[$i]} exited status=$status; stopping stack (STACK_SUPERVISE=0)" >&2
          exit "$status"
        fi
        restarts[$i]=$(( restarts[$i] + 1 ))
        if [[ "${restarts[$i]}" -gt "${STACK_MAX_RESTARTS:-5}" ]]; then
          echo "[stack] ${names[$i]} exited status=$status and exceeded ${STACK_MAX_RESTARTS:-5} restarts; stopping stack" >&2
          exit "$status"
        fi
        echo "[stack] ${names[$i]} exited status=$status; restarting (#${restarts[$i]})" >&2
        sleep 2
        respawn "$i"
      done
      sleep 1
    done
    ;;
  *) echo "usage: $0 {nest|manager|runtime|console|all}" >&2; exit 2 ;;
esac
