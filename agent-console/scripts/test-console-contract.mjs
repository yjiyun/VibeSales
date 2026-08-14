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
  const { auth } = await import('../src/shared/auth.js');
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
  await pipelineApi.start({ client_code: 'acme' });
  const managerSse = []; await managerEvents('run-contract', (event, data) => managerSse.push([event, data]));
  const runtimeSse = []; await runtimeChat({ clientCode: 'acme', userId: 'u1', sessionId: 's1', runtimeAgentId: 'a1' }, 'hello', (event, data) => runtimeSse.push([event, data]));
  const managerRequest = requests.find(item => item.label === 'manager' && item.method === 'POST');
  const pipelineRequest = requests.find(item => item.label === 'nest' && item.path === '/api/v1/pipeline/start');
  const runtimeRequest = requests.find(item => item.label === 'runtime');
  if (managerRequest?.headers.authorization !== 'Bearer manager-contract-token' || pipelineRequest?.headers.authorization !== 'Bearer pipeline-contract-token' || runtimeRequest?.headers.authorization !== 'Bearer runtime-contract-token') throw new Error('backend-specific bearer token routing failed');
  if (managerSse.at(-1)?.[0] !== 'done' || runtimeSse.at(-1)?.[0] !== 'done') throw new Error('manager/runtime SSE did not reach done');

  const phase1 = { gate: 'PASS', client_code: 'acme', triage: { channel: 'wecom', industry: 'beauty', needs_long_term_memory: true, needs_skill_evolution: false }, summary: { industry: { id: 'beauty' }, business_goals: [{ id: 'faq_deflect' }], business_brief: 'brief' } };
  const calls = [];
  const api = { managerApi: { create: async body => (calls.push(['manager', body]), { run_id: 'platform-run' }) }, pipelineApi: { start: async body => (calls.push(['pipeline', body]), { run_id: 'local-run' }) } };
  await createBuildRun(phase1, 'platform', api); await createBuildRun(phase1, 'local', api);
  if (calls[0][0] !== 'manager' || JSON.parse(calls[0][1].spec).phase1_result.client_code !== 'acme') throw new Error('platform CTA did not forward authoritative Phase1Result');
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

  const events = [];
  await consumeSse(new Response('event: message\r\ndata: {"delta":"ok"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n'), (event, data) => events.push([event, data]));
  if (events.length !== 2 || events[0][0] !== 'message' || events[0][1].delta !== 'ok' || events[1][0] !== 'done') throw new Error('SSE message/done contract failed: ' + JSON.stringify(events));
  process.stdout.write('[PASS] agent-console preserves seven authenticated Wizard calls, dual-mode CTA, three proxies and SSE message/done\n');
} finally {
  if (vite) await vite.close();
  await Promise.all(servers.map(close));
}
