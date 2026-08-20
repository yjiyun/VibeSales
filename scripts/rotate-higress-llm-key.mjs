#!/usr/bin/env node
/**
 * Rotate the vendor LLM API key held by Higress (AI Provider tokens only).
 *
 * Nest / Runtime / Manager / Worker keep using the gateway consumer token and
 * are not restarted. Higress data-plane is restarted after apply so wasm/envoy
 * cannot keep serving the previous key.
 *
 * Default is dry-run. Writes require: --apply --confirm rotate-llm-key
 *
 *   HIGRESS_LLM_API_KEY=sk-new node scripts/rotate-higress-llm-key.mjs \
 *     --target local --env deploy/local/.env.local
 *
 *   HIGRESS_LLM_API_KEY=sk-new node scripts/rotate-higress-llm-key.mjs \
 *     --target agentteams --env docs/agentteams/local-development.env.local \
 *     --apply --confirm rotate-llm-key
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONFIRM = 'rotate-llm-key';
const argv = process.argv.slice(2);

function usage() {
  return `usage: node scripts/rotate-higress-llm-key.mjs --target local|agentteams --env <file> [--apply --confirm ${CONFIRM}] [--no-restart] [--no-persist] [--provider <name>] [--key-file <file>]

New key: HIGRESS_LLM_API_KEY, or --key-file, or the env file's HIGRESS_DASHSCOPE_API_KEY / AGENTTEAMS_LLM_API_KEY.
`;
}

function takeFlag(name) {
  const index = argv.indexOf(name);
  if (index < 0) return false;
  argv.splice(index, 1);
  return true;
}

function takeOption(name) {
  const index = argv.indexOf(name);
  if (index < 0) return '';
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`${name} requires a value`);
  argv.splice(index, 2);
  return value;
}

const apply = takeFlag('--apply');
const noRestart = takeFlag('--no-restart');
const noPersist = takeFlag('--no-persist');
const confirm = takeOption('--confirm');
const target = takeOption('--target');
const envFileArg = takeOption('--env');
const providerOverride = takeOption('--provider');
const keyFile = takeOption('--key-file');
if (argv.length) throw new Error(`unexpected arguments: ${argv.join(' ')}\n${usage()}`);
if (target !== 'local' && target !== 'agentteams') {
  throw new Error(`--target local|agentteams is required\n${usage()}`);
}
if (!envFileArg) throw new Error(`--env <file> is required\n${usage()}`);
if (apply && confirm !== CONFIRM) {
  throw new Error(`apply requires --confirm ${CONFIRM}`);
}

const parseEnv = text => Object.fromEntries(
  text
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const at = line.indexOf('=');
      return [line.slice(0, at).trim(), line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2')];
    }),
);

const envFile = path.resolve(envFileArg);
if (!fs.existsSync(envFile)) throw new Error(`env file not found: ${envFile}`);

function loadEnvFile(file) {
  if (!file || !fs.existsSync(file)) return {};
  return parseEnv(fs.readFileSync(file, 'utf8'));
}

function companionEnvFiles() {
  const extra = String(process.env.HIGRESS_COMPANION_ENV || '').trim();
  const skipDefaults = String(process.env.HIGRESS_SKIP_COMPANION_ENV || '').trim() === '1';
  const defaults = skipDefaults
    ? []
    : target === 'agentteams'
      ? [
        path.join(rootDir, 'docs/agentteams/local-development.env.local'),
        path.join(rootDir, 'deploy/agentteams/integration.env'),
      ]
      : [path.join(rootDir, 'deploy/local/.env.local')];
  const extras = extra ? extra.split(path.delimiter) : [];
  return [...defaults, ...extras]
    .map(file => path.resolve(file))
    .filter((file, index, all) => file !== envFile && all.indexOf(file) === index);
}

const envLayers = [...companionEnvFiles().map(loadEnvFile), loadEnvFile(envFile)];
const pick = (key, fallback = '') => {
  const fromProc = String(process.env[key] ?? '').trim();
  if (fromProc) return fromProc;
  for (let index = envLayers.length - 1; index >= 0; index--) {
    const value = String(envLayers[index][key] ?? '').trim();
    if (value) return value;
  }
  return fallback;
};
const required = key => {
  const value = pick(key);
  if (!value) throw new Error(`${key} is required`);
  return value;
};
const optional = (key, fallback = '') => pick(key, fallback);
const redact = value => {
  const text = String(value || '');
  if (!text) return '';
  return text.length <= 8 ? '***' : `${text.slice(0, 8)}***`;
};

function readKeyFile(file) {
  const text = fs.readFileSync(file, 'utf8').trim();
  const first = text.split(/\r?\n/).find(line => line && !line.trim().startsWith('#')) ?? '';
  const line = first.includes('=') ? first.slice(first.indexOf('=') + 1) : first;
  return line.trim();
}

function inspectContainerEnv(name) {
  const raw = execFileSync('docker', ['inspect', '--format', '{{json .Config.Env}}', name], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return Object.fromEntries(JSON.parse(raw).map(line => {
    const at = line.indexOf('=');
    return [line.slice(0, at), line.slice(at + 1)];
  }));
}

function resolveAdmin() {
  const user = optional('HIGRESS_ADMIN_USER') || optional('AGENTTEAMS_ADMIN_USER');
  const password = optional('HIGRESS_ADMIN_PASSWORD') || optional('AGENTTEAMS_ADMIN_PASSWORD');
  if (user && password) return { user, password, source: 'env' };
  if (target !== 'agentteams') {
    throw new Error('HIGRESS_ADMIN_USER and HIGRESS_ADMIN_PASSWORD are required');
  }
  const controller = inspectContainerEnv('agentteams-controller');
  const nextUser = user || String(controller.AGENTTEAMS_ADMIN_USER || '').trim();
  const nextPassword = password || String(controller.AGENTTEAMS_ADMIN_PASSWORD || '').trim();
  if (!nextUser || !nextPassword) {
    throw new Error('Higress admin credentials missing from env and agentteams-controller');
  }
  return { user: nextUser, password: nextPassword, source: 'agentteams-controller' };
}

function resolveProviderName() {
  if (providerOverride) return providerOverride;
  if (optional('HIGRESS_LLM_PROVIDER')) return optional('HIGRESS_LLM_PROVIDER');
  if (target === 'agentteams') return 'openai-compat';
  const providerType = optional('HIGRESS_MODEL_PROVIDER', 'qwen');
  const openaiApiUrl = optional('HIGRESS_OPENAI_API_URL');
  if (openaiApiUrl) return providerType === 'qwen' ? 'openai' : providerType;
  return providerType === 'qwen' ? 'qwen' : providerType;
}

function resolveConsoleUrl() {
  if (optional('HIGRESS_CONSOLE_URL')) return optional('HIGRESS_CONSOLE_URL').replace(/\/$/, '');
  if (optional('AGENTTEAMS_HIGRESS_CONSOLE_URL')) {
    return optional('AGENTTEAMS_HIGRESS_CONSOLE_URL').replace(/\/$/, '');
  }
  if (target === 'local') {
    return `http://127.0.0.1:${required('LOCAL_HIGRESS_CONSOLE_PORT')}`;
  }
  return 'http://127.0.0.1:18001';
}

function resolveGatewayUrl() {
  if (optional('HIGRESS_GATEWAY_URL')) return optional('HIGRESS_GATEWAY_URL').replace(/\/$/, '');
  if (target === 'local') {
    return `http://127.0.0.1:${required('LOCAL_HIGRESS_GATEWAY_PORT')}`;
  }
  return 'http://127.0.0.1:18080';
}

function inspectGatewayKey() {
  for (const [name, key] of [
    ['agentteams-manager', 'AGENTTEAMS_MANAGER_GATEWAY_KEY'],
    ['agentteams-worker-chatflows-leader', 'AGENTTEAMS_WORKER_GATEWAY_KEY'],
  ]) {
    try {
      const value = String(inspectContainerEnv(name)[key] || '').trim();
      if (value) return value;
    } catch {
      // container may be absent in unit tests
    }
  }
  return '';
}

function resolveConsumerToken() {
  if (target === 'agentteams' && pick('HIGRESS_SKIP_DOCKER') !== '1') {
    const gatewayKey = inspectGatewayKey();
    if (gatewayKey) return gatewayKey;
  }
  return optional('HIGRESS_CONSUMER_TOKEN')
    || optional('RUNTIME_LLM_TOKEN')
    || optional('QWEN_GATEWAY_TOKEN');
}

function resolveNewKey() {
  if (optional('HIGRESS_LLM_API_KEY')) return optional('HIGRESS_LLM_API_KEY');
  if (keyFile) return readKeyFile(keyFile);
  if (target === 'local') return optional('HIGRESS_DASHSCOPE_API_KEY');
  return optional('AGENTTEAMS_LLM_API_KEY') || optional('HIGRESS_DASHSCOPE_API_KEY');
}

function persistTargets(newKey) {
  if (noPersist) return [];
  const targets = [];
  if (target === 'local') {
    targets.push({ file: envFile, key: 'HIGRESS_DASHSCOPE_API_KEY', value: newKey });
  } else {
    const managerEnv = optional('AGENTTEAMS_MANAGER_ENV')
      || path.join(os.homedir(), 'agentteams-manager.env');
    if (fs.existsSync(managerEnv)) {
      targets.push({ file: managerEnv, key: 'AGENTTEAMS_LLM_API_KEY', value: newKey });
    }
  }
  return targets;
}

function upsertEnvLine(file, key, value) {
  const original = fs.readFileSync(file, 'utf8');
  const pattern = new RegExp(`^${key}=.*$`, 'm');
  const next = pattern.test(original)
    ? original.replace(pattern, `${key}=${value}`)
    : `${original.replace(/\n+$/, '')}\n${key}=${value}\n`;
  if (next !== original) fs.writeFileSync(file, next.endsWith('\n') ? next : `${next}\n`);
  return next !== original;
}

const consoleUrl = new URL(`${resolveConsoleUrl()}/`);
const gatewayUrl = resolveGatewayUrl();
const providerName = resolveProviderName();
const admin = resolveAdmin();
const newKey = resolveNewKey();
if (!newKey) throw new Error('new key missing: set HIGRESS_LLM_API_KEY, --key-file, or the env-file vendor key');
const consumerToken = resolveConsumerToken();
if (!consumerToken) {
  throw new Error('HIGRESS_CONSUMER_TOKEN or RUNTIME_LLM_TOKEN missing in --env (or companion local-development.env.local / deploy/local/.env.local); do not pass the consumer token on the command line');
}
const probeModel = optional('HIGRESS_PROBE_MODEL')
  || optional('QWEN_MODEL')
  || optional('RUNTIME_MODEL')
  || 'deepseek-v4-flash-0731';
const probeModelName = probeModel.replace(/^dashscope:/, '') || 'deepseek-v4-flash-0731';
const openaiApiUrl = optional('HIGRESS_OPENAI_API_URL');
const persist = persistTargets(newKey);
const restartKind = noRestart ? 'none' : (optional('HIGRESS_RESTART_COMMAND') ? 'command' : target);
let cookie = '';

function unwrap(payload) {
  if (!payload || typeof payload !== 'object') return payload;
  if (payload.data && typeof payload.data === 'object' && !Array.isArray(payload.data)) return payload.data;
  return payload;
}

async function request(url, { method = 'GET', body, headers = {}, expect = [200], timeoutMs = 20_000 } = {}) {
  const nextHeaders = { accept: 'application/json', ...headers };
  if (cookie) nextHeaders.cookie = cookie;
  if (body !== undefined) nextHeaders['content-type'] = 'application/json';
  const response = await fetch(url, {
    method,
    headers: nextHeaders,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  if (!expect.includes(response.status)) {
    throw new Error(`${method} ${url} failed: HTTP ${response.status} ${text.slice(0, 400)}`);
  }
  return { status: response.status, text, json: text ? tryJson(text) : null, headers: response.headers };
}

function tryJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function login({ attempts = 3 } = {}) {
  let lastError = 'Higress login failed';
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await fetch(new URL('/session/login', consoleUrl), {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        body: JSON.stringify({ username: admin.user, password: admin.password, autoLogin: false }),
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok) {
        lastError = `Higress login failed: HTTP ${response.status}`;
      } else {
        const setCookie = response.headers.getSetCookie?.() ?? [];
        const headerCookie = response.headers.get('set-cookie') ?? '';
        const candidates = [...setCookie, ...headerCookie.split(/,(?=\s*[^;]+=)/)]
          .map(item => item.split(';', 1)[0].trim());
        cookie = candidates.find(item => item.startsWith('_hi_sess=')) ?? '';
        if (cookie.startsWith('_hi_sess=')) return;
        lastError = 'Higress login did not return _hi_sess';
      }
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    if (attempt + 1 < attempts) await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(lastError);
}

async function consoleJson(pathname, options = {}) {
  const result = await request(new URL(pathname, consoleUrl), options);
  return result.json;
}

function desiredProvider(existing) {
  const next = { ...existing, tokens: [newKey], version: existing.version ?? 0 };
  if (openaiApiUrl) {
    next.rawConfigs = {
      ...(existing.rawConfigs ?? {}),
      openaiCustomUrl: openaiApiUrl,
      apiUrl: openaiApiUrl,
    };
  }
  return next;
}

function tokensEqual(left, right) {
  return JSON.stringify(left ?? []) === JSON.stringify(right ?? []);
}

async function waitHttp(url, { attempts = 60, expect = [200] } = {}) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
      if (expect.includes(response.status) || response.ok) return;
    } catch {
      // retry
    }
    await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(`timed out waiting for ${url}`);
}

function restartDataplane() {
  const override = optional('HIGRESS_RESTART_COMMAND');
  if (override) {
    execFileSync('sh', ['-lc', override], { stdio: ['ignore', 'pipe', 'pipe'], cwd: rootDir });
    return { kind: 'command', detail: 'HIGRESS_RESTART_COMMAND' };
  }
  if (target === 'local') {
    execFileSync('docker', [
      'compose',
      '--env-file', envFile,
      '-f', path.join(rootDir, 'deploy/local/compose.yaml'),
      'restart', 'higress',
    ], { stdio: ['ignore', 'pipe', 'pipe'], cwd: rootDir });
    return { kind: 'local-compose', detail: 'docker compose restart higress' };
  }
  restartAgentteamsGateway();
  return { kind: 'agentteams', detail: 'rollout restart higress-system gateway' };
}

function restartAgentteamsGateway() {
  const sighup = () => {
    execFileSync('docker', [
      'exec', 'agentteams-controller', 'sh', '-lc',
      'pid=$(ps -eo pid,comm | awk \'$2=="envoy"{print $1; exit}\'); test -n "$pid" && kill -HUP "$pid"',
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
  };
  try {
    const listUrl = 'https://localhost:18443/apis/apps/v1/namespaces/higress-system/deployments';
    const raw = execFileSync('docker', [
      'exec', 'agentteams-controller', 'curl', '-skS', '--fail-with-body', listUrl,
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    const list = JSON.parse(raw);
    const items = (list.items ?? []).filter(item => /higress|gateway/i.test(item.metadata?.name ?? ''));
    if (!items.length) {
      sighup();
      return;
    }
    const restartedAt = new Date().toISOString();
    for (const deployment of items) {
      deployment.spec ??= {};
      deployment.spec.template ??= {};
      deployment.spec.template.metadata ??= {};
      deployment.spec.template.metadata.annotations = {
        ...(deployment.spec.template.metadata.annotations ?? {}),
        'kubectl.kubernetes.io/restartedAt': restartedAt,
      };
      const name = deployment.metadata.name;
      execFileSync('docker', [
        'exec', '-i', 'agentteams-controller', 'curl', '-skS', '--fail-with-body',
        '-X', 'PUT', '-H', 'Content-Type: application/json', '--data-binary', '@-',
        `${listUrl}/${encodeURIComponent(name)}`,
      ], { input: JSON.stringify(deployment), encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    }
  } catch {
    sighup();
  }
}

async function probeGateway() {
  const url = new URL('/v1/chat/completions', `${gatewayUrl}/`);
  let last = '';
  for (let attempt = 0; attempt < 20; attempt++) {
    const accepted = await fetch(url, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${consumerToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: probeModelName,
        messages: [{ role: 'user', content: 'ping' }],
        max_tokens: 8,
      }),
      signal: AbortSignal.timeout(15_000),
    });
    const body = await accepted.text();
    last = `HTTP ${accepted.status} ${body.slice(0, 240)}`;
    if (accepted.ok) return { ok: true, status: accepted.status, warning: '' };
    if ([401, 403, 429].includes(accepted.status) && /invalid_api_key|insufficient_quota|quota|Incorrect API key/i.test(body)) {
      const credential = /invalid_api_key|Incorrect API key/i.test(body);
      return {
        ok: !credential,
        status: accepted.status,
        warning: credential
          ? 'upstream rejected the new API key'
          : 'upstream accepted the route but rejected quota',
      };
    }
    if (![401, 403, 404, 429].includes(accepted.status)) {
      throw new Error(`authorized model probe got unexpected ${last}`);
    }
    await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(`authorized model probe did not become ready: ${last}`);
}

function publicPlan(existing, action) {
  return {
    mode: apply ? 'apply' : 'plan',
    target,
    console: consoleUrl.origin,
    gateway: gatewayUrl,
    provider: providerName,
    action,
    token: {
      from: redact(existing?.tokens?.[0]),
      to: redact(newKey),
      changed: !tokensEqual(existing?.tokens, [newKey]),
    },
    endpoint: openaiApiUrl || existing?.rawConfigs?.openaiCustomUrl || existing?.rawConfigs?.apiUrl || '',
    persist: persist.map(item => ({ file: item.file, key: item.key })),
    restart: {
      dataplane: restartKind,
      nest: 'skip',
      runtime: 'skip',
      manager: 'skip',
      workers: 'skip',
      reason: 'apps hold the Higress consumer token, not the vendor key; restart Higress data-plane so wasm cannot keep the old key',
    },
    probe: { model: probeModelName, consumer: redact(consumerToken) },
  };
}

try {
await login();
const existingResponse = await consoleJson(`/v1/ai/providers/${encodeURIComponent(providerName)}`, { expect: [200, 404] });
const existing = unwrap(existingResponse);
if (!existingResponse || existingResponse?.message === 'not found' || !existing?.name) {
  throw new Error(`AI provider ${providerName} not found on ${consoleUrl.origin}; bootstrap Higress first, this script does not create providers`);
}
const desired = desiredProvider(existing);
const changed = !tokensEqual(existing.tokens, desired.tokens)
  || JSON.stringify(existing.rawConfigs ?? {}) !== JSON.stringify(desired.rawConfigs ?? {});
const action = changed ? 'update' : 'unchanged';
const plan = publicPlan(existing, action);
process.stdout.write(`${JSON.stringify(plan, null, 2)}\n`);

if (!apply) {
  process.stdout.write(`[PLAN] no writes; re-run with --apply --confirm ${CONFIRM}\n`);
  process.exit(0);
}

if (changed) {
  const put = await request(new URL(`/v1/ai/providers/${encodeURIComponent(providerName)}`, consoleUrl), {
    method: 'PUT',
    body: desired,
    expect: [200, 201],
  });
  if (put.status === 405) {
    throw new Error('PUT /v1/ai/providers (collection) is not used; named PUT returned 405');
  }
  for (const item of persist) upsertEnvLine(item.file, item.key, item.value);
}

let restarted = { kind: 'none', detail: 'unchanged, skipped' };
if (changed && restartKind !== 'none') {
  try {
    restarted = restartDataplane();
    await waitHttp(new URL('/', consoleUrl), { expect: [200, 401, 302] });
    cookie = '';
    await login({ attempts: 20 });
  } catch (error) {
    restarted = {
      kind: 'failed',
      detail: error instanceof Error ? error.message.slice(0, 180) : String(error).slice(0, 180),
    };
    process.stderr.write(`[WARN] dataplane restart failed, continuing to probe: ${restarted.detail}\n`);
  }
}

const probe = await probeGateway();
if (!probe.ok) {
  process.stderr.write(`[FAIL] ${probe.warning || 'model probe failed'} (HTTP ${probe.status}); dataplane restart=${restarted.detail}\n`);
  process.exit(2);
}
if (probe.warning) process.stderr.write(`[WARN] ${probe.warning}\n`);
const summary = changed
  ? `[PASS] Higress provider ${providerName} token updated; dataplane ${restarted.detail}; apps not restarted\n`
  : `[PASS] Higress provider ${providerName} already has this token; skipped write and dataplane restart; apps not restarted\n`;
process.stdout.write(summary);
} catch (error) {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
}
