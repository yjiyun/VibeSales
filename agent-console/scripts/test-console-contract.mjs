import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { consumeSse } from '../src/shared/sse.js';
import { createBuildRun } from '../src/wizard/build-run.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requests = [];
const backend = label => new Promise((resolve, reject) => {
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      const body = Buffer.concat(chunks).toString();
      requests.push({ label, path: request.url, method: request.method, headers: request.headers, body });
      if ((label === 'manager' && request.url.includes('/events')) || (label === 'runtime' && request.url.startsWith('/api/v1/chat'))) {
        response.setHeader('content-type', 'text/event-stream');
        response.end('event: message\r\ndata: {"delta":"ok"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n');
        return;
      }
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ label, path: request.url, run_id: 'run-contract' }));
    });
  });
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => resolve(server));
});
const close = server => new Promise(resolve => server.close(resolve));
const url = server => `http://127.0.0.1:${server.address().port}`;

const servers = await Promise.all(['nest', 'manager', 'runtime'].map(backend));
let vite;
try {
  process.env.NEST_API = url(servers[0]);
  process.env.MANAGER_API = url(servers[1]);
  process.env.RUNTIME_API = url(servers[2]);
  const config = (await import('../vite.config.js?contract=' + Date.now())).default;
  const { createServer } = await import('vite');
  vite = await createServer({ ...config, root, configFile: false, server: { ...config.server, port: 0 } });
  await vite.listen();
  const base = url(vite.httpServer);
  const nativeFetch = globalThis.fetch;
  const probes = [
    ['/api/probe', 'nest', '/api/probe'],
    ['/orchestration/probe', 'manager', '/probe'],
    ['/runtime/probe', 'runtime', '/probe'],
  ];
  for (const [route, label, expectedPath] of probes) {
    const response = await fetch(base + route);
    const body = await response.json();
    if (!response.ok || body.label !== label || body.path !== expectedPath) throw new Error(`proxy ${route} mismatch: ${JSON.stringify(body)}`);
  }

  const storage = new Map();
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  };
  globalThis.fetch = (input, init) => nativeFetch(typeof input === 'string' && input.startsWith('/') ? base + input : input, init);
  const { auth, restoreEnvAuth, saveAuth } = await import('../src/shared/auth.js');
  if (!storage.get('agent-console.boot-fingerprint')) throw new Error('boot fingerprint missing after env hydrate');
  const envWizard = auth.wizardToken;
  auth.wizardToken = 'drawer-override-token';
  saveAuth();
  if (storage.get('agent-console.wizard-token') !== 'drawer-override-token') throw new Error('saveAuth did not persist drawer override');
  restoreEnvAuth();
  if (auth.wizardToken !== envWizard) throw new Error('restoreEnvAuth did not restore this boot env');
  auth.wizardToken = 'wizard-contract-token'; auth.managerToken = 'manager-contract-token';
  auth.pipelineToken = 'pipeline-contract-token'; auth.runtimeToken = 'runtime-contract-token';
  auth.role = 'orchestrator'; auth.actor = '@contract:local';
  const { wizardApi, managerApi, pipelineApi, managerEvents, runtimeChat } = await import('../src/shared/api.js?contract=' + Date.now());
  requests.length = 0;
  await wizardApi.health(); await wizardApi.catalogs(); await wizardApi.createSession({ llm: false });
  await wizardApi.snapshot('session-1'); await wizardApi.answer('session-1', { text: 'hello' });
  await wizardApi.preview('session-1'); await wizardApi.briefTemplate('session-1');
  const wizardRequests = requests.filter(item => item.label === 'nest');
  const wizardPaths = ['/api/health', '/api/catalogs', '/api/wizard/sessions', '/api/wizard/sessions/session-1', '/api/wizard/sessions/session-1/answer', '/api/wizard/sessions/session-1/preview', '/api/wizard/sessions/session-1/template'];
  if (wizardRequests.length !== 7 || wizardRequests.some((item, index) => item.path !== wizardPaths[index])) throw new Error('seven Wizard routes mismatch: ' + JSON.stringify(wizardRequests));
  if (wizardRequests.some(item => item.headers.authorization !== 'Bearer wizard-contract-token' || item.headers['x-role'] !== 'orchestrator' || item.headers['x-actor'] !== '@contract:local')) throw new Error('Wizard credential headers missing');
  if (wizardRequests.some(item => /client_code/.test(item.body))) throw new Error('Wizard UI submitted untrusted client_code');

  await managerApi.create({ client_code: 'acme', spec: 'phase1' });
  await managerApi.room('run-contract');
  await pipelineApi.start({ client_code: 'acme' });
  const managerSse = []; await managerEvents('run-contract', (event, data) => managerSse.push([event, data]));
  const runtimeSse = []; await runtimeChat({ clientCode: 'acme', userId: 'u1', sessionId: 's1', runtimeAgentId: 'a1' }, 'hello', (event, data) => runtimeSse.push([event, data]));
  const managerRequest = requests.find(item => item.label === 'manager' && item.method === 'POST');
  const roomRequest = requests.find(item => item.label === 'manager' && item.path === '/api/v1/orchestrations/run-contract/room');
  const pipelineRequest = requests.find(item => item.label === 'nest' && item.path === '/api/v1/pipeline/start');
  const runtimeRequest = requests.find(item => item.label === 'runtime');
  if (managerRequest?.headers.authorization !== 'Bearer manager-contract-token' || pipelineRequest?.headers.authorization !== 'Bearer pipeline-contract-token' || runtimeRequest?.headers.authorization !== 'Bearer runtime-contract-token') throw new Error('backend-specific bearer token routing failed');
  if (!roomRequest || roomRequest.method !== 'GET' || roomRequest.headers.authorization !== 'Bearer manager-contract-token') throw new Error('manager room route mismatch: ' + JSON.stringify(roomRequest));
  if (managerSse.at(-1)?.[0] !== 'done' || runtimeSse.at(-1)?.[0] !== 'done') throw new Error('manager/runtime SSE did not reach done');

  const phase1 = { gate: 'PASS', client_code: 'acme', triage: { channel: 'wecom', industry: 'beauty', needs_long_term_memory: true, needs_skill_evolution: false }, summary: { industry: { id: 'beauty' }, business_goals: [{ id: 'faq_deflect' }], business_brief: 'brief' } };
  const calls = [];
  const api = { managerApi: { create: async body => (calls.push(['manager', body]), { run_id: 'platform-run' }) }, pipelineApi: { start: async body => (calls.push(['pipeline', body]), { run_id: 'local-run' }) } };
  await createBuildRun(phase1, 'platform', api); await createBuildRun(phase1, 'local', api);
  if (calls[0][0] !== 'manager' || JSON.parse(calls[0][1].spec).phase1_result.client_code !== 'acme') throw new Error('platform CTA did not forward authoritative Phase1Result');
  if (calls[0][1].room_id) throw new Error('platform CTA should omit room_id when VITE_LEADER_ROOM_ID is empty');
  if (calls[1][0] !== 'pipeline' || calls[1][1].industry_id !== 'beauty' || calls[1][1].goal_ids[0] !== 'faq_deflect' || calls[1][1].needs_long_term_memory !== true) throw new Error('local CTA mapping mismatch');
  let rejected = false; try { await createBuildRun({ ...phase1, gate: 'ASK' }, 'platform', api); } catch { rejected = true; }
  if (!rejected) throw new Error('CTA accepted non-PASS Phase1Result');

  const { savePublication, loadPublication, isChatReady, clearPublication } = await import('../src/shared/publication.js?v5=' + Date.now());
  clearPublication();
  if (loadPublication() !== null || isChatReady()) throw new Error('empty publication should not be chat-ready');
  savePublication({ clientCode: 'acme_beauty', runtimeAgentId: 'beauty_wecom_cs-acme_beauty', runId: 'r1', buildPath: 'P3C' });
  if (!isChatReady() || loadPublication().runtimeAgentId !== 'beauty_wecom_cs-acme_beauty') throw new Error('publication bind failed');
  const { fromPipelineGet } = await import('../src/shared/run-snapshot.js?v5=' + Date.now());
  const snap = fromPipelineGet({
    run: { run_id: 'r1', status: 'WAITING_HUMAN', build_path: 'P3C', client_code: 'acme_beauty' },
    artifacts: [
      { kind: 'approval', payload: { status: 'PENDING', approval_id: 'a1' } },
      { kind: 'blueprint', payload: { runtimeAgentId: 'beauty_wecom_cs-acme_beauty', meta: { scenarios: ['beauty_wecom_cs'] }, guidance: { role: '客服' }, skills: [{ name: 'memory' }], tools: { allow: ['memory_search'] } } },
    ],
  });
  if (snap.approvalId !== 'a1' || snap.runtimeAgentId !== 'beauty_wecom_cs-acme_beauty' || !snap.memory) throw new Error('pipeline snapshot mapping mismatch');
  if (snap.artifacts.find(item => item.kind === 'blueprint')?.payload?.runtimeAgentId !== 'beauty_wecom_cs-acme_beauty') throw new Error('pipeline snapshot dropped artifact payload');
  const { extractApprovalId, applyApprovalGate, mentionsRun } = await import('../src/shared/run-snapshot.js?gate=' + Date.now());
  const run = '1b4f918e-5aa7-4147-a1db-7cb98e8ede30';
  if (!mentionsRun('`1b4f918e` (acme_agri) — 等待 Human 审批', run) || mentionsRun('other run', run)) throw new Error('run mention prefix matching failed');
  const approvalId = extractApprovalId([
    { body: '1. `1b4f918e` (acme_agri) — ⏸️ 等待 Human 审批 (approval_id=699838ba-409e-4802-8aea-7b0ceaabbafa)' },
  ], run);
  if (approvalId !== '699838ba-409e-4802-8aea-7b0ceaabbafa') throw new Error('informal approval_id was not extracted: ' + approvalId);
  // Leader 的口头汇报只能当线索（approvalGuess），不能直接冒充权威 status/approvalId——
  // 之前版本会把 status 强改成 WAITING_HUMAN，导致 Leader 编造消息就能点亮发布按钮
  // （实测事故：run aef1e08b，approval_id 本身也是编的），409 之后还会死循环。
  const gated = applyApprovalGate({ runId: run, status: 'DISPATCHED', approvalId: '' }, approvalId);
  if (gated.status !== 'DISPATCHED' || gated.approvalId || gated.approvalGuess !== approvalId) {
    throw new Error('approval gate must only record a guess, not fabricate authoritative status/approvalId');
  }
  // 权威源已经给出 approvalId 时，聊天文本的猜测不应覆盖它。
  const already = applyApprovalGate({ runId: run, status: 'WAITING_HUMAN', approvalId: 'real-1' }, approvalId);
  if (already.approvalId !== 'real-1' || already.approvalGuess) throw new Error('approval gate must not override an authoritative approvalId');
  const { mergePlatformSnapshot, publicationFromSnapshot } = await import('../src/shared/run-snapshot.js?gate2=' + Date.now());
  const merged = mergePlatformSnapshot(
    { run_id: 'r-succ', status: 'DISPATCHED', pending_approvals: [], artifacts: [] },
    {
      run: { run_id: 'r-succ', status: 'SUCCEEDED', current_phase: 'P4', build_path: 'P3C', client_code: 'acme_beauty' },
      artifacts: [
        { kind: 'blueprint', payload: { runtimeAgentId: 'beauty_wecom_cs-acme_beauty', meta: { scenarios: ['beauty_wecom_cs'] }, guidance: { role: '客服' } } },
        { kind: 'import_result', payload: { binding: { client_code: 'acme_beauty', runtime_agent_id: 'beauty_wecom_cs-acme_beauty' } } },
      ],
    },
  );
  if (merged.status !== 'SUCCEEDED' || merged.managerStatus !== 'DISPATCHED' || merged.approvalId) {
    throw new Error('platform merge must prefer Nest SUCCEEDED over manager DISPATCHED: ' + JSON.stringify(merged));
  }
  const pub = publicationFromSnapshot(merged);
  if (pub.clientCode !== 'acme_beauty' || pub.runtimeAgentId !== 'beauty_wecom_cs-acme_beauty') {
    throw new Error('publicationFromSnapshot missed import_result binding: ' + JSON.stringify(pub));
  }
  const { isPublishGateTerminal, leaderBlockedMessage } = await import('../src/shared/build-progress.js?gate=' + Date.now());
  if (isPublishGateTerminal('RUNNING') || isPublishGateTerminal('DISPATCHED')) {
    throw new Error('RUNNING/DISPATCHED must keep waiting for the publish gate');
  }
  if (!isPublishGateTerminal('WAITING_HUMAN') || !isPublishGateTerminal('FAILED')) {
    throw new Error('WAITING_HUMAN/FAILED must stop the publish-gate wait');
  }
  const stalled = leaderBlockedMessage({
    messages: [{ for_run: true, body: 'RUN_BLOCKED mcp timeout' }],
  });
  if (!stalled.startsWith('RUN_BLOCKED')) throw new Error('leaderBlockedMessage missed RUN_BLOCKED');
  const recovered = leaderBlockedMessage({
    messages: [
      { for_run: true, body: 'RUN_BLOCKED mcp timeout' },
      { for_run: true, body: '* REPORT status=BLOCKED_HUMAN' },
      { for_run: true, body: 'APPROVAL_REQUIRED run_id=r1 approval_id=a1' },
    ],
  });
  if (recovered) throw new Error('APPROVAL_REQUIRED must clear a prior RUN_BLOCKED: ' + recovered);

  const events = [];
  await consumeSse(new Response('event: message\r\ndata: {"delta":"ok"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n'), (event, data) => events.push([event, data]));
  if (events.length !== 2 || events[0][0] !== 'message' || events[0][1].delta !== 'ok' || events[1][0] !== 'done') throw new Error('SSE message/done contract failed: ' + JSON.stringify(events));
  process.stdout.write('[PASS] agent-console preserves seven authenticated Wizard calls, dual-mode CTA, three proxies and SSE message/done\n');
} finally {
  if (vite) await vite.close();
  await Promise.all(servers.map(close));
}
