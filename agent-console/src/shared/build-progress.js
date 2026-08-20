/**
 * 「构建智能体」过程卡：产物阶段做骨架，Room 过滤后的关键消息做旁注。
 * 不改 RoomTimeline；思考 / tool JSON 在前端丢掉。
 */
import { expertRole, latestArtifact } from './artifact-preview';

export const BUILD_STEPS = [
  { id: 'dispatch', label: '派发编排' },
  { id: 'guidance', label: '业务指导' },
  { id: 'persona', label: '人设专家', expert: 'persona-expert' },
  { id: 'business', label: '业务专家', expert: 'business-expert' },
  { id: 'skill', label: '技能专家', expert: 'skill-expert' },
  { id: 'tool', label: '工具专家', expert: 'tool-expert' },
  { id: 'blueprint', label: '合成 Blueprint' },
  { id: 'check', label: '静态自检' },
  { id: 'gate', label: '等待确认发布' },
];

const EXPERT_IDS = ['persona', 'business', 'skill', 'tool'];

export function senderLocalpart(sender) {
  if (typeof sender !== 'string' || !sender) return '';
  if (sender.startsWith('@')) return sender.slice(1).split(':')[0] || sender;
  return sender;
}

export function clipNote(body, max = 80) {
  const first = String(body ?? '')
    .trim()
    .split(/\n/)[0]
    .replace(/^@\S+\s*/, '')
    .trim();
  if (!first) return '';
  return first.length > max ? `${first.slice(0, max)}…` : first;
}

