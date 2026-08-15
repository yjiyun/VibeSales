#!/usr/bin/env bash
# 产物与专家团观察 P0：从 ArtifactStore JSON 打印人读摘要。
# 用法见 docs/agentteams/产物与专家团观察.md
set -euo pipefail
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec node "$root_dir/scripts/inspect-run.mjs" "$@"
