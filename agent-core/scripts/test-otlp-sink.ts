/**
 * 验证 OtlpTraceSink 的纯函数 toOtlpResourceSpans：
 * - span 名取 gen_ai.operation.name（面板识别）
 * - 有 traceparent 时复用其 traceId/spanId（真父子）
 * - 无 traceparent 时按 run_id 派生稳定 traceId（同会话聚合）
 * - gen_ai.session.id 补齐 = run_id
 * - Resource 带 service.name + workspace/feature/sdk.name
 *
 * 设置 OTLP_LIVE=1 且 ARMS_LICENSE_KEY=... 时，额外真发一条到 endpoint 做端到端确认。
 */
import { toOtlpResourceSpans } from '../src/common/otlp-sink';
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

// 1) 带 traceparent 的工具调用 → span 名 execute_tool，复用 traceId/spanId
const tp = '00-11111111111111111111111111111111-2222222222222222-01';
const p1 = toOtlpResourceSpans(rec({ run_id: 'run-1', client_code: 'acme', traceparent: tp, tool: 'lookup', server: 'p1' }), cfg) as any;
const s1 = spanOf(p1), a1 = attrMap(s1);
if (s1.name !== 'execute_tool') throw new Error('span name should be execute_tool, got ' + s1.name);
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

// 3) LLM 调用（Qwen）→ span 名 chat
const p3 = toOtlpResourceSpans(rec({ run_id: 'run-3', model: 'qwen-plus', prompt_tokens: 10, completion_tokens: 5 }, 'QwenService.chatJson', 'usage'), cfg) as any;
if (spanOf(p3).name !== 'chat') throw new Error('Qwen scope should map to chat, got ' + spanOf(p3).name);
if (attrMap(spanOf(p3))['gen_ai.usage.input_tokens'] !== '10') throw new Error('token mapping lost');

process.stdout.write('[PASS] toOtlpResourceSpans: span 名/traceparent 复用/run_id 派生/session.id/token/Resource 均正确\n');

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
