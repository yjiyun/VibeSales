import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'scripts/rotate-higress-llm-key.mjs');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rotate-higress-llm-key-'));
const envFile = path.join(tmp, '.env.local');
const restartMarker = path.join(tmp, 'restarted');
const managerEnv = path.join(tmp, 'agentteams-manager.env');
const state = {
  providers: {
    qwen: {
      name: 'qwen',
      type: 'qwen',
      tokens: ['sk-oldkey-aaaaaaaa'],
      version: 3,
      rawConfigs: { qwenEnableCompatible: true },
    },
  },
  sources: {},
  mutations: [],
  completions: 0,
};

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
    if (url.pathname === '/' && request.method === 'GET') {
      send(response, 200, { ok: true });
      return;
    }
    if (url.pathname === '/session/login' && request.method === 'POST') {
      response.setHeader('set-cookie', '_hi_sess=test-session; HttpOnly; Path=/');
      send(response, 200, { name: 'admin' });
      return;
    }
    if (url.pathname === '/v1/chat/completions' && request.method === 'POST') {
      state.completions += 1;
      if (request.headers.authorization !== 'Bearer test-consumer-token-16') {
        send(response, 401, { error: { message: 'Unauthorized' } });
        return;
      }
      send(response, 200, { choices: [{ message: { content: 'pong' } }] });
      return;
    }
    if (request.headers.cookie !== '_hi_sess=test-session') {
      send(response, 401, { message: 'Login required' });
      return;
    }
    if (url.pathname === '/v1/ai/providers' && request.method === 'PUT') {
      state.mutations.push('PUT /v1/ai/providers');
      send(response, 405, { message: 'Method Not Allowed' });
      return;
    }
    if (url.pathname.startsWith('/v1/ai/providers/') && request.method === 'GET') {
      const name = decodeURIComponent(url.pathname.slice('/v1/ai/providers/'.length));
      const provider = state.providers[name];
      send(response, provider ? 200 : 404, provider ? { data: provider } : { message: 'not found' });
      return;
    }
    if (url.pathname.startsWith('/v1/ai/providers/') && request.method === 'PUT') {
      const name = decodeURIComponent(url.pathname.slice('/v1/ai/providers/'.length));
      state.mutations.push(`PUT /v1/ai/providers/${name}`);
      assert.equal(body.name, name);
      assert.ok(Array.isArray(body.tokens) && body.tokens.length === 1);
      state.providers[name] = { ...state.providers[name], ...body, tokens: body.tokens };
      send(response, 200, { data: state.providers[name] });
      return;
    }
    if (url.pathname.startsWith('/v1/service-sources/') && request.method === 'GET') {
      const name = decodeURIComponent(url.pathname.slice('/v1/service-sources/'.length));
      const source = state.sources[name];
      send(response, source ? 200 : 404, source ? { data: source } : { message: 'not found' });
      return;
    }
    if (url.pathname.startsWith('/v1/service-sources/') && request.method === 'PUT') {
      const name = decodeURIComponent(url.pathname.slice('/v1/service-sources/'.length));
      state.mutations.push(`PUT /v1/service-sources/${name}`);
      assert.equal(body.name, name);
      assert.ok(body.domain);
      state.sources[name] = { ...state.sources[name], ...body };
      send(response, 200, { data: state.sources[name] });
      return;
    }
    if (
      url.pathname.startsWith('/v1/consumers')
      || url.pathname.startsWith('/v1/mcpServer')
      || url.pathname.startsWith('/v1/ai/routes')
    ) {
      state.mutations.push(`${request.method} ${url.pathname}`);
      send(response, 500, { message: 'rotate-key must not touch consumers/mcp/routes' });
      return;
    }
    send(response, 404, { message: 'not found' });
  });
});

await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', resolve);
});
const port = server.address().port;

