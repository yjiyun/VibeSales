#!/usr/bin/env node

import fs from 'node:fs';

const envFile = process.argv[2];
if (!envFile) throw new Error('usage: node scripts/configure-local-higress.mjs <env-file>');

const parseEnv = text => Object.fromEntries(
  text
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const at = line.indexOf('=');
      return [line.slice(0, at), line.slice(at + 1)];
    }),
);

const env = { ...process.env, ...parseEnv(fs.readFileSync(envFile, 'utf8')) };
const required = key => {
  const value = String(env[key] || '').trim();
  if (!value) throw new Error(`${key} is required`);
  return value;
};

const consoleUrl = new URL(`http://127.0.0.1:${required('LOCAL_HIGRESS_CONSOLE_PORT')}`);
const gatewayPort = required('LOCAL_HIGRESS_GATEWAY_PORT');
const adminUser = required('HIGRESS_ADMIN_USER');
const adminPassword = required('HIGRESS_ADMIN_PASSWORD');
const consumerName = required('HIGRESS_GATEWAY_CONSUMER');
const consumerToken = required('HIGRESS_CONSUMER_TOKEN');
const providerType = required('HIGRESS_MODEL_PROVIDER');
const dashscopeKey = required('HIGRESS_DASHSCOPE_API_KEY');
const openaiApiUrl = String(env.HIGRESS_OPENAI_API_URL || '').trim();
const modelPatterns = String(env.HIGRESS_QWEN_MODELS || '*').trim();
const probeModel = String(env.QWEN_MODEL || env.RUNTIME_MODEL || 'deepseek-v4-flash-0731')
  .replace(/^dashscope:/, '')
  .trim() || 'deepseek-v4-flash-0731';
const providerName = openaiApiUrl
  ? (providerType === 'qwen' ? 'openai' : providerType)
  : (providerType === 'qwen' ? 'qwen' : providerType);

let cookie = '';
const data = value => Array.isArray(value) ? value : Array.isArray(value?.data) ? value.data : [];

