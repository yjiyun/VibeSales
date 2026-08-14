#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const envFile = process.argv[2];
if (envFile) {
  if (!fs.existsSync(envFile)) throw new Error(`env file not found: ${envFile}`);
  for (const raw of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    if (at < 1) continue;
    const key = line.slice(0, at).trim();
    if (process.env[key] === undefined) process.env[key] = line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
  }
}

const required = name => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} required`);
  return value;
};
const origin = name => {
  const value = new URL(required(name));
  if (!['http:', 'https:'].includes(value.protocol) || value.username || value.password) throw new Error(`${name} must be an HTTP(S) origin`);
  return value.origin;
};
const timeoutSeconds = Number(process.env.AGENTTEAMS_RUN_TIMEOUT_SECONDS ?? 3600);
if (!Number.isFinite(timeoutSeconds) || timeoutSeconds < 1) throw new Error('AGENTTEAMS_RUN_TIMEOUT_SECONDS must be positive');
const pollMillis = Number(process.env.AGENTTEAMS_E2E_POLL_MS ?? 2000);
if (!Number.isFinite(pollMillis) || pollMillis < 10) throw new Error('AGENTTEAMS_E2E_POLL_MS must be >= 10');
const sleep = millis => new Promise(resolve => setTimeout(resolve, millis));

function authoritativePhase1Spec() {
  const file = path.resolve(required('AGENTTEAMS_PHASE1_RESULT_FILE'));
  let phase1;
  try { phase1 = JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) { throw new Error(`AGENTTEAMS_PHASE1_RESULT_FILE must contain valid JSON: ${error.message}`); }
  if (!phase1 || Array.isArray(phase1) || typeof phase1 !== 'object') throw new Error('Phase1Result must be a JSON object');
  if (phase1.gate !== 'PASS') throw new Error('Phase1Result gate must be PASS');
  const clientCode = required('AGENTTEAMS_RUN_CLIENT_CODE');
  if (phase1.client_code !== clientCode) throw new Error('Phase1Result client_code must match AGENTTEAMS_RUN_CLIENT_CODE');
  if (!phase1.triage || typeof phase1.triage !== 'object') throw new Error('Phase1Result triage is required');
  return { clientCode, spec: JSON.stringify({ phase: 'P1', phase1_result: phase1 }, null, 2) };
}

async function request(name, url, init = {}, expected = [200]) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    const text = await response.text();
    if (!expected.includes(response.status)) throw new Error(`${name} HTTP ${response.status} ${text.slice(0, 300)}`);
    return { response, text };
  } finally { clearTimeout(timer); }
}
async function json(name, url, init = {}, expected = [200]) {
  const { text } = await request(name, url, init, expected);
  try { return text ? JSON.parse(text) : {}; } catch { throw new Error(`${name} returned non-JSON`); }
}
const headers = (token, role, actor) => ({
  authorization: `Bearer ${token}`,
  accept: 'application/json',
  ...(role ? { 'x-role': role } : {}),
  ...(actor ? { 'x-actor': actor } : {}),
});
const postJson = (token, role, actor, body) => ({
  method: 'POST', headers: { ...headers(token, role, actor), 'content-type': 'application/json' }, body: JSON.stringify(body),
});
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function matrixToken(matrix, userId) {
  const supplied = process.env.AGENTTEAMS_MATRIX_ACCESS_TOKEN?.trim();
  if (supplied) return supplied;
  const login = await json('Matrix manager login', `${matrix}/_matrix/client/v3/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'm.login.password', identifier: { type: 'm.id.user', user: userId }, password: required('AGENTTEAMS_MATRIX_PASSWORD') }),
  });
  if (login.user_id !== userId || typeof login.access_token !== 'string' || !login.access_token) throw new Error('Matrix manager login identity mismatch');
  return login.access_token;
}
function bodies(value, out = []) {
  if (Array.isArray(value)) for (const item of value) bodies(item, out);
  else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) {
    if (key === 'body' && typeof item === 'string') out.push(item); else bodies(item, out);
  }
  return out;
}
function artifactsByKind(artifacts) {
  const grouped = new Map();
  for (const artifact of artifacts) {
    if (!grouped.has(artifact.kind)) grouped.set(artifact.kind, []);
    grouped.get(artifact.kind).push(artifact);
  }
  return grouped;
}

