#!/usr/bin/env node
'use strict';

const fs = require('fs');
const crypto = require('crypto');

const WORKER_CONSUMERS = [
  'worker-wizard-intent',
  'worker-template-match',
  'worker-template-personalize',
  'worker-flow-generate',
  'worker-blueprint-compose',
  'worker-persona-expert',
  'worker-business-expert',
  'worker-skill-expert',
  'worker-tool-expert',
  'worker-flow-import-run',
];

const args = new Set(process.argv.slice(2));
const mode = args.has('--apply') ? 'apply' : 'plan';
const envFile = process.argv.slice(2).find(value => !value.startsWith('--'));
const confirmIndex = process.argv.indexOf('--confirm');
const confirmation = confirmIndex >= 0 ? process.argv[confirmIndex + 1] : '';

if (!envFile) {
  throw new Error('usage: init-agentteams-worker-consumers.js <integration.env> [--apply --confirm worker-consumers-only]');
}
if (mode === 'apply' && confirmation !== 'worker-consumers-only') {
  throw new Error('apply requires --confirm worker-consumers-only');
}

const parse = file => Object.fromEntries(
  fs.readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const at = line.indexOf('=');
      return [line.slice(0, at), line.slice(at + 1)];
    }),
);

const values = parse(envFile);
const required = key => {
  const value = values[key]?.trim();
  if (!value) throw new Error(key + ' required');
  return value;
};

const consoleUrl = new URL(required('AGENTTEAMS_HIGRESS_CONSOLE_URL'));
const username = required('AGENTTEAMS_ADMIN_USER');
const password = required('AGENTTEAMS_ADMIN_PASSWORD');

let cookie = '';

const data = value => Array.isArray(value) ? value : Array.isArray(value?.data) ? value.data : [];
const summarize = value => ({
  name: value.name,
  credentials: (value.credentials ?? []).map(item => ({
    type: item.type,
    source: item.source,
    values: Array.isArray(item.values) ? item.values.map(() => '<redacted>') : [],
  })),
});

async function request(path, { method = 'GET', body, expect = [200] } = {}) {
  const headers = { accept: 'application/json' };
  if (cookie) headers.cookie = cookie;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(new URL(path, consoleUrl), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  const text = await response.text();
  if (!expect.includes(response.status)) {
    throw new Error(`${method} ${path} failed: HTTP ${response.status} ${text.slice(0, 300)}`);
  }
  return text ? JSON.parse(text) : null;
}

async function login() {
  const response = await fetch(new URL('/session/login', consoleUrl), {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ username, password, autoLogin: false }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`Higress login failed: HTTP ${response.status}`);
  const setCookie = response.headers.get('set-cookie') ?? '';
  cookie = setCookie.split(';', 1)[0];
  if (!cookie.startsWith('_hi_sess=')) throw new Error('Higress login did not return _hi_sess');
}

function classify(existing) {
  if (!existing) return 'create';
  const credentials = existing.credentials ?? [];
  const ok = credentials.length === 1 &&
    credentials[0]?.type === 'key-auth' &&
    credentials[0]?.source === 'BEARER' &&
    Array.isArray(credentials[0]?.values) &&
    credentials[0].values.length === 1 &&
    String(credentials[0].values[0] ?? '').trim().length > 0;
  return ok ? 'unchanged' : 'conflict';
}

function buildConsumer(name) {
  return {
    name,
    version: 0,
    credentials: [{
      type: 'key-auth',
      source: 'BEARER',
      values: [crypto.randomBytes(32).toString('hex')],
    }],
  };
}

async function inspect() {
  const consumersResponse = await request('/v1/consumers');
  const consumers = data(consumersResponse);
  const byName = new Map(consumers.map(item => [item.name, item]));
  return WORKER_CONSUMERS.map(name => {
    const existing = byName.get(name);
    return {
      name,
      action: classify(existing),
      existing: existing ? summarize(existing) : null,
    };
  });
}

function assertNoConflicts(plan) {
  const conflicts = plan.filter(item => item.action === 'conflict').map(item => item.name);
  if (conflicts.length) {
    throw new Error(`refusing writes because existing consumers differ: ${conflicts.join(', ')}`);
  }
}

async function apply(plan) {
  assertNoConflicts(plan);
  for (const item of plan) {
    if (item.action !== 'create') continue;
    await request('/v1/consumers', {
      method: 'POST',
      body: buildConsumer(item.name),
      expect: [200, 201],
    });
  }
  const after = await inspect();
  assertNoConflicts(after);
  if (after.some(item => item.action !== 'unchanged')) {
    throw new Error('worker consumer initialization did not converge');
  }
  return after;
}

(async () => {
  await login();
  const before = await inspect();
  assertNoConflicts(before);
  if (mode === 'plan') {
    process.stdout.write(`${JSON.stringify({
      mode,
      scope: 'create only; existing worker consumers with valid BEARER credentials are untouched',
      consumers: before.map(item => ({ name: item.name, action: item.action })),
    }, null, 2)}\n`);
    return;
  }
  const after = await apply(before);
  process.stdout.write(`${JSON.stringify({
    result: 'applied',
    mode,
    scope: 'create only; existing worker consumers with valid BEARER credentials are untouched',
    consumers: after.map(item => ({ name: item.name, action: item.action })),
  }, null, 2)}\n`);
})().catch(error => {
  process.stderr.write('[FAIL] ' + (error instanceof Error ? error.message : String(error)) + '\n');
  process.exit(1);
});
