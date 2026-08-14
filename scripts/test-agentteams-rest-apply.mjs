import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resources = new Map([
  ['workers/opspilot-zero-demo-leader', { name: 'opspilot-zero-demo-leader' }],
  ['teams/opspilot-zero-demo', { name: 'opspilot-zero-demo' }],
]);
const mutations = [];
const skillObjects = new Map();
let skillPuts = 0;
const server = http.createServer((request, response) => {
  const chunks = [];
  request.on('data', chunk => chunks.push(chunk));
  request.on('end', () => {
    const parsed = new URL(request.url, 'http://local');
    if (parsed.pathname === '/agentteams-storage' && parsed.search === '?location' && request.method === 'GET') {
      response.writeHead(200, { 'content-type': 'application/xml' }).end('<LocationConstraint></LocationConstraint>'); return;
    }
    if (parsed.pathname === '/agentteams-storage' && request.method === 'HEAD') {
      response.writeHead(200).end(); return;
    }
    if (parsed.pathname.startsWith('/agentteams-storage/')) {
      const object = decodeURIComponent(parsed.pathname.slice('/agentteams-storage/'.length));
      if (request.method === 'GET') {
        if (!skillObjects.has(object)) response.writeHead(404, { 'content-type': 'application/xml' }).end('<Error><Code>NoSuchKey</Code></Error>');
        else response.writeHead(200, { 'content-type': 'text/markdown' }).end(skillObjects.get(object));
        return;
      }
      if (request.method === 'PUT') {
        skillObjects.set(object, Buffer.concat(chunks)); skillPuts += 1;
        response.writeHead(200, { etag: '"skill-etag"' }).end(); return;
      }
    }
    if (request.headers.authorization !== 'Bearer rest-contract-token') {
      response.writeHead(401).end('{"message":"unauthorized"}'); return;
    }
    const key = request.url.replace('/api/v1/', '');
    if (request.method === 'GET') {
      if (!resources.has(key)) response.writeHead(404).end('{"message":"missing"}');
      else response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(resources.get(key)));
      return;
    }
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    if (key === 'teams' && (!Array.isArray(body.workerMembers) || body.workers !== undefined)) {
      response.writeHead(400).end('{"message":"workerMembers is required"}'); return;
    }
    if (request.method === 'POST' && key.startsWith('humans/')) {
      response.writeHead(405, { allow: 'DELETE, GET, HEAD' }).end('Method Not Allowed'); return;
    }
    mutations.push({ method: request.method, key, body });
    const collection = key.split('/')[0];
    const resourceKey = request.method === 'POST' && !key.includes('/') ? `${collection}/${body.name}` : key;
    resources.set(resourceKey, body);
    response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  });
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
const endpoint = `http://127.0.0.1:${server.address().port}`;
const run = (overrides = {}) => new Promise((resolve, reject) => {
  const child = spawn(path.join(root, 'agentteams-apply.sh'), [], {
    cwd: root,
    env: { ...process.env, AGENTTEAMS_APPLY_TRANSPORT: 'rest', AGENTTEAMS_CONTROLLER_URL: endpoint, AGENTTEAMS_AUTH_TOKEN: 'rest-contract-token', CHATFLOWS_MCP_BASE_URL: 'https://gateway.test', AGENTTEAMS_FS_ENDPOINT: endpoint, AGENTTEAMS_FS_ACCESS_KEY: 'admin', AGENTTEAMS_FS_SECRET_KEY: 'minio-contract-secret', AGENTTEAMS_FS_BUCKET: 'agentteams-storage', ...overrides },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  child.on('error', reject);
  child.on('close', code => resolve({ code, stderr }));
});
try {
  const missing = await run({ AGENTTEAMS_FS_SECRET_KEY: '' });
  if (missing.code === 0 || !missing.stderr.includes('AGENTTEAMS_FS_SECRET_KEY is required') || mutations.length !== 0) throw new Error('REST apply did not fail closed before CR mutation when Skill credentials were missing');
  const first = await run();
  if (first.code !== 0) throw new Error(`first REST apply exited ${first.code}: ${first.stderr}`);
  if (mutations.length !== 13 || mutations.some(item => item.method !== 'POST')) throw new Error('first REST apply was not 13 creates');
  if (skillObjects.size !== 15 || skillPuts !== 15) throw new Error(`first REST apply did not upload 15 Worker Skill files: objects=${skillObjects.size} puts=${skillPuts}`);
  if (![...skillObjects.keys()].includes('agents/chatflows-leader/skills/leader-route/SKILL.md')) throw new Error('Leader Skill object path mismatch');
  const second = await run();
  if (second.code !== 0) throw new Error(`second REST apply exited ${second.code}: ${second.stderr}`);
  if (mutations.length !== 25 || mutations.slice(13).some(item => item.method !== 'PUT')) throw new Error('second REST apply was not 12 supported updates with Human unchanged');
  if (skillPuts !== 15) throw new Error('unchanged Worker Skills were uploaded again');
  if (!resources.has('workers/opspilot-zero-demo-leader') || !resources.has('teams/opspilot-zero-demo')) throw new Error('unrelated resources were touched');
  const team = resources.get('teams/chatflows-build-team');
  if (team.workerMembers.length !== 11 || team.workerMembers[0].name !== 'chatflows-leader' || team.workerMembers[0].role !== 'team_leader') throw new Error('Team REST mapping mismatch');
  const worker = resources.get('workers/wizard-intent');
  if (!worker.soul.includes('Bundled Skill contract: p1-wizard-gate') || worker.mcpServers[0].url !== 'https://gateway.test/mcp-servers/chatflows-p1/mcp') throw new Error('rendered Worker contract missing');
  if (!resources.has('humans/chatflows-coordinator')) throw new Error('Human resource missing');
  if (resources.get('humans/chatflows-coordinator').permissionLevel !== 2) throw new Error('Human must use L2 Team-scoped permission');
  process.stdout.write('[PASS] REST apply fails closed, syncs 15 Worker Skills idempotently, creates/updates 11 Workers → Team → Human, preserves unrelated resources\n');
} finally {
  await new Promise(resolve => server.close(resolve));
}
