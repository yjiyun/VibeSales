/**
 * Console 浏览器级 P3C 全流程（Playwright / Chromium headless）
 *
 * 默认读取 agent-core/fixtures/wizard-e2e/p3c-guyu-wecom.yaml；
 * CONSOLE_P3C_FIXTURE 可换成 p3c-jifei-agri.yaml。从向导入口走完：
 * P1 → P2 → P3C 专家团 → 向导内确认发布 → 右侧沙盒必测冒烟。
 *
 * 起停见 scripts/run-console-p3c-e2e.sh。现有 test-console-ui.mjs 仍作 P3/契约烟测，不改。
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { formatHtml, summarizeStore } from '../../scripts/inspect-run.mjs';

const require = createRequire(import.meta.url);
const { parse: parseYaml } = require('../../agent-core/node_modules/yaml');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '../..');
const fixturePath =
  process.env.CONSOLE_P3C_FIXTURE ??
  path.join(rootDir, 'agent-core/fixtures/wizard-e2e/p3c-guyu-wecom.yaml');
const fixture = parseYaml(fs.readFileSync(fixturePath, 'utf8'));

const base = (process.env.CONSOLE_UI_BASE ?? 'http://127.0.0.1:25183').replace(/\/$/, '');
const shots = process.env.CONSOLE_P3C_SHOTS ?? path.join(rootDir, 'agent-core/tmp/wizard-p3c-e2e');
const wizardToken = required('CONSOLE_UI_WIZARD_TOKEN');
const pipelineToken = required('CONSOLE_UI_PIPELINE_TOKEN');
const runtimeToken = required('CONSOLE_UI_RUNTIME_TOKEN');
const actor = process.env.CONSOLE_UI_ACTOR ?? '@developer:local';
const nestBase = (process.env.CONSOLE_UI_NEST_BASE ?? 'http://127.0.0.1:23311').replace(/\/$/, '');

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} required`);
  return value;
}

function step(stage) {
  return (fixture.steps ?? []).find((s) => s.stage === stage);
}

const startedAt = Date.now();
const timings = [];
function mark(name) {
  const ms = Date.now() - startedAt;
  timings.push({ name, ms });
  process.stdout.write(`[STEP] ${name} +${ms}ms\n`);
}

if (fs.existsSync(shots)) {
  for (const name of fs.readdirSync(shots)) {
    if (/\.(png|json)$/.test(name)) fs.unlinkSync(path.join(shots, name));
  }
}
fs.mkdirSync(shots, { recursive: true });
const captured = [];

async function shot(page, name, focus, { viewportOnly = true } = {}) {
  if (focus) await focus.scrollIntoViewIfNeeded();
  const file = path.join(shots, `${String(captured.length + 1).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: !viewportOnly });
  captured.push(path.basename(file));
  process.stdout.write(`[SHOT] ${path.basename(file)}\n`);
}

async function toastsGone(page) {
  const toast = page.locator('.el-message').first();
  if ((await page.locator('.el-message').count()) === 0) return;
  await toast.waitFor({ state: 'detached', timeout: 8_000 }).catch(() => {});
}

async function pipelineGet(runId) {
  const response = await fetch(`${nestBase}/api/v1/pipeline/${runId}`, {
    headers: {
      authorization: `Bearer ${pipelineToken}`,
      'x-role': 'admin',
      'x-actor': actor,
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`pipeline GET ${response.status}: ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

/** 绕过 XSender 发送钮不同步；比逐字 keyboard.type 快且稳。 */
async function wizardAnswer(page, body) {
  const ok = await page.evaluate(async (payload) => {
    function walk(el, acc = []) {
      if (el.__vueParentComponent) acc.push(el.__vueParentComponent);
      for (const child of el.children || []) walk(child, acc);
      return acc;
    }
    const comps = walk(document.querySelector('.wz-app') || document.body);
    for (const comp of comps) {
      const setup = comp.setupState || {};
      if (typeof setup.answer === 'function') {
        await setup.answer(payload);
        return true;
      }
    }
    return false;
  }, body);
  assert.equal(ok, true, 'wizard answer() not found on Vue setupState');
}

function isProbeReply(answer) {
  return /DRY_RUN_OK|BLUEPRINT_OK|链路探针|产物探针/.test(answer);
}

