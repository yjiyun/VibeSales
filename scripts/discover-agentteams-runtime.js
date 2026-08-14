#!/usr/bin/env node
'use strict';

const rawBase = process.env.AGENTTEAMS_CONTROLLER_URL?.trim();
const token = process.env.AGENTTEAMS_AUTH_TOKEN?.trim();
const teamName = process.env.AGENTTEAMS_TEAM_NAME?.trim() || 'chatflows-build-team';
const leaderName = process.env.AGENTTEAMS_LEADER_NAME?.trim() || 'chatflows-leader';
if (!rawBase || !token) throw new Error('AGENTTEAMS_CONTROLLER_URL and AGENTTEAMS_AUTH_TOKEN are required');
if (![teamName, leaderName].every(name => /^[a-z0-9][a-z0-9-]*$/.test(name))) throw new Error('invalid Team or Leader resource name');

const base = new URL(rawBase);
if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
  throw new Error('AGENTTEAMS_CONTROLLER_URL must be an HTTP(S) origin');
}
const get = async pathname => {
  const response = await fetch(new URL(pathname, base), {
    headers: { authorization: `Bearer ${token}`, accept: 'application/json' },
    signal: AbortSignal.timeout(10_000),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Controller GET ${pathname} failed: HTTP ${response.status} ${text.slice(0, 160)}`);
  try { return JSON.parse(text); } catch { throw new Error(`Controller GET ${pathname} returned invalid JSON`); }
};
const matrixId = /^@[A-Za-z0-9._=\/-]+:[^\s:]+(?::\d+)?$/;
const roomId = /^![A-Za-z0-9._=\/-]+:[^\s:]+(?::\d+)?$/;

(async () => {
  const [team, leader] = await Promise.all([
    get(`/api/v1/teams/${encodeURIComponent(teamName)}`),
    get(`/api/v1/workers/${encodeURIComponent(leaderName)}`),
  ]);
  if (team.leaderName && team.leaderName !== leaderName) throw new Error(`Team leader mismatch: expected ${leaderName}, got ${team.leaderName}`);
  if (!roomId.test(team.teamRoomID ?? '')) throw new Error(`Team ${teamName} is not ready: teamRoomID missing or invalid`);
  if (!matrixId.test(leader.matrixUserID ?? '')) throw new Error(`Leader ${leaderName} is not ready: matrixUserID missing or invalid`);
  process.stdout.write(JSON.stringify({
    AGENTTEAMS_LEADER_ROOM_ID: team.teamRoomID,
    AGENTTEAMS_LEADER_IDS: leader.matrixUserID,
  }));
})().catch(error => {
  process.stderr.write(`[FAIL] ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
