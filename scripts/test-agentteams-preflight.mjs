import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'scripts/preflight-agentteams-integration.js');
const workers = [
  'chatflows-leader', 'wizard-intent', 'template-match', 'template-personalize',
  'flow-generate', 'blueprint-compose', 'flow-import-run', 'persona-expert',
  'business-expert', 'skill-expert', 'tool-expert',
];
const requests = [];
const objects = new Map();

const server = http.createServer((request, response) => {
  requests.push(`${request.method} ${request.url}`);
  const send = (status, body = {}) => {
    response.writeHead(status, { 'content-type': 'application/json' });
    response.end(request.method === 'HEAD' ? undefined : JSON.stringify(body));
  };
  const url = new URL(request.url ?? '/', 'http://local');
  if (request.method === 'GET' && url.pathname === '/api/v1/workers') {
    send(200, workers.map(name => ({ name })));
    return;
  }
  if (request.method === 'GET' && url.pathname.startsWith('/api/v1/workers/')) {
    const name = decodeURIComponent(url.pathname.slice('/api/v1/workers/'.length));
    send(200, {
      name,
      phase: 'Pending',
      containerState: 'stopped',
      matrixUserID: `@${name}:matrix.test`,
    });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/teams/chatflows-build-team') {
    send(200, {
      name: 'chatflows-build-team',
      teamRoomID: '!chatflows:matrix.test',
      leaderReady: false,
      readyWorkers: 0,
      totalWorkers: 10,
    });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/_matrix/client/versions') {
    send(200, { versions: ['v1.11'] });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/_matrix/client/v3/account/whoami') {
    send(200, { user_id: '@manager:matrix.test' });
    return;
  }
  if (request.method === 'GET' && url.pathname === '/healthz') {
    send(200, { status: 'ok' });
    return;
  }
  if (request.method === 'POST' && /^\/mcp-servers\/chatflows-p(?:1|2|3|3b|3c|4)\/mcp$/.test(url.pathname)) {
    send(201, { jsonrpc: '2.0', id: 1, result: { tools: [{ name: 'test-tool' }] } });
    return;
  }
  // MinIO first resolves the bucket region, then performs the existence check.
  if (request.method === 'GET' && url.pathname === '/agentteams-storage' && url.search === '?location') {
    response.writeHead(200, { 'content-type': 'application/xml' });
    response.end('<LocationConstraint xmlns="http://s3.amazonaws.com/doc/2006-03-01/">us-east-1</LocationConstraint>');
    return;
  }
  if (request.method === 'HEAD' && url.pathname === '/agentteams-storage') {
    send(200);
    return;
  }
  // Manager 任务目录身份要真实 put/get/delete 一次对象，所以 stub 要有最小对象存储，
  // 且只放行固定 prefix 之下的 key —— 与 configure-chatflows-task-storage.js 的 policy 同口径。
  const objectMatch = /^\/agentteams-storage\/(teams\/chatflows-build-team\/shared\/tasks\/.+)$/.exec(url.pathname);
  if (objectMatch) {
    const key = decodeURIComponent(objectMatch[1]);
    if (request.method === 'PUT') {
      const chunks = [];
      request.on('data', chunk => chunks.push(chunk));
      request.once('end', () => {
        objects.set(key, Buffer.concat(chunks));
        response.writeHead(200, { etag: '"stub"' }).end();
      });
      return;
    }
    if (request.method === 'GET') {
      const body = objects.get(key);
      if (!body) { send(404, { message: 'no such key' }); return; }
      response.writeHead(200, { 'content-type': 'application/json', 'content-length': String(body.length) });
      response.end(body);
      return;
    }
    if (request.method === 'DELETE') {
      objects.delete(key);
      response.writeHead(204).end();
      return;
    }
  }
  send(404, { message: 'not found' });
});

