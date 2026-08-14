import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'scripts/configure-higress-chatflows-mcp.mjs');
const names = ['chatflows-p1', 'chatflows-p2', 'chatflows-p3', 'chatflows-p3b', 'chatflows-p3c', 'chatflows-p4'];
const routeName = name => `mcp-server-${name}.internal`;
const state = { sources: [], consumers: [], mcps: [], routes: [], mutations: [] };

function send(response, status, body = {}) {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

const server = http.createServer((request, response) => {
  let raw = '';
  request.on('data', chunk => { raw += chunk; });
  request.on('end', () => {
    const body = raw ? JSON.parse(raw) : undefined;
    const url = new URL(request.url ?? '/', 'http://local');
    if (url.pathname === '/session/login' && request.method === 'POST') {
      response.setHeader('set-cookie', '_hi_sess=test-session; HttpOnly; Path=/');
      send(response, 200, { name: 'admin' });
      return;
    }
    if (request.headers.cookie !== '_hi_sess=test-session') {
      send(response, 401, { message: 'Login required' });
      return;
    }
    const collections = {
      '/v1/service-sources': state.sources,
      '/v1/consumers': state.consumers,
      '/v1/mcpServer': state.mcps,
      '/v1/routes': state.routes,
    };
    const collection = collections[url.pathname];
    if (request.method === 'GET' && collection) {
      send(response, 200, { success: true, total: collection.length, data: collection });
      return;
    }
    if (request.method === 'GET' && url.pathname.startsWith('/v1/mcpServer/')) {
      const name = decodeURIComponent(url.pathname.slice('/v1/mcpServer/'.length));
      const item = state.mcps.find(candidate => candidate.name === name);
      send(response, item ? 200 : 404, item ? { success: true, data: item } : { message: 'not found' });
      return;
    }
    if (request.method === 'GET' && url.pathname.startsWith('/v1/routes/')) {
      const name = decodeURIComponent(url.pathname.slice('/v1/routes/'.length));
      const item = state.routes.find(candidate => candidate.name === name);
      send(response, item ? 200 : 404, item ? { success: true, data: item } : { message: 'not found' });
      return;
    }
    if (request.method === 'POST' && collection) {
      collection.push(structuredClone(body));
      state.mutations.push(`${request.method} ${url.pathname}`);
      send(response, 201, body);
      return;
    }
    if (request.method === 'PUT' && url.pathname === '/v1/mcpServer') {
      // Mimic harmless backend normalization: MCP service versions are omitted on read.
      const normalized = structuredClone(body);
      normalized.services = normalized.services.map(({ version: _version, ...service }) => service);
      const existing = state.mcps.findIndex(item => item.name === normalized.name);
      if (existing >= 0) state.mcps[existing] = normalized;
      else state.mcps.push(normalized);
      const route = state.routes.find(item => item.name === routeName(normalized.name));
      if (route) route.headerControl = null;
      else state.routes.push({ name: routeName(normalized.name), headerControl: null });
      state.mutations.push(`${request.method} ${url.pathname} ${body.name}`);
      send(response, 200, normalized);
      return;
    }
    if (request.method === 'PUT' && url.pathname.startsWith('/v1/routes/')) {
      const name = decodeURIComponent(url.pathname.slice('/v1/routes/'.length));
      const index = state.routes.findIndex(item => item.name === name);
      if (index >= 0) state.routes[index] = structuredClone(body);
      else state.routes.push(structuredClone(body));
      state.mutations.push(`${request.method} ${url.pathname}`);
      send(response, 200, body);
      return;
    }
    if (request.method === 'DELETE') {
      const specs = [
        ['/v1/mcpServer/', state.mcps],
        ['/v1/consumers/', state.consumers],
        ['/v1/service-sources/', state.sources],
      ];
      const spec = specs.find(([prefix]) => url.pathname.startsWith(prefix));
      if (spec) {
        const [prefix, items] = spec;
        const name = decodeURIComponent(url.pathname.slice(prefix.length));
        const index = items.findIndex(item => item.name === name);
        if (index >= 0) items.splice(index, 1);
        if (prefix === '/v1/mcpServer/') {
          const routeIndex = state.routes.findIndex(item => item.name === routeName(name));
          if (routeIndex >= 0) state.routes.splice(routeIndex, 1);
        }
        state.mutations.push(`${request.method} ${url.pathname}`);
        send(response, 200, { success: true });
        return;
      }
    }
    send(response, 404, { message: 'not found' });
  });
});

await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', resolve);
});

const port = server.address().port;
const baseEnv = {
  ...process.env,
  AGENTTEAMS_HIGRESS_CONSOLE_URL: `http://127.0.0.1:${port}`,
  AGENTTEAMS_ADMIN_USER: 'admin',
  AGENTTEAMS_ADMIN_PASSWORD: 'test-password',
  // Higress 把 Consumer Bearer 原样转发给 Nest，两者必须同值（§2.6.4 Higress 权威方案）。
  HIGRESS_CONSUMER_TOKEN: 'test-shared-bearer-at-least-16',
  MCP_SERVER_TOKEN: 'test-shared-bearer-at-least-16',
};

