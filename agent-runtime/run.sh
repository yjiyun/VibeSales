#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

if [[ ! -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class ]]; then
  ./build-dist.sh
fi

java_ok() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "(1[7-9]|2[0-9])'
}

pick_java_home() {
  local maven_home candidate
  maven_home="$(mvn -version 2>&1 | sed -n 's/^Java version: .* runtime: //p' | head -1)"
  for candidate in \
    "${JAVA_HOME:-}" \
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "$maven_home"
  do
    [[ -n "$candidate" ]] && java_ok "$candidate" || continue
    printf '%s' "$candidate"
    return 0
  done
  echo "agent-runtime needs JDK 17+." >&2
  echo "export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" >&2
  return 1
}

java_home="$(pick_java_home)"
exec "$java_home/bin/java" -cp "target/dist/classes:target/dist/lib/*" com.yjiyun.chatflows.runtime.RuntimeApplication
