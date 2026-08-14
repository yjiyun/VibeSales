#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const YAML = require(path.join(__dirname, '..', 'agent-core', 'node_modules', 'yaml'));

const [file] = process.argv.slice(2);
const rawBase = process.env.AGENTTEAMS_CONTROLLER_URL?.trim();
const token = process.env.AGENTTEAMS_AUTH_TOKEN?.trim();
if (!file || !rawBase || !token) {
  throw new Error('usage: AGENTTEAMS_CONTROLLER_URL=... AGENTTEAMS_AUTH_TOKEN=... apply-agentteams-rest.js <rendered-resource.yaml>');
}

const base = new URL(rawBase);
if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
  throw new Error('AGENTTEAMS_CONTROLLER_URL must be an HTTP(S) origin');
}
const cr = YAML.parse(fs.readFileSync(file, 'utf8'));
const kind = cr?.kind;
const name = cr?.metadata?.name;
const collections = { Worker: 'workers', Team: 'teams', Human: 'humans' };
if (!collections[kind] || typeof name !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(name) || !cr?.spec || Array.isArray(cr.spec)) {
  throw new Error(`invalid AgentTeams resource: ${file}`);
}

const body = structuredClone(cr.spec);
body.name = name;
if (kind === 'Team') {
  if (typeof body.leader !== 'string' || !body.leader || !Array.isArray(body.workers)) throw new Error('Team leader/workers required');
  body.workerMembers = [{ name: body.leader, role: 'team_leader' }, ...body.workers.map(worker => ({ name: worker, role: 'worker' }))];
  delete body.workers;
  delete body.leader;
}

const collection = `/api/v1/${collections[kind]}`;
const resource = `${collection}/${encodeURIComponent(name)}`;
const request = async (method, pathname, payload) => {
  const response = await fetch(new URL(pathname, base), {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      accept: 'application/json',
      ...(payload === undefined ? {} : { 'content-type': 'application/json' }),
    },
    body: payload === undefined ? undefined : JSON.stringify(payload),
    signal: AbortSignal.timeout(10_000),
  });
  return { response, text: await response.text() };
};

const desiredFieldsMatch = (existing, desired) => Object.entries(desired).every(([key, value]) =>
  JSON.stringify(existing?.[key]) === JSON.stringify(value));

(async () => {
  const lookup = await request('GET', resource);
  let method;
  let pathname;
  if (lookup.response.ok) {
    if (kind === 'Human') {
      let existing;
      try { existing = JSON.parse(lookup.text); } catch { throw new Error(`Controller GET Human/${name} returned invalid JSON`); }
      if (!desiredFieldsMatch(existing, body)) {
        throw new Error(`Controller Human/${name} differs and this Controller exposes no Human update method`);
      }
      process.stdout.write(`[APPLY] UNCHANGED Human/${name}\n`);
      return;
    }
    method = 'PUT'; pathname = resource;
  } else if (lookup.response.status === 404) {
    method = 'POST'; pathname = collection;
  } else {
    throw new Error(`Controller lookup ${kind}/${name} failed: HTTP ${lookup.response.status} ${lookup.text.slice(0, 200)}`);
  }
  const applied = await request(method, pathname, body);
  if (!applied.response.ok) throw new Error(`Controller ${method} ${kind}/${name} failed: HTTP ${applied.response.status} ${applied.text.slice(0, 200)}`);
  process.stdout.write(`[APPLY] ${method} ${kind}/${name}\n`);
})().catch(error => {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
