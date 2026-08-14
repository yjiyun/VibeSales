#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"
if [[ ! -f target/dist/classes/com/yjiyun/chatflows/manager/ManagerApplication.class ]]; then
  ./build-dist.sh
fi
maven_java_home="$(mvn -version 2>&1 | sed -n 's/^Java version: .* runtime: //p' | head -1)"
if [[ -z "$maven_java_home" || ! -x "$maven_java_home/bin/java" ]]; then
  echo "Unable to resolve Maven Java runtime" >&2
  exit 1
fi
exec "$maven_java_home/bin/java" -cp "target/dist/classes:target/dist/lib/*" com.yjiyun.chatflows.manager.ManagerApplication "$@"
