#!/usr/bin/env bash
# ============================================================================
# split-integration-env.sh
# 把 prod-deploy/integration.env（或 /etc/agentteams/integration.env）
# 拆分为三个独立的 systemd EnvFile，仅做变量范围过滤，不改值不变。
#
# 使用：
#   sudo bash prod-deploy/split-integration-env.sh \
#        --src  prod-deploy/integration.env  \
#        --out  /etc/agentteams
#   或者直接（无参数：默认读 ./integration.env 输出到 ./split-out/
#
# 输出文件：
#   $OUT_DIR/agent-core.env
#   $OUT_DIR/agent-runtime.env
#   $OUT_DIR/agent-manager.env
#
# 拆分范围（与三份 env.example 原始变量分区）：
#   agent-core.env   覆盖：§A1+§A4+§B1+B2+B3+B4(Runtime_A+§C全部+§D1+§D21+§D31+§D5+§D6
#   agent-runtime.env 覆盖：§A2+§A4+§B3+B4(全部)+§B5(RUNTIME_AUTH/ADMIN+§D1_PG+REDIS+§D22+§D32+§D6
#   agent-manager.env 覆盖：§A3+§A4+§B1+B2+B5(CHATFLOWS_)+§B6(WEB_AUTH不需要)+§B7+§C2+§D4+§D6
#
# 所有变量都会写入：顶部加注释说明来源；空变量会被保留
# ============================================================================
set -euo pipefail

SRC="./integration.env"
OUT_DIR="./split-out"

# ---- arg parse ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --src|-i) SRC="$2"; shift 2;;
    --out|-o) OUT_DIR="$2"; shift 2;;
    -h|--help)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *)
      echo "unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

[[ -f "$SRC" ]] || { echo "SRC env file not found: $SRC" >&2; exit 1; }
mkdir -p "$OUT_DIR"
umask 077

# ---- helper: 读 integration.env 的所有 KEY=VALUE 行（过滤空行、#注释）----
function env_kvs() {
  grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$SRC" || true
}

function trim_ws() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

