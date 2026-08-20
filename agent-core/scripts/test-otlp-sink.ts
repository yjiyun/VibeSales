/**
 * 验证 OtlpTraceSink：
 *
 * A) 纯函数 toOtlpResourceSpans
 * - span 名取中文显示名（spanDisplayName），分类仍靠 gen_ai.operation.name 属性
 * - 有 traceparent 时复用其 traceId/spanId（真父子）
 * - 无 traceparent 时按 run_id 派生稳定 traceId（同会话聚合）
 * - gen_ai.session.id 补齐 = run_id
 * - Resource 带 service.name + workspace/feature/sdk.name
 *
 * B) 熔断策略（线上事故回归）
 * 一次瞬时 `fetch failed` 曾让整个 Nest 进程永久静音，控制台上 vibe-sales-nest 直接没数据。
 * 这里锁住：瞬时失败只冷却、冷却结束自动放行、成功即清零；只有 401/403 这类致命错才永久熔断。
 *
 * 设置 OTLP_LIVE=1 且 ARMS_LICENSE_KEY=... 时，额外真发一条到 endpoint 做端到端确认。
 */
import { OtlpTraceSink, toOtlpResourceSpans } from '../src/common/otlp-sink';
import { TraceRecord } from '../src/common/trace-sink';

const rec = (data: Record<string, unknown>, scope = 'MCP', event = 'tool.result'): TraceRecord => ({
  kind: 'step', ts: Date.now(), seq: 1, flow: 'P1', requestId: 'req-1',
  scope, event, data, deltaMs: 1, totalMs: 1, ms: 120, level: 'info', verboseOnly: false,
});

const cfg = {
  serviceName: 'vibe-sales-nest',
  resourceAttributes:
    'acs.cms.workspace=agentloop-7f371f9a844483cf38ba3e84bc46add5,acs.arms.service.feature=genai_app,gen_ai.instrumentation.sdk.name=loongsuite-genai-utils',
};

const spanOf = (payload: any) => payload.resourceSpans[0].scopeSpans[0].spans[0];
const attrMap = (span: any): Record<string, any> =>
  Object.fromEntries(span.attributes.map((a: any) => [a.key, Object.values(a.value)[0]]));
const resAttrMap = (payload: any): Record<string, any> =>
  Object.fromEntries(payload.resourceSpans[0].resource.attributes.map((a: any) => [a.key, Object.values(a.value)[0]]));

// 1) 带 traceparent 的工具调用 → span 名是中文（{阶段}·{工具}·{结果}），复用 traceId/spanId。
// 分类字段 gen_ai.operation.name 仍是 execute_tool —— 面板靠它出图标，名字只管好看。
const tp = '00-11111111111111111111111111111111-2222222222222222-01';
const p1 = toOtlpResourceSpans(rec({ run_id: 'run-1', client_code: 'acme', traceparent: tp, tool: 'match', server: 'p2', phase: 'P2' }), cfg) as any;
const s1 = spanOf(p1), a1 = attrMap(s1);
if (s1.name !== 'P2·模板匹配·结果') throw new Error('span name should be Chinese display name, got ' + s1.name);
if (a1['gen_ai.operation.name'] !== 'execute_tool') throw new Error('operation.name must stay execute_tool, got ' + a1['gen_ai.operation.name']);
if (s1.traceId !== '11111111111111111111111111111111') throw new Error('traceId must reuse traceparent');
if (s1.spanId !== '2222222222222222') throw new Error('spanId must reuse traceparent');
if (a1['gen_ai.session.id'] !== 'run-1') throw new Error('session.id must = run_id');
if (a1['agentteams.run_id'] !== 'run-1') throw new Error('run_id attr missing');
const r1 = resAttrMap(p1);
if (r1['service.name'] !== 'vibe-sales-nest') throw new Error('service.name missing');
if (r1['acs.arms.service.feature'] !== 'genai_app') throw new Error('feature missing');

// 2) 无 traceparent → traceId 按 run_id 派生（稳定、同 run 一致）
const p2a = toOtlpResourceSpans(rec({ run_id: 'run-2', client_code: 'acme', tool: 'a' }), cfg) as any;
const p2b = toOtlpResourceSpans(rec({ run_id: 'run-2', client_code: 'acme', tool: 'b' }), cfg) as any;
if (spanOf(p2a).traceId !== spanOf(p2b).traceId) throw new Error('same run_id must derive same traceId');
if (spanOf(p2a).spanId === spanOf(p2b).spanId) throw new Error('different spans must have different spanId');

// 3) LLM 调用（Qwen）→ 中文名「大模型·用量」，operation.name 仍是 chat
const p3 = toOtlpResourceSpans(rec({ run_id: 'run-3', model: 'qwen-plus', prompt_tokens: 10, completion_tokens: 5 }, 'Qwen', 'usage'), cfg) as any;
const a3 = attrMap(spanOf(p3));
if (spanOf(p3).name !== '大模型·用量') throw new Error('Qwen usage should be Chinese, got ' + spanOf(p3).name);
if (a3['gen_ai.operation.name'] !== 'chat') throw new Error('Qwen scope should map operation.name=chat, got ' + a3['gen_ai.operation.name']);
if (a3['gen_ai.usage.input_tokens'] !== '10') throw new Error('token mapping lost');

