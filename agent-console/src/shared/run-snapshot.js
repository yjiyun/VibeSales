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
    phase: run.current_phase,
    buildPath: run.build_path,
    clientCode: run.client_code,
    approvalId: pending?.payload?.approval_id ?? '',
    selfcheckOk: check?.payload?.ok !== false,
    sceneId: bp?.meta?.scenarios?.[0] ?? '',
    runtimeAgentId: bp?.runtimeAgentId ?? '',
    displayName: bp?.guidance?.role || bp?.runtimeAgentId || '',
    skills: (bp?.skills ?? []).map((item) => item.name).filter(Boolean),
    memory: bp?.tools?.allow?.includes('memory_search') === true,
    artifacts: artifacts.map((item) => ({
      kind: item.kind,
      version: item.version,
      written_by: item.written_by,
      created_at: item.created_at,
      payload: item.payload,
    })),
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

/**
 * platform 模式的发布闸门快照：**阶段与审批以 Nest 为权威**，manager 只补编排侧信息。
 *
 * manager 的 `status` 是它自己的编排状态（`DISPATCHED` 等），只表示「已把 NEW_RUN 派给
 * Leader」，不跟踪 Nest 的阶段推进；`pending_approvals` 也是 manager 自己维护的集合。
 * 所以 Nest 早就 `WAITING_HUMAN`/`phase=P4`、approval 已 PENDING 时，manager 仍是
 * `DISPATCHED` —— 向导若只读 manager，就会一直转圈、永远不出「确认发布」。
 */
export function mergePlatformSnapshot(managerData, pipelineData) {
  const fromManager = fromManagerGet(managerData);
  if (!pipelineData) return fromManager;
  const fromNest = fromPipelineGet(pipelineData);
  return {
    ...fromManager,
    ...fromNest,
    runId: fromNest.runId || fromManager.runId,
    // Nest 没给 approval 时（例如还没到 P4）退回 manager 的 pending_approvals
    approvalId: fromNest.approvalId || fromManager.approvalId,
    managerStatus: fromManager.status,
  };
}

export function mentionsRun(body, runId) {
  const text = String(body ?? '');
  const id = String(runId ?? '');
  if (!text || !id) return false;
  if (text.includes(id)) return true;
  const prefix = id.slice(0, 8);
  return prefix.length === 8 && (text.includes('`' + prefix + '`') || new RegExp('\\b' + prefix + '\\b').test(text));
}

export function extractApprovalId(messages, runId) {
  for (const item of messages ?? []) {
    const body = String(item?.body ?? '');
    if (!mentionsRun(body, runId)) continue;
    const proto = body.match(/APPROVAL_REQUIRED\s+run_id=([A-Za-z0-9_-]+)\s+approval_id=([A-Za-z0-9_-]+)/);
    if (proto) return proto[2];
    if (/等待 Human 审批|WAITING_HUMAN|pending_approval|APPROVAL_REQUIRED/i.test(body)) {
      const informal = body.match(/approval_id=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/);
      if (informal) return informal[1];
    }
  }
  return '';
}

/**
 * 聊天文本里的 `APPROVAL_REQUIRED`/「等待 Human 审批」只是 Leader（LLM）的口头汇报，
 * 不是权威事实——Leader 可能在 Nest 真正进入 WAITING_HUMAN 之前就编出这句话（实测发生过：
 * run aef1e08b，approval_id 本身也是编的）。这里不再直接把 snapshot.status 改写成
 * WAITING_HUMAN 去解锁「确认发布」，只把抠出来的 approvalId 记成待确认的线索
 * （approvalGuess），供调用方去问一次权威源，问不到就仍然维持原状态、按钮不会点亮。
 */
export function applyApprovalGate(snapshot, approvalId) {
  if (!snapshot || !approvalId) return snapshot;
  if (snapshot.approvalId) return snapshot;
  return { ...snapshot, approvalGuess: approvalId };
}

/** Nest 已 SUCCEEDED 时不必再走审批响应，从快照产物里抽出沙盒绑定。 */
export function publicationFromSnapshot(snapshot) {
  const imported = last(snapshot?.artifacts, 'import_result');
  const binding = imported?.payload?.binding ?? {};
  return {
    clientCode: binding.client_code ?? snapshot?.clientCode ?? '',
    runtimeAgentId: binding.runtime_agent_id ?? snapshot?.runtimeAgentId ?? '',
    runId: snapshot?.runId ?? '',
    sceneId: snapshot?.sceneId ?? '',
    displayName: snapshot?.displayName ?? '',
    buildPath: snapshot?.buildPath ?? '',
  };
}

export function publicationFromApprove(decided, snapshot) {
  const binding = decided?.result?.binding ?? {};
  const fromSnap = publicationFromSnapshot(snapshot);
  return {
    clientCode: binding.client_code || fromSnap.clientCode,
    runtimeAgentId: binding.runtime_agent_id || fromSnap.runtimeAgentId,
    runId: snapshot.runId,
    sceneId: snapshot.sceneId,
    displayName: snapshot.displayName,
    buildPath: decided?.result?.path || fromSnap.buildPath,
  };
}
