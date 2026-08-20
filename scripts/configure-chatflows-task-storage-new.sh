#!/usr/bin/env bash
# ============================================================================
# 与 scripts/configure-chatflows-task-storage.js --check/--apply 注入到容器的
# CHECK_SHELL / APPLY_SHELL 内容 1:1 等价，但：
#   - 强制用 bash（shebang + 内部分配 exec /bin/bash）避免 dash Illegal option
#   - 完整保留所有原始 stderr 不 redact，便于定位
#   - 在 docker exec 之前把宿主机 envFile 拷贝到容器的 /envFile（
#     原 Node 脚本硬编码从 /envFile grep，不会自动传文件）
# ============================================================================
set -u

VIBESALES_ENV="${VIBESALES_ENV:-deploy/agentteams/integration.env}"
MODE="${1:-check}"   # check | apply | rollback

case "$MODE" in
  check|apply|rollback) ;;
  *)
    echo "usage: $0 [check|apply|rollback]" >&2
    exit 2
    ;;
esac

if [[ ! -f "$VIBESALES_ENV" ]]; then
  echo "envFile not found: $VIBESALES_ENV" >&2
  exit 2
fi

# ---- 0) 传 env 文件到容器的 /envFile（原脚本内部硬编码路径读 /envFile） ----
docker cp "$VIBESALES_ENV" agentteams-controller:/envFile || exit 11
docker exec agentteams-controller chmod 0644 /envFile || exit 12

# ---- 1) 选择要注入的 SHELL 正文，与 Node 版保持一致 ----
if [[ "$MODE" == "check" ]]; then
INNER_SCRIPT=$(cat <<'SHELL_EOF'
#!/usr/bin/env bash
set -u
# 注意：原 Node 脚本 CHECK 模式为了兼容首次不存在的对象，
# 对 policy info / user info / policy entities 的非零退出并不视为失败，
# 所以这里不使用 set -e；只有 mc alias set / 三常量校验失败才算硬错

echo "[debug][1] 容器内 AGENTTEAMS_MINIO_USER=${AGENTTEAMS_MINIO_USER:-<empty>}"

u="${AGENTTEAMS_MINIO_USER:?AGENTTEAMS_MINIO_USER empty in container}"
p="${AGENTTEAMS_MINIO_PASSWORD:?AGENTTEAMS_MINIO_PASSWORD empty in container}"

endpoint="$(grep -E '^CHATFLOWS_TASK_FS_ENDPOINT=' /envFile | cut -d= -f2- | tr -d '\r')"
access="$(grep -E '^CHATFLOWS_TASK_FS_ACCESS_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
bucket="$(grep -E '^CHATFLOWS_TASK_FS_BUCKET=' /envFile | cut -d= -f2- | tr -d '\r')"
prefix="$(grep -E '^CHATFLOWS_TASK_FS_PREFIX=' /envFile | cut -d= -f2- | tr -d '\r')"
secret="$(grep -E '^CHATFLOWS_TASK_FS_SECRET_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
endpoint="${endpoint%%/}"

echo "[debug][2] 5 变量 from /envFile:"
echo "  endpoint = $endpoint"
echo "  access   = $access"
echo "  bucket   = $bucket"
echo "  prefix   = $prefix"
echo "  secret   = ${secret:0:4}***"

# ---- 常量硬校验 3 条（Node 脚本 exit 2/3/4 原样）----
if [[ "$access" != "chatflows-task-manager" ]]; then
  echo "[HARD_FAIL] access != chatflows-task-manager => $access"; exit 2
fi
if [[ "$bucket" != "agentteams-storage" ]]; then
  echo "[HARD_FAIL] bucket != agentteams-storage => $bucket"; exit 3
fi
if [[ "$prefix" != "teams/chatflows-build-team/shared/tasks" ]]; then
  echo "[HARD_FAIL] prefix != 默认前缀 => $prefix"; exit 4
fi

# ---- 4 条原始 mc 命令（对应 Node 脚本 CHECK_SHELL）----
echo "[debug][3] mc alias set myminio $endpoint ${AGENTTEAMS_MINIO_USER} ********"
if ! mc alias set myminio "$endpoint" "$u" "$p"; then
  echo "[HARD_FAIL] mc alias set 返回非零（见上原始报错：EndpointUnreachable / InvalidAccessKeyId / 403…）"
  exit 10
fi

echo "[debug][4] mc admin policy info myminio chatflows-task-manager-fixed-team"
mc admin policy info myminio chatflows-task-manager-fixed-team || \
  echo "[WARN][4] policy 未找到，首次执行属正常（不视为 check 失败）"

echo "[debug][5] mc admin user info myminio $access"
mc admin user info myminio "$access" || \
  echo "[WARN][5] user $access 未找到，首次执行属正常（不视为 check 失败）"

echo "[debug][6] mc admin policy entities myminio --user $access"
mc admin policy entities myminio --user "$access" || \
  echo "[WARN][6] policy entities 查询无记录（首次执行属正常，不视为 check 失败）"

echo "[PASS] CHECK（raw） 成功"
SHELL_EOF
)
elif [[ "$MODE" == "apply" ]]; then
# ---- 0a) 宿主侧先生成 policy.json，再 docker cp 进容器（因为 mc admin policy create 必须读真实文件，不能 stdin）----
POLICY_TMP_HOST="$(mktemp)"
trap 'rm -f "$POLICY_TMP_HOST"' EXIT

