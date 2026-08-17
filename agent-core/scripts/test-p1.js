#!/usr/bin/env node
/**
 * [P1] fixtures/p1/manifest.json 回归：逐条调用 `p1` CLI，断言 gate / scene / next_ask。
 *
 *   npm run test:p1
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const manifest = JSON.parse(
  fs.readFileSync(path.join(root, 'fixtures/p1/manifest.json'), 'utf8'),
);

/** 从可能夹杂 banner / ANSI 的 stdout 里抠出最后一个 JSON 对象。 */
function extractJson(stdout) {
  const text = (stdout || '')
    // eslint-disable-next-line no-control-regex
    .replace(/\u001b\[[0-9;]*m/g, '');
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end < start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1));
  } catch {
    return null;
  }
}

let failed = 0;

for (const c of manifest.cases) {
  const file = path.join(root, 'fixtures/p1', c.file);
  const flag = c.mode === 'slots' ? '--slots' : '--triage';
  const args = [
    '-r',
    'dotenv/config',
    path.join(root, 'src/main.ts'),
    'p1',
    '--trace-off',
    '--client-code',
    c.client_code,
    flag,
    file,
    '--expect-gate',
    c.expect_gate,
  ];

  const r = spawnSync(
    process.execPath,
    [
      path.join(root, 'node_modules/ts-node/dist/bin.js'),
      ...args,
    ],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, DEMO_TRACE: '0' },
      timeout: 15000,
    },
  );

  const parsed = extractJson(r.stdout) || extractJson(r.stderr);
  const gate = parsed?.gate;
  const scene = parsed?.triage?.scene_id;
  const nextSlot = parsed?.triage?.next_ask?.slot;
  const okExit = r.status === 0;
  let ok = okExit && gate === c.expect_gate;

  if (ok && c.expect_scene && scene !== c.expect_scene) ok = false;
  if (ok && c.expect_next_slot && nextSlot !== c.expect_next_slot) ok = false;

  const status = ok ? 'PASS' : 'FAIL';
  if (!ok) failed += 1;

  let extra = '';
  if (!ok) {
    const errBit = (r.stderr || r.stdout || '').slice(0, 240).replace(/\n/g, ' ');
    extra = ` exit=${r.status} got_gate=${gate} err=${errBit}`;
  }
  console.log(
    `[${status}] ${c.id} gate=${gate} scene=${scene || '-'} next=${nextSlot || '-'}${extra}`,
  );
}

console.log('---');
console.log(
  `P1 fixtures: ${manifest.cases.length - failed}/${manifest.cases.length} passed`,
);
process.exit(failed > 0 ? 1 : 0);
