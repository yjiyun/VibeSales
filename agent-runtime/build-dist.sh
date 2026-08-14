#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$root_dir"
mvn -o -q clean compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dist/lib
mkdir -p target/dist/classes target/dist/workspace
cp -R target/classes/. target/dist/classes/
cp -R workspace/. target/dist/workspace/
test -f target/dist/classes/com/yjiyun/chatflows/runtime/RuntimeApplication.class
