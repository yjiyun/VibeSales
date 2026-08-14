/**
 * Console 浏览器级 UI 验收（Playwright / Chromium headless）
 *
 * 与 `test-console-contract.mjs` 的分工：
 * - contract：起 stub 后端 + vite dev server，断言 HTTP 契约（路径、头、body 约束）。
 * - 本文件：连**真** Nest BFF + 真 agent-runtime + 真 vite dev server，在真实浏览器里
 *   渲染 Vue，逐屏点击走完「搭建向导（生成+发布）→ 编排看板重放 → 沙盒试聊禁用」，断言 DOM 上看得见的判据，
 *   并逐屏截图落盘作为 `agentTeams架构改造v3-设计结构.md` §8.1 #17/#18 的运行证据。
 *
 * 三个后端由 CONSOLE_UI_BASE / 相关 env 注入，本文件不负责起进程（起停见
 * `scripts/run-console-ui-evidence.sh`），因此可以在任意已就绪的本机环境上重放。
 *
 * 凭证走 `page.addInitScript` 预置 localStorage（与 `src/shared/auth.js` 的键名一致），
 * 页面上凭证输入框都是 `type="password" show-password`，截图不泄露明文。
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const base = (process.env.CONSOLE_UI_BASE ?? 'http://127.0.0.1:25173').replace(/\/$/, '');
const shots = process.env.CONSOLE_UI_SHOTS ?? '/tmp/chatflows-ui-evidence/shots';
const wizardToken = required('CONSOLE_UI_WIZARD_TOKEN');
const pipelineToken = required('CONSOLE_UI_PIPELINE_TOKEN');
const runtimeToken = required('CONSOLE_UI_RUNTIME_TOKEN');
const runtimeClientCode = process.env.CONSOLE_UI_RUNTIME_CLIENT_CODE ?? 'smoke_beauty';
const runtimeAgentId = required('CONSOLE_UI_RUNTIME_AGENT_ID');
const actor = process.env.CONSOLE_UI_ACTOR ?? '@developer:local';

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} required`);
  return value;
}

fs.mkdirSync(shots, { recursive: true });
const captured = [];
/**
 * 截图。向导是三栏内滚布局，fullPage 只会拍到滚动容器**当前**的位置，
 * 所以凡是要给人看某张卡片当证据的，都要把那张卡先滚进视野（focus 参数）。
 */
async function shot(page, name, focus, { viewportOnly = false } = {}) {
  if (focus) await focus.scrollIntoViewIfNeeded();
  const file = path.join(shots, `${String(captured.length + 1).padStart(2, '0')}-${name}.png`);
  // viewportOnly：判据里含 el-message 这类 fixed 元素时用。fullPage 会把 fixed 元素锚到
  // 整页顶部而不是当前视口，红条就会跑到帧边缘被裁；只拍视口反而如实还原用户看到的画面。
  await page.screenshot({ path: file, fullPage: !viewportOnly });
  captured.push(path.basename(file));
  process.stdout.write(`[SHOT] ${path.basename(file)}\n`);
}

/**
 * Element Plus 的过渡默认 0.3s（抽屉位移、菜单选中色）。断言只看 DOM 里元素在不在，
 * 元素在动画途中就已经可断言了，此时截图会拍到「还在画面外的抽屉」。
 * 连续两帧位置不变才认为落位。
 */
async function settled(page, selector) {
  await page.waitForFunction(sel => {
    const node = document.querySelector(sel);
    if (!node) return false;
    const box = node.getBoundingClientRect();
    const now = `${Math.round(box.left)}x${Math.round(box.top)}x${getComputedStyle(node).opacity}`;
    const previous = node.__uiEvidenceBox;
    node.__uiEvidenceBox = now;
    return previous !== undefined && previous === now;
  }, selector, { timeout: 15_000, polling: 120 });
}

/**
 * 等左侧菜单的**高亮颜色**落到目标项。
 *
 * 注意不能只等 `.el-menu-item.is-active`：class 在点击那一刻就翻好了，但 Element Plus 的
 * 选中色是 0.3s color 过渡，此刻画面上蓝的仍是上一项。截图证据看的是像素而不是 class，
 * 所以判据必须落在 getComputedStyle().color 上 —— 目标项到位、且其余项都已褪回默认色。
 */