async function main() {
  const { clientCode, spec } = authoritativePhase1Spec();
  if (process.argv.includes('--validate-phase1-only')) {
    process.stdout.write('[PASS] authoritative Wizard Phase1Result is valid for the credential tenant\n');
    return;
  }
  const manager = origin('MANAGER_API');
  const managerToken = required('MANAGER_AUTH_TOKEN');
  const nest = origin('CHATFLOWS_NEST_URL');
  const pipelineToken = required('PIPELINE_CONTROL_TOKEN');
  const runtime = origin('AGENT_RUNTIME_URL');
  const runtimeToken = required('AGENT_RUNTIME_TOKEN');
  const matrix = origin('AGENTTEAMS_MATRIX_URL');
  const managerActor = process.env.AGENTTEAMS_MANAGER_ACTOR?.trim() || required('AGENTTEAMS_MATRIX_USER_ID');
  const humanActor = process.env.AGENTTEAMS_E2E_HUMAN_ACTOR?.trim() || required('AGENTTEAMS_HUMAN_IDS').split(',')[0].trim();
  required('AGENTLOOP_ENDPOINT'); required('AGENTLOOP_ACCESS_KEY'); required('AGENTLOOP_ACCESS_SECRET');

  await json('Manager health', `${manager}/api/v1/health`, { headers: headers(managerToken, 'orchestrator', managerActor) });
  const created = await json('Manager create orchestration', `${manager}/api/v1/orchestrations`, postJson(managerToken, 'orchestrator', managerActor, { client_code: clientCode, spec }), [202]);
  const runId = String(created.run_id ?? '');
  if (!uuid.test(runId)) throw new Error('Manager did not return a Nest-issued UUID run_id');
  if (created.client_code !== clientCode) throw new Error('Manager create response tenant mismatch');
  const roomId = String(created.room_id ?? '');
  if (!roomId.startsWith('!')) throw new Error('Manager create response missing Team room_id');
  process.stdout.write(`[PASS] Manager created Nest-issued run_id=${runId}\n`);

  const deadline = Date.now() + timeoutSeconds * 1000;
  let managerRun = created;
  let pipelineView;
  let approvalId = '';
  while (Date.now() < deadline) {
    managerRun = await json('Manager orchestration state', `${manager}/api/v1/orchestrations/${encodeURIComponent(runId)}`, { headers: headers(managerToken, 'orchestrator', managerActor) });
    pipelineView = await json('Nest pipeline state', `${nest}/api/v1/pipeline/${encodeURIComponent(runId)}`, { headers: headers(pipelineToken, 'orchestrator', managerActor) });
    if (pipelineView.run?.run_id !== runId || pipelineView.run?.client_code !== clientCode) throw new Error('Nest run identity diverged from Manager run');
    const pending = Array.isArray(managerRun.pending_approvals) ? managerRun.pending_approvals : [];
    const approval = Array.isArray(pipelineView.artifacts) ? [...pipelineView.artifacts].reverse().find(item => item.kind === 'approval' && item.payload?.status === 'PENDING') : undefined;
    if (pipelineView.run?.status === 'WAITING_HUMAN' && pending.includes(approval?.payload?.approval_id)) {
      approvalId = approval.payload.approval_id; break;
    }
    if (['FAILED', 'ABORTED'].includes(managerRun.status) || ['FAILED', 'ABORTED'].includes(pipelineView.run?.status)) throw new Error(`run terminated before approval: manager=${managerRun.status} nest=${pipelineView.run?.status}`);
    await sleep(pollMillis);
  }
  if (!approvalId) throw new Error('timed out waiting for the same approval_id in Manager timeline and Nest artifacts');
  process.stdout.write(`[PASS] Manager timeline and Nest artifacts agree on approval_id=${approvalId}\n`);

  const decision = await json('Manager Human approval', `${manager}/api/v1/orchestrations/${encodeURIComponent(runId)}/approval`, postJson(managerToken, 'human', humanActor, { approval_id: approvalId, approved: true }));
  if (decision.run_id !== runId || decision.approval?.approval_id !== approvalId || decision.approval?.decision !== 'APPROVE' || !decision.approval?.proof) throw new Error('Manager approval did not return Nest-signed proof');

  while (Date.now() < deadline) {
    managerRun = await json('Manager terminal state', `${manager}/api/v1/orchestrations/${encodeURIComponent(runId)}`, { headers: headers(managerToken, 'orchestrator', managerActor) });
    pipelineView = await json('Nest terminal state', `${nest}/api/v1/pipeline/${encodeURIComponent(runId)}`, { headers: headers(pipelineToken, 'orchestrator', managerActor) });
    if (managerRun.status === 'SUCCEEDED' && pipelineView.run?.status === 'SUCCEEDED') break;
    if (['FAILED', 'ABORTED'].includes(managerRun.status) || ['FAILED', 'ABORTED'].includes(pipelineView.run?.status)) throw new Error(`run failed after approval: manager=${managerRun.status} nest=${pipelineView.run?.status}`);
    await sleep(pollMillis);
  }
  if (managerRun.status !== 'SUCCEEDED' || pipelineView.run?.status !== 'SUCCEEDED') throw new Error('timed out waiting for Manager/Nest SUCCEEDED');

  const artifacts = pipelineView.artifacts;
  if (!Array.isArray(artifacts) || artifacts.some(item => item.run_id !== runId || item.client_code !== clientCode)) throw new Error('artifact identity mismatch');
  const grouped = artifactsByKind(artifacts);
  for (const kind of ['wizard_state','triage','match_result','guidance','expert_dispatch','blueprint','blueprint_check','approval','import_result','dry_run','evidence']) {
    if (!grouped.has(kind)) throw new Error(`required artifact missing: ${kind}`);
  }
  if ((grouped.get('expert_result') ?? []).length < 4) throw new Error('four P3C expert_result artifacts required');
  if (pipelineView.run.build_path !== 'P3C') throw new Error(`expected P3C platform path, got ${pipelineView.run.build_path}`);
  const blueprint = grouped.get('blueprint').at(-1)?.payload;
  if (!blueprint || blueprint.meta?.runId !== runId || blueprint.clientCode !== clientCode || !blueprint.blueprintId || !blueprint.runtimeAgentId) throw new Error('Blueprint provenance/identity invalid');
  process.stdout.write('[PASS] Nest run/artifact contract is complete for P1→P2→P3C→P4\n');

  const published = await json('Publish Blueprint', `${nest}/api/v1/blueprints/publish?blueprintId=${encodeURIComponent(blueprint.blueprintId)}&clientCode=${encodeURIComponent(clientCode)}`, postJson(required('BLUEPRINT_ADMIN_TOKEN'), 'admin', humanActor, {}));
  if (published.status !== 'PUBLISHED') throw new Error('Blueprint was not published');
  const chatUrl = `${runtime}/api/v1/chat?clientCode=${encodeURIComponent(clientCode)}&userId=${encodeURIComponent(humanActor)}&sessionId=${encodeURIComponent(runId)}&runtimeAgentId=${encodeURIComponent(blueprint.runtimeAgentId)}`;
  const chat = await request('Runtime chat SSE', chatUrl, { method: 'POST', headers: { authorization: `Bearer ${runtimeToken}`, 'content-type': 'text/plain' }, body: '请进行交付验收并简要回复。' });
  if (!/^event: message\r?$/m.test(chat.text) || !/^event: done\r?$/m.test(chat.text) || /^event: error\r?$/m.test(chat.text)) throw new Error('Runtime SSE must contain message + done and no error');
  process.stdout.write('[PASS] Published Blueprint produced Runtime SSE message + done\n');

  const token = await matrixToken(matrix, required('AGENTTEAMS_MATRIX_USER_ID'));
  const timeline = await json('Matrix Team timeline', `${matrix}/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/messages?dir=b&limit=100`, { headers: { authorization: `Bearer ${token}` } });
  const runMessages = bodies(timeline).filter(body => body.includes(runId));
  if (runMessages.length < 2 || !runMessages.some(body => body.includes('APPROVAL_PROOF')) || !runMessages.some(body => body.includes(`task-${runId}`) || body.includes(`run_id=${runId}`))) throw new Error('Team Room does not contain one complete run timeline');
  process.stdout.write(`[PASS] Team Room contains one run timeline (${runMessages.length} messages)\n`);

  const evidence = {
    run_id: runId, client_code: clientCode, room_id: roomId, status: 'SUCCEEDED', build_path: 'P3C',
    artifact_kinds: [...grouped.keys()].sort(), runtime_sse: ['message', 'done'], matrix_messages: runMessages.length,
    worker_usage_available: false, agentloop_export: 'configured; query-side aggregation requires AgentLoop console/API evidence',
  };
  const evidenceFile = process.env.AGENTTEAMS_E2E_EVIDENCE_FILE?.trim();
  if (evidenceFile) { fs.mkdirSync(path.dirname(path.resolve(evidenceFile)), { recursive: true }); fs.writeFileSync(path.resolve(evidenceFile), JSON.stringify(evidence, null, 2) + '\n'); }
  process.stdout.write(`[PASS] platform P1-P4 e2e run_id=${runId}; worker_usage_available=false\n`);
  process.stdout.write('[PENDING] AgentLoop query-side proof for layers 1/3/4 is external to the ingestion endpoint\n');
}

main().catch(error => {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