async function sendSandbox(page, item) {
  const text = item.text ?? item;
  const sandbox = page.locator('.sandbox');
  const before = await page.locator('.chat-item.assistant').count();
  const composer = sandbox.locator('.composer textarea');
  await composer.fill(text);
  await composer.press('Enter');
  const assistant = page.locator('.chat-item.assistant').nth(before);
  await assistant.locator('.el-tag', { hasText: 'done' }).waitFor({ timeout: 120_000 });
  const answer = (await assistant.innerText()).trim();
  const body = answer
    .replace(/\bdone\b/gi, '')
    .replace(/链路探针，非智能体/g, '')
    .replace(/产物探针，非真模型/g, '')
    .trim();
  assert.ok(body.length > 0, `assistant 气泡不能为空：${text}`);
  const expectProbe = item.expect?.probe;
  if (expectProbe === false) {
    assert.equal(
      isProbeReply(answer),
      false,
      `${item.id ?? text} 应是智能体回复，不能是链路/产物探针`,
    );
  } else if (expectProbe === true && /DRY_RUN_OK/.test(answer)) {
    assert.ok(answer.includes(text), `deterministic-test 应回声原文：${text}`);
  }
  return answer;
}

const headed = process.env.CONSOLE_P3C_HEADED === '1';
const browser = await chromium.launch({
  headless: !headed,
  slowMo: headed ? 200 : 0,
});
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const consoleErrors = [];
const failedRequests = [];

