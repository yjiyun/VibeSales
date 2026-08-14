#!/usr/bin/env node
'use strict';

const rawBase = process.env.AGENTTEAMS_MATRIX_URL?.trim();
const token = process.env.AGENTTEAMS_MATRIX_ACCESS_TOKEN?.trim();
const configuredUser = process.env.AGENTTEAMS_MATRIX_USER_ID?.trim();
const password = process.env.AGENTTEAMS_MATRIX_PASSWORD?.trim();
const managers = new Set((process.env.AGENTTEAMS_MANAGER_IDS ?? '').split(',').map(value => value.trim()).filter(Boolean));
if (!rawBase || managers.size === 0) throw new Error('AGENTTEAMS_MATRIX_URL and AGENTTEAMS_MANAGER_IDS are required');
if (!token && !(configuredUser && password)) throw new Error('Matrix access token or user/password required');

const base = new URL(rawBase);
if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
  throw new Error('AGENTTEAMS_MATRIX_URL must be an HTTP(S) origin');
}
const request = async (pathname, options = {}) => {
  const response = await fetch(new URL(pathname, base), { ...options, signal: AbortSignal.timeout(10_000) });
  const text = await response.text();
  if (!response.ok) throw new Error(`Matrix identity check failed: HTTP ${response.status}`);
  try { return JSON.parse(text); } catch { throw new Error('Matrix identity check returned invalid JSON'); }
};

(async () => {
  const identity = token
    ? (await request('/_matrix/client/v3/account/whoami', { headers: { authorization: `Bearer ${token}`, accept: 'application/json' } })).user_id
    : (await request('/_matrix/client/v3/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        body: JSON.stringify({ type: 'm.login.password', identifier: { type: 'm.id.user', user: configuredUser }, password }),
      })).user_id;
  if (typeof identity !== 'string' || !identity) throw new Error('Matrix identity check response has no user_id');
  if (configuredUser && identity !== configuredUser) throw new Error('Matrix credential identity does not match AGENTTEAMS_MATRIX_USER_ID');
  if (!managers.has(identity)) throw new Error('Matrix credential identity is not in AGENTTEAMS_MANAGER_IDS');
})().catch(error => {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
