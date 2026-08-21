import * as fs from 'fs';
import * as path from 'path';
import { spanDisplayName, TOOL_ALIASES, EVENT_ALIASES } from '../src/common/span-aliases';
import { toAgentLoopEnvelope } from '../src/common/agentloop-sink';

const eq = (got: string, want: string, msg: string) => { if (got !== want) throw new Error(`${msg}: got "${got}" want "${want}"`); };

// 1) MCP 工具入口层：{阶段}·{工具中文}·{调用|结果}。
eq(spanDisplayName('MCP', 'tool.call', 'match', 'P2'), 'P2·模板匹配·调用', 'MCP tool.call');
eq(spanDisplayName('MCP', 'tool.result', 'match', 'P2'), 'P2·模板匹配·结果', 'MCP tool.result');
eq(spanDisplayName('MCP', 'tool.call', 'composeBlueprint', 'P3C'), 'P3C·合成蓝图·调用', 'P3C compose');
eq(spanDisplayName('MCP', 'tool.call', 'import', 'P4'), 'P4·导入发布·调用', 'P4 import');

// 2) 阶段内二级 span：EVENT_ALIASES 命中。
eq(spanDisplayName('P1', 'gate.evaluated', 'buildPhase1Result', 'P1'), 'P1·闸门计算', 'P1 gate');
eq(spanDisplayName('P4', 'approval.verified', 'import', 'P4'), 'P4·审批核验', 'P4 approval');
eq(spanDisplayName('P4', 'blocked.error', 'import', 'P4'), 'P4·阻塞可重试', 'P4 blocked');

// 3) Flow 段落标记按规则推导（不逐条枚举）。
eq(spanDisplayName('Flow', 'mcp.chatflows-p3c.begin'), '流程·P3C 开始', 'Flow p3c begin');
eq(spanDisplayName('Flow', 'Match pipeline'), '流程·匹配管线', 'Flow match pipeline');

// 4) 底层/横切 span 也有中文名。
eq(spanDisplayName('Match', 'done'), '匹配·完成', 'Match done');
eq(spanDisplayName('Decide', 'branch'), '裁决·分支', 'Decide branch');
eq(spanDisplayName('Qwen', 'chatJson.done'), '大模型·响应', 'Qwen done');

// 5) 未收录 → 回退 {scope}.{event}（英文语义名，不崩、不空）。
eq(spanDisplayName('NewScope', 'some.event'), 'NewScope.some.event', 'fallback');
eq(spanDisplayName('MCP', 'tool.call', 'unknownTool', 'P9'), 'MCP.tool.call', 'unknown tool falls back');

// 6) 端到端：信封 name 用中文别名，但 gen_ai.operation.name 枚举值不变（面板分类不破）。
const rec = (scope: string, event: string, data: Record<string, unknown>) =>
  ({ kind: 'step', ts: Date.now(), seq: 1, flow: 'mcp', requestId: 'r', scope, event, data, deltaMs: 0, totalMs: 0, level: 'info' } as any);
const env = toAgentLoopEnvelope(rec('MCP', 'tool.call', { run_id: 'run-1', phase: 'P2', tool: 'match', server: 'chatflows-p2' }));
eq(env.name, 'P2·模板匹配·调用', 'envelope name is Chinese alias');
if (env.attributes['gen_ai.operation.name'] !== 'execute_tool') throw new Error('operation.name enum must stay execute_tool');
const chatEnv = toAgentLoopEnvelope(rec('Qwen', 'chatJson.done', { run_id: 'run-1', parsed: { ok: true } }));
if (chatEnv.attributes['gen_ai.operation.name'] !== 'chat') throw new Error('Qwen operation.name must stay chat');

// 7) 映射表完整性：mcp.controller 的全部工具都在 TOOL_ALIASES 里（防止新增工具漏配）。
const allTools = ['ask','revise','buildPhase1Result','match','preview','deriveGuidance','injectSections','writeBack','generate','selfcheck','listSkillCandidates','listToolCandidates','renderPersona','submitExpertResult','composeBlueprint','blueprintSelfcheck','persistBlueprint','import','bindProject','dryRun'];
const missing = allTools.filter(t => !TOOL_ALIASES[t]);
if (missing.length) throw new Error('TOOL_ALIASES missing: ' + missing.join(','));
if (Object.keys(EVENT_ALIASES).length < 20) throw new Error('EVENT_ALIASES suspiciously small');

