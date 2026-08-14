/** 把 local pipeline GET 与 platform manager GET 收成向导发布卡能用的同一份快照。 */

function last(artifacts, kind, predicate) {
  const matched = (artifacts ?? []).filter((item) => item.kind === kind && (!predicate || predicate(item)));
  return matched.at(-1);
}

export function fromPipelineGet(data) {
  const run = data?.run ?? {};
  const artifacts = data?.artifacts ?? [];
  const pending = last(artifacts, 'approval', (item) => item.payload?.status === 'PENDING');
  const blueprint = last(artifacts, 'blueprint');
  const check = last(artifacts, 'blueprint_check') ?? last(artifacts, 'flow_check');
  const bp = blueprint?.payload ?? null;
  return {
    runId: run.run_id,
    status: run.status,
    buildPath: run.build_path,
    clientCode: run.client_code,
    approvalId: pending?.payload?.approval_id ?? '',
    selfcheckOk: check?.payload?.ok !== false,
    sceneId: bp?.meta?.scenarios?.[0] ?? '',
    runtimeAgentId: bp?.runtimeAgentId ?? '',
    displayName: bp?.guidance?.role || bp?.runtimeAgentId || '',
    skills: (bp?.skills ?? []).map((item) => item.name).filter(Boolean),
    memory: bp?.tools?.allow?.includes('memory_search') === true,
    artifacts: artifacts.map((item) => ({ kind: item.kind, version: item.version })),
  };
}

export function fromManagerGet(data) {
  const pending = data?.pending_approvals ?? [];
  const lastPending = pending.at(-1);
  return {
    runId: data?.run_id,
    status: data?.status,
    buildPath: data?.build_path,
    clientCode: data?.client_code,
    approvalId: typeof lastPending === 'string' ? lastPending : lastPending?.approval_id ?? '',
    selfcheckOk: true,
    sceneId: '',
    runtimeAgentId: '',
    displayName: '',
    skills: [],
    memory: false,
    artifacts: data?.artifacts ?? [],
  };
}

export function publicationFromApprove(decided, snapshot) {
  const binding = decided?.result?.binding ?? {};
  return {
    clientCode: binding.client_code ?? snapshot.clientCode,
    runtimeAgentId: binding.runtime_agent_id ?? snapshot.runtimeAgentId,
    runId: snapshot.runId,
    sceneId: snapshot.sceneId,
    displayName: snapshot.displayName,
    buildPath: decided?.result?.path ?? snapshot.buildPath,
  };
}
