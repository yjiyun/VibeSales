#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")" && pwd)"
manifest="$root_dir/agentteams-resources/kustomization.yaml"
render_dir=""
cleanup(){ if [[ -n "$render_dir" ]]; then rm -rf "$render_dir"; fi; }
trap cleanup EXIT
if [[ "$#" -gt 0 ]]; then
  if [[ "$1" != "-f" ]]; then
    echo "usage: $0 -f [agentteams-resources/kustomization.yaml]" >&2
    exit 2
  fi
  if [[ "$#" -gt 1 ]]; then manifest="$2"; fi
fi
transport="${AGENTTEAMS_APPLY_TRANSPORT:-auto}"
if [[ "$transport" == "auto" ]]; then
  if command -v agt >/dev/null 2>&1; then transport="agt"; else transport="rest"; fi
fi
if [[ "$transport" != "agt" && "$transport" != "rest" ]]; then
  echo "AGENTTEAMS_APPLY_TRANSPORT must be auto, agt or rest" >&2
  exit 3
fi
if [[ "$transport" == "agt" ]] && ! command -v agt >/dev/null 2>&1; then
  echo "agt CLI is required for AGENTTEAMS_APPLY_TRANSPORT=agt; no deployment was modified" >&2
  exit 3
fi
if [[ "$transport" == "rest" ]] && { [[ -z "${AGENTTEAMS_CONTROLLER_URL:-}" ]] || [[ -z "${AGENTTEAMS_AUTH_TOKEN:-}" ]]; }; then
  echo "REST apply requires AGENTTEAMS_CONTROLLER_URL and AGENTTEAMS_AUTH_TOKEN; no deployment was modified" >&2
  exit 3
fi
base_url="${CHATFLOWS_MCP_BASE_URL:-}"
if [[ -z "$base_url" ]]; then
  echo "CHATFLOWS_MCP_BASE_URL is required (HTTPS, or private-network HTTP)" >&2
  exit 4
fi
render_dir="$(mktemp -d -t chatflows-agentteams.XXXXXX)"
while IFS= read -r rel; do
  source_file="$(dirname "$manifest")/$rel"
  rendered_file="$render_dir/$(basename "$rel")"
  node "$root_dir/scripts/render-agentteams-resource.js" "$source_file" "$rendered_file" "$root_dir" "$base_url" "${HIGRESS_CONSUMER_TOKEN:-}"
  if grep -qE 'higress\.(example|local)' "$rendered_file"; then
    echo "unresolved Higress placeholder in $rel" >&2
    exit 5
  fi
  # REST only persists CR JSON. qwenpaw also requires every declared Skill as
  # agents/<worker>/skills/<skill>/SKILL.md in the AgentTeams MinIO bucket.
  # Sync first so a Worker can never be reconciled with dangling Skill names.
  if [[ "$transport" == "rest" ]]; then
    node "$root_dir/scripts/sync-agentteams-worker-skills.js" "$rendered_file"
  fi
  if [[ "$transport" == "agt" ]]; then
    agt apply -f "$rendered_file"
  else
    node "$root_dir/scripts/apply-agentteams-rest.js" "$rendered_file"
  fi
done < <(sed -n 's/^  - //p' "$manifest")