async function menuHighlighted(page, label) {
  await page.waitForFunction(([text, active]) => {
    const items = [...document.querySelectorAll('.el-menu-item')];
    if (!items.length) return false;
    return items.every(node => {
      const color = getComputedStyle(node).color;
      return node.innerText.trim() === text ? color === active : color !== active;
    });
  }, [label, 'rgb(64, 158, 255)'], { timeout: 15_000, polling: 100 });
}

/** el-message 被 teleport 到 body 且 fixed 在视口顶部，会盖住下一屏的判据；等它自己散场 */
async function toastsGone(page) {
  await page.waitForFunction(() => document.querySelectorAll('.el-message').length === 0, undefined, {
    timeout: 30_000,
    polling: 200,
  });
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const consoleErrors = [];
/** 用例故意触发的报错（如重放审批的 409），末尾洁净断言要把它们扣掉 */
const expectedErrors = [];
const failedRequests = [];

try {
  const page = await context.newPage();
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('requestfailed', request => failedRequests.push(`${request.method()} ${request.url()}`));
  // 三个 Bearer 分流 + role/actor 走 localStorage，键名必须与 src/shared/auth.js 一致。
  await page.addInitScript(([wizard, pipeline, runtime, role, who]) => {
    localStorage.setItem('agent-console.wizard-token', wizard);
    localStorage.setItem('agent-console.pipeline-token', pipeline);
    localStorage.setItem('agent-console.runtime-token', runtime);
    localStorage.setItem('agent-console.role', role);
    localStorage.setItem('agent-console.actor', who);
    localStorage.setItem('agent-console.page', 'wizard');
    localStorage.removeItem('agent-console.last-run-id');
    localStorage.removeItem('agent-console.last-run-mode');
    localStorage.removeItem('agent-console.publication');
  }, [wizardToken, pipelineToken, runtimeToken, 'admin', actor]);

  await page.goto(base + '/', { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: '智能体助手' }).waitFor();
  await page.getByText('从一句话开始，搭出你的第一个 Agent').waitFor();
  // 租户由服务端凭证绑定：起始屏不得出现 client_code 输入框。
  assert.equal(await page.locator('input[placeholder*="client_code" i]').count(), 0, '向导起始屏不得让用户填 client_code');
  await page.getByText('租户由当前 Bearer 凭证在服务端绑定，页面不会提交 client_code。').waitFor();
  await shot(page, 'wizard-welcome');

  await page.locator('.wz-start__form').getByRole('button', { name: '开始' }).click();

  // S1 行业单选：点击即提交
  const industryCard = page.locator('.wz-qcard').last();
  await industryCard.getByText('你所在的行业是什么？').waitFor();
  await shot(page, 'wizard-s1-industry', industryCard);
  await industryCard.locator('.wz-opt', { hasText: '美妆' }).first().click();

  // S2 业务目标多选：勾三项后点提交
  const goalsCard = page.locator('.wz-qcard:not(.is-history)').last();
  await goalsCard.getByText('你更希望 AI 先帮你处理哪类业务？').waitFor();
  for (const label of [
    '回答客户常见问题，减少流失和重复咨询',
    '介绍产品/服务，并推荐合适方案',
    '收集问题信息，并转交给人工处理',
  ]) {
    await goalsCard.locator('.wz-opt', { hasText: label }).first().click();
  }
  const picked = await goalsCard.locator('.wz-opt.is-active').count();
  assert.equal(picked, 3, `业务目标应勾中三项，实际 ${picked}`);
  await shot(page, 'wizard-s2-goals', goalsCard);
  await goalsCard.getByRole('button', { name: '提交' }).click();

  // S3 业务简述：自由文本走底部 XSender
  const briefCard = page.locator('.wz-qcard:not(.is-history)').last();
  await briefCard.getByText('简单说说你主要卖什么、或者主要做什么业务。').waitFor();
  await briefCard.getByText('请在底部输入框回答本问题。').waitFor();
  // XSender 是 contenteditable 富文本，不是 input/textarea，只能敲键盘不能 fill()。
  const sender = page.locator('.wz-compose [contenteditable="true"]').first();
  await sender.click();
  await page.keyboard.type('我们是美妆品牌，希望企业微信客服自动回答护肤咨询、推荐产品，复杂问题转人工。');
  await shot(page, 'wizard-s3-brief', briefCard);
  await page.keyboard.press('Enter');

  // S4 CTA：选「先看看效果」收口成 Phase1Result
  const ctaCard = page.locator('.wz-qcard:not(.is-history)').last();
  const preview = ctaCard.locator('.wz-opt', { hasText: '先看看效果' }).first();
  await preview.waitFor({ timeout: 60_000 });
  await shot(page, 'wizard-s4-cta', ctaCard);
  await preview.click();

  // 结果卡：gate=PASS + 可生成 v0
  const result = page.locator('.wz-result').last();
  await result.getByText('向导已完成').waitFor({ timeout: 30_000 });
  await result.getByText('闸门 PASS').waitFor();
  await result.getByText('可生成 v0').waitFor();
  // 头部状态标签：stage=DONE + 已完成，证明 P1 真的收口而不是卡在某一问
  await page.locator('.wz-header').getByText('DONE', { exact: true }).waitFor();
  await page.locator('.wz-header').getByText('已完成', { exact: true }).waitFor();
  await shot(page, 'wizard-result-gate-pass', result);

  // §8.6/§8.7：CTA「先看看效果」直串 P2，匹配结果落成时间线独立一格
  const matches = page.locator('.wz-timeline-item.is-match');
  await matches.first().waitFor({ timeout: 60_000 });
  const matched = await matches.count();
  await shot(page, 'wizard-p2-match', matches.first());

  // 重跑 P2：旧卡降级为历史，新卡进流末尾
  await result.getByRole('button', { name: /重跑匹配（P2）|先看看效果（P2 匹配）/ }).click();
  await page.waitForFunction(
    count => document.querySelectorAll('.wz-timeline-item.is-match').length > count,
    matched,
    { timeout: 60_000 },
  );
  assert.ok(
    await page.locator('.wz-timeline-item.is-match .wz-match.is-history').count() > 0,
    '重跑 P2 后旧匹配卡应降级为历史版本',
  );
  await shot(page, 'wizard-p2-rerun-history', page.locator('.wz-timeline-item.is-match').last());

  // 结果卡 CTA 生成：local 模式必须经此入口，提交的是权威 Phase1Result
  const buildButton = result.getByRole('button', { name: /开始生成（local）/ });
  await buildButton.waitFor();
  await buildButton.click();
  const publish = page.locator('.wz-publish').last();
  await publish.getByText('WAITING_HUMAN').waitFor({ timeout: 60_000 });
  const runId = await page.evaluate(() => localStorage.getItem('agent-console.last-run-id'));
  assert.match(runId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/, `run_id 不是 UUID：${runId}`);
  await shot(page, 'wizard-build-run-created', publish);
  assert.equal(
    await page.evaluate(() => localStorage.getItem('agent-console.last-run-id')),
    runId,
    '向导生成后必须把 run_id 交给控制面',
  );
  assert.equal(await page.evaluate(() => localStorage.getItem('agent-console.last-run-mode')), 'local');

  await publish.getByRole('button', { name: '确认发布' }).click();
  await page.locator('.el-message--success', { hasText: '已发布' }).waitFor({ timeout: 60_000 });
  await publish.getByText('已发布').first().waitFor();
  await shot(page, 'wizard-published', publish);

  // 编排看板仍可排障：接过 run_id，验证时间线，并重放审批必须 409
  await toastsGone(page);
  await page.getByRole('menuitem', { name: '编排看板' }).click();
  await page.getByRole('heading', { name: '编排看板' }).waitFor();
  await menuHighlighted(page, '编排看板');
  assert.equal(await page.locator('input[placeholder="run_id"]').inputValue(), runId, '看板要自动接过上一步的 run_id');
  await page.getByRole('button', { name: '刷新' }).click();
  await page.getByText('Artifact 时间线').waitFor({ timeout: 30_000 });
  const timeline = page.locator('.el-timeline').first();
  await timeline.locator('.el-timeline-item', { hasText: 'wizard_state' }).first().waitFor();
  for (const kind of ['triage', 'match_result', 'guidance', 'approval', 'evidence']) {
    assert.ok(
      await timeline.locator('.el-timeline-item', { hasText: kind }).count() > 0,
      `Artifact 时间线缺 ${kind}`,
    );
  }
  const descriptions = page.locator('.el-descriptions').first();
  await descriptions.getByText('SUCCEEDED').waitFor({ timeout: 30_000 });
  await descriptions.getByText(runId).waitFor();
  const approvalInput = page.locator('input[placeholder="approval_id"]');
  const approvalId = await approvalInput.inputValue();
  assert.match(approvalId, /^[0-9a-f-]{36}$/, `看板要带出 approval_id，实际 ${approvalId}`);
  await shot(page, 'runs-waiting-human', descriptions);

  // 同一 approval_id 不得二次生效（防重放）：run 已 SUCCEEDED，控制面必须 409。
  const errorsBeforeReplay = consoleErrors.length;
  await toastsGone(page);
  await page.getByRole('button', { name: '批准' }).click();
  const rejected = page.locator('.el-message--error').first();
  await rejected.waitFor({ timeout: 30_000 });
  // 红条也是 0.3s 进场（从 translateY(-100%) 滑下），挂上 DOM 那刻还在视口上方，
  // 先等它落位。它只活 3s，所以文案要在截图前取走，取晚了节点已 detach。
  await settled(page, '.el-message--error');
  const rejectedText = (await rejected.innerText()).trim();
  // 红条 fixed 在视口顶部，滚动不影响它；把 run 面板滚上来，让「SUCCEEDED」与拒绝原因同框。
  await descriptions.scrollIntoViewIfNeeded();
  await shot(page, 'runs-replay-rejected', undefined, { viewportOnly: true });
  const replayErrors = consoleErrors.slice(errorsBeforeReplay);
  assert.ok(
    replayErrors.some(text => text.includes('409')),
    `重放审批应让浏览器收到 409，实际 console：${replayErrors.join(' | ')}`,
  );
  expectedErrors.push(...replayErrors);
  assert.match(rejectedText, /run is not waiting for P4 approval/, '重放审批的红条应给出控制面的拒绝原因');

  // 沙盒试聊：P3 发布的是工作流，没有可对话智能体，右侧 Tab 必须禁用，也不得出现 seed。
  await toastsGone(page);
  await page.getByRole('menuitem', { name: '搭建向导' }).click();
  await page.getByRole('heading', { name: '智能体助手' }).waitFor();
  await menuHighlighted(page, '搭建向导');
  const sandboxTab = page.locator('.wz-runtime-tabs').getByRole('tab', { name: '沙盒试聊' });
  await sandboxTab.waitFor();
  assert.ok(
    (await sandboxTab.getAttribute('aria-disabled')) === 'true'
      || (await sandboxTab.evaluate((el) => el.classList.contains('is-disabled'))),
    '没有可对话产物时沙盒试聊应禁用',
  );
  assert.equal(
    await page.locator('.el-form-item', { hasText: 'clientCode' }).count(),
    0,
    '沙盒试聊默认不得手填 seed clientCode',
  );
  assert.equal(
    await page.evaluate(() => JSON.parse(localStorage.getItem('agent-console.publication') || '{}').runtimeAgentId || ''),
    '',
    'P3 发布不得写入 P3C runtimeAgentId',
  );
  await shot(page, 'wizard-sandbox-disabled');

  // 凭证抽屉：五个 Bearer 必须是 password 输入，截图不泄露明文
  await page.getByRole('button', { name: '连接凭证' }).click();
  await page.getByText('本机开发连接凭证').waitFor();
  // 抽屉从右侧滑入 0.3s。文本一挂上 DOM 就可断言了，但那一刻抽屉还在视口外，
  // 直接截图会拍到「没有抽屉的聊天页」。等它位置稳定再拍。
  await settled(page, '.el-drawer');
  const passwordInputs = page.locator('.el-drawer input[type="password"]');
  const passwordCount = await passwordInputs.count();
  assert.equal(passwordCount, 5, `凭证抽屉应有 5 个密码框，实际 ${passwordCount}`);
  const visible = await page.locator('.el-drawer input:not([type="password"])').evaluateAll(
    nodes => nodes.map(node => node.value).filter(value => /token|secret/i.test(value)),
  );
  assert.deepEqual(visible, [], '凭证抽屉不得以明文渲染任何 token');
  await shot(page, 'credentials-masked');

  assert.deepEqual(failedRequests, [], `页面有失败请求：${failedRequests.join(', ')}`);
  const unexpected = consoleErrors.filter(
    text => !/favicon|Download the Vue Devtools/i.test(text) && !expectedErrors.includes(text),
  );
  assert.deepEqual(unexpected, [], `浏览器 console 有预期外报错：${unexpected.join(' | ')}`);

  fs.writeFileSync(
    path.join(shots, 'manifest.json'),
    JSON.stringify({ base, run_id: runId, approval_id: approvalId, shots: captured, at: new Date().toISOString() }, null, 2),
  );
  process.stdout.write(`[PASS] Console UI walks wizard generate/publish → ops replay 409 (${captured.length} screenshots)\n`);
} finally {
  await context.close();
  await browser.close();
}