try {
  const page = await context.newPage();
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('requestfailed', (request) => failedRequests.push(`${request.method()} ${request.url()}`));

  await page.addInitScript(([wizard, pipeline, runtime, runtimeAdmin, role, who]) => {
    localStorage.setItem('agent-console.wizard-token', wizard);
    localStorage.setItem('agent-console.pipeline-token', pipeline);
    localStorage.setItem('agent-console.runtime-token', runtime);
    localStorage.setItem('agent-console.runtime-admin-token', runtimeAdmin);
    localStorage.setItem('agent-console.role', role);
    localStorage.setItem('agent-console.actor', who);
    localStorage.setItem('agent-console.page', 'wizard');
    localStorage.removeItem('agent-console.last-run-id');
    localStorage.removeItem('agent-console.last-run-mode');
    localStorage.removeItem('agent-console.publication');
    const style = document.createElement('style');
    style.textContent = '*,*::before,*::after{transition:none!important;animation:none!important}';
    document.documentElement.appendChild(style);
  }, [wizardToken, pipelineToken, runtimeToken, process.env.CONSOLE_UI_RUNTIME_ADMIN_TOKEN ?? '', 'admin', actor]);

  await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '智能体助手' }).waitFor();
  await page.getByText('从一句话开始，搭出你的第一个 Agent').waitFor();
  mark('welcome-ready');

  const welcome = step('welcome');
  const llmSwitch = page.locator('.wz-start__form .el-switch').first();
  if ((welcome?.llm === false || fixture.llm === false) && (await llmSwitch.count()) > 0) {
    const checked = await llmSwitch.getAttribute('aria-checked');
    if (checked === 'true') {
      await llmSwitch.click({ force: true }).catch(() => {});
    }
  }
  // 拦截 createSession：即使 UI 开关没关掉，也强制 llm:false，保证无 Key/有 Key 都确定性。
  await page.route('**/api/wizard/sessions', async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    const post = route.request().postDataJSON() ?? {};
    await route.continue({
      postData: JSON.stringify({ ...post, llm: false }),
      headers: {
        ...route.request().headers(),
        'content-type': 'application/json',
      },
    });
  });
  await shot(page, 'wizard-welcome');
  const welcomeExpect = step('welcome')?.expect ?? {};
  const modelSelect = page.locator('.wz-model-select');
  await modelSelect.waitFor();
  await modelSelect.getByText(welcomeExpect.default_model ?? 'deepseek-v4-flash-0731').waitFor();
  await page.locator('.wz-start__form').getByRole('button', { name: '开始' }).click();

  const chatOnly = process.env.CONSOLE_P3C_CHAT_ONLY === '1';
  let runtimeClientCode = process.env.CONSOLE_P3C_CLIENT_CODE ?? 'acme_beauty';
  let runtimeAgentId = process.env.CONSOLE_P3C_RUNTIME_AGENT_ID ?? 'beauty_wecom_cs-acme_beauty';
  let runId = '';
  let approvalId = '';

  if (chatOnly) {
    await page.locator('.wz-runtime-tabs').waitFor();
    await page.evaluate(({ clientCode, runtimeAgentId: agentId }) => {
      localStorage.setItem(
        'agent-console.publication',
        JSON.stringify({
          clientCode,
          runtimeAgentId: agentId,
          sceneId: 'beauty_wecom_cs',
          buildPath: 'P3C',
          displayName: 'customer_success',
          publishedAt: new Date().toISOString(),
        }),
      );
      function walk(el, acc = []) {
        if (el.__vueParentComponent) acc.push(el.__vueParentComponent);
        for (const child of el.children || []) walk(child, acc);
        return acc;
      }
      for (const comp of walk(document.querySelector('.wz-app') || document.body)) {
        const setup = comp.setupState || {};
        if (typeof setup.goChat === 'function') {
          setup.goChat();
          return;
        }
      }
      throw new Error('goChat not found');
    }, { clientCode: runtimeClientCode, runtimeAgentId });
    mark('chat-only-bound');
  } else {
  const s1 = step('S1_INDUSTRY');
  const industryCard = page.locator('.wz-qcard').last();
  await industryCard.getByText('你所在的行业是什么？').waitFor();
  await industryCard.locator('.wz-opt', { hasText: s1.label }).first().click();
  await shot(page, 'wizard-s1-industry', industryCard);
  mark('s1-industry');

  const s2 = step('S2_GOALS');
  const goalsCard = page.locator('.wz-qcard:not(.is-history)').last();
  await goalsCard.getByText('你更希望 AI 先帮你处理哪类业务？').waitFor();
  for (const label of s2.labels) {
    await goalsCard.locator('.wz-opt', { hasText: label }).first().click();
  }
  assert.equal(
    await goalsCard.locator('.wz-opt.is-active').count(),
    s2.labels.length,
    `业务目标应勾中 ${s2.labels.length} 项`,
  );
  await shot(page, 'wizard-s2-goals', goalsCard);
  await goalsCard.getByRole('button', { name: '提交' }).click();
  mark('s2-goals');

  const s3 = step('S3_BRIEF');
  const briefCard = page.locator('.wz-qcard:not(.is-history)').last();
  await briefCard.getByText('简单说说你主要卖什么、或者主要做什么业务。').waitFor();
  await shot(page, 'wizard-s3-brief', briefCard);
  await wizardAnswer(page, { text: s3.text });
  mark('s3-brief');

  const s4 = step('S4_CTA');
  const ctaCard = page.locator('.wz-qcard:not(.is-history)').last();
  const preview = ctaCard.locator('.wz-opt', { hasText: s4.label }).first();
  await preview.waitFor({ timeout: 30_000 });
  await shot(page, 'wizard-s4-cta', ctaCard);
  await preview.click();
  mark('s4-cta');

  const result = page.locator('.wz-result').last();
  await result.getByText('向导已完成').waitFor({ timeout: 30_000 });
  await result.getByText(`闸门 ${s4.expect.gate}`).waitFor();
  await page.locator('.wz-header').getByText('DONE', { exact: true }).waitFor();
  await page.locator('.wz-header').getByText('已完成', { exact: true }).waitFor();
  await shot(page, 'wizard-result-gate-pass', result);

  await result.getByRole('button', { name: /查看 Phase1Result JSON/ }).click();
  const phase1Json = result.locator('pre').first();
  await phase1Json.waitFor();
  const phase1 = JSON.parse(await phase1Json.innerText());
  assert.equal(phase1.gate, s4.expect.gate);
  assert.equal(phase1.triage?.scene_id, s4.expect.scene_id);
  assert.equal(
    phase1.triage?.needs_long_term_memory === true,
    s4.expect.needs_long_term_memory === true,
    'needs_long_term_memory mismatch',
  );
  mark('gate-pass');

  const matches = page.locator('.wz-timeline-item.is-match');
  await matches.first().waitFor({ timeout: 60_000 });
  await shot(page, 'wizard-p2-match', matches.first());
  mark('p2-match');

  const build = step('result');
  const buildButton = result.getByRole('button', { name: /开始生成（(local|platform)）/ });
  await buildButton.waitFor();
  const buttonLabel = (await buildButton.innerText()).trim();
  const isPlatform = /platform/.test(buttonLabel);
  await buildButton.click();
  const publish = page.locator('.wz-publish').last();
  await publish.getByText('WAITING_HUMAN').waitFor({ timeout: isPlatform ? 1_800_000 : 120_000 });
  await publish.getByText('P3C').waitFor();
  await publish.getByRole('button', { name: '确认发布' }).waitFor();
  runId = await page.evaluate(() => localStorage.getItem('agent-console.last-run-id'));
  assert.match(runId, /^[0-9a-f-]{36}$/);
  await shot(page, 'wizard-build-run-created', publish);
  mark('waiting-human');

  const afterBuild = await pipelineGet(runId);
  assert.equal(afterBuild.run.build_path, build.expect.path, `expected ${build.expect.path}`);
  assert.equal(afterBuild.run.status, build.expect.status);
  for (const kind of [
    'wizard_state',
    'triage',
    'match_result',
    'guidance',
    'expert_dispatch',
    'blueprint',
    'blueprint_check',
    'approval',
  ]) {
    assert.ok(
      afterBuild.artifacts.some((item) => item.kind === kind),
      `生成结果缺 ${kind}`,
    );
  }

  const healthRes = await fetch(`${nestBase}/api/health`, {
    headers: {
      authorization: `Bearer ${wizardToken}`,
      'x-role': 'admin',
      'x-actor': actor,
    },
  });
  const health = await healthRes.json().catch(() => ({}));
  const inspectorOn = health.artifact_inspector === true;
  if (inspectorOn) {
    const artifactTab = page.locator('.wz-runtime-tabs').getByRole('tab', { name: '产物' });
    await artifactTab.click();
    await page.locator('.wz-artifacts').waitFor();
    await shot(page, 'wizard-artifacts-p3c', page.locator('.wz-artifacts'));
    mark('artifacts');
    const expertTab = page.locator('.wz-runtime-tabs').getByRole('tab', { name: '专家团' });
    await expertTab.click();
    const expertRoom = page.locator('.wz-expert-room');
    await expertRoom.waitFor();
    const expertMode = await expertRoom.getAttribute('data-mode');
    if (expertMode === 'local') {
      assert.match(
        await expertRoom.locator('[data-empty="local"]').innerText(),
        /不向 Matrix 派活/,
        'local 专家团应明示空房间是预期',
      );
    } else {
      assert.equal(expertMode, 'platform', '专家团 data-mode 应为 local 或 platform');
    }
    await shot(page, 'wizard-expert-room-p3c', expertRoom);
    mark('expert-room');
  }

  await publish.getByRole('button', { name: '确认发布' }).click();
  const approveOutcome = await Promise.race([
    page.locator('.el-message--success', { hasText: '已发布' }).waitFor({ timeout: 120_000 }).then(() => 'ok'),
    page.locator('.el-message--error').first().waitFor({ timeout: 120_000 }).then(async () => {
      const text = await page.locator('.el-message--error').first().innerText();
      throw new Error('publish failed: ' + text.trim());
    }),
  ]);
  assert.equal(approveOutcome, 'ok');
  await publish.getByText('已发布').first().waitFor({ timeout: 30_000 });
  await shot(page, 'wizard-published-p3c', publish);
  mark('published');

  const afterApprove = await pipelineGet(runId);
  assert.equal(afterApprove.run.status, 'SUCCEEDED');
  assert.equal(afterApprove.run.build_path, 'P3C');
  const blueprintArt = afterApprove.artifacts.find((a) => a.kind === 'blueprint');
  assert.ok(blueprintArt?.payload?.runtimeAgentId, 'blueprint missing runtimeAgentId');
  assert.ok(blueprintArt?.payload?.clientCode, 'blueprint missing clientCode');
  runtimeClientCode = blueprintArt.payload.clientCode;
  runtimeAgentId = blueprintArt.payload.runtimeAgentId;
  approvalId = [...afterApprove.artifacts].reverse().find((a) => a.kind === 'approval')?.payload?.approval_id ?? '';

  await toastsGone(page);
  await publish.getByRole('button', { name: '去试聊' }).click();
  }

  const sandboxTab = page.locator('.wz-runtime-tabs').getByRole('tab', { name: '沙盒试聊' });
  await sandboxTab.waitFor();
  assert.equal(await sandboxTab.getAttribute('aria-selected'), 'true', '应打开右侧沙盒试聊');
  assert.ok(
    (await sandboxTab.getAttribute('aria-disabled')) !== 'true',
    '已发布 P3C 后沙盒试聊应可点',
  );
  const sandbox = page.locator('.sandbox');
  await sandbox.waitFor();
  assert.equal(await sandbox.getAttribute('data-runtime-agent-id'), runtimeAgentId, '沙盒必须绑定本次 runtimeAgentId');
  assert.equal(await sandbox.getAttribute('data-client-code'), runtimeClientCode, '沙盒必须绑定本次 clientCode');
  assert.notEqual(runtimeAgentId, 'beauty_wecom_cs-smoke_beauty', '不得回落到 seed');

  const chat = step('chat');
  const messages = chat.messages?.length
    ? chat.messages
    : [{ id: 'sse_probe', text: chat.message ?? 'SSE health check', expect: { probe: true } }];
  const agentChat = process.env.CONSOLE_P3C_AGENT_CHAT === '1';
  await shot(page, 'chat-form-ready-p3c');
  const chatReplies = [];
  for (const item of messages) {
    if (item.expect?.probe === false && !agentChat) {
      process.stdout.write(`[SKIP] chat:${item.id} 需要智能体 runtime（CONSOLE_P3C_AGENT_CHAT=1）\n`);
      mark(`skip:${item.id}`);
      continue;
    }
    const reply = await sendSandbox(page, item);
    chatReplies.push({ id: item.id ?? item.text, text: item.text, reply: reply.slice(0, 240), probe: isProbeReply(reply) });
    mark(`chat:${item.id ?? 'message'}`);
  }
  await shot(page, 'chat-sse-done-p3c');

  if (!chatOnly) {
  await page.locator('.wz-runtime-tabs').getByRole('tab', { name: '信息收集' }).click();
  await page.locator('.wz-runtime-tabs').getByRole('tab', { name: '沙盒试聊' }).click();
  assert.equal(await sandboxTab.getAttribute('aria-selected'), 'true', '已发布后应能再次进入沙盒');
  await page.locator('.wz-publish').last().getByRole('button', { name: '去试聊' }).click();
  assert.equal(await sandboxTab.getAttribute('aria-selected'), 'true', '再次点击去试聊应打开沙盒');
  await shot(page, 'wizard-reenter-sandbox');
  mark('reenter-sandbox');

  await page.getByRole('menuitem', { name: '编排看板' }).click();
  await page.getByRole('heading', { name: '编排看板' }).waitFor();
  await page.getByRole('menuitem', { name: '搭建向导' }).click();
  await page.getByRole('heading', { name: '智能体助手' }).waitFor();
  await page.locator('.wz-publish').last().getByText('已发布').first().waitFor();
  await page.locator('.wz-result').last().getByText('向导已完成').waitFor();
  await sandboxTab.waitFor();
  assert.ok(
    (await sandboxTab.getAttribute('aria-disabled')) !== 'true',
    '切到编排看板再回来，已发布沙盒仍可点',
  );
  await shot(page, 'wizard-keepalive-after-runs');
  mark('keepalive');
  }

  assert.deepEqual(failedRequests, [], `页面有失败请求：${failedRequests.join(', ')}`);
  const unexpected = consoleErrors.filter(
    (text) => !/favicon|Download the Vue Devtools/i.test(text),
  );
  assert.deepEqual(unexpected, [], `浏览器 console 有预期外报错：${unexpected.join(' | ')}`);

  const elapsedMs = Date.now() - startedAt;
  const storeFile = process.env.CONSOLE_P3C_STORE_FILE || process.env.ARTIFACT_STORE_FILE || '';
  let inspectorDump = null;
  if (runId && storeFile && fs.existsSync(storeFile)) {
    const summary = summarizeStore(JSON.parse(fs.readFileSync(storeFile, 'utf8')), runId);
    fs.writeFileSync(path.join(shots, 'inspector.json'), JSON.stringify(summary, null, 2));
    fs.writeFileSync(path.join(shots, 'inspector.html'), formatHtml(summary));
    inspectorDump = { html: 'inspector.html', json: 'inspector.json' };
    captured.push('inspector.html', 'inspector.json');
  }
  fs.writeFileSync(
    path.join(shots, 'manifest.json'),
    JSON.stringify(
      {
        fixture: path.basename(fixturePath),
        base,
        run_id: runId,
        approval_id: approvalId,
        build_path: 'P3C',
        runtime_client_code: runtimeClientCode,
        runtime_agent_id: runtimeAgentId,
        agent_chat: agentChat,
        chat_only: chatOnly,
        inspector: inspectorDump,
        chat: chatReplies,
        timings,
        elapsed_ms: elapsedMs,
        shots: captured,
        at: new Date().toISOString(),
      },
      null,
      2,
    ),
  );
  process.stdout.write(
    `[PASS] P3C wizard generate → publish → ${messages.length} sandbox turns (${captured.length} screenshots, ${elapsedMs}ms)\n`,
  );
} finally {
  await context.close();
  await browser.close();
}