async function request(path, { method = 'GET', body, expect = [200] } = {}) {
  const headers = { accept: 'application/json' };
  if (cookie) headers.cookie = cookie;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(new URL(path, consoleUrl), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20_000),
  });
  if (!expect.includes(response.status)) {
    const text = await response.text();
    throw new Error(`${method} ${path} failed: HTTP ${response.status} ${text.slice(0, 400)}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function initAndLogin() {
  await fetch(new URL('/system/init', consoleUrl), {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ adminUser: { name: adminUser, password: adminPassword, displayName: adminUser } }),
    signal: AbortSignal.timeout(20_000),
  });

  const login = async (username, password) => {
    const response = await fetch(new URL('/session/login', consoleUrl), {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ username, password }),
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error(`Higress login failed: HTTP ${response.status}`);
    const setCookie = response.headers.get('set-cookie') ?? '';
    cookie = setCookie.split(';', 1)[0];
    if (!cookie.startsWith('_hi_sess=')) throw new Error('Higress login did not return _hi_sess');
  };

  try {
    await login(adminUser, adminPassword);
  } catch (error) {
    if (adminUser !== 'admin' || adminPassword === 'admin') throw error;
    await login('admin', 'admin');
    const current = await request('/v1/users/admin');
    const body = current?.data ?? current ?? { name: 'admin', displayName: 'admin' };
    body.password = adminPassword;
    await request('/v1/users/admin', { method: 'PUT', body, expect: [200, 201] });
    cookie = '';
    await login(adminUser, adminPassword);
  }
}

function eq(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

async function upsertConsumer() {
  const desired = {
    name: consumerName,
    credentials: [{ type: 'key-auth', source: 'BEARER', values: [consumerToken] }],
  };
  const existing = (await request('/v1/consumers')).data?.find?.(item => item.name === consumerName);
  if (!existing) return request('/v1/consumers', { method: 'POST', body: desired, expect: [200, 201] });
  if (!eq(existing.credentials, desired.credentials)) {
    return request(`/v1/consumers/${encodeURIComponent(consumerName)}`, { method: 'PUT', body: desired, expect: [200, 201] });
  }
}

async function upsertProvider() {
  const desired = openaiApiUrl || providerType !== 'qwen'
    ? {
        type: 'openai',
        name: providerName,
        tokens: [dashscopeKey],
        protocol: 'openai/v1',
        modelMapping: {},
        rawConfigs: {
          openaiCustomUrl: openaiApiUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
          apiUrl: openaiApiUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
          protocol: 'openai',
          chatflowsLocal: true,
        },
      }
    : {
        type: 'qwen',
        name: 'qwen',
        tokens: [dashscopeKey],
        protocol: 'openai/v1',
        tokenFailoverConfig: { enabled: false },
        rawConfigs: { qwenEnableSearch: false, qwenEnableCompatible: true, qwenFileIds: [], chatflowsLocal: true },
      };
  const name = desired.name;
  const existingResponse = await request(`/v1/ai/providers/${encodeURIComponent(name)}`, { expect: [200, 404] });
  const existing = existingResponse?.data ?? existingResponse;
  if (!existingResponse || existingResponse?.message === 'not found' || !existing) {
    return request('/v1/ai/providers', { method: 'POST', body: desired, expect: [200, 201] });
  }
  const next = { ...existing, ...desired, version: existing.version ?? 0 };
  if (!eq(existing.tokens, desired.tokens) || !eq(existing.rawConfigs, desired.rawConfigs)) {
    return request(`/v1/ai/providers/${encodeURIComponent(name)}`, { method: 'PUT', body: next, expect: [200, 201] });
  }
}

async function upsertAiRoute() {
  const desired = {
    name: 'default-ai-route',
    domains: [],
    pathPredicate: { matchType: 'PRE', matchValue: '/', caseSensitive: false },
    upstreams: [{ provider: providerName, weight: 100, modelMapping: {} }],
    modelPredicates: [],
    authConfig: {
      enabled: false,
      allowedCredentialTypes: [],
      allowedConsumers: [],
    },
    headerControl: {
      enabled: true,
      request: { add: [{ key: 'user-agent', value: 'chatflows-local-dev' }], set: [], remove: [] },
      response: { add: [], set: [], remove: [] },
    },
  };
  const existingResponse = await request('/v1/ai/routes/default-ai-route', { expect: [200, 404] });
  const existing = existingResponse?.data ?? existingResponse;
  if (!existingResponse || existingResponse?.message === 'not found' || !existing) {
    return request('/v1/ai/routes', { method: 'POST', body: desired, expect: [200, 201] });
  }
  const next = { ...existing, ...desired, version: existing.version ?? 0 };
  return request('/v1/ai/routes/default-ai-route', { method: 'PUT', body: next, expect: [200, 201] });
}

async function upsertMcp() {
  const sourceName = 'chatflows-local-business-tools';
  const desiredSource = {
    name: sourceName,
    type: 'dns',
    domain: 'local-business-mcp.chatflows.local',
    port: 3200,
    protocol: 'http',
    sni: null,
    proxyName: '',
    properties: { enableMCPServer: false },
    authN: { enabled: false },
  };
  const desiredServer = {
    name: 'business-tools',
    description: 'Local business tools MCP for chatflows true-model loop',
    type: 'DIRECT_ROUTE',
    services: [{ name: `${sourceName}.dns`, port: 3200, version: '1.0', weight: 100 }],
    consumerAuthInfo: {
      enable: true,
      type: 'key-auth',
      allowedConsumers: [consumerName],
    },
    domains: [],
    directRouteConfig: {
      path: '/mcp-servers/business-tools/mcp',
      transportType: 'streamable',
    },
  };

  const sources = data(await request('/v1/service-sources'));
  const existingSource = sources.find(item => item.name === sourceName);
  if (!existingSource) {
    await request('/v1/service-sources', { method: 'POST', body: desiredSource, expect: [200, 201] });
  } else if (!eq({
    name: existingSource.name,
    type: existingSource.type,
    domain: existingSource.domain,
    port: Number(existingSource.port),
    protocol: existingSource.protocol,
  }, {
    name: desiredSource.name,
    type: desiredSource.type,
    domain: desiredSource.domain,
    port: Number(desiredSource.port),
    protocol: desiredSource.protocol,
  })) {
    await request(`/v1/service-sources/${encodeURIComponent(sourceName)}`, { method: 'PUT', body: desiredSource, expect: [200, 201] });
  }

  const mcpResponse = await request('/v1/mcpServer/business-tools', { expect: [200, 404, 502] });
  const existingMcp = mcpResponse?.data ?? mcpResponse;
  if (
    !mcpResponse ||
    mcpResponse?.message === 'not found' ||
    /NotFoundException/i.test(String(mcpResponse?.message || '')) ||
    !existingMcp
  ) {
    await request('/v1/mcpServer', { method: 'PUT', body: desiredServer, expect: [200, 201] });
  } else {
    const next = { ...existingMcp, ...desiredServer };
    await request('/v1/mcpServer', { method: 'PUT', body: next, expect: [200, 201] });
  }
}

async function authProbe() {
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const rpc = body => ({
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });

  const mcpUrl = new URL(`http://127.0.0.1:${gatewayPort}/mcp-servers/business-tools/mcp`);
  let mcpReady = false;
  for (let attempt = 0; attempt < 20; attempt++) {
    const denied = await fetch(mcpUrl, rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' }));
    if (![401, 403, 404].includes(denied.status)) {
      throw new Error(`expected MCP auth rejection or warmup 404, got HTTP ${denied.status}`);
    }
    const accepted = await fetch(mcpUrl, {
      ...rpc({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
      headers: {
        'content-type': 'application/json',
        accept: 'application/json',
        authorization: `Bearer ${consumerToken}`,
      },
    });
    if (accepted.ok) {
      const acceptedBody = await accepted.json();
      if (Array.isArray(acceptedBody?.result?.tools) && acceptedBody.result.tools.some(tool => tool.name === 'crm_query')) {
        mcpReady = true;
        break;
      }
    }
    await sleep(2000);
  }
  if (!mcpReady) throw new Error('authorized MCP probe did not converge to crm_query within 40s');

  const llmUrl = new URL(`http://127.0.0.1:${gatewayPort}/v1/chat/completions`);
  let modelReady = false;
  let modelCredentialWarning = '';
  for (let attempt = 0; attempt < 20; attempt++) {
    const acceptedModel = await fetch(llmUrl, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${consumerToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: probeModel,
        messages: [{ role: 'user', content: 'ping' }],
        max_tokens: 1,
      }),
      signal: AbortSignal.timeout(15_000),
    });
    const modelBody = await acceptedModel.text();
    if (acceptedModel.ok) {
      modelReady = true;
      break;
    }
    if (
      [401, 403, 429].includes(acceptedModel.status) &&
      /invalid_api_key|insufficient_quota|quota|Incorrect API key/i.test(modelBody)
    ) {
      modelReady = true;
      modelCredentialWarning = 'upstream LLM rejected the configured API key or quota; Higress AI route is wired correctly';
      break;
    }
    if (![401, 403, 404, 429].includes(acceptedModel.status)) {
      throw new Error(`authorized model probe got unexpected HTTP ${acceptedModel.status}: ${modelBody.slice(0, 200)}`);
    }
    await sleep(2000);
  }
  if (!modelReady) throw new Error('authorized model probe did not become ready within 40s');
  if (modelCredentialWarning) {
    process.stderr.write(`[WARN] ${modelCredentialWarning}\n`);
  }
}

await initAndLogin();
await upsertConsumer();
await upsertProvider();
await upsertAiRoute();
await upsertMcp();
await authProbe();
process.stdout.write('[PASS] local Higress configured for AI gateway and business-tools MCP\n');