# 临时把 bucket/prefix 从 envFile 展开，生成 JSON
POLICY_BUCKET="$(grep -E '^CHATFLOWS_TASK_FS_BUCKET=' "$VIBESALES_ENV" | cut -d= -f2- | tr -d '\r')"
POLICY_PREFIX="$(grep -E '^CHATFLOWS_TASK_FS_PREFIX=' "$VIBESALES_ENV" | cut -d= -f2- | tr -d '\r')"
cat > "$POLICY_TMP_HOST" <<POLICY_EOF
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetBucketLocation"],"Resource":["arn:aws:s3:::${POLICY_BUCKET}"]},{"Effect":"Allow","Action":["s3:ListBucket"],"Resource":["arn:aws:s3:::${POLICY_BUCKET}"],"Condition":{"StringLike":{"s3:prefix":["${POLICY_PREFIX}","${POLICY_PREFIX}/*"]}}},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::${POLICY_BUCKET}/${POLICY_PREFIX}/*"]}]}
POLICY_EOF
docker cp "$POLICY_TMP_HOST" agentteams-controller:/tmp/chatflows-task-policy.json || exit 21
rm -f "$POLICY_TMP_HOST"
trap - EXIT

INNER_SCRIPT=$(cat <<'SHELL_EOF'
#!/usr/bin/env bash
set -euo pipefail

u="${AGENTTEAMS_MINIO_USER:?AGENTTEAMS_MINIO_USER empty in container}"
p="${AGENTTEAMS_MINIO_PASSWORD:?AGENTTEAMS_MINIO_PASSWORD empty in container}"

endpoint="$(grep -E '^CHATFLOWS_TASK_FS_ENDPOINT=' /envFile | cut -d= -f2- | tr -d '\r')"
access="$(grep -E '^CHATFLOWS_TASK_FS_ACCESS_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
bucket="$(grep -E '^CHATFLOWS_TASK_FS_BUCKET=' /envFile | cut -d= -f2- | tr -d '\r')"
prefix="$(grep -E '^CHATFLOWS_TASK_FS_PREFIX=' /envFile | cut -d= -f2- | tr -d '\r')"
secret="$(grep -E '^CHATFLOWS_TASK_FS_SECRET_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
endpoint="${endpoint%%/}"

if [[ "$access" != "chatflows-task-manager" ]]; then echo "[HARD_FAIL] access"; exit 2; fi
if [[ "$bucket" != "agentteams-storage" ]]; then echo "[HARD_FAIL] bucket"; exit 3; fi
if [[ "$prefix" != "teams/chatflows-build-team/shared/tasks" ]]; then echo "[HARD_FAIL] prefix"; exit 4; fi

# 1) 如果 sk 空 → 自动生成（与 Node saveSecret 等价：base64url ≥ 32）
if [[ -z "$secret" ]]; then
  NEW_SK="$(node -e 'console.log(require("crypto").randomBytes(32).toString("base64url"))')"
  # 回写容器内 /envFile，后面对外 docker cp 回宿主
  ESCAPED="$(printf '%s' "$NEW_SK" | sed 's/[#&]/\\&/g')"
  sed -i -E "s|^CHATFLOWS_TASK_FS_SECRET_KEY=.*|CHATFLOWS_TASK_FS_SECRET_KEY=${ESCAPED}|" /envFile
  echo "[INFO] 自动生成 CHATFLOWS_TASK_FS_SECRET_KEY=${NEW_SK:0:4}*** 并回写容器内 /envFile"
fi

mc alias set myminio "$endpoint" "$u" "$p"

# （与 Node APPLY_SHELL 完全等价：create policy → ensure user → attach）
mc admin policy rm myminio chatflows-task-manager-fixed-team 2>/dev/null || true
# 注意：必须传入真实文件路径，不能 stdin
mc admin policy create myminio chatflows-task-manager-fixed-team /tmp/chatflows-task-policy.json

if mc admin user info myminio "$access" >/dev/null 2>&1; then
  # 用户已存在 → 若 env 里 SK 有值则同步更新密码（保持与 /envFile 一致）
  if [[ -n "$secret" ]]; then
    mc admin user add myminio "$access" "$secret"
    echo "[INFO] 用户 $access 已存在，按 /envFile 的 SK 同步更新密码"
  else
    echo "[INFO] 用户 $access 已存在，且 /envFile 无 SK，保留原密码"
  fi
else
  # 用户不存在 → 新建（优先用 envFile 里的 SK，其次自动生成）
  USK="$secret"
  if [[ -z "$USK" ]]; then
    USK="$(grep -E '^CHATFLOWS_TASK_FS_SECRET_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
  fi
  mc admin user add myminio "$access" "$USK"
fi
mc admin policy attach myminio chatflows-task-manager-fixed-team --user "$access"

# （冲突安全校验：AGENTTEAMS_FS_ACCESS_KEY 和 chatflows-task-manager 必须不同）
FS_AK="$(grep -E '^AGENTTEAMS_FS_ACCESS_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
if [[ -n "$FS_AK" && "$FS_AK" == "$access" ]]; then
  echo "[HARD_FAIL][P3 红线] CHATFLOWS_TASK_FS_ACCESS_KEY 与 AGENTTEAMS_FS_ACCESS_KEY 相同"
  exit 5
fi

echo "[PASS] APPLY（raw） 成功；如自动生成了 SK，执行完后会自动 docker cp 回 宿主机的 VIBESALES_ENV 指定路径"
SHELL_EOF
)
else  # rollback
INNER_SCRIPT=$(cat <<'SHELL_EOF'
#!/usr/bin/env bash
set -uo pipefail

u="${AGENTTEAMS_MINIO_USER:?AGENTTEAMS_MINIO_USER empty in container}"
p="${AGENTTEAMS_MINIO_PASSWORD:?AGENTTEAMS_MINIO_PASSWORD empty in container}"
access="$(grep -E '^CHATFLOWS_TASK_FS_ACCESS_KEY=' /envFile | cut -d= -f2- | tr -d '\r')"
endpoint="$(grep -E '^CHATFLOWS_TASK_FS_ENDPOINT=' /envFile | cut -d= -f2- | tr -d '\r')"
endpoint="${endpoint%%/}"

mc alias set myminio "$endpoint" "$u" "$p"
mc admin policy detach myminio chatflows-task-manager-fixed-team --user "$access" 2>/dev/null || true
mc admin user rm myminio "$access" 2>/dev/null || true
mc admin policy rm myminio chatflows-task-manager-fixed-team 2>/dev/null || true
echo "[PASS] ROLLBACK（raw） 成功"
SHELL_EOF
)
fi

# ---- 2) docker exec 强制切 /bin/bash 执行内脚本（避免 dash Illegal option -o pipefail） ----
set +e
printf '%s\n' "$INNER_SCRIPT" | docker exec -i agentteams-controller /bin/bash -s
rc=$?
set -e

# ---- 3) apply 模式下，把容器内 /envFile 回拷贝到宿主机（保存 SK 自动生成的结果）----
if [[ "$MODE" == "apply" ]]; then
  docker cp agentteams-controller:/envFile "$VIBESALES_ENV"
  echo "[INFO] 已把容器内 /envFile 回写到 $VIBESALES_ENV（包含 SK 自动生成）"
fi

echo "[INFO] docker exec exit=$rc"
exit "$rc"