function isEnglishReasoning(text) {
  const sample = String(text ?? '').slice(0, 400);
  const letters = (sample.match(/[A-Za-z]/g) || []).length;
  const cjk = (sample.match(/[\u4e00-\u9fff]/g) || []).length;
  if (letters > 80 && letters > cjk * 3) return true;
  return /^(The user|I need to|Let me|I'll |I will |First,|Okay,|Alright,)/i.test(sample);
}

function isToolDump(text) {
  const body = String(text ?? '');
  if (/tool_call|function_call|show_tool|"name"\s*:\s*"(read_file|write_file|shell|mcp_)/i.test(body)) {
    return true;
  }
  return body.includes('```json') && body.length > 400;
}

export function isNoiseBody(body) {
  const text = String(body ?? '').trim();
  if (!text) return true;
  if (text.startsWith('{') || text.startsWith('[')) return true;
  if (/<\/?think>/i.test(text)) return true;
  if (/^thinking\b/i.test(text)) return true;
  if (isToolDump(text)) return true;
  if (isEnglishReasoning(text)) return true;
  return false;
}

export function isLeaderFailureBody(body) {
  const text = String(body ?? '').trim();
  if (!text || /NEW_RUN/.test(text)) return false;
  return /^Internal error$/i.test(text) || /^RUN_BLOCKED\b/i.test(text);
}

export function leaderBlockedMessage(room) {
  const msgs = room?.messages ?? [];
  for (let i = 0; i < msgs.length; i += 1) {
    const body = String(msgs[i]?.body ?? '');
    if (msgs[i]?.for_run && isLeaderFailureBody(body)) return body;
    if (msgs[i]?.for_run && /NEW_RUN/.test(body) && isLeaderFailureBody(msgs[i + 1]?.body)) {
      return String(msgs[i + 1].body).trim();
    }
  }
  return '';
}

export function stepIdForSender(sender) {
  const local = senderLocalpart(sender).toLowerCase();
  if (!local) return '';
  if (local.includes('persona')) return 'persona';
  if (local.includes('business')) return 'business';
  if (local.includes('skill')) return 'skill';
  if (local.includes('tool')) return 'tool';
  if (local.includes('blueprint') || local.includes('compose')) return 'blueprint';
  if (local.includes('import')) return 'gate';
  if (local.includes('leader')) return 'leader';
  if (local.includes('intent') || local.includes('match') || local.includes('personalize')) return 'dispatch';
  return '';
}

export function stepIdFromBody(body) {
  const text = String(body ?? '');
  if (/NEW_RUN/.test(text)) return 'dispatch';
  if (/APPROVAL_REQUIRED|WAITING_HUMAN|pending_approval/i.test(text)) return 'gate';
  if (/composeBlueprint|blueprint_check|selfcheck|静态自检/i.test(text)) {
    return /selfcheck|blueprint_check|静态自检/i.test(text) ? 'check' : 'blueprint';
  }
  if (/guidance|业务指导/i.test(text)) return 'guidance';
  if (/@[\w.-]*persona/.test(text)) return 'persona';
  if (/@[\w.-]*business/.test(text)) return 'business';
  if (/@[\w.-]*skill/.test(text)) return 'skill';
  if (/@[\w.-]*tool/.test(text)) return 'tool';
  return '';
}

/**
 * 只留本 run 的关键人话：去掉思考 / JSON / tool dump。
 * 紧随 for_run 的 Internal error / RUN_BLOCKED 一并保留。
 */
export function filterRoomMessages(messages) {
  const rows = Array.isArray(messages) ? messages : [];
  const kept = [];
  for (let i = 0; i < rows.length; i += 1) {
    const row = rows[i];
    const body = String(row?.body ?? '');
    const followError =
      i > 0 && Boolean(rows[i - 1]?.for_run) && isLeaderFailureBody(body);
    if (!row?.for_run && !followError) continue;
    if (isNoiseBody(body) && !isLeaderFailureBody(body) && !/NEW_RUN|APPROVAL_REQUIRED/.test(body)) {
      continue;
    }
    kept.push(row);
  }
  return kept;
}

function hasExpertArtifact(artifacts, expert) {
  return (artifacts ?? []).some(
    (item) =>
      item.kind === 'expert_result' &&
      String(expertRole(item)).toLowerCase().includes(String(expert).replace(/-expert$/, '')),
  );
}

function artifactDone(artifacts, kinds) {
  return kinds.some((kind) => latestArtifact(artifacts, kind));
}

function collectNotes(roomMessages) {
  const notes = Object.fromEntries(BUILD_STEPS.map((step) => [step.id, []]));
  for (const row of filterRoomMessages(roomMessages)) {
    const body = String(row?.body ?? '');
    let stepId = stepIdForSender(row?.sender);
    if (stepId === 'leader' || !stepId) stepId = stepIdFromBody(body) || stepId;
    if (!stepId || stepId === 'leader' || !notes[stepId]) continue;
    const text = clipNote(body);
    if (!text) continue;
    const list = notes[stepId];
    if (list[list.length - 1] === text) continue;
    list.push(text);
    if (list.length > 2) list.splice(0, list.length - 2);
  }
  return notes;
}

function checkFailed(artifacts) {
  const check =
    latestArtifact(artifacts, 'blueprint_check') ?? latestArtifact(artifacts, 'flow_check');
  return check?.payload?.ok === false;
}

/**
 * @param {{ artifacts?: object[], status?: string, phase?: string, mode?: string, roomMessages?: object[] }} input
 * @returns {{ title: string, status: string, steps: object[] }}
 */
export function deriveBuildSteps(input = {}) {
  const artifacts = input.artifacts ?? [];
  const status = String(input.status ?? '');
  const mode = input.mode === 'platform' ? 'platform' : 'local';
  const notes = collectNotes(input.roomMessages ?? []);
  const terminal = ['WAITING_HUMAN', 'SUCCEEDED', 'FAILED', 'ABORTED'].includes(status);
  const failed = ['FAILED', 'ABORTED'].includes(status);
  const gateReady = status === 'WAITING_HUMAN' || status === 'SUCCEEDED';
  const approvalPending =
    latestArtifact(artifacts, 'approval', (item) => item.payload?.status === 'PENDING') ||
    latestArtifact(artifacts, 'approval');

  const laterKinds = ['guidance', 'expert_result', 'blueprint', 'blueprint_check', 'flow_check', 'approval'];
  const dispatched =
    mode === 'local' ||
    /NEW_RUN/.test((input.roomMessages ?? []).map((row) => row?.body).join('\n')) ||
    laterKinds.some((kind) => latestArtifact(artifacts, kind)) ||
    ['DISPATCHED', 'WAITING_HUMAN', 'SUCCEEDED'].includes(status);

  const done = {
    dispatch: dispatched,
    guidance: artifactDone(artifacts, ['guidance']),
    persona: hasExpertArtifact(artifacts, 'persona-expert'),
    business: hasExpertArtifact(artifacts, 'business-expert'),
    skill: hasExpertArtifact(artifacts, 'skill-expert'),
    tool: hasExpertArtifact(artifacts, 'tool-expert'),
    blueprint: artifactDone(artifacts, ['blueprint']),
    check: artifactDone(artifacts, ['blueprint_check', 'flow_check']),
    gate: gateReady || Boolean(approvalPending),
  };

  if (terminal && !failed) {
    for (const step of BUILD_STEPS) done[step.id] = true;
  }

  const expertsUnlocked = done.guidance || EXPERT_IDS.some((id) => done[id]);
  const expertsAllDone = EXPERT_IDS.every((id) => done[id]);

  const states = {};
  for (const step of BUILD_STEPS) {
    if (done[step.id]) states[step.id] = checkFailed(artifacts) && step.id === 'check' ? 'error' : 'done';
    else states[step.id] = 'pending';
  }

  if (!terminal || failed) {
    if (!done.dispatch) states.dispatch = 'running';
    else if (!done.guidance) states.guidance = 'running';
    else if (!expertsAllDone && expertsUnlocked) {
      for (const id of EXPERT_IDS) {
        if (!done[id]) states[id] = 'running';
      }
    } else if (!done.blueprint) states.blueprint = 'running';
    else if (!done.check) states.check = 'running';
    else if (!done.gate) states.gate = 'running';
  }

  if (failed) {
    const running = BUILD_STEPS.find((step) => states[step.id] === 'running');
    if (running) states[running.id] = 'error';
    else {
      const pending = BUILD_STEPS.find((step) => states[step.id] === 'pending');
      if (pending) states[pending.id] = 'error';
    }
  }

  const blockedNote = leaderBlockedMessage({ messages: input.roomMessages });
  if (blockedNote) {
    const running = BUILD_STEPS.find((step) => states[step.id] === 'running') ?? BUILD_STEPS[0];
    states[running.id] = 'error';
    const list = notes[running.id];
    const clipped = clipNote(blockedNote, 120);
    if (clipped && list[list.length - 1] !== clipped) list.push(clipped);
  }

  let title = '构建智能体';
  if (failed) title = '构建失败';
  else if (gateReady) title = '已生成，待确认发布';
  else if (status === 'SUCCEEDED') title = '已生成';

  const steps = BUILD_STEPS.map((step) => ({
    id: step.id,
    label: step.label,
    state: states[step.id],
    notes: notes[step.id] ?? [],
  }));

  return { title, status: status || 'RUNNING', steps };
}

export function emptyBuildProgress(mode = 'local') {
  return deriveBuildSteps({ artifacts: [], status: 'RUNNING', mode, roomMessages: [] });
}
