#!/usr/bin/env node
/**
 * P0 产物观察 CLI：从 ArtifactStore JSON 打人读摘要，可选导出 HTML/JSON。
 * 不依赖 Nest / runtime 是否开启 ARTIFACT_INSPECTOR。
 *
 *   node scripts/inspect-run.mjs [--store FILE] [--run RUN_ID] [--html FILE] [--json FILE]
 *   node scripts/inspect-run.mjs --self-test
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function arg(name, fallback = '') {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

function latest(artifacts, kind, predicate) {
  const matched = (artifacts ?? []).filter(
    (item) => item.kind === kind && (!predicate || predicate(item)),
  );
  return matched.at(-1);
}

function clip(text, n = 12) {
  const lines = String(text ?? '')
    .split(/\r?\n/)
    .filter((line) => line.trim().length > 0);
  return lines.slice(0, n).join('\n');
}

function expertPayload(item) {
  const body = item?.payload ?? {};
  return body.payload ?? body;
}

function expertRole(item) {
  return item?.payload?.role ?? item?.written_by ?? 'expert';
}

export function summarizeStore(store, runId) {
  const runs = store.runs ?? [];
  const run =
    (runId ? runs.find((item) => item.run_id === runId) : null) ??
    [...runs].reverse().find((item) => ['SUCCEEDED', 'WAITING_HUMAN', 'RUNNING'].includes(item.status)) ??
    runs.at(-1);
  if (!run) throw new Error('store has no run');
  const artifacts = (store.artifacts ?? []).filter((item) => item.run_id === run.run_id);
  const blueprintArt = latest(artifacts, 'blueprint');
  const check = latest(artifacts, 'blueprint_check') ?? latest(artifacts, 'flow_check');
  const experts = artifacts.filter((item) => item.kind === 'expert_result');
  const bp =
    blueprintArt?.payload ??
    (store.blueprints ?? []).find((item) => item.source_run_id === run.run_id)?.payload ??
    null;
  const record = (store.blueprints ?? []).find(
    (item) => item.source_run_id === run.run_id || item.payload?.meta?.runId === run.run_id,
  );
  const binding = (store.bindings ?? []).find(
    (item) => item.blueprint_id === record?.blueprint_id || item.runtime_agent_id === bp?.runtimeAgentId,
  );
  const kinds = [...new Set(artifacts.map((item) => item.kind))];
  const persona = experts.find((item) => expertRole(item) === 'persona-expert');
  return {
    run,
    kinds,
    artifacts,
    experts: experts.map((item) => ({
      role: expertRole(item),
      version: item.version,
      preview: clip(JSON.stringify(expertPayload(item)), 8),
      soulMd: expertPayload(item)?.soulMd ?? '',
      agentsMd: expertPayload(item)?.agentsMd ?? '',
    })),
    persona: {
      soulMd: expertPayload(persona)?.soulMd || bp?.prompt?.soulMd || '',
      agentsMd: expertPayload(persona)?.agentsMd || bp?.prompt?.agentsMd || '',
    },
    blueprint: bp,
    blueprintStatus: record?.status ?? null,
    check: check?.payload ?? null,
    binding: binding ?? null,
  };
}

export function formatText(summary) {
  const run = summary.run;
  const bp = summary.blueprint;
  const lines = [
    `run_id          ${run.run_id}`,
    `status          ${run.status}`,
    `build_path      ${run.build_path ?? '—'}`,
    `client_code     ${run.client_code}`,
    `kinds           ${summary.kinds.join(', ') || '—'}`,
    `blueprint       ${bp?.blueprintId ?? '—'}  status=${summary.blueprintStatus ?? '—'}  agent=${bp?.runtimeAgentId ?? '—'}`,
    `selfcheck       ${summary.check?.ok === false ? 'FAIL' : summary.check?.ok === true ? 'OK' : '—'}`,
    `binding         ${summary.binding ? `${summary.binding.client_code}/${summary.binding.user_id} → ${summary.binding.runtime_agent_id}` : '—'}`,
    '',
    '--- persona / soulMd ---',
    clip(summary.persona.soulMd) || '（无）',
    '',
    '--- persona / agentsMd ---',
    clip(summary.persona.agentsMd) || '（无）',
    '',
    '--- experts ---',
    ...summary.experts.map((item) => `${item.role}  v${item.version}`),
    '',
    '--- skills ---',
    (bp?.skills ?? []).map((item) => item.name).join(', ') || '—',
  ];
  return lines.join('\n');
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function formatHtml(summary) {
  const run = summary.run;
  const bp = summary.blueprint;
  const checks = (summary.check?.checks ?? [])
    .map(
      (item) =>
        `<li class="${item.ok ? 'ok' : item.severity === 'warning' ? 'warn' : 'fail'}">#${item.id} ${escapeHtml(item.name)} ${item.ok ? 'OK' : 'FAIL'}</li>`,
    )
    .join('');
  const experts = summary.experts
    .map(
      (item) =>
        `<article><h3>${escapeHtml(item.role)}</h3><pre>${escapeHtml(item.soulMd || item.agentsMd || item.preview)}</pre></article>`,
    )
    .join('');
  const stages = ['triage', 'match_result', 'expert_result', 'blueprint', 'blueprint_check', 'approval', 'import_result']
    .map((kind) => {
      const present = summary.kinds.includes(kind);
      return `<span class="pill ${present ? 'ok' : 'miss'}">${escapeHtml(kind)}</span>`;
    })
    .join('');
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>产物观察 ${escapeHtml(run.run_id)}</title>
  <style>
    body{font:14px/1.5 -apple-system,BlinkMacSystemFont,sans-serif;margin:24px;color:#303133;background:#f5f7fa}
    h1,h2,h3{margin:0 0 8px}
    .meta{color:#909399;margin-bottom:16px}
    .pills{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0 24px}
    .pill{padding:4px 8px;border:1px solid #dcdfe6;border-radius:999px;background:#fff;font-size:12px}
    .pill.ok{border-color:#67c23a;color:#67c23a}
    .pill.miss{color:#c0c4cc}
    pre{background:#fff;border:1px solid #e4e7ed;border-radius:8px;padding:12px;white-space:pre-wrap;word-break:break-word}
    article{background:#fff;border:1px solid #e4e7ed;border-radius:8px;padding:12px;margin:8px 0}
    .ok{color:#67c23a}.warn{color:#e6a23c}.fail{color:#f56c6c}
  </style>
</head>
<body>
  <h1>链路产物观察</h1>
  <p class="meta">${escapeHtml(run.run_id)} · ${escapeHtml(run.status)} · ${escapeHtml(run.build_path)} · ${escapeHtml(run.client_code)}</p>
  <div class="pills">${stages}</div>
  <h2>人格 soulMd</h2>
  <pre>${escapeHtml(summary.persona.soulMd || '（无）')}</pre>
  <h2>工作准则 agentsMd</h2>
  <pre>${escapeHtml(clip(summary.persona.agentsMd, 20) || '（无）')}</pre>
  <h2>专家输出</h2>
  ${experts || '<p>无 expert_result</p>'}
  <h2>Blueprint</h2>
  <pre>id=${escapeHtml(bp?.blueprintId)}
status=${escapeHtml(summary.blueprintStatus)}
runtimeAgentId=${escapeHtml(bp?.runtimeAgentId)}
skills=${escapeHtml((bp?.skills ?? []).map((item) => item.name).join(', '))}</pre>
  <h2>13 项自检</h2>
  <ul>${checks || '<li>无</li>'}</ul>
  <h2>绑定</h2>
  <pre>${escapeHtml(JSON.stringify(summary.binding, null, 2) || '—')}</pre>
</body>
</html>`;
}

function defaultStore() {
  return (
    process.env.ARTIFACT_STORE_FILE?.trim() ||
    process.env.CONSOLE_P3C_STORE_FILE?.trim() ||
    '/tmp/chatflows-manual-stack/agentteams-store.json'
  );
}

function selfTest() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'inspect-run-'));
  const storeFile = path.join(tmp, 'store.json');
  const store = {
    runs: [
      {
        run_id: '11111111-1111-4111-8111-111111111111',
        client_code: 'acme_beauty',
        status: 'SUCCEEDED',
        build_path: 'P3C',
      },
    ],
    artifacts: [
      { run_id: '11111111-1111-4111-8111-111111111111', kind: 'triage', version: 1, payload: { scene_id: 'beauty_wecom_cs' } },
      {
        run_id: '11111111-1111-4111-8111-111111111111',
        kind: 'expert_result',
        version: 1,
        payload: { role: 'persona-expert', payload: { soulMd: '# 身份\n以美肤师身份帮助', agentsMd: '# 工作准则\n你是美肤师' } },
      },
      {
        run_id: '11111111-1111-4111-8111-111111111111',
        kind: 'blueprint',
        version: 1,
        payload: {
          blueprintId: 'bp_test',
          runtimeAgentId: 'beauty_wecom_cs-acme_beauty',
          prompt: { soulMd: '# 身份\n以美肤师身份帮助', agentsMd: '# 工作准则\n你是美肤师' },
          skills: [{ name: 'human-handoff' }],
        },
      },
    ],
    blueprints: [
      {
        blueprint_id: 'bp_test',
        status: 'PUBLISHED',
        source_run_id: '11111111-1111-4111-8111-111111111111',
        payload: { runtimeAgentId: 'beauty_wecom_cs-acme_beauty' },
      },
    ],
    bindings: [
      {
        client_code: 'acme_beauty',
        user_id: 'developer_local',
        runtime_agent_id: 'beauty_wecom_cs-acme_beauty',
        blueprint_id: 'bp_test',
      },
    ],
  };
  fs.writeFileSync(storeFile, JSON.stringify(store));
  const summary = summarizeStore(JSON.parse(fs.readFileSync(storeFile, 'utf8')));
  const text = formatText(summary);
  if (!text.includes('PUBLISHED') || !text.includes('以美肤师身份帮助') || !text.includes('persona-expert')) {
    throw new Error('self-test text missing expected fields:\n' + text);
  }
  const html = formatHtml(summary);
  if (!html.includes('beauty_wecom_cs-acme_beauty') || !html.includes('persona-expert')) {
    throw new Error('self-test html missing expected fields');
  }
  fs.rmSync(tmp, { recursive: true, force: true });
  process.stdout.write('[PASS] inspect-run self-test\n');
}

function main() {
  if (process.argv.includes('--self-test')) {
    selfTest();
    return;
  }
  const storeFile = path.resolve(arg('--store', defaultStore()));
  if (!fs.existsSync(storeFile)) throw new Error('store not found: ' + storeFile);
  const store = JSON.parse(fs.readFileSync(storeFile, 'utf8'));
  const summary = summarizeStore(store, arg('--run') || undefined);
  const text = formatText(summary);
  process.stdout.write(text + '\n');
  const htmlFile = arg('--html');
  if (htmlFile) {
    fs.mkdirSync(path.dirname(path.resolve(htmlFile)), { recursive: true });
    fs.writeFileSync(path.resolve(htmlFile), formatHtml(summary));
  }
  const jsonFile = arg('--json');
  if (jsonFile) {
    fs.mkdirSync(path.dirname(path.resolve(jsonFile)), { recursive: true });
    fs.writeFileSync(path.resolve(jsonFile), JSON.stringify(summary, null, 2));
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    main();
  } catch (error) {
    process.stderr.write(String(error?.message ?? error) + '\n');
    process.exit(1);
  }
}
