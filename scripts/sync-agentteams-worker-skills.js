#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const YAML = require(path.join(__dirname, '..', 'agent-core', 'node_modules', 'yaml'));
const Minio = require(path.join(__dirname, '..', 'agent-core', 'node_modules', 'minio'));

function required(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is required for Worker Skill sync`);
  return value;
}

function workerSkillPlan(renderedFile, root = path.join(__dirname, '..')) {
  const cr = YAML.parse(fs.readFileSync(renderedFile, 'utf8'));
  if (cr?.kind !== 'Worker') return null;
  const worker = cr?.metadata?.name;
  const skills = cr?.spec?.skills;
  if (typeof worker !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(worker) || !Array.isArray(skills)) {
    throw new Error(`invalid Worker Skill declaration: ${renderedFile}`);
  }
  const files = skills.map(name => {
    if (typeof name !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(name)) throw new Error(`invalid Skill name for ${worker}`);
    const source = path.join(root, 'worker-packages', 'skills', name, 'SKILL.md');
    if (!fs.existsSync(source)) throw new Error(`missing Skill package: ${source}`);
    return { name, source, object: `agents/${worker}/skills/${name}/SKILL.md`, content: fs.readFileSync(source) };
  });
  return { worker, files };
}

async function streamBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function existingObject(client, bucket, object) {
  try { return await streamBuffer(await client.getObject(bucket, object)); }
  catch (error) {
    if (['NoSuchKey', 'NotFound', 'NoSuchObject'].includes(error?.code) || error?.statusCode === 404) return null;
    throw error;
  }
}

async function syncPlan(client, bucket, plan) {
  if (!plan) return { uploaded: 0, unchanged: 0 };
  if (!await client.bucketExists(bucket)) throw new Error(`AgentTeams Skill bucket does not exist: ${bucket}`);
  let uploaded = 0, unchanged = 0;
  for (const file of plan.files) {
    const current = await existingObject(client, bucket, file.object);
    if (current?.equals(file.content)) { unchanged += 1; continue; }
    await client.putObject(bucket, file.object, file.content, file.content.length, { 'Content-Type': 'text/markdown; charset=utf-8' });
    uploaded += 1;
  }
  return { uploaded, unchanged };
}

function clientFromEnv(env) {
  const endpoint = new URL(required(env, 'AGENTTEAMS_FS_ENDPOINT'));
  if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash) {
    throw new Error('AGENTTEAMS_FS_ENDPOINT must be an HTTP(S) origin');
  }
  return new Minio.Client({
    endPoint: endpoint.hostname,
    port: Number(endpoint.port || (endpoint.protocol === 'https:' ? 443 : 80)),
    useSSL: endpoint.protocol === 'https:',
    accessKey: required(env, 'AGENTTEAMS_FS_ACCESS_KEY'),
    secretKey: required(env, 'AGENTTEAMS_FS_SECRET_KEY'),
  });
}

async function main() {
  const [renderedFile] = process.argv.slice(2);
  if (!renderedFile) throw new Error('usage: sync-agentteams-worker-skills.js <rendered-worker.yaml>');
  const plan = workerSkillPlan(renderedFile);
  if (!plan) return;
  const bucket = required(process.env, 'AGENTTEAMS_FS_BUCKET');
  const result = await syncPlan(clientFromEnv(process.env), bucket, plan);
  process.stdout.write(`[SKILLS] Worker/${plan.worker} uploaded=${result.uploaded} unchanged=${result.unchanged}\n`);
}

module.exports = { workerSkillPlan, syncPlan };
if (require.main === module) main().catch(error => { process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`); process.exit(1); });
