#!/usr/bin/env node

const SERVERS = [
  ['chatflows-p1', 'Chatflows P1 wizard tools'],
  ['chatflows-p2', 'Chatflows P2 template matching tools'],
  ['chatflows-p3', 'Chatflows P3 personalization tools'],
  ['chatflows-p3b', 'Chatflows P3B flow generation tools'],
  ['chatflows-p3c', 'Chatflows P3C blueprint tools'],
  ['chatflows-p4', 'Chatflows P4 import and dry-run tools'],
];
const TRANSIENT_WORKER_CONSUMERS = new Map([
  ['chatflows-p1', ['worker-wizard-intent']],
  ['chatflows-p2', ['worker-template-match']],
  ['chatflows-p3', ['worker-template-personalize']],
  ['chatflows-p3b', ['worker-flow-generate']],
  ['chatflows-p3c', [
    'worker-blueprint-compose', 'worker-persona-expert', 'worker-business-expert',
    'worker-skill-expert', 'worker-tool-expert',
  ]],
  ['chatflows-p4', ['worker-flow-import-run']],
]);

const args = new Set(process.argv.slice(2));
const mode = args.has('--apply') ? 'apply' : args.has('--rollback') ? 'rollback' : 'plan';
const confirmIndex = process.argv.indexOf('--confirm');
const confirmation = confirmIndex >= 0 ? process.argv[confirmIndex + 1] : '';
if (mode !== 'plan' && confirmation !== 'chatflows-mcp-only') {
  throw new Error(`${mode} requires --confirm chatflows-mcp-only`);
}

