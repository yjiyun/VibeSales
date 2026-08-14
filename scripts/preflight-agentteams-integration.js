#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const infraOnly = process.argv.includes('--infra-only');
const envFile = process.argv.slice(2).find(argument => argument !== '--infra-only');

if (envFile) {
  if (!fs.existsSync(envFile)) throw new Error(`env file not found: ${envFile}`);
  for (const raw of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at < 1) continue;
    const key = line.slice(0, at).trim();
    if (process.env[key] === undefined) {
      process.env[key] = line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
    }
  }
}

const required = name => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} required`);
  return value;
};
const bearer = token => ({ authorization: `Bearer ${token}` });
const ids = name => new Set(required(name).split(',').map(value => value.trim()).filter(Boolean));

async function json(name, url, init = {}, expectedStatus = 200) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8_000);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    const text = await response.text();
    const expectedStatuses = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus];
    if (!expectedStatuses.includes(response.status)) {
      throw new Error(`${name} HTTP ${response.status} ${text.slice(0, 200)}`);
    }
    let body;
    try { body = text ? JSON.parse(text) : {}; }
    catch { throw new Error(`${name} returned non-JSON`); }
    process.stdout.write(`[PASS] ${name}\n`);
    return body;
  } finally {
    clearTimeout(timer);
  }
}

function minioClient(endpointName, accessName, secretName) {
  const Minio = require(path.join(root, 'agent-core/node_modules/minio'));
  const endpoint = new URL(required(endpointName));
  return new Minio.Client({
    endPoint: endpoint.hostname,
    port: Number(endpoint.port || (endpoint.protocol === 'https:' ? 443 : 80)),
    useSSL: endpoint.protocol === 'https:',
    accessKey: required(accessName),
    secretKey: required(secretName),
  });
}

// 两套 MinIO 身份必须分别验：AGENTTEAMS_FS_* 是平台 admin（REST apply 投影 Skill 到
// agents/<worker>/skills/），CHATFLOWS_TASK_FS_* 是 prefix 受限的 Manager 任务目录身份
// （ManagerConfig 读这五项）。前者只验 bucket 存在，后者必须真实读写删一次对象。
async function checkAgentTeamsMinio() {
  const platform = minioClient('AGENTTEAMS_FS_ENDPOINT', 'AGENTTEAMS_FS_ACCESS_KEY', 'AGENTTEAMS_FS_SECRET_KEY');
  if (!await platform.bucketExists(required('AGENTTEAMS_FS_BUCKET'))) {
    throw new Error('AgentTeams platform bucket missing');
  }
  process.stdout.write('[PASS] AgentTeams platform MinIO bucket\n');

  const taskMinio = minioClient('CHATFLOWS_TASK_FS_ENDPOINT', 'CHATFLOWS_TASK_FS_ACCESS_KEY', 'CHATFLOWS_TASK_FS_SECRET_KEY');
  const taskBucket = required('CHATFLOWS_TASK_FS_BUCKET');
  const taskPrefix = required('CHATFLOWS_TASK_FS_PREFIX');
  if (taskPrefix !== 'teams/chatflows-build-team/shared/tasks') {
    throw new Error('CHATFLOWS_TASK_FS_PREFIX must be the fixed TeamHarness object-key prefix');
  }
  const crypto = require('crypto');
  const taskObject = `${taskPrefix}/task-${crypto.randomUUID()}/meta.json`;
  const marker = Buffer.from('chatflows-preflight-health');
  try {
    await taskMinio.putObject(taskBucket, taskObject, marker, marker.length, { 'content-type': 'application/json' });
    const stream = await taskMinio.getObject(taskBucket, taskObject);
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    if (!Buffer.concat(chunks).equals(marker)) throw new Error('AgentTeams Team task MinIO health read mismatch');
  } finally {
    await taskMinio.removeObject(taskBucket, taskObject);
  }
  process.stdout.write('[PASS] dedicated fixed-Team MinIO object-key read/write/delete\n');
}

// A22：off 不导出；stderr 只打本地；on 才要求上报凭证。
function checkAgentLoopExporter() {
  const exporter = (process.env.AGENTLOOP_EXPORTER ?? 'off').trim().toLowerCase();
  if (!['off', 'stderr', 'on'].includes(exporter)) throw new Error('AGENTLOOP_EXPORTER must be off, stderr or on');
  if (exporter === 'on') {
    required('AGENTLOOP_ENDPOINT');
    required('AGENTLOOP_ACCESS_KEY');
    required('AGENTLOOP_ACCESS_SECRET');
  }
  process.stdout.write(`[PASS] AgentLoop exporter contract (${exporter})\n`);
}

// A21：配了 runtime 模型网关就必须是 Higress，HTTPS 或容器私网 HTTP。
function checkRuntimeModelGateway() {
  if (!process.env.RUNTIME_LLM_BASE_URL && !process.env.RUNTIME_LLM_TOKEN) return;
  const llm = new URL(required('RUNTIME_LLM_BASE_URL'));
  const token = required('RUNTIME_LLM_TOKEN');
  if (token.length < 16) throw new Error('RUNTIME_LLM_TOKEN must be at least 16 characters');
  const host = llm.hostname.toLowerCase();
  const privateHost = host === 'localhost' || host === '127.0.0.1' || host === '::1'
    || host.startsWith('10.') || host.startsWith('192.168.')
    || /^172\.(1[6-9]|2\d|3[01])\./.test(host) || !host.includes('.');
  if (!(llm.protocol === 'https:' || (llm.protocol === 'http:' && privateHost))
    || (!privateHost && !host.includes('higress'))) {
    throw new Error('RUNTIME_LLM_BASE_URL must target Higress over HTTPS or private-network HTTP');
  }
  process.stdout.write('[PASS] agent-runtime model gateway contract\n');
}

async function main() {
  const controller = required('AGENTTEAMS_CONTROLLER_URL').replace(/\/$/, '');
  const controllerToken = required('AGENTTEAMS_AUTH_TOKEN');
  const matrix = required('AGENTTEAMS_MATRIX_URL').replace(/\/$/, '');
  const matrixUser = required('AGENTTEAMS_MATRIX_USER_ID');
  let matrixToken = process.env.AGENTTEAMS_MATRIX_ACCESS_TOKEN?.trim();
  if (!matrixToken) {
    const login = await json('Matrix manager password login', `${matrix}/_matrix/client/v3/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        type: 'm.login.password',
        identifier: { type: 'm.id.user', user: matrixUser },
        password: required('AGENTTEAMS_MATRIX_PASSWORD'),
      }),
    });
    if (login.user_id !== matrixUser || typeof login.access_token !== 'string' || !login.access_token) {
      throw new Error('Matrix login identity/token mismatch');
    }
    matrixToken = login.access_token;
  }

  const gateway = required('CHATFLOWS_MCP_BASE_URL').replace(/\/$/, '');
  const gatewayToken = required('HIGRESS_CONSUMER_TOKEN');
  const nest = required('CHATFLOWS_NEST_URL').replace(/\/$/, '');
  const workersResponse = await json('Controller workers', `${controller}/api/v1/workers`, {
    headers: bearer(controllerToken),
  });

  let discoveredLeader = '';
  if (!infraOnly) {
    const workers = Array.isArray(workersResponse)
      ? workersResponse
      : (workersResponse.items ?? workersResponse.workers ?? []);
    const names = new Set(workers.map(worker => worker.name ?? worker.metadata?.name));
    for (const name of [
      'chatflows-leader', 'wizard-intent', 'template-match', 'template-personalize',
      'flow-generate', 'blueprint-compose', 'flow-import-run', 'persona-expert',
      'business-expert', 'skill-expert', 'tool-expert',
    ]) {
      if (!names.has(name)) throw new Error(`Controller missing Worker ${name}`);
    }
    const teamName = process.env.AGENTTEAMS_TEAM_NAME?.trim() || 'chatflows-build-team';
    const leaderName = process.env.AGENTTEAMS_LEADER_NAME?.trim() || 'chatflows-leader';
    const team = await json('Controller Chatflows Team', `${controller}/api/v1/teams/${encodeURIComponent(teamName)}`, {
      headers: bearer(controllerToken),
    });
    const workerStates = await Promise.all([...names].filter(name => [
      'chatflows-leader', 'wizard-intent', 'template-match', 'template-personalize',
      'flow-generate', 'blueprint-compose', 'flow-import-run', 'persona-expert',
      'business-expert', 'skill-expert', 'tool-expert',
    ].includes(name)).map(async name => ({
      name,
      state: await json(`Controller Worker ${name}`, `${controller}/api/v1/workers/${encodeURIComponent(name)}`, {
        headers: bearer(controllerToken),
      }),
    })));
    const leader = workerStates.find(worker => worker.name === leaderName)?.state;
    if (!leader) throw new Error(`Controller missing Leader ${leaderName}`);
    if (typeof team.teamRoomID !== 'string' || !team.teamRoomID.startsWith('!')) {
      throw new Error('Chatflows Team is not ready: teamRoomID missing');
    }
    if (typeof leader.matrixUserID !== 'string' || !leader.matrixUserID.startsWith('@')) {
      throw new Error('Chatflows Leader is not ready: matrixUserID missing');
    }
    const stopped = workerStates.filter(worker => worker.state.containerState !== 'running');
    if (stopped.length) {
      throw new Error(`Chatflows Workers are not running: ${stopped.map(worker => `${worker.name}=${worker.state.containerState ?? worker.state.phase ?? 'unknown'}`).join(', ')}`);
    }
    if (team.leaderReady !== true || team.readyWorkers !== team.totalWorkers || team.totalWorkers !== 10) {
      throw new Error(`Chatflows Team is not ready: leaderReady=${team.leaderReady} readyWorkers=${team.readyWorkers}/${team.totalWorkers}`);
    }
    if (process.env.AGENTTEAMS_LEADER_ROOM_ID?.trim()
      && process.env.AGENTTEAMS_LEADER_ROOM_ID.trim() !== team.teamRoomID) {
      throw new Error('AGENTTEAMS_LEADER_ROOM_ID does not match Controller Team status');
    }
    discoveredLeader = leader.matrixUserID;
    process.stdout.write('[PASS] Controller has 11 Chatflows Workers and ready Team/Leader\n');
  }

  await json('Matrix versions', `${matrix}/_matrix/client/versions`);
  const whoami = await json('Matrix whoami', `${matrix}/_matrix/client/v3/account/whoami`, {
    headers: bearer(matrixToken),
  });
  const humans = ids('AGENTTEAMS_HUMAN_IDS');
  const managers = ids('AGENTTEAMS_MANAGER_IDS');
  const configuredLeaders = new Set((process.env.AGENTTEAMS_LEADER_IDS ?? '')
    .split(',').map(value => value.trim()).filter(Boolean));
  const leaders = infraOnly ? configuredLeaders : new Set([discoveredLeader, ...configuredLeaders]);
  if (whoami.user_id !== matrixUser || !managers.has(whoami.user_id)) {
    throw new Error('Matrix token identity must match AGENTTEAMS_MATRIX_USER_ID and manager allowlist');
  }
  if ([...managers].some(value => humans.has(value) || leaders.has(value))
    || [...humans].some(value => leaders.has(value))) {
    throw new Error('Matrix Manager/Human/Leader identities must be disjoint');
  }

  // 真集群 e2e 的 Human 审批必须用独立的授权 Human 身份，不得复用 Manager/Leader 凭证。
  const e2eHuman = process.env.AGENTTEAMS_E2E_HUMAN_USER_ID?.trim();
  if (e2eHuman) {
    let humanToken = process.env.AGENTTEAMS_E2E_HUMAN_ACCESS_TOKEN?.trim();
    if (!humanToken) {
      const login = await json('Matrix E2E Human password login', `${matrix}/_matrix/client/v3/login`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          type: 'm.login.password',
          identifier: { type: 'm.id.user', user: e2eHuman },
          password: required('AGENTTEAMS_E2E_HUMAN_PASSWORD'),
        }),
      });
      if (login.user_id !== e2eHuman || typeof login.access_token !== 'string' || !login.access_token) {
        throw new Error('E2E Human login identity/token mismatch');
      }
      humanToken = login.access_token;
    }
    const humanWho = await json('Matrix E2E Human whoami', `${matrix}/_matrix/client/v3/account/whoami`, {
      headers: bearer(humanToken),
    });
    if (humanWho.user_id !== e2eHuman || !humans.has(e2eHuman) || leaders.has(e2eHuman) || managers.has(e2eHuman)) {
      throw new Error('E2E Human must be an isolated authorized Human identity');
    }
  }

  await json('Nest MCP health', `${nest}/healthz`);
  for (const server of ['chatflows-p1', 'chatflows-p2', 'chatflows-p3', 'chatflows-p3b', 'chatflows-p3c', 'chatflows-p4']) {
    const response = await json(`Higress MCP ${server}`, `${gateway}/mcp-servers/${server}/mcp`, {
      method: 'POST',
      headers: { ...bearer(gatewayToken), 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
    }, [200, 201]);
    if (!Array.isArray(response.result?.tools) || response.result.tools.length === 0) {
      throw new Error(`${server} tools/list empty`);
    }
  }
  await checkAgentTeamsMinio();
  checkAgentLoopExporter();

  if (infraOnly) {
    process.stdout.write('[PASS] local-development platform preflight complete (Runtime/PostgreSQL/Redis deferred)\n');
    return;
  }

  checkRuntimeModelGateway();
  const runtime = required('AGENT_RUNTIME_URL').replace(/\/$/, '');
  if (required('CHATFLOWS_APPROVAL_SIGNING_SECRET') !== required('PIPELINE_APPROVAL_SIGNING_SECRET')) {
    throw new Error('Java/Nest approval signing secrets must match');
  }
  if (required('AGENT_RUNTIME_TOKEN') !== required('RUNTIME_AUTH_TOKEN')) {
    throw new Error('Nest/runtime bearer tokens must match');
  }
  // A23：普通与管理凭证必须分离，两侧 AuthService 构造器都会强制。
  for (const [regular, admin] of [['RUNTIME_AUTH_TOKEN', 'RUNTIME_ADMIN_TOKEN'], ['MANAGER_AUTH_TOKEN', 'MANAGER_ADMIN_TOKEN']]) {
    const regularValue = required(regular);
    const adminValue = required(admin);
    if (regularValue.length < 16 || adminValue.length < 16) throw new Error(`${regular} and ${admin} must be at least 16 characters`);
    if (regularValue === adminValue) throw new Error(`${regular} and ${admin} must differ`);
  }
  process.stdout.write('[PASS] runtime/manager dual-token separation\n');
  await json('agent-runtime auth boundary', `${runtime}/api/v1/dryrun`, { method: 'POST' }, 401);

  const { Pool } = require(path.join(root, 'agent-core/node_modules/pg'));
  const ssl = process.env.DATABASE_SSL === '1' ? { rejectUnauthorized: true } : undefined;
  const pool = new Pool({ connectionString: required('DATABASE_URL'), max: 1, ssl });
  const runtimePool = new Pool({ connectionString: required('AGENT_RUNTIME_DATABASE_URL'), max: 1, ssl });
  try {
    const result = await pool.query("select to_regclass('public.run') as run,to_regclass('public.artifact') as artifact,to_regclass('public.agent_blueprint') as blueprint,to_regclass('public.agent_binding') as binding");
    if (Object.values(result.rows[0]).some(value => !value)) throw new Error('PostgreSQL AgentTeams tables missing');
    const forced = await pool.query("select relname,relforcerowsecurity from pg_class where relname=any(array['run','artifact','agent_blueprint','agent_binding'])");
    if (forced.rows.length !== 4 || forced.rows.some(row => !row.relforcerowsecurity)) {
      throw new Error('PostgreSQL FORCE RLS missing');
    }
    const appRole = await pool.query("select pg_has_role(current_user,'chatflows_app','member') as app,pg_has_role(current_user,'agent_runtime','member') as runtime");
    const runtimeRole = await runtimePool.query("select pg_has_role(current_user,'agent_runtime','member') as runtime,pg_has_role(current_user,'chatflows_app','member') as app");
    if (!appRole.rows[0].app || appRole.rows[0].runtime || !runtimeRole.rows[0].runtime || runtimeRole.rows[0].app) {
      throw new Error('PostgreSQL LOGIN role separation invalid');
    }
    process.stdout.write('[PASS] PostgreSQL schema, FORCE RLS and LOGIN separation\n');
  } finally {
    await Promise.all([pool.end(), runtimePool.end()]);
  }

  const artifactMinio = minioClient('MINIO_ENDPOINT', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY');
  if (!await artifactMinio.bucketExists(required('MINIO_BUCKET'))) throw new Error('Chatflows artifact bucket missing');
  process.stdout.write('[PASS] Chatflows artifact MinIO bucket\n');

  const redis = new URL(required('REDIS_URL'));
  const net = require('net');
  await new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: redis.hostname, port: Number(redis.port || 6379) });
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('Redis timeout')); }, 5_000);
    socket.once('connect', () => socket.write('*1\r\n$4\r\nPING\r\n'));
    socket.once('data', data => {
      clearTimeout(timer);
      socket.destroy();
      if (!String(data).startsWith('+PONG')) reject(new Error('Redis PING failed'));
      else resolve();
    });
    socket.once('error', reject);
  });
  process.stdout.write('[PASS] Redis PING\n');
  process.stdout.write('[PASS] full integration preflight complete\n');
}

main().catch(error => {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
