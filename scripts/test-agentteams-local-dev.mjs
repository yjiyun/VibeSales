import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'scripts/run-agentteams-local-dev.sh');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'agentteams-local-preflight-'));
const listen = server => new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => resolve(server));
});
const close = server => new Promise(resolve => server.close(resolve));
const port = server => server.address().port;

const httpServer = await listen(http.createServer((request, response) => {
  response.setHeader('content-type', 'application/json');
  response.end(request.url?.includes('/workers') ? '[]' : '{}');
}));
// The preflight uses the real MinIO SDK. Point it at a minimal signed-response
// stub by returning an S3-compatible success for HEAD bucket.
httpServer.removeAllListeners('request');
httpServer.on('request', (request, response) => {
  if (request.method === 'HEAD') {
    response.writeHead(/^\/tasks\/?$/.test(request.url ?? '') ? 200 : 404).end();
    return;
  }
  response.setHeader('content-type', 'application/json');
  if (request.url === '/_matrix/client/v3/account/whoami') {
    const userId = request.headers.authorization === 'Bearer human-token' ? '@human:local' : '@manager:local';
    response.end(JSON.stringify({ user_id: userId })); return;
  }
  if (request.url === '/_matrix/client/v3/login') {
    response.end(JSON.stringify({ user_id: '@manager:local', access_token: 'manager-token' })); return;
  }
  if (request.url === '/api/v1/teams/chatflows-build-team') {
    response.end(JSON.stringify({ leaderName: 'chatflows-leader', teamRoomID: '!chatflows:local' })); return;
  }
  if (request.url === '/api/v1/workers/chatflows-leader') {
    response.end(JSON.stringify({ matrixUserID: '@chatflows-leader:local' })); return;
  }
  response.end(request.url?.includes('/workers') ? '[]' : '{}');
});
const postgres = await listen(net.createServer(socket => socket.end()));
const redis = await listen(net.createServer(socket => socket.end()));

const values = {
  WEB_AUTH_TOKEN: 'wizard-token-0123456789', WEB_AUTH_CLIENT_CODE: 'acme',
  PIPELINE_CONTROL_TOKEN: 'pipeline-token-0123456789', CHATFLOWS_APPROVAL_SIGNING_SECRET: 'approval-secret-at-least-32-characters', PIPELINE_APPROVAL_SIGNING_SECRET: 'approval-secret-at-least-32-characters',
  // Nest 侧 DATABASE_URL 是 postgresql:// 形式（pg Pool）；runtime 侧连接串走
  // AGENT_RUNTIME_DATABASE_URL，由 preflight 归一化成 jdbc: 后 export 成 DATABASE_URL。
  ARTIFACT_STORE: 'postgres', DATABASE_URL: `postgresql://app:test@127.0.0.1:${port(postgres)}/chatflows`,
  AGENT_RUNTIME_DATABASE_URL: `postgresql://127.0.0.1:${port(postgres)}/chatflows`,
  DATABASE_USER: 'runtime', DATABASE_PASSWORD: 'test', REDIS_URL: `redis://127.0.0.1:${port(redis)}/0`,
  MINIO_ENDPOINT: `http://127.0.0.1:${port(httpServer)}`, MINIO_ACCESS_KEY: 'test', MINIO_SECRET_KEY: 'test', MINIO_BUCKET: 'artifacts',
  QWEN_BASE_URL: 'https://higress.example/compatible-mode/v1', QWEN_GATEWAY_TOKEN: 'qwen-gateway-token-0123456789', DASHSCOPE_API_KEY: '',
  AGENTTEAMS_CONTROLLER_URL: `http://127.0.0.1:${port(httpServer)}`, AGENTTEAMS_AUTH_TOKEN: 'controller-token',
  AGENTTEAMS_MATRIX_URL: `http://127.0.0.1:${port(httpServer)}`, AGENTTEAMS_MATRIX_ACCESS_TOKEN: 'matrix-token',
  CHATFLOWS_TASK_FS_ENDPOINT: `http://127.0.0.1:${port(httpServer)}`, CHATFLOWS_TASK_FS_ACCESS_KEY: 'chatflows-task-manager', CHATFLOWS_TASK_FS_SECRET_KEY: 'test', CHATFLOWS_TASK_FS_BUCKET: 'tasks', CHATFLOWS_TASK_FS_PREFIX: 'teams/chatflows-build-team/shared/tasks',
  AGENTTEAMS_HUMAN_IDS: '@human:local', AGENTTEAMS_LEADER_IDS: '@leader:local', AGENTTEAMS_MANAGER_IDS: '@manager:local', AGENTTEAMS_LEADER_ROOM_ID: '!leader:local',
  CHATFLOWS_NEST_URL: `http://127.0.0.1:${port(httpServer)}`,
  MANAGER_AUTH_TOKEN: 'manager-token-0123456789', MANAGER_ADMIN_TOKEN: 'manager-admin-token-0123456789', ORCHESTRATOR_LLM: 'off',
  RUNTIME_AUTH_TOKEN: 'runtime-token-0123456789', RUNTIME_ADMIN_TOKEN: 'runtime-admin-token-0123456789',
  RUNTIME_MCP_URL: `http://127.0.0.1:${port(httpServer)}/mcp-servers/test/mcp`, RUNTIME_MCP_TOKEN: 'mcp-token',
  BLUEPRINT_ADMIN_URL: `http://127.0.0.1:${port(httpServer)}`, BLUEPRINT_ADMIN_TOKEN: 'blueprint-token-0123456789',
  RUNTIME_LLM_BASE_URL: 'https://model.higress.example/v1', RUNTIME_LLM_TOKEN: 'runtime-model-token-0123456789',
};
const runtimeEnv = (overrides = {}) => ({ ...overrides });