process.stdout.write('[PASS] toOtlpResourceSpans: 中文 span 名/operation.name 分类/traceparent 复用/run_id 派生/session.id/token/Resource 均正确\n');

// ── B) 熔断策略：瞬时失败自动恢复，致命错永久熔断 ─────────────────────────
const liveFetch = globalThis.fetch;
type FetchResult = { ok: boolean; status: number } | Error;
let queued: FetchResult[] = [];
let calls = 0;
(globalThis as any).fetch = () => {
  calls += 1;
  const next = queued.shift();
  if (next instanceof Error) return Promise.reject(next);
  return Promise.resolve({ ok: next?.ok ?? true, status: next?.status ?? 200 } as Response);
};
const stderrLines: string[] = [];
const realWrite = process.stderr.write.bind(process.stderr);
(process.stderr as any).write = (chunk: any) => { stderrLines.push(String(chunk)); return true; };
const tick = () => new Promise((r) => setImmediate(r));
const mkSink = () => new OtlpTraceSink({ mode: 'on', endpoint: 'https://example.invalid/v1/traces', serviceName: 'vibe-sales-nest' });
const one = () => rec({ run_id: 'brk', client_code: 'acme', tool: 'match', server: 'p2' });

(async () => {
  // B1) 瞬时网络失败 → 进冷却但不永久熔断；冷却期内不再发请求。
  const s = mkSink();
  queued = [new Error('fetch failed')];
  s.emit(one());
  await tick();
  if (!s.isEnabled()) throw new Error('瞬时失败不该永久熔断（这就是线上静音的成因）');
  if (!s.isCooling()) throw new Error('瞬时失败后应进入冷却');
  const before = calls;
  s.emit(one());
  if (calls !== before) throw new Error('冷却期内不应再发请求');

  // B2) 冷却到点自动放行，一次成功即清零并打 recovered。
  (s as any).cooldownUntil = Date.now() - 1;
  queued = [{ ok: true, status: 200 }];
  s.emit(one());
  await tick();
  if (calls !== before + 1) throw new Error('冷却结束后应重新尝试上报');
  if (s.isCooling()) throw new Error('成功后应退出冷却');
  if (!stderrLines.some((l) => l.includes('recovered'))) throw new Error('恢复应留一行日志，便于区分“熔断过但恢复”与“从此静音”');

  // B3) 连续失败退避递增（5s → 15s）。
  const s2 = mkSink();
  queued = [new Error('fetch failed')];
  s2.emit(one());
  await tick();
  const firstGap = (s2 as any).cooldownUntil - Date.now();
  (s2 as any).cooldownUntil = Date.now() - 1;
  queued = [{ ok: false, status: 503 }];
  s2.emit(one());
  await tick();
  const secondGap = (s2 as any).cooldownUntil - Date.now();
  if (!(secondGap > firstGap)) throw new Error(`退避未递增: ${firstGap} → ${secondGap}`);
  if (!s2.isEnabled()) throw new Error('5xx 属瞬时，不该永久熔断');

  // B4) 401/403 这类致命错 → 永久熔断（重试改不了结果）。
  for (const status of [401, 403, 404]) {
    const s3 = mkSink();
    queued = [{ ok: false, status }];
    s3.emit(one());
    await tick();
    if (s3.isEnabled()) throw new Error('HTTP ' + status + ' 应永久熔断');
  }

  (process.stderr as any).write = realWrite;
  (globalThis as any).fetch = liveFetch;
  process.stdout.write('[PASS] OtlpTraceSink 熔断：瞬时失败冷却+自动恢复+退避递增，鉴权/配置错才永久熔断\n');
})().catch((e) => {
  (process.stderr as any).write = realWrite;
  (globalThis as any).fetch = liveFetch;
  console.error(e);
  process.exit(1);
});

// 4) 可选：真发一条到 endpoint（OTLP_LIVE=1 + ARMS_LICENSE_KEY）
if (process.env.OTLP_LIVE === '1') {
  const key = process.env.ARMS_LICENSE_KEY;
  const endpoint = process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT ||
    'https://proj-xtrace-3922099f398c18d91efc45571ab29-cn-guangzhou.cn-guangzhou.log.aliyuncs.com/apm/trace/opentelemetry/v1/traces';
  if (!key) { console.error('OTLP_LIVE 需要 ARMS_LICENSE_KEY'); process.exit(2); }
  const runId = 'nest-live-' + Date.now();
  const body = JSON.stringify(toOtlpResourceSpans(
    rec({ run_id: runId, client_code: 'nest_live', tool: 'lookup_order', server: 'p1', phase: 'P1' }), cfg));
  fetch(endpoint, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-arms-license-key': key,
      'x-arms-project': 'proj-xtrace-3922099f398c18d91efc45571ab29-cn-guangzhou',
      'x-cms-workspace': 'agentloop-7f371f9a844483cf38ba3e84bc46add5',
    },
    body,
  }).then(async (res) => {
    console.log('[live] run_id=' + runId + ' HTTP ' + res.status);
    console.log('[live] 去控制台按会话 ' + runId + ' 查 execute_tool span（service=vibe-sales-nest）');
  }).catch((e) => console.error('[live] 失败', e?.message));
}
