#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"

mvn -q -DskipTests clean compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dist/lib
mkdir -p target/dist/classes
cp -R target/classes/. target/dist/classes/
test -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class
