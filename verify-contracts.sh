#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd "$(dirname "$0")" && pwd)"
export ARTIFACT_STORE=file FLOW_PLATFORM_MODE=local DEMO_TRACE=0 LOG_STDERR=off
contract_temp="$(mktemp -d -t chatflows-contracts.XXXXXX)"
proof_file="$contract_temp/approval-proof.json"
proof_secret="approval-cross-language-secret-0123456789"
cleanup(){ rm -rf "$contract_temp"; }
trap cleanup EXIT
export ARTIFACT_STORE_FILE="$contract_temp/store.json"
export FLOW_PROJECT_ROOT="$contract_temp/flows"

export ORCHESTRATOR_LLM="${ORCHESTRATOR_LLM:-off}"   # A24：默认不打真模型
manager_agentloop_file="$contract_temp/manager-agentloop.json"
runtime_agentloop_file="$contract_temp/runtime-agentloop.json"

echo "[CONTRACT] Node resources, P1, MCP, P3/P3B/P3C and Human approval"
( cd "$root_dir/agent-console" && npm run test:contract && npm run build )
node "$root_dir/scripts/inspect-run.mjs" --self-test
cd "$root_dir/agent-core"
npm run build
npm run test:resources
"$root_dir/scripts/test-agentteams-apply.sh"
npm run test:mcp
npm run test:mcp-production
npm run test:control
npm run test:web-auth
npm run test:qwen-gateway
npm run test:p1
BLUEPRINT_SMOKE_DIR="$contract_temp/smokes" npm run test:p3c-industries
npm run test:worker-mcp

echo "[CONTRACT] Java manager, Matrix/HMAC and rendered CR manifest"
cd "$root_dir/agent-manager"
./build-dist.sh
mvn -o -q test-compile
for test in ManagerSelfTest ManagerConfigSelfTest ManagerAuthSelfTest ManagerHttpSelfTest RunSupervisorSelfTest CompletionGateSelfTest MatrixPasswordLoginSelfTest AgentLoopContractSelfTest ManifestApplySelfTest; do
  mvn -o -q exec:java -Dexec.mainClass="com.yjiyun.chatflows.manager.$test" -Dexec.classpathScope=test
done
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.platform.RestPlatformClientApplySelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.matrix.RestMatrixClientSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.matrix.RestMatrixClientJoinInviteSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.matrix.RoomTimelineSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.agent.OrchestrationStoreSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.agent.OrchestrationPlannerSuspendSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.agent.OrchestrationPlannerFallbackSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.observability.AgentLoopExporterSelfTest -Dexec.classpathScope=test -Dexec.args="$manager_agentloop_file"
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.observability.ManagerTelemetryMessagesSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.observability.ManagerSpanAliasesSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.manager.ApprovalProofExport -Dexec.classpathScope=test -Dexec.args="$proof_file $proof_secret"
cd "$root_dir/agent-core"
APPROVAL_PROOF_FILE="$proof_file" APPROVAL_PROOF_SECRET="$proof_secret" npm run test:approval-proof

echo "[CONTRACT] Java runtime projection, gateway model, cross-language and replicas"
cd "$root_dir/agent-runtime"
./build-dist.sh
mvn -o -q test-compile
for test in RuntimeSelfTest InspectSelfTest RuntimeGatewaySelfTest RuntimeGatewayModelSelfTest RuntimeAgentLoopSelfTest RuntimeDistributedSelfTest; do
  mvn -o -q exec:java -Dexec.mainClass="com.yjiyun.chatflows.runtime.$test" -Dexec.classpathScope=test
done
mvn -o -q exec:java -Dexec.mainClass=com.vibesales.salesagent.observability.RuntimeTelemetryInstallSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.vibesales.salesagent.observability.RuntimeTelemetryMessagesSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.vibesales.salesagent.observability.SpanAliasesSelfTest -Dexec.classpathScope=test
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.runtime.CrossLanguageSelfTest -Dexec.classpathScope=test -Dexec.args="$contract_temp/smokes/beauty.json"
mvn -o -q exec:java -Dexec.mainClass=com.yjiyun.chatflows.runtime.IndustrySmokeSelfTest -Dexec.classpathScope=test -Dexec.args="$contract_temp/smokes"
cd "$root_dir/agent-core"
MANAGER_AGENTLOOP_ENVELOPE="$manager_agentloop_file" RUNTIME_AGENTLOOP_ENVELOPE="$runtime_agentloop_file" npm run test:agentloop-aggregation
npm run test:agentloop-messages
npm run test:span-aliases
npm run test:otlp-sink

cd "$root_dir"
node scripts/test-agentteams-local-dev.mjs
node scripts/test-agentteams-platform-e2e.mjs
node scripts/test-higress-chatflows-mcp-config.mjs
node scripts/test-rotate-higress-llm-key.mjs
node scripts/test-agentteams-preflight.mjs
node scripts/validate-agentteams.js
node scripts/e2e-human-approver.js --self-test
node --check scripts/preflight-agentteams-integration.js scripts/render-agentteams-bundle.js scripts/discover-local-agentteams-env.js scripts/configure-agentteams-higress.js scripts/discover-agentteams-resources.js scripts/run-agentteams-platform-e2e.js scripts/configure-agentteams-leader-tools.js scripts/configure-agentteams-worker-mcp.js scripts/rotate-higress-llm-key.mjs scripts/test-rotate-higress-llm-key.mjs
bash -n verify-all.sh verify-contracts.sh scripts/deploy-agentteams-stack.sh scripts/run-agentteams-e2e.sh scripts/run-agentteams-stack-e2e.sh scripts/test-bff-http.sh scripts/test-agentteams-apply.sh scripts/run-local-docker-stack.sh scripts/start-local-docker-stack-manual.sh
git diff --check
git -C agent-core diff --check
echo "[PASS] no-port contracts: P1-P4/MCP/Human/manager/runtime/cross-language/replicas"
echo "[NOTICE] This does not replace verify-all.sh HTTP/SSE or real AgentTeams infrastructure E2E."
echo "[NOTICE] Real-cluster entrypoints stay out of both gates: scripts/run-agentteams-stack-e2e.sh, scripts/run-agentteams-platform-e2e.js."