await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', resolve);
});
const endpoint = `http://127.0.0.1:${server.address().port}`;
const env = {
  ...process.env,
  AGENTTEAMS_CONTROLLER_URL: endpoint,
  AGENTTEAMS_AUTH_TOKEN: 'controller-test-token',
  AGENTTEAMS_MATRIX_URL: endpoint,
  AGENTTEAMS_MATRIX_USER_ID: '@manager:matrix.test',
  AGENTTEAMS_MATRIX_ACCESS_TOKEN: 'matrix-test-token',
  AGENTTEAMS_HUMAN_IDS: '@human:matrix.test',
  AGENTTEAMS_MANAGER_IDS: '@manager:matrix.test',
  AGENTTEAMS_LEADER_IDS: '@chatflows-leader:matrix.test',
  CHATFLOWS_MCP_BASE_URL: endpoint,
  HIGRESS_CONSUMER_TOKEN: 'gateway-test-token-at-least-16',
  CHATFLOWS_NEST_URL: endpoint,
  // 平台 admin 身份（REST apply 投影 Skill）
  AGENTTEAMS_FS_ENDPOINT: endpoint,
  AGENTTEAMS_FS_ACCESS_KEY: 'test-access',
  AGENTTEAMS_FS_SECRET_KEY: 'test-secret',
  AGENTTEAMS_FS_BUCKET: 'agentteams-storage',
  // Manager 任务目录身份（ManagerConfig 读这五项），与上面是两套凭证
  CHATFLOWS_TASK_FS_ENDPOINT: endpoint,
  CHATFLOWS_TASK_FS_ACCESS_KEY: 'chatflows-task-manager',
  CHATFLOWS_TASK_FS_SECRET_KEY: 'task-secret',
  CHATFLOWS_TASK_FS_BUCKET: 'agentteams-storage',
  CHATFLOWS_TASK_FS_PREFIX: 'teams/chatflows-build-team/shared/tasks',
  AGENTLOOP_EXPORTER: 'off',
};
for (const key of [
  'AGENT_RUNTIME_URL', 'AGENT_RUNTIME_TOKEN', 'RUNTIME_AUTH_TOKEN', 'RUNTIME_ADMIN_TOKEN',
  'MANAGER_AUTH_TOKEN', 'MANAGER_ADMIN_TOKEN', 'RUNTIME_LLM_BASE_URL', 'RUNTIME_LLM_TOKEN',
  'DATABASE_URL', 'AGENT_RUNTIME_DATABASE_URL', 'REDIS_URL',
  'CHATFLOWS_APPROVAL_SIGNING_SECRET', 'PIPELINE_APPROVAL_SIGNING_SECRET',
  'AGENTTEAMS_E2E_HUMAN_USER_ID',
]) delete env[key];

function runWith(args, overrides = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script, ...args], {
      cwd: root,
      env: { ...env, ...overrides },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', status => resolve({ status, stdout, stderr }));
  });
}
const run = args => runWith(args);

try {
  const localDevelopment = await run(['--infra-only']);
  assert.equal(localDevelopment.status, 0, `${localDevelopment.stderr}\nstdout:\n${localDevelopment.stdout}\nrequests:\n${requests.join('\n')}`);
  assert.match(localDevelopment.stdout, /local-development platform preflight complete/);
  assert.match(localDevelopment.stdout, /dedicated fixed-Team MinIO object-key read\/write\/delete/);
  assert.doesNotMatch(localDevelopment.stdout + localDevelopment.stderr, /AGENT_RUNTIME_URL required|DATABASE_URL required|REDIS_URL required/);
  assert.equal(objects.size, 0, '任务目录健康探测必须删掉自己写的对象');

  const full = await run([]);
  assert.notEqual(full.status, 0);
  assert.match(full.stderr, /Chatflows Workers are not running: .*stopped/);
  assert.doesNotMatch(full.stderr, /AGENT_RUNTIME_URL required|DATABASE_URL required|REDIS_URL required/);

  // 任务目录 prefix 是 TeamHarness 的固定 object-key 前缀，改了必须早失败。
  const driftedPrefix = await runWith(['--infra-only'], { CHATFLOWS_TASK_FS_PREFIX: 'teams/other-team/shared/tasks' });
  assert.notEqual(driftedPrefix.status, 0);
  assert.match(driftedPrefix.stderr, /CHATFLOWS_TASK_FS_PREFIX must be the fixed TeamHarness object-key prefix/);

  // A22：AGENTLOOP_EXPORTER 只能是 off/stderr/on；on 时必须给上报凭证。
  const badExporter = await runWith(['--infra-only'], { AGENTLOOP_EXPORTER: 'yes' });
  assert.notEqual(badExporter.status, 0);
  assert.match(badExporter.stderr, /AGENTLOOP_EXPORTER must be off, stderr or on/);
  const exporterOn = await runWith(['--infra-only'], { AGENTLOOP_EXPORTER: 'on' });
  assert.notEqual(exporterOn.status, 0);
  assert.match(exporterOn.stderr, /AGENTLOOP_ENDPOINT required/);
  process.stdout.write('[PASS] preflight separates local-development infrastructure from full Runtime/PG/Redis, verifies the fixed-Team task object-key path, the AgentLoop exporter contract and rejects stopped Workers first\n');
} finally {
  await new Promise(resolve => server.close(resolve));
}