function normalize_env_line() {
  local line="$1"
  local key="${line%%=*}"
  local value="${line#*=}"
  value="$(trim_ws "$value")"
  if [[ ${#value} -ge 2 ]]; then
    local first="${value:0:1}"
    local last="${value: -1}"
    if [[ ("$first" == '"' && "$last" == '"') || ("$first" == "'" && "$last" == "'") || ("$first" == '`' && "$last" == '`') ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s=%s\n' "$key" "$value"
}

# ---- helper: 按 key 列表过滤（支持通配 % 为任意）----
# 用法：filter_keys "前缀1 前缀2 完整名3 ..."
# 前缀/通配 % 匹配任意前缀；或完整名
function filter_keys() {
  local patterns="$1"
  env_kvs | while IFS= read -r line; do
    key="${line%%=*}"
    for pat in $patterns; do
      if [[ "$pat" == *"%" ]]; then
        prefix="${pat%%%*}"
        if [[ "$key" == "$prefix"* ]]; then normalize_env_line "$line"; continue 2; fi
      else
        if [[ "$key" == "$pat" ]]; then normalize_env_line "$line"; continue 2; fi
      fi
    done
  done
}

# ---- agent-core.env keys ----
CORE_KEYS=(
  WEB_HOST CHATFLOWS_ROOT ARTIFACT_STORE DATABASE_POOL_SIZE DATABASE_SSL
  MINIO_BUCKET FLOW_PLATFORM_MODE ORCHESTRATION_MODE YUNFLOW_DRY_RUN_PATH
  AGENT_RUNTIME_URL
  MCP_SERVER_TOKEN PIPELINE_CONTROL_TOKEN BLUEPRINT_ADMIN_TOKEN
  RUNTIME_AUTH_TOKEN AGENT_RUNTIME_TOKEN
  PIPELINE_APPROVAL_SIGNING_SECRET
  WEB_AUTH_TOKEN WEB_AUTH_CLIENT_CODE
  AGENTLOOP_% OTEL_EXPORTER_OTLP_PROTOCOL
  QWEN_%
  DATABASE_URL
  P3C_BUSINESS_MCP_URL
  MINIO_ENDPOINT MINIO_ACCESS_KEY MINIO_SECRET_KEY
  YUNFLOW_%
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_RESOURCE_ATTRIBUTES ARMS_LICENSE_KEY OTEL_EXPORTER_OTLP_HEADERS
  AGENTLOOP_ENDPOINT AGENTLOOP_ACCESS_KEY AGENTLOOP_ACCESS_SECRET
)
RUNTIME_KEYS=(
  RUNTIME_MODE RUNTIME_HOST RUNTIME_PORT AGENTSCOPE_WORKSPACE DATABASE_USER
  BLUEPRINT_ADMIN_URL AGENT_MODEL_NAME
  BLUEPRINT_ADMIN_TOKEN RUNTIME_AUTH_TOKEN RUNTIME_ADMIN_TOKEN
  AGENTLOOP_% OTEL_EXPORTER_OTLP_PROTOCOL
  DATABASE_PASSWORD REDIS_URL
  AGENT_MODEL_BASE_URL AGENT_MODEL_API_KEY DASHSCOPE_API_KEY OPENAI_API_KEY
  RUNTIME_MCP_URL RUNTIME_MCP_TOKEN
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_RESOURCE_ATTRIBUTES ARMS_LICENSE_KEY OTEL_EXPORTER_OTLP_HEADERS
  AGENTLOOP_ENDPOINT AGENTLOOP_ACCESS_KEY AGENTLOOP_ACCESS_SECRET
)
MANAGER_KEYS=(
  MANAGER_HOST MANAGER_PORT AGENTTEAMS_RUN_TIMEOUT_SECONDS
  AGENTTEAMS_TEAM_NAME AGENTTEAMS_LEADER_NAME AGENTTEAMS_TEAM_FILE
  CHATFLOWS_NEST_URL ORCHESTRATOR_LLM ORCHESTRATOR_MODEL ORCHESTRATOR_STATE_HOME
  CHATFLOWS_TASK_FS_BUCKET CHATFLOWS_TASK_FS_PREFIX
  AGENTTEAMS_PHASE1_RESULT_FILE
  AGENTLOOP_% OTEL_EXPORTER_OTLP_PROTOCOL
  MCP_SERVER_TOKEN HIGRESS_CONSUMER_TOKEN
  PIPELINE_CONTROL_TOKEN
  CHATFLOWS_APPROVAL_SIGNING_SECRET
  MANAGER_AUTH_TOKEN MANAGER_ADMIN_TOKEN
  AGENTTEAMS_HUMAN_IDS AGENTTEAMS_LEADER_IDS AGENTTEAMS_MANAGER_IDS
  AGENTTEAMS_LEADER_ROOM_ID
  AGENTTEAMS_CONTROLLER_URL AGENTTEAMS_AUTH_TOKEN
  AGENTTEAMS_MATRIX_URL AGENTTEAMS_MATRIX_USER_ID AGENTTEAMS_MATRIX_PASSWORD AGENTTEAMS_MATRIX_ACCESS_TOKEN
  CHATFLOWS_TASK_FS_ENDPOINT CHATFLOWS_TASK_FS_ACCESS_KEY CHATFLOWS_TASK_FS_SECRET_KEY
  AGENTTEAMS_FS_ENDPOINT AGENTTEAMS_FS_ACCESS_KEY AGENTTEAMS_FS_SECRET_KEY AGENTTEAMS_FS_BUCKET
  ORCHESTRATOR_LLM_BASE_URL
  OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_RESOURCE_ATTRIBUTES ARMS_LICENSE_KEY OTEL_EXPORTER_OTLP_HEADERS
  AGENTLOOP_ENDPOINT AGENTLOOP_ACCESS_KEY AGENTLOOP_ACCESS_SECRET
)

function join_keys() { local IFS=' '; echo "$*"; }

# ---- 写每个文件----

{
  echo "# ============================================================================"
  echo "# agent-core.env (由 split-integration-env.sh 从 integration.env 拆分生成"
  echo "# 目标机部署位置：/etc/agentteams/agent-core.env（属主 root:agentteams 权限 640）"
  echo "# 加载方式：systemd unit agentteams-agent-core-mcp.service / agentteams-agent-core-bff.service"
  echo "#           EnvironmentFile=/etc/agentteams/agent-core.env"
  echo "# ============================================================================"
  filter_keys "$(join_keys "${CORE_KEYS[@]}")"
} > "$OUT_DIR/agent-core.env"

{
  echo "# ============================================================================"
  echo "# agent-runtime.env (由 split-integration-env.sh 从 integration.env 拆分生成"
  echo "# 目标机部署位置：/etc/agentteams/agent-runtime.env（属主 root:agentteams 权限 640）"
  echo "# 加载方式：agentteams-agent-runtime.service EnvironmentFile=/etc/agentteams/agent-runtime.env"
  echo "# ============================================================================"
  runtime_db_line="$(grep -E '^AGENT_RUNTIME_DATABASE_URL=' "$SRC" | head -n1 || true)"
  if [[ -z "$runtime_db_line" ]]; then
    runtime_db_line="$(grep -E '^RUNTIME_DATABASE_URL=' "$SRC" | head -n1 || true)"
    if [[ -n "$runtime_db_line" ]]; then
      runtime_db_line="AGENT_RUNTIME_DATABASE_URL=${runtime_db_line#*=}"
    fi
  fi
  [[ -n "$runtime_db_line" ]] && normalize_env_line "$runtime_db_line"
  filter_keys "$(join_keys "${RUNTIME_KEYS[@]}")"
} > "$OUT_DIR/agent-runtime.env"

{
  echo "# ============================================================================"
  echo "# agent-manager.env (由 split-integration-env.sh 从 integration.env 拆分生成"
  echo "# 目标机部署位置：/etc/agentteams/agent-manager.env（属主 root:agentteams 权限 640）"
  echo "# 加载方式：agentteams-agent-manager.service EnvironmentFile=/etc/agentteams/agent-manager.env"
  echo "# 兼容：configure-chatflows-task-storage.js --apply /etc/agentteams/integration.env"
  echo "#       会直接回写 integration.env 的 CHATFLOWS_TASK_FS_SECRET_KEY"
  echo "# ============================================================================"
  filter_keys "$(join_keys "${MANAGER_KEYS[@]}")"
} > "$OUT_DIR/agent-manager.env"

# 修正权限
chmod 0640 "$OUT_DIR"/agent-{core,runtime,manager}.env
# 仅在可能的属主修正（若运行时以 root 执行为 root:root；非root则跳过）
if [[ "$(id -u)" == 0 ]]; then
  OWNER_GROUP="${VIBESALES_GROUP:-agentteams}"
  getent group "$OWNER_GROUP" >/dev/null 2>&1 && chown "root:$OWNER_GROUP" "$OUT_DIR"/agent-{core,runtime,manager}.env || true
fi

ls -la "$OUT_DIR"/agent-{core,runtime,manager}.env >&2
echo "[PASS] split ok: $OUT_DIR"