const writeEnv = (name, overrides = {}) => {
  const file = path.join(temp, name + '.env');
  fs.writeFileSync(file, Object.entries({ ...values, ...overrides }).filter(([, value]) => value !== undefined).map(([key, value]) => `${key}=${value}`).join('\n') + '\n');
  return file;
};
const run = (component, envFile) => new Promise((resolve, reject) => {
  const child = spawn(script, [component], {
    cwd: root, env: { ...process.env, AGENTTEAMS_PREFLIGHT_ONLY: '1', AGENTTEAMS_LOCAL_ENV: envFile },
  });
  let stdout = '', stderr = '';
  child.stdout.on('data', chunk => { stdout += chunk; });
  child.stderr.on('data', chunk => { stderr += chunk; });
  child.once('error', reject);
  child.once('close', status => resolve({ status, stdout, stderr }));
});

try {
  for (const component of ['nest', 'manager', 'runtime']) {
    const result = await run(component, writeEnv(component));
    if (result.status !== 0) throw new Error(`${component} preflight failed: ${result.stderr || result.stdout}`);
  }
  const discovered = await run('manager', writeEnv('discover-team-runtime', { AGENTTEAMS_LEADER_IDS: '', AGENTTEAMS_LEADER_ROOM_ID: '' }));
  if (discovered.status !== 0) throw new Error(`manager Controller discovery failed: ${discovered.stderr || discovered.stdout}`);
  const notReady = await run('manager', writeEnv('team-not-ready', { AGENTTEAMS_LEADER_IDS: '', AGENTTEAMS_LEADER_ROOM_ID: '', AGENTTEAMS_TEAM_NAME: 'team-not-ready' }));
  if (notReady.status === 0 || !notReady.stderr.includes('teamRoomID missing or invalid')) throw new Error('manager accepted a Team without a ready room');
  const missing = await run('manager', writeEnv('missing-controller-token', { AGENTTEAMS_AUTH_TOKEN: undefined }));
  if (missing.status === 0 || !missing.stderr.includes('AGENTTEAMS_AUTH_TOKEN is required')) throw new Error('manager missing-variable contract failed');
  const missingApproval = await run('nest', writeEnv('missing-pipeline-approval', { PIPELINE_APPROVAL_SIGNING_SECRET: undefined }));
  if (missingApproval.status === 0 || !missingApproval.stderr.includes('PIPELINE_APPROVAL_SIGNING_SECRET is required')) throw new Error('Nest approval-secret contract failed');
  const mismatchedApproval = await run('manager', writeEnv('mismatched-approval', { PIPELINE_APPROVAL_SIGNING_SECRET: 'a-different-approval-secret-at-least-32' }));
  if (mismatchedApproval.status === 0 || !mismatchedApproval.stderr.includes('must match')) throw new Error('cross-language approval-secret equality contract failed');
  const overlap = await run('manager', writeEnv('overlapping-identities', { AGENTTEAMS_HUMAN_IDS: '@manager:local' }));
  if (overlap.status === 0 || !overlap.stderr.includes('overlaps')) throw new Error('Matrix identity separation contract failed');
  const humanCredential = await run('manager', writeEnv('human-matrix-token', { AGENTTEAMS_MATRIX_ACCESS_TOKEN: 'human-token' }));
  if (humanCredential.status === 0 || !humanCredential.stderr.includes('not in AGENTTEAMS_MANAGER_IDS')) throw new Error('Human Matrix credential was accepted as Manager');
  const wrongBucket = await run('manager', writeEnv('missing-task-bucket', { CHATFLOWS_TASK_FS_BUCKET: 'missing' }));
  if (wrongBucket.status === 0 || !wrongBucket.stderr.includes('bucket')) throw new Error('MinIO bucket existence contract failed');
  // A21：直连模型厂商地址必须被拒；容器私网 HTTP 必须被放行（与三处 validatedHigress 同口径）。
  const direct = await run('runtime', writeEnv('direct-model', runtimeEnv({ RUNTIME_LLM_BASE_URL: 'https://dashscope.aliyuncs.com/v1' })));
  if (direct.status === 0 || !direct.stderr.includes('RUNTIME_LLM_BASE_URL must target Higress')) throw new Error('runtime gateway contract failed');
  const privateGateway = await run('runtime', writeEnv('private-gateway', runtimeEnv({ RUNTIME_LLM_BASE_URL: 'http://higress-gateway:8080/v1' })));
  if (privateGateway.status !== 0) throw new Error(`private-network HTTP gateway was rejected: ${privateGateway.stderr}`);
  // A23：普通/管理凭证必须分离，否则 AuthService 构造器会在 serve 时抛错。
  const sameRuntimeTokens = await run('runtime', writeEnv('same-runtime-tokens', runtimeEnv({ RUNTIME_ADMIN_TOKEN: values.RUNTIME_AUTH_TOKEN })));
  if (sameRuntimeTokens.status === 0 || !sameRuntimeTokens.stderr.includes('must differ')) throw new Error('runtime dual-token separation contract failed');
  const shortManagerAdmin = await run('manager', writeEnv('short-manager-admin', { MANAGER_ADMIN_TOKEN: 'too-short' }));
  if (shortManagerAdmin.status === 0 || !shortManagerAdmin.stderr.includes('MANAGER_ADMIN_TOKEN must be at least 16 characters')) throw new Error('manager admin-token length contract failed');
  // RuntimeApplication 把 DATABASE_URL 原样交给 DriverManager：非 postgres 协议必须早失败，
  // authority 里带凭证也必须早失败（pgjdbc 不支持）。
  const wrongScheme = await run('runtime', writeEnv('wrong-database-scheme', runtimeEnv({ AGENT_RUNTIME_DATABASE_URL: `mysql://127.0.0.1:${port(postgres)}/chatflows` })));
  if (wrongScheme.status === 0 || !wrongScheme.stderr.includes('AGENT_RUNTIME_DATABASE_URL must be a postgresql://')) throw new Error('runtime jdbc URL contract failed');
  const embeddedCredentials = await run('runtime', writeEnv('embedded-credentials', runtimeEnv({ AGENT_RUNTIME_DATABASE_URL: `postgresql://runtime:test@127.0.0.1:${port(postgres)}/chatflows` })));
  if (embeddedCredentials.status === 0 || !embeddedCredentials.stderr.includes('must not embed credentials')) throw new Error('runtime jdbc credential contract failed');
  // 生产 Nest 只能拿网关消费者凭证，不得注入厂商直连 key。
  const dashscope = await run('nest', writeEnv('nest-dashscope', { DASHSCOPE_API_KEY: 'sk-direct-vendor-key' }));
  if (dashscope.status === 0 || !dashscope.stderr.includes('must not receive DASHSCOPE_API_KEY')) throw new Error('Nest vendor-key contract failed');
  process.stdout.write('[PASS] local-dev preflight checks Nest/manager/runtime dependencies, identity separation, task MinIO bucket, dual tokens, jdbc URL, vendor-key rejection and Higress model gateway\n');
} finally {
  await Promise.all([close(httpServer), close(postgres), close(redis)]);
  fs.rmSync(temp, { recursive: true, force: true });
}