function run(args = [], overrides = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script, ...args], {
      cwd: root,
      env: { ...baseEnv, ...overrides },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', status => resolve({ status, stdout, stderr }));
  });
}

try {
  const plan = await run();
  assert.equal(state.mutations.length, 0, 'plan mode mutated state');
  assert.equal(plan.status, 0, plan.stderr);
  assert.deepEqual(JSON.parse(plan.stdout).mcpServers.map(item => item.action), Array(6).fill('create'));

  const splitToken = await run([], { MCP_SERVER_TOKEN: 'test-backend-bearer-at-least-16' });
  assert.notEqual(splitToken.status, 0);
  assert.match(splitToken.stderr, /HIGRESS_CONSUMER_TOKEN and MCP_SERVER_TOKEN must match/);
  assert.equal(state.mutations.length, 0, 'split token mutated state');

  const badConfirmation = await run(['--apply', '--confirm', 'wrong']);
  assert.notEqual(badConfirmation.status, 0);
  assert.match(badConfirmation.stderr, /requires --confirm chatflows-mcp-only/);
  assert.equal(state.mutations.length, 0, 'bad confirmation mutated state');

  state.sources.push({ name: 'chatflows-nest-local', type: 'static', domain: 'wrong:1', port: 80, protocol: 'http' });
  const conflict = await run(['--apply', '--confirm', 'chatflows-mcp-only']);
  assert.notEqual(conflict.status, 0);
  assert.match(conflict.stderr, /refusing all writes.*service source/);
  assert.equal(state.mutations.length, 0, 'conflict did not reject atomically');
  state.sources.length = 0;

  const apply = await run(['--apply', '--confirm', 'chatflows-mcp-only']);
  assert.equal(apply.status, 0, apply.stderr);
  assert.equal(state.sources.length, 1);
  assert.equal(state.consumers.length, 1);
  assert.deepEqual(state.mcps.map(item => item.name), names);
  // 六个 MCP 只绑共享 Consumer；瞬时 Worker consumer 由独立凭证脚本管理，不写进 server 声明。
  assert.deepEqual(
    state.mcps.map(item => item.consumerAuthInfo.allowedConsumers),
    Array(6).fill(['chatflows-mcp-local']),
  );
  assert.deepEqual(state.mcps.map(item => item.directRouteConfig.path), names.map(name => `/mcp-servers/${name}`));
  // 两个 token 同值后不再需要改写 Authorization：脚本完全不写生成路由，headerControl 保持后端原样。
  assert.ok(state.routes.every(item => item.headerControl == null));
  assert.deepEqual(state.mutations, [
    'POST /v1/service-sources',
    'POST /v1/consumers',
    ...names.map(name => `PUT /v1/mcpServer ${name}`),
  ]);

  state.mutations.length = 0;
  const idempotent = await run(['--apply', '--confirm', 'chatflows-mcp-only']);
  assert.equal(idempotent.status, 0, idempotent.stderr);
  assert.equal(state.mutations.length, 0, 'idempotent apply emitted writes');

  state.mcps[0].directRouteConfig.path += '/mcp';
  const migration = await run(['--apply', '--confirm', 'chatflows-mcp-only']);
  assert.equal(migration.status, 0, migration.stderr);
  assert.deepEqual(state.mutations, ['PUT /v1/mcpServer chatflows-p1']);
  assert.equal(state.mcps[0].directRouteConfig.path, '/mcp-servers/chatflows-p1');

  // 历史态把瞬时 Worker consumer 写进了 server 声明：识别为可迁移，规整回只绑共享 Consumer，而非硬冲突。
  state.mutations.length = 0;
  state.mcps[0].consumerAuthInfo.allowedConsumers = ['chatflows-mcp-local', 'worker-wizard-intent'];
  const workerConsumerMigration = await run(['--apply', '--confirm', 'chatflows-mcp-only']);
  assert.equal(workerConsumerMigration.status, 0, workerConsumerMigration.stderr);
  assert.deepEqual(state.mutations, ['PUT /v1/mcpServer chatflows-p1']);
  assert.deepEqual(state.mcps[0].consumerAuthInfo.allowedConsumers, ['chatflows-mcp-local']);

  state.mutations.length = 0;
  const rollback = await run(['--rollback', '--confirm', 'chatflows-mcp-only']);
  assert.equal(rollback.status, 0, rollback.stderr);
  assert.deepEqual([state.sources.length, state.consumers.length, state.mcps.length, state.routes.length], [0, 0, 0, 0]);
  assert.deepEqual(state.mutations, [
    ...[...names].reverse().map(name => `DELETE /v1/mcpServer/${name}`),
    'DELETE /v1/consumers/chatflows-mcp-local',
    'DELETE /v1/service-sources/chatflows-nest-local',
  ]);

  process.stdout.write('[PASS] Higress Chatflows MCP shared-consumer auth/token equality/plan/apply/conflict/idempotency/migration/rollback guards\n');
} finally {
  await new Promise(resolve => server.close(resolve));
}