// 8) 机械完整性守卫：扫描 src 里所有 trace.step/detail/timed 与 setFlow，
// 断言每个都能解析出中文名（不落到英文兜底）。这是"有没有漏配"的根本防线——
// 新增打点若忘了配别名，本测试直接失败并列出缺项。
const hasCJK = (s: string) => /[一-鿿]/.test(s);
const srcDir = path.join(__dirname, '..', 'src');
const files: string[] = [];
(function walk(d: string) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.ts')) files.push(p);
  }
})(srcDir);

const uncovered: string[] = [];
// scope 首参既可能是字面量（'P4'），也可能是模块级常量（SCOPE）——后者要先解析出常量值，
// 否则整族 `trace.step(SCOPE, 'x')` 会被漏检（Web.Wizard / P1.WizardLlm 就是这么漏的）。
const stepRe = /trace\.(?:step|detail|timed)\(\s*(?:'([^']+)'|([A-Z_][A-Z0-9_]*))\s*,\s*[`']([^`']+)[`']/g;
const flowRe = /setFlow\(\s*'([^']+)'/g;
const bannerRe = /trace\.banner\(\s*[`']([^`']*)[`']/g;
const scopeConstRe = /^const\s+([A-Z_][A-Z0-9_]*)\s*=\s*'([^']+)'/gm;
// 模板串里的 ${...} 占位（如 `${at}_summary.done`）无法静态求值，交由针对性用例覆盖，
// 这里跳过并单独统计，确保「跳过」是显式的而不是静默漏过。
const skippedDynamic: string[] = [];

for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  // 收集本文件的 SCOPE 类常量，供首参是标识符时解析。
  const consts: Record<string, string> = {};
  for (const m of src.matchAll(scopeConstRe)) consts[m[1]] = m[2];

  for (const m of src.matchAll(stepRe)) {
    const scope = m[1] ?? consts[m[2]!];
    const event = m[3];
    if (!scope) continue; // 未知常量（非本文件定义），无法静态解析
    if (scope === 'MCP') continue; // MCP 走 tool 别名，单独测过
    if (event.includes('${')) { skippedDynamic.push(`${scope}.${event} (${path.basename(f)})`); continue; }
    const name = spanDisplayName(scope, event);
    if (!hasCJK(name)) uncovered.push(`${scope}.${event} → "${name}" (${path.basename(f)})`);
  }
  for (const m of src.matchAll(flowRe)) {
    const flow = m[1];
    if (flow === 'mcp.') continue; // 动态拼接，运行时是 mcp.chatflows-pN，规则覆盖
    const name = spanDisplayName('Flow', `${flow}.begin`);
    if (!hasCJK(name)) uncovered.push(`Flow.${flow}.begin → "${name}" (${path.basename(f)})`);
  }
  // banner 也产 span（scope=Flow，event=自由标题），同样要有中文名。
  for (const m of src.matchAll(bannerRe)) {
    const title = m[1];
    if (title.includes('${')) { skippedDynamic.push(`banner:${title} (${path.basename(f)})`); continue; }
    const name = spanDisplayName('Flow', title);
    if (!hasCJK(name)) uncovered.push(`Flow(banner).${title} → "${name}" (${path.basename(f)})`);
  }
}
if (uncovered.length) throw new Error('以下 span 未配中文别名（落到英文兜底）:\n  ' + [...new Set(uncovered)].join('\n  '));

// 9) 动态 event（模板串）的针对性覆盖：静态扫描跳过了它们，这里按实际取值域断言。
// Web 向导摘要 event 是 `${at}_summary.{ready|done}`，at ∈ {S4,S5}。
for (const at of ['S4', 'S5']) for (const kind of ['ready', 'done']) {
  const name = spanDisplayName('Web.Wizard', `${at}_summary.${kind}`);
  if (!hasCJK(name)) throw new Error(`动态 event 未配别名: Web.Wizard.${at}_summary.${kind} → "${name}"`);
}
// Web 向导 banner 是 `Web wizard ${stage}`，stage 取 WizardStage 联合类型全集。
for (const stage of ['S1_INDUSTRY','S1_INDUSTRY_FREE','S2_GOALS','S3_BRIEF','S4_CTA','S5_DETAIL','S5_CTA','S6_REVISE','DONE','ABORTED']) {
  const name = spanDisplayName('Flow', `Web wizard ${stage}`);
  if (!hasCJK(name) || name.includes(stage)) throw new Error(`向导 banner stage 未配别名: ${stage} → "${name}"`);
}
if (!skippedDynamic.length) throw new Error('预期存在动态 event（模板串），扫描器可能失效');

process.stdout.write('[PASS] span 中文别名：全量 trace.step/setFlow 均有中文名（机械扫描守卫），回退安全，operation.name 枚举不破\n');
