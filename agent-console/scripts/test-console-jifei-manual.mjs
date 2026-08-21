/**
 * 极飞向导手工路径自动化（Playwright）。
 *
 * 对齐 docs/agentteams/测试用例/test2-jifei-rag-manual.md：
 * 关 LLM 接待员 → S1–S4 → 点下「开始生成（local|platform）」→ 等到 run 已创建即停。
 * 不等「确认发布」、不去沙盒试聊、不打开编排看板。
 *
 * 起停见 scripts/run-console-jifei-manual.sh。
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const require = createRequire(import.meta.url);
const { parse: parseYaml } = require('../../agent-core/node_modules/yaml');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '../..');
const fixturePath =
  process.env.CONSOLE_JIFEI_FIXTURE ??
  path.join(rootDir, 'agent-core/fixtures/wizard-e2e/jifei-manual.yaml');
const fixture = parseYaml(fs.readFileSync(fixturePath, 'utf8'));

const base = (process.env.CONSOLE_UI_BASE ?? 'http://127.0.0.1:25193').replace(/\/$/, '');
const shots =
  process.env.CONSOLE_JIFEI_SHOTS ?? path.join(rootDir, 'agent-core/tmp/wizard-jifei-manual');
const wizardToken = required('CONSOLE_UI_WIZARD_TOKEN');
const pipelineToken = required('CONSOLE_UI_PIPELINE_TOKEN');
const runtimeToken = process.env.CONSOLE_UI_RUNTIME_TOKEN ?? '';
const actor = process.env.CONSOLE_UI_ACTOR ?? '@developer:local';

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

async function shot(page, name, focus) {
  if (focus) await focus.scrollIntoViewIfNeeded();
  const file = path.join(shots, `${String(captured.length + 1).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  captured.push(path.basename(file));
  process.stdout.write(`[SHOT] ${path.basename(file)}\n`);
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

const headed = process.env.CONSOLE_JIFEI_HEADED === '1';
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

  await page.addInitScript(([wizard, pipeline, runtime, runtimeAdmin, manager, managerAdmin, role, who]) => {
    localStorage.setItem('agent-console.wizard-token', wizard);
    localStorage.setItem('agent-console.pipeline-token', pipeline);
    localStorage.setItem('agent-console.runtime-token', runtime);
    localStorage.setItem('agent-console.runtime-admin-token', runtimeAdmin);
    localStorage.setItem('agent-console.manager-token', manager);
    localStorage.setItem('agent-console.manager-admin-token', managerAdmin);
    localStorage.setItem('agent-console.role', role);
    localStorage.setItem('agent-console.actor', who);
    localStorage.setItem('agent-console.page', 'wizard');
    localStorage.removeItem('agent-console.last-run-id');
    localStorage.removeItem('agent-console.last-run-mode');
    localStorage.removeItem('agent-console.publication');
    const style = document.createElement('style');
    style.textContent = '*,*::before,*::after{transition:none!important;animation:none!important}';
    document.documentElement.appendChild(style);
  }, [
    wizardToken,
    pipelineToken,
    runtimeToken,
    process.env.CONSOLE_UI_RUNTIME_ADMIN_TOKEN ?? '',
    process.env.CONSOLE_UI_MANAGER_TOKEN ?? '',
    process.env.CONSOLE_UI_MANAGER_ADMIN_TOKEN ?? '',
    'admin',
    actor,
  ]);

  await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '智能体助手' }).waitFor();
  await page.getByText('从一句话开始，搭出你的第一个 Agent').waitFor();
  mark('welcome-ready');

  const llmSwitch = page.locator('.wz-start__form .el-switch').first();
  if ((await llmSwitch.count()) > 0) {
    const checked = await llmSwitch.getAttribute('aria-checked');
    if (checked === 'true') await llmSwitch.click({ force: true }).catch(() => {});
  }
  await page.route(
    (url) => url.pathname === '/api/wizard/sessions',
    async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      const post = route.request().postDataJSON() ?? {};
      await route.continue({
        postData: JSON.stringify({ ...post, llm: false }),
        headers: {
          ...route.request().headers(),
          'content-type': 'application/json',
        },
      });
    },
  );
  await shot(page, 'wizard-welcome');
  const welcomeExpect = step('welcome')?.expect ?? {};
  const modelSelect = page.locator('.wz-model-select');
  await modelSelect.waitFor();
  await modelSelect.getByText(welcomeExpect.default_model ?? 'deepseek-v4-flash').waitFor();
  await page.locator('.wz-start__form').getByRole('button', { name: '开始' }).click();

  const s1 = step('S1_INDUSTRY');
  const industryCard = page.locator('.wz-qcard').last();
  await industryCard.getByText('你所在的行业是什么？').waitFor({ timeout: 20_000 });
  await industryCard.locator('.wz-opt', { hasText: s1.label }).first().click();
  await shot(page, 'wizard-s1-industry', industryCard);
  await industryCard.getByRole('button', { name: '确认' }).click();
  mark('s1-industry');

  const s2 = step('S2_GOALS');
  const goalsCard = page.locator('.wz-qcard:not(.is-history)').last();
  await goalsCard.getByText('你更希望 AI 先帮你处理哪类业务？').waitFor({ timeout: 20_000 });
  for (const label of s2.labels) {
    await goalsCard.locator('.wz-opt', { hasText: label }).first().click();
  }
  assert.equal(
    await goalsCard.locator('.wz-opt.is-active').count(),
    s2.labels.length,
    `业务目标应勾中 ${s2.labels.length} 项`,
  );
  await shot(page, 'wizard-s2-goals', goalsCard);
  await goalsCard.getByRole('button', { name: '确认' }).click();
  mark('s2-goals');

  const s3 = step('S3_BRIEF');
  const briefCard = page.locator('.wz-qcard:not(.is-history)').last();
  await briefCard.getByText('简单说说你主要卖什么、或者主要做什么业务。').waitFor({ timeout: 20_000 });
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
  await result.getByText('向导已完成').waitFor({ timeout: 45_000 });
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

  const buildButton = result.getByRole('button', { name: /开始构建（(local|platform)）/ });
  await buildButton.waitFor();
  assert.equal(await buildButton.isEnabled(), true, '开始构建 应可点');
  const buttonLabel = (await buildButton.innerText()).trim();
  await shot(page, 'wizard-before-generate', result);
  await buildButton.click();
  mark('generate-clicked');

  await page.waitForFunction(() => {
    const id = localStorage.getItem('agent-console.last-run-id') || '';
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id);
  }, null, { timeout: 30_000 });
  const runId = await page.evaluate(() => localStorage.getItem('agent-console.last-run-id'));
  const runMode = await page.evaluate(() => localStorage.getItem('agent-console.last-run-mode'));
  await page.getByText(/正在按你的需求生成智能体/).first().waitFor({ timeout: 10_000 }).catch(() => {});
  await shot(page, 'wizard-generate-started');
  mark('generate-started');

  const unexpected = consoleErrors.filter((text) => !/favicon|Download the Vue Devtools/i.test(text));
  const unexpectedFails = failedRequests.filter((item) => !/favicon/i.test(item));
  assert.deepEqual(unexpectedFails, [], `页面有失败请求：${unexpectedFails.join(', ')}`);
  assert.deepEqual(unexpected, [], `浏览器 console 有预期外报错：${unexpected.join(' | ')}`);

  const elapsedMs = Date.now() - startedAt;
  fs.writeFileSync(
    path.join(shots, 'manifest.json'),
    JSON.stringify(
      {
        fixture: path.basename(fixturePath),
        base,
        stop_at: 'start_generate',
        generate_button: buttonLabel,
        run_id: runId,
        run_mode: runMode,
        scene_id: phase1.triage?.scene_id,
        needs_long_term_memory: phase1.triage?.needs_long_term_memory === true,
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
    `[PASS] jifei-manual clicked ${buttonLabel} run_id=${runId} mode=${runMode} (${captured.length} shots, ${elapsedMs}ms)\n`,
  );
} finally {
  await context.close();
  await browser.close();
}