const required = name => {
  const value = (process.env[name] ?? '').trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const consoleUrl = new URL(required('AGENTTEAMS_HIGRESS_CONSOLE_URL'));
const username = required('AGENTTEAMS_ADMIN_USER');
const password = required('AGENTTEAMS_ADMIN_PASSWORD');
const gatewayToken = required('HIGRESS_CONSUMER_TOKEN');
const backendToken = required('MCP_SERVER_TOKEN');
if (gatewayToken !== backendToken) {
  throw new Error('HIGRESS_CONSUMER_TOKEN and MCP_SERVER_TOKEN must match: Higress Bearer is forwarded to Nest');
}

const sourceName = (process.env.HIGRESS_CHATFLOWS_SOURCE_NAME ?? 'chatflows-nest-local').trim();
const consumerName = (process.env.HIGRESS_CHATFLOWS_CONSUMER_NAME ?? 'chatflows-mcp-local').trim();
const upstream = (process.env.HIGRESS_CHATFLOWS_UPSTREAM ?? '127.0.0.1:13104').trim();
if (!/^[A-Za-z0-9.-]+:\d+$/.test(upstream)) throw new Error('HIGRESS_CHATFLOWS_UPSTREAM must be host:port');

let cookie = '';
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
  if (!expect.includes(response.status)) {
    const text = await response.text();
    throw new Error(`${method} ${path} failed: HTTP ${response.status} ${text.slice(0, 300)}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function login() {
  const response = await fetch(new URL('/session/login', consoleUrl), {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ username, password }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`Higress login failed: HTTP ${response.status}`);
  const setCookie = response.headers.get('set-cookie') ?? '';
  cookie = setCookie.split(';', 1)[0];
  if (!cookie.startsWith('_hi_sess=')) throw new Error('Higress login did not return _hi_sess');
}

const desiredSource = {
  name: sourceName,
  type: 'static',
  domain: upstream,
  port: 80,
  protocol: 'http',
  sni: null,
  proxyName: '',
  properties: { enableMCPServer: false },
  authN: { enabled: false },
};
const desiredConsumer = {
  name: consumerName,
  version: 0,
  credentials: [{ type: 'key-auth', source: 'BEARER', values: [gatewayToken] }],
};
const desiredMcps = SERVERS.map(([name, description]) => ({
  name,
  description: `${description} (managed by chatflows v3 integration)`,
  type: 'DIRECT_ROUTE',
  services: [{ name: `${sourceName}.static`, port: 80, version: '1.0', weight: 100 }],
  consumerAuthInfo: {
    enable: true,
    type: 'key-auth',
    allowedConsumers: [consumerName],
  },
  domains: [],
  directRouteConfig: {
    // Higress exposes /mcp-servers/<name>/mcp and appends the MCP transport
    // suffix to this upstream base path. Including /mcp here duplicates it.
    path: `/mcp-servers/${name}`,
    transportType: 'streamable',
  },
}));

// The first chatflows-mcp-only apply used the public gateway path as the
// upstream base path. Accept only that exact, known state as a safe migration;
// every other same-name difference remains a hard conflict.
const legacyMcps = desiredMcps.map(desired => [
  {
    ...desired,
    consumerAuthInfo: {
      ...desired.consumerAuthInfo,
      allowedConsumers: [consumerName, ...(TRANSIENT_WORKER_CONSUMERS.get(desired.name) ?? [])],
    },
  },
  {
    ...desired,
    directRouteConfig: {
      ...desired.directRouteConfig,
      path: `${desired.directRouteConfig.path}/mcp`,
    },
  },
  {
    ...desired,
    consumerAuthInfo: {
      ...desired.consumerAuthInfo,
      allowedConsumers: [consumerName, ...(TRANSIENT_WORKER_CONSUMERS.get(desired.name) ?? [])],
    },
    directRouteConfig: {
      ...desired.directRouteConfig,
      path: `${desired.directRouteConfig.path}/mcp`,
    },
  },
]);

const data = value => Array.isArray(value) ? value : Array.isArray(value?.data) ? value.data : [];
const eq = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const sourceView = value => value && ({
  name: value.name,
  type: value.type,
  domain: value.domain,
  port: Number(value.port),
  protocol: value.protocol ?? '',
});
const sourceExpectedView = sourceView(desiredSource);
const consumerView = value => value && ({
  name: value.name,
  credentials: (value.credentials ?? []).map(item => ({
    type: item.type,
    source: item.source,
    values: item.values,
  })),
});
const consumerExpectedView = consumerView(desiredConsumer);
const mcpView = value => value && ({
  name: value.name,
  description: value.description,
  type: value.type,
  services: (value.services ?? []).map(service => ({
    name: service.name,
    port: Number(service.port),
    weight: Number(service.weight),
  })),
  consumerAuthInfo: value.consumerAuthInfo && ({
    enable: value.consumerAuthInfo.enable,
    type: value.consumerAuthInfo.type,
    allowedConsumers: value.consumerAuthInfo.allowedConsumers,
  }),
  domains: value.domains ?? [],
  directRouteConfig: value.directRouteConfig,
});

function classify(existing, desired, view, expectedView = view(desired), migrationViews = []) {
  if (!existing) return 'create';
  const existingView = view(existing);
  if (eq(existingView, expectedView)) return 'unchanged';
  return migrationViews.some(migrationView => eq(existingView, migrationView)) ? 'update' : 'conflict';
}

function publicPlan(plan) {
  return {
    mode,
    scope: 'additive only; existing routes and consumers outside these names are untouched',
    upstream,
    serviceSource: { name: sourceName, action: plan.source.action },
    consumer: { name: consumerName, action: plan.consumer.action, credential: '<redacted Bearer>' },
    mcpServers: plan.mcps.map(item => ({
      name: item.desired.name,
      action: item.action,
      gatewayPath: `/mcp-servers/${item.desired.name}/mcp`,
      upstreamBasePath: item.desired.directRouteConfig.path,
      allowedConsumers: item.desired.consumerAuthInfo.allowedConsumers,
    })),
  };
}

async function inspect() {
  const [sourcesResponse, consumersResponse, mcpsResponse] = await Promise.all([
    request('/v1/service-sources'),
    request('/v1/consumers'),
    request('/v1/mcpServer'),
  ]);
  const sources = data(sourcesResponse);
  const consumers = data(consumersResponse);
  const mcps = data(mcpsResponse);
  const sourceExisting = sources.find(item => item.name === sourceName);
  const consumerExisting = consumers.find(item => item.name === consumerName);
  const mcpExisting = new Map(await Promise.all(desiredMcps.map(async desired => {
    const summary = mcps.find(item => item.name === desired.name);
    if (!summary) return [desired.name, undefined];
    const detail = await request(`/v1/mcpServer/${encodeURIComponent(desired.name)}`);
    return [desired.name, detail?.data ?? detail];
  })));
  return {
    source: {
      desired: desiredSource,
      existing: sourceExisting,
      action: classify(sourceExisting, desiredSource, sourceView, sourceExpectedView),
    },
    consumer: {
      desired: desiredConsumer,
      existing: consumerExisting,
      action: classify(consumerExisting, desiredConsumer, consumerView, consumerExpectedView),
    },
    mcps: desiredMcps.map((desired, index) => {
      const existing = mcpExisting.get(desired.name);
      return {
        desired,
        existing,
        action: classify(existing, desired, mcpView, mcpView(desired), legacyMcps[index].map(mcpView)),
      };
    }),
  };
}

function assertNoConflicts(plan) {
  const conflicts = [
    plan.source.action === 'conflict' && `service source ${sourceName}`,
    plan.consumer.action === 'conflict' && `consumer ${consumerName}`,
    ...plan.mcps.filter(item => item.action === 'conflict').map(item => `MCP server ${item.desired.name}`),
  ].filter(Boolean);
  if (conflicts.length) throw new Error(`refusing all writes because existing objects differ: ${conflicts.join(', ')}`);
}

async function apply(plan) {
  assertNoConflicts(plan);
  if (plan.source.action === 'create') {
    await request('/v1/service-sources', { method: 'POST', body: desiredSource, expect: [200, 201] });
  }
  if (plan.consumer.action === 'create') {
    await request('/v1/consumers', { method: 'POST', body: desiredConsumer, expect: [200, 201] });
  }
  for (const item of plan.mcps) {
    if (item.action === 'create' || item.action === 'update') {
      await request('/v1/mcpServer', { method: 'PUT', body: item.desired, expect: [200, 201] });
    }
  }
  const after = await inspect();
  assertNoConflicts(after);
  if ([after.source, after.consumer, ...after.mcps].some(item => item.action !== 'unchanged')) {
    throw new Error('Higress apply did not converge to the requested state');
  }
  return after;
}

async function rollback(plan) {
  // Rollback is intentionally strict: only delete objects that still exactly match this script's desired state.
  assertNoConflicts(plan);
  for (const item of [...plan.mcps].reverse()) {
    if (item.action === 'unchanged') {
      await request(`/v1/mcpServer/${encodeURIComponent(item.desired.name)}`, { method: 'DELETE', expect: [200, 204] });
    }
  }
  if (plan.consumer.action === 'unchanged') {
    await request(`/v1/consumers/${encodeURIComponent(consumerName)}`, { method: 'DELETE', expect: [200, 204] });
  }
  if (plan.source.action === 'unchanged') {
    await request(`/v1/service-sources/${encodeURIComponent(sourceName)}`, { method: 'DELETE', expect: [200, 204] });
  }
}

await login();
const before = await inspect();
if (mode === 'plan') {
  assertNoConflicts(before);
  process.stdout.write(`${JSON.stringify(publicPlan(before), null, 2)}\n`);
} else if (mode === 'apply') {
  const after = await apply(before);
  process.stdout.write(`${JSON.stringify({ result: 'applied', ...publicPlan(after) }, null, 2)}\n`);
} else {
  await rollback(before);
  process.stdout.write(`${JSON.stringify({ result: 'rolled back', names: [sourceName, consumerName, ...SERVERS.map(([name]) => name)] }, null, 2)}\n`);
}