fs.writeFileSync(envFile, [
  'LOCAL_HIGRESS_CONSOLE_PORT=18011',
  'LOCAL_HIGRESS_GATEWAY_PORT=18081',
  'HIGRESS_ADMIN_USER=admin',
  'HIGRESS_ADMIN_PASSWORD=test-password',
  'HIGRESS_CONSUMER_TOKEN=test-consumer-token-16',
  'HIGRESS_MODEL_PROVIDER=qwen',
  'HIGRESS_DASHSCOPE_API_KEY=sk-oldkey-aaaaaaaa',
  'QWEN_MODEL=qwen-plus',
].join('\n') + '\n');
fs.writeFileSync(managerEnv, 'AGENTTEAMS_LLM_API_KEY=sk-oldkey-aaaaaaaa\n');

const baseEnv = {
  ...process.env,
  HIGRESS_CONSOLE_URL: `http://127.0.0.1:${port}`,
  HIGRESS_GATEWAY_URL: `http://127.0.0.1:${port}`,
  HIGRESS_LLM_API_KEY: 'sk-newkey-bbbbbbbb',
  HIGRESS_RESTART_COMMAND: `printf restarted > '${restartMarker}'`,
  AGENTTEAMS_MANAGER_ENV: managerEnv,
  HIGRESS_SKIP_COMPANION_ENV: '1',
  HIGRESS_SKIP_DOCKER: '1',
};
delete baseEnv.HIGRESS_CONSUMER_TOKEN;
delete baseEnv.RUNTIME_LLM_TOKEN;
delete baseEnv.QWEN_GATEWAY_TOKEN;

function parseReport(text) {
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  assert.ok(start >= 0 && end > start, `report JSON missing: ${text}`);
  return JSON.parse(text.slice(start, end + 1));
}

