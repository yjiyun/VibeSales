#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-run}" # run | help

HUMAN_NAME="${HUMAN_NAME:-chatflows-manager-ext}"
HUMAN_USERNAME="${HUMAN_USERNAME:-$HUMAN_NAME}"
HUMAN_DISPLAY_NAME="${HUMAN_DISPLAY_NAME:-Chatflows Manager Ext}"
TEAM_NAME="${TEAM_NAME:-chatflows-build-team}"
HUMAN_PERMISSION_LEVEL="${HUMAN_PERMISSION_LEVEL:-1}"
WAIT_SECONDS="${WAIT_SECONDS:-120}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"

log(){ echo "[INFO] $*"; }
pass(){ echo "[PASS] $*"; }
warn(){ echo "[WARN] $*" >&2; }
fail(){ echo "[FAIL] $*" >&2; exit 1; }
step(){ echo; echo "[STEP] $*"; }

usage() {
  cat <<'EOF'
usage:
  1) export AGENTTEAMS_CONTROLLER_URL='http://10.1.1.42:18090'
  2) export AGENTTEAMS_AUTH_TOKEN='...'
  3) export MATRIX_URL='http://10.1.1.42:18080'
  4) scripts/provision-chatflows-manager-ext-human.sh

optional env:
  HUMAN_NAME=chatflows-manager-ext
  HUMAN_USERNAME=chatflows-manager-ext
  HUMAN_DISPLAY_NAME='Chatflows Manager Ext'
  TEAM_NAME=chatflows-build-team
  HUMAN_PERMISSION_LEVEL=1
  WAIT_SECONDS=120
  POLL_INTERVAL_SECONDS=2

notes:
  - this script creates or updates one Human CR through Controller REST
  - it waits until the Human becomes Active
  - it logs in to Matrix with the generated initial password
  - it ensures the Human can join the target Team room
  - it prints the env snippet you can copy into agent-manager.env / integration.env
EOF
}

case "$mode" in
  run) ;;
  help|-h|--help)
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

require_env() {
  local key="$1"
  local value="${!key:-}"
  [[ -n "$value" ]] || fail "$key is required"
}

json_get() {
  local json="$1"
  local expr="$2"
  printf '%s' "$json" | jq -r "$expr"
}

controller_get() {
  local path="$1"
  curl -fsS \
    -H "Authorization: Bearer $AGENTTEAMS_AUTH_TOKEN" \
    "${AGENTTEAMS_CONTROLLER_URL%/}${path}"
}

matrix_whoami() {
  local token="$1"
  curl -fsS \
    -H "Authorization: Bearer $token" \
    "${MATRIX_URL%/}/_matrix/client/v3/account/whoami"
}

