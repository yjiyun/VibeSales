#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const envFile = process.argv[2] || path.join(root, 'docs/agentteams/integration-test.env.local');
if (!fs.existsSync(envFile)) throw new Error(`env file not found: ${envFile}`);
for (const raw of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
  const line = raw.trim(); if (!line || line.startsWith('#')) continue;
  const at = line.indexOf('='); if (at < 1) continue;
  const key = line.slice(0, at).trim();
  if (process.env[key] === undefined) process.env[key] = line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
}
const required = name => { const value = process.env[name]?.trim(); if (!value) throw new Error(`${name} required`); return value; };
const getJson = async (name, url, token) => {
  try {
    const response = await fetch(url, { headers: token ? { authorization:`Bearer ${token}`, accept:'application/json' } : { accept:'application/json' }, signal:AbortSignal.timeout(8000) });
    const text = await response.text();
    if (!response.ok) return { ok:false, detail:`HTTP ${response.status}` };
    try { return { ok:true, body:text ? JSON.parse(text) : {} }; } catch { return { ok:false, detail:'non-JSON response' }; }
  } catch (error) { return { ok:false, detail:error?.cause?.code || error.name || 'request failed' }; }
};
const items = (body, key) => Array.isArray(body) ? body : Array.isArray(body?.items) ? body.items : Array.isArray(body?.[key]) ? body[key] : [];
const names = list => list.map(item => item?.name ?? item?.metadata?.name).filter(Boolean).sort();
const tcp = (host, port) => new Promise(resolve => {
  const socket = net.createConnection({ host, port });
  const timer = setTimeout(() => socket.destroy(new Error('timeout')), 3000);
  socket.once('connect', () => { clearTimeout(timer); socket.end(); resolve({ ok:true }); });
  socket.once('error', error => { clearTimeout(timer); resolve({ ok:false, detail:error.code || error.message }); });
});
const endpointTcp = async (raw, fallbackHost, defaultPort) => {
  try { const url = raw ? new URL(raw) : null; return tcp(url?.hostname || fallbackHost, Number(url?.port || defaultPort)); }
  catch { return { ok:false, detail:'invalid URL' }; }
};
const line = (name, result, extra = '') => {
  const detail = result.ok ? extra : [extra, result.detail].filter(Boolean).join(' — ');
  process.stdout.write(`[${result.ok?'PASS':'MISS'}] ${name}${detail ? `: ${detail}` : ''}\n`);
};

async function main() {
  const controller = required('AGENTTEAMS_CONTROLLER_URL').replace(/\/$/, '');
  const token = required('AGENTTEAMS_AUTH_TOKEN');
  for (const [kind, key] of [['workers','workers'],['teams','teams'],['humans','humans']]) {
    const result = await getJson(`Controller ${kind}`, `${controller}/api/v1/${kind}`, token);
    if (!result.ok) { line(`Controller ${kind}`, result); continue; }
    const found = names(items(result.body, key));
    const chatflows = found.filter(name => name.startsWith('chatflows-') || name === 'wizard-intent' || ['template-match','template-personalize','flow-generate','blueprint-compose','flow-import-run','persona-expert','business-expert','skill-expert','tool-expert'].includes(name));
    line(`Controller ${kind}`, result, `total=${found.length}, chatflows=${chatflows.length}${chatflows.length ? ` (${chatflows.join(',')})` : ''}`);
  }

  const matrix = required('AGENTTEAMS_MATRIX_URL').replace(/\/$/, '');
  const versions = await getJson('Matrix versions', `${matrix}/_matrix/client/versions`);
  line('Matrix versions', versions);
  const matrixToken = process.env.AGENTTEAMS_MATRIX_ACCESS_TOKEN?.trim();
  if (matrixToken) {
    const who = await getJson('Matrix whoami', `${matrix}/_matrix/client/v3/account/whoami`, matrixToken);
    line('Matrix credential identity', who, who.ok ? String(who.body?.user_id || 'missing user_id') : '');
  } else line('Matrix credential identity', { ok:false, detail:'access token missing (password login not attempted)' });

  const Minio = require(path.join(root, 'agent-core/node_modules/minio'));
  const endpoint = new URL(required('AGENTTEAMS_FS_ENDPOINT'));
  const minio = new Minio.Client({ endPoint:endpoint.hostname, port:Number(endpoint.port || (endpoint.protocol === 'https:' ? 443 : 80)), useSSL:endpoint.protocol === 'https:', accessKey:required('AGENTTEAMS_FS_ACCESS_KEY'), secretKey:required('AGENTTEAMS_FS_SECRET_KEY') });
  try { const exists = await minio.bucketExists(required('AGENTTEAMS_FS_BUCKET')); line('MinIO AgentTeams bucket', {ok:exists}, exists ? required('AGENTTEAMS_FS_BUCKET') : 'not found'); }
  catch (error) { line('MinIO AgentTeams bucket', {ok:false,detail:error.code || error.name}); }

  const host = process.env.AGENTTEAMS_HOST?.trim() || new URL(controller).hostname;
  line('PostgreSQL TCP', await endpointTcp(process.env.DATABASE_URL?.trim(), host, 5432), `${process.env.DATABASE_URL?.trim() ? 'configured endpoint' : `${host}:5432 fallback probe`}`);
  line('Redis TCP', await endpointTcp(process.env.REDIS_URL?.trim(), host, 6379), `${process.env.REDIS_URL?.trim() ? 'configured endpoint' : `${host}:6379 fallback probe`}`);
  for (const [name, key] of [['Nest','CHATFLOWS_NEST_URL'],['Runtime','AGENT_RUNTIME_URL'],['Manager','MANAGER_API'],['MCP gateway','CHATFLOWS_MCP_BASE_URL'],['AgentLoop','AGENTLOOP_ENDPOINT']]) {
    const value = process.env[key]?.trim();
    line(`${name} configuration`, {ok:Boolean(value),detail:`${key} missing`}, value ? new URL(value).origin : '');
  }
}
main().catch(error => { process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`); process.exit(1); });