function run(args, overrides = {}) {
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

const localArgs = ['--target', 'local', '--env', envFile];

try {
  const missingTarget = await run(['--env', envFile]);
  assert.notEqual(missingTarget.status, 0);
  assert.match(missingTarget.stderr, /--target local\|agentteams/);

  const badConfirm = await run([...localArgs, '--apply', '--confirm', 'wrong']);
  assert.notEqual(badConfirm.status, 0);
  assert.match(badConfirm.stderr, /requires --confirm rotate-llm-key/);
  assert.equal(state.mutations.length, 0);

  const missingProvider = await run([...localArgs, '--provider', 'missing']);
  assert.notEqual(missingProvider.status, 0);
  assert.match(missingProvider.stderr, /AI provider missing not found/);
  assert.equal(state.mutations.length, 0);

  const plan = await run(localArgs);
  assert.equal(plan.status, 0, plan.stderr);
  const planJson = parseReport(plan.stdout);
  assert.equal(planJson.mode, 'plan');
  assert.equal(planJson.action, 'update');
  assert.equal(planJson.provider, 'qwen');
  assert.equal(planJson.token.from, 'sk-oldke***');
  assert.equal(planJson.token.to, 'sk-newke***');
  assert.equal(planJson.token.changed, true);
  assert.equal(planJson.endpoint.from, '');
  assert.equal(planJson.endpoint.to, '');
  assert.equal(planJson.endpoint.changed, false);
  assert.equal(planJson.restart.dataplane, 'command');
  assert.equal(planJson.restart.nest, 'skip');
  assert.equal(planJson.restart.runtime, 'skip');
  assert.equal(planJson.restart.manager, 'skip');
  assert.equal(planJson.restart.workers, 'skip');
  assert.equal(state.mutations.length, 0, 'plan mode must not write');
  assert.equal(state.completions, 0, 'plan mode must not probe');
  assert.match(plan.stdout, /\[PLAN\]/);
  assert.doesNotMatch(plan.stdout + plan.stderr, /sk-newkey-bbbbbbbb/);

  const emptyShellToken = await run(localArgs, { HIGRESS_CONSUMER_TOKEN: '' });
  assert.equal(emptyShellToken.status, 0, emptyShellToken.stderr);

  const slimEnv = path.join(tmp, 'slim.env');
  const companionEnv = path.join(tmp, 'companion.env');
  fs.writeFileSync(slimEnv, [
    'HIGRESS_ADMIN_USER=admin',
    'HIGRESS_ADMIN_PASSWORD=test-password',
    'HIGRESS_MODEL_PROVIDER=qwen',
    'HIGRESS_DASHSCOPE_API_KEY=sk-oldkey-aaaaaaaa',
    'QWEN_MODEL=qwen-plus',
  ].join('\n') + '\n');
  fs.writeFileSync(companionEnv, 'RUNTIME_LLM_TOKEN=test-consumer-token-16\n');
  const fromCompanion = await run(['--target', 'local', '--env', slimEnv], {
    HIGRESS_COMPANION_ENV: companionEnv,
  });
  assert.equal(fromCompanion.status, 0, fromCompanion.stderr);

  const apply = await run([...localArgs, '--apply', '--confirm', 'rotate-llm-key']);
  assert.equal(apply.status, 0, apply.stderr);
  assert.deepEqual(state.mutations, ['PUT /v1/ai/providers/qwen']);
  assert.deepEqual(state.providers.qwen.tokens, ['sk-newkey-bbbbbbbb']);
  assert.equal(state.completions, 1);
  assert.match(fs.readFileSync(envFile, 'utf8'), /^HIGRESS_DASHSCOPE_API_KEY=sk-newkey-bbbbbbbb$/m);
  assert.equal(fs.readFileSync(restartMarker, 'utf8'), 'restarted');
  assert.match(apply.stdout, /token updated/);
  assert.match(apply.stdout, /apps not restarted/);
  assert.doesNotMatch(apply.stdout + apply.stderr, /sk-newkey-bbbbbbbb/);

  fs.rmSync(restartMarker, { force: true });
  const idempotent = await run([...localArgs, '--apply', '--confirm', 'rotate-llm-key']);
  assert.equal(idempotent.status, 0, idempotent.stderr);
  assert.equal(parseReport(idempotent.stdout).action, 'unchanged');
  assert.match(idempotent.stdout, /already has this token and endpoint/);
  assert.deepEqual(state.mutations, ['PUT /v1/ai/providers/qwen']);
  assert.equal(fs.existsSync(restartMarker), false, 'unchanged apply must not restart');
  assert.equal(state.completions, 2);

  state.providers['openai-compat'] = {
    name: 'openai-compat',
    type: 'openai',
    tokens: ['sk-oldkey-aaaaaaaa'],
    version: 1,
    rawConfigs: {
      openaiCustomUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      openaiCustomServiceName: 'openai-compat.dns',
    },
  };
  state.sources['openai-compat'] = {
    name: 'openai-compat',
    type: 'dns',
    domain: 'dashscope.aliyuncs.com',
    port: 443,
    protocol: 'https',
    version: '6',
  };
  fs.rmSync(restartMarker, { force: true });
  const agentteams = await run([
    '--target', 'agentteams',
    '--env', envFile,
    '--apply',
    '--confirm', 'rotate-llm-key',
  ]);
  assert.equal(agentteams.status, 0, agentteams.stderr);
  assert.equal(parseReport(agentteams.stdout).provider, 'openai-compat');
  assert.ok(state.mutations.includes('PUT /v1/ai/providers/openai-compat'));
  assert.match(fs.readFileSync(managerEnv, 'utf8'), /^AGENTTEAMS_LLM_API_KEY=sk-newkey-bbbbbbbb$/m);
  assert.doesNotMatch(fs.readFileSync(envFile, 'utf8'), /openai-compat/);

  const badUrl = await run([...localArgs, '--url', 'http://example.com/v1']);
  assert.notEqual(badUrl.status, 0);
  assert.match(badUrl.stderr, /must be HTTPS/);

  const vendorUrl = 'https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1';
  state.sources.qwen = {
    name: 'qwen',
    type: 'dns',
    domain: 'dashscope.aliyuncs.com',
    port: 443,
    protocol: 'https',
    version: '1',
  };
  fs.rmSync(restartMarker, { force: true });
  const urlPlan = await run([...localArgs, '--url', vendorUrl]);
  assert.equal(urlPlan.status, 0, urlPlan.stderr);
  const urlPlanJson = parseReport(urlPlan.stdout);
  assert.equal(urlPlanJson.action, 'update');
  assert.equal(urlPlanJson.token.changed, false);
  assert.equal(urlPlanJson.endpoint.changed, true);
  assert.equal(urlPlanJson.endpoint.to, vendorUrl);
  assert.equal(state.mutations.filter(item => item === 'PUT /v1/ai/providers/qwen').length, 1);

  const urlApply = await run([...localArgs, '--url', vendorUrl, '--apply', '--confirm', 'rotate-llm-key']);
  assert.equal(urlApply.status, 0, urlApply.stderr);
  assert.match(urlApply.stdout, /endpoint updated/);
  assert.equal(state.providers.qwen.type, 'openai');
  assert.equal(state.providers.qwen.rawConfigs.openaiCustomUrl, vendorUrl);
  assert.equal(state.providers.qwen.rawConfigs.apiUrl, vendorUrl);
  assert.equal(state.sources.qwen.domain, 'ws-example.cn-beijing.maas.aliyuncs.com');
  assert.ok(state.mutations.includes('PUT /v1/service-sources/qwen'));
  assert.match(fs.readFileSync(envFile, 'utf8'), new RegExp(`^HIGRESS_OPENAI_API_URL=${vendorUrl.replaceAll('/', '\\/')}$`, 'm'));
  assert.equal(fs.readFileSync(restartMarker, 'utf8'), 'restarted');

  fs.rmSync(restartMarker, { force: true });
  const urlIdempotent = await run([...localArgs, '--url', vendorUrl, '--apply', '--confirm', 'rotate-llm-key']);
  assert.equal(urlIdempotent.status, 0, urlIdempotent.stderr);
  assert.equal(parseReport(urlIdempotent.stdout).action, 'unchanged');
  assert.equal(parseReport(urlIdempotent.stdout).provider, 'qwen');
  assert.equal(fs.existsSync(restartMarker), false);

  state.providers.openai = {
    name: 'openai',
    type: 'openai',
    tokens: ['sk-newkey-bbbbbbbb'],
    version: 1,
    rawConfigs: { openaiCustomUrl: vendorUrl, apiUrl: vendorUrl },
  };
  const namedOpenai = await run([...localArgs, '--url', vendorUrl, '--apply', '--confirm', 'rotate-llm-key']);
  assert.equal(namedOpenai.status, 0, namedOpenai.stderr);
  assert.equal(parseReport(namedOpenai.stdout).provider, 'openai');
  assert.equal(parseReport(namedOpenai.stdout).action, 'unchanged');

  const putsBeforeEnvUrl = state.mutations.length;
  const otherUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1';
  const fromEnvUrl = await run(localArgs, { HIGRESS_LLM_API_URL: otherUrl, HIGRESS_LLM_API_KEY: 'sk-newkey-bbbbbbbb' });
  assert.equal(fromEnvUrl.status, 0, fromEnvUrl.stderr);
  assert.equal(parseReport(fromEnvUrl.stdout).endpoint.to, otherUrl);
  assert.equal(parseReport(fromEnvUrl.stdout).endpoint.changed, true);
  assert.equal(parseReport(fromEnvUrl.stdout).action, 'update');
  assert.equal(state.mutations.length, putsBeforeEnvUrl, 'HIGRESS_LLM_API_URL dry-run must not PUT');

  const compatUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1';
  fs.rmSync(restartMarker, { force: true });
  const agentteamsUrl = await run([
    '--target', 'agentteams',
    '--env', envFile,
    '--url', vendorUrl,
    '--apply',
    '--confirm', 'rotate-llm-key',
  ]);
  assert.equal(agentteamsUrl.status, 0, agentteamsUrl.stderr);
  assert.equal(state.providers['openai-compat'].rawConfigs.openaiCustomUrl, vendorUrl);
  assert.equal(state.sources['openai-compat'].domain, 'ws-example.cn-beijing.maas.aliyuncs.com');
  assert.ok(state.mutations.includes('PUT /v1/service-sources/openai-compat'));
  assert.match(fs.readFileSync(managerEnv, 'utf8'), new RegExp(`^AGENTTEAMS_OPENAI_BASE_URL=${vendorUrl.replaceAll('/', '\\/')}$`, 'm'));

  process.stdout.write('[PASS] rotate-higress-llm-key plan/confirm/named-PUT/persist/dataplane-restart/idempotency/agentteams/redaction/vendor-url\n');
} finally {
  await new Promise(resolve => server.close(resolve));
  fs.rmSync(tmp, { recursive: true, force: true });
}