main() {
  require_command curl
  require_command jq
  require_command node
  require_command python3

  require_env AGENTTEAMS_CONTROLLER_URL
  require_env AGENTTEAMS_AUTH_TOKEN
  require_env MATRIX_URL

  step "Validate target team"
  local team_json team_room_id
  team_json="$(controller_get "/api/v1/teams/${TEAM_NAME}")" || fail "failed to fetch team: $TEAM_NAME"
  team_room_id="$(json_get "$team_json" '.teamRoomID // empty')"
  [[ -n "$team_room_id" && "$team_room_id" != "null" ]] || fail "teamRoomID is empty for team: $TEAM_NAME"
  log "Target team room: $team_room_id"

  step "Render Human resource"
  local tmp_yaml
  tmp_yaml="$(mktemp)"
  cat > "$tmp_yaml" <<EOF
apiVersion: agentteams.io/v1beta1
kind: Human
metadata:
  name: ${HUMAN_NAME}
spec:
  displayName: ${HUMAN_DISPLAY_NAME}
  username: ${HUMAN_USERNAME}
  permissionLevel: ${HUMAN_PERMISSION_LEVEL}
  accessibleTeams:
    - ${TEAM_NAME}
  note: external manager identity for VibeSales agent-manager
EOF
  log "Rendered manifest: $tmp_yaml"

  step "Apply Human resource"
  AGENTTEAMS_CONTROLLER_URL="$AGENTTEAMS_CONTROLLER_URL" \
  AGENTTEAMS_AUTH_TOKEN="$AGENTTEAMS_AUTH_TOKEN" \
  node "$root_dir/scripts/apply-agentteams-rest.js" "$tmp_yaml"
  pass "Human resource submitted"

  step "Wait for Human to become Active"
  local human_json phase deadline now
  deadline=$(( $(date +%s) + WAIT_SECONDS ))
  while true; do
    human_json="$(controller_get "/api/v1/humans/${HUMAN_NAME}")" || fail "failed to fetch human: $HUMAN_NAME"
    phase="$(json_get "$human_json" '.phase // empty')"
    if [[ "$phase" == "Active" ]]; then
      pass "Human is Active"
      break
    fi
    if [[ "$phase" == "Failed" ]]; then
      fail "Human provisioning failed: $(json_get "$human_json" '.message // "unknown error"')"
    fi
    now="$(date +%s)"
    if (( now >= deadline )); then
      fail "timed out waiting for Human Active; last phase=$phase message=$(json_get "$human_json" '.message // ""')"
    fi
    log "Current phase=$phase; waiting ${POLL_INTERVAL_SECONDS}s"
    sleep "$POLL_INTERVAL_SECONDS"
  done

  step "Extract Human credentials"
  local human_user_id human_password
  human_user_id="$(json_get "$human_json" '.matrixUserID // empty')"
  human_password="$(json_get "$human_json" '.initialPassword // empty')"
  [[ -n "$human_user_id" && "$human_user_id" != "null" ]] || fail "status.matrixUserID is empty for Human: $HUMAN_NAME"
  if [[ -z "$human_password" || "$human_password" == "null" ]]; then
    fail "status.initialPassword is empty for Human: $HUMAN_NAME; if this Human already existed, recreate with a new name or reset password first"
  fi
  log "Human Matrix user: $human_user_id"

  step "Log in to Matrix"
  local login_json human_token
  login_json="$(curl -fsS -X POST "${MATRIX_URL%/}/_matrix/client/v3/login" \
    -H 'Content-Type: application/json' \
    -d "{
      \"type\":\"m.login.password\",
      \"identifier\":{\"type\":\"m.id.user\",\"user\":\"${human_user_id}\"},
      \"password\":\"${human_password}\"
    }")"
  human_token="$(json_get "$login_json" '.access_token // empty')"
  [[ -n "$human_token" && "$human_token" != "null" ]] || fail "Matrix login succeeded without access_token"
  pass "Matrix login succeeded"

  step "Verify Matrix identity"
  local whoami_json is_guest
  whoami_json="$(matrix_whoami "$human_token")"
  is_guest="$(json_get "$whoami_json" '.is_guest // false')"
  [[ "$(json_get "$whoami_json" '.user_id // empty')" == "$human_user_id" ]] || fail "whoami user_id does not match Human matrixUserID"
  [[ "$is_guest" != "true" ]] || fail "Human Matrix token is guest; expected non-guest"
  pass "Matrix identity is non-guest"

  step "Ensure Human can join team room"
  local joined_json enc_room join_code join_body
  joined_json="$(curl -fsS \
    -H "Authorization: Bearer $human_token" \
    "${MATRIX_URL%/}/_matrix/client/v3/joined_rooms")"
  if ! printf '%s' "$joined_json" | jq -e --arg room "$team_room_id" '.joined_rooms // [] | index($room)' >/dev/null 2>&1; then
    enc_room="$(python3 - <<'PY' "$team_room_id"
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
)"
    join_body="$(mktemp)"
    join_code="$(curl -sS -o "$join_body" -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $human_token" \
      -H "Content-Type: application/json" \
      "${MATRIX_URL%/}/_matrix/client/v3/join/${enc_room}" \
      -d '{}')"
    if [[ "$join_code" != "200" ]]; then
      cat "$join_body" >&2 || true
      rm -f "$join_body"
      fail "failed to join team room; http=$join_code"
    fi
    rm -f "$join_body"
    pass "Joined team room"
  else
    pass "Human already joined team room"
  fi

  step "Print env snippet"
  cat <<EOF
AGENTTEAMS_MATRIX_USER_ID=${human_user_id}
AGENTTEAMS_MANAGER_IDS=${human_user_id}
AGENTTEAMS_MATRIX_PASSWORD=${human_password}
AGENTTEAMS_MATRIX_ACCESS_TOKEN=${human_token}
AGENTTEAMS_LEADER_ROOM_ID=${team_room_id}
EOF

  echo
  pass "provision flow completed successfully"
}

main "$@"
