import { createHash, createHmac, randomUUID } from 'crypto';
import { TraceRecord, TraceSink } from './trace-sink';
import { spanDisplayName } from './span-aliases';

export interface AgentLoopEnvelope {
  traceparent?: string;
  name: string;
  timestamp: string;
  attributes: Record<string, string | number | boolean>;
}

export type AgentLoopExporterMode = 'off' | 'stderr' | 'on';

/**
 * 正文采集开关（方案 §5.3，与 Java 两端 OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT 同名同义）：
 * false / none 时不写任何正文属性（LLM/AGENT 的 gen_ai.input/output.messages、工具的
 * gen_ai.tool.call.arguments/result），spec/提示词含 PII 时可整体关闭；关闭后调用链与 token 仍可用。默认采集。
 */
function captureContent(): boolean {
  const v = String(process.env.OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT ?? 'span_and_event').trim().toLowerCase();
  return !(v === 'false' || v === 'none' || v === '');
}
// 单条正文上限，避免超大 attribute（面板/传输侧都吃不消）；超出截断并标注。
const MESSAGE_MAX_CHARS = 8000;
function clamp(text: string): string {
  return text.length > MESSAGE_MAX_CHARS ? text.slice(0, MESSAGE_MAX_CHARS) + ` …(+${text.length - MESSAGE_MAX_CHARS} chars)` : text;
}
// 面板认的 JSON 形状：[{role,parts:[{type:text,content}],finish_reason?}]（与 P0 探针一致）。
function messagesJson(role: string, text: string, finishReason?: string): string {
  const msg: Record<string, unknown> = { role, parts: [{ type: 'text', content: clamp(text) }] };
  if (finishReason) msg.finish_reason = finishReason;
  return JSON.stringify([msg]);
}
// 把 data 里的值收敛成一段文本（string 原样；object/其它 JSON 序列化）。
function asText(value: unknown): string | undefined {
  if (typeof value === 'string') return value.length ? value : undefined;
  if (value === undefined || value === null) return undefined;
  try { return JSON.stringify(value); } catch { return undefined; }
}

// 阶段 span 业务 data 兜底摘要：仅当 scope 以 P1/P2/P3/P4 开头（含 P3B/P3C/P1.Wizard 等）时，
// 把 data 剔除关联字段（run_id 等）与已单独处理的 io 字段后剩下的业务字段序列化为输出。
// 底层管线（Match/Decide/Filter/Rank/Tenant/Catalogs/…）与 Flow 路标返回 undefined，保持输出列留空。
const RELATION_KEYS = new Set(['run_id','client_code','request_id','session_id','phase','tool','traceparent','agent','server','step_output','tool_input','tool_output']);
function phaseDataSummary(scope: string, data: Record<string, unknown>): string | undefined {
  if (!/^P[1-4]/.test(scope)) return undefined;
  const biz: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(data)) if (!RELATION_KEYS.has(k) && v !== undefined) biz[k] = v;
  if (!Object.keys(biz).length) return undefined;
  try { return JSON.stringify(biz); } catch { return undefined; }
}

/** 运行级 span 的 scope（见 {@link McpService.finishRun}）：一次搭建 run 收尾时补的 AGENT span。 */
export const RUN_SPAN_SCOPE = 'AgentRun';

/** TraceRecord → OTel GenAI / vibesales 属性；业务代码仍只调用 trace.step。 */
export function toAgentLoopEnvelope(rec: TraceRecord): AgentLoopEnvelope {
  const data = rec.data && typeof rec.data === 'object' && !Array.isArray(rec.data)
    ? rec.data as Record<string, unknown> : {};
  const scalar = (key: string) => {
    const value = data[key];
    return ['string','number','boolean'].includes(typeof value) ? value as string|number|boolean : undefined;
  };
  const isRun = rec.scope === RUN_SPAN_SCOPE;
  const isLlm = rec.scope.includes('Qwen');
  const attributes: Record<string, string | number | boolean> = {
    'gen_ai.operation.name': isRun ? 'invoke_agent' : isLlm ? 'chat' : 'execute_tool',
    // 面板按 gen_ai.span.kind 决定"从哪个属性读输入/输出"（loongsuite otel-util-genai 的
    // GenAiSpanKindValues）：AGENT/LLM/ENTRY 读 gen_ai.input/output.messages，TOOL 读
    // gen_ai.tool.call.arguments/result。以前不写这个属性，面板按 operation.name 猜成 TOOL，
    // 于是我们写在 messages 里的正文谁也不读——span 明细里看得见值，输入/输出栏却是空的。
    'gen_ai.span.kind': isRun ? 'AGENT' : isLlm ? 'LLM' : 'TOOL',
    'vibesales.request_id': rec.requestId,
    'vibesales.flow': rec.flow,
    'vibesales.scope': rec.scope,
    'vibesales.event': rec.event,
    'vibesales.seq': rec.seq,
    'vibesales.trace.link': typeof data.traceparent === 'string' ? 'traceparent' : 'run_id_fallback',
  };
  if (isRun) {
    // 运行级 AGENT span：面板顶部的"输入/输出"摘要与 Agents 数只认 AGENT/ENTRY 类 span，
    // 叶子 execute_tool 的正文再全也不会被汇总。这里放整条 run 的诉求与终态产物。
    const agentName = scalar('agent_name');
    if (agentName !== undefined) attributes['gen_ai.agent.name'] = agentName;
    if (captureContent()) {
      const input = asText(data.run_input);
      if (input !== undefined) attributes['gen_ai.input.messages'] = messagesJson('user', input);
      const output = asText(data.run_output);
      if (output !== undefined) attributes['gen_ai.output.messages'] = messagesJson('assistant', output, 'stop');
    }
  } else if (isLlm) {
    attributes['gen_ai.system'] = 'dashscope';
    // A22：只有可控层 1/3/4 的用量能归到 run；pre-run 向导用量不得混入 run 总量，
    // 且 stock Worker 用量在平台不提供时必须显式声明不可用。
    const runId = scalar('run_id');
    attributes['vibesales.usage.scope'] = runId ? 'run' : 'pre_run';
    if (runId) attributes['vibesales.worker_usage_available'] = false;
    // 面板 Input/Output 摘要（§5.3）：正文分布在两条记录上——chatJson.prompt 带 system/user、
    // chatJson.done 带 parsed/content。各自落到对应 span 的 input/output.messages；面板读到哪条即显示哪条。
    if (captureContent()) {
      const input = asText(data.user) ?? asText(data.system);
      if (input !== undefined) attributes['gen_ai.input.messages'] = messagesJson('user', input);
      const output = asText(data.parsed) ?? asText(data.content);
      if (output !== undefined) attributes['gen_ai.output.messages'] = messagesJson('assistant', output, 'stop');
    }
  } else if (captureContent()) {
    // 工具类 span（TOOL，如 MCP tool.call/tool.result）的面板"输入/输出"读的是
    // gen_ai.tool.call.arguments / gen_ai.tool.call.result（loongsuite otel-util-genai
    // 的 getToolCallDataAttributes），**不是** input/output.messages —— 后者只在 LLM/AGENT/ENTRY
    // 那条路径上写。所以这两个键才是工具节点正文的正主；messages 一并保留，兼容旧面板视图与
    // 已存在的自测断言，两处内容一致。
    // controller 把工具入参放 tool_input、返回值放 tool_output（已剔除 _ctx 与 approval.proof）。
    const toolInput = asText(data.tool_input);
    if (toolInput !== undefined) {
      attributes['gen_ai.tool.call.arguments'] = clamp(toolInput);
      attributes['gen_ai.input.messages'] = messagesJson('tool', toolInput);
    }
    // 输出来源三级：① 工具返回值 tool_output（MCP 入口层）② step_output（mcp.service.mark 打包的
    // 业务产出）③ 阶段 span（scope=P1..P4）的业务 data 兜底摘要——覆盖直接 trace.step 的阶段节点
    // （如 P1.Wizard.compute），无需逐处埋点。底层管线（Match/Decide/…）与 Flow 路标不摘要，避免噪声。
    const output = asText(data.tool_output) ?? asText(data.step_output) ?? phaseDataSummary(rec.scope, data);
    if (output !== undefined) {
      attributes['gen_ai.tool.call.result'] = clamp(output);
      attributes['gen_ai.output.messages'] = messagesJson('tool', output, 'stop');
    }
  }
  for (const [from, to] of [
    ['run_id','vibesales.run_id'], ['session_id','vibesales.session_id'], ['client_code','vibesales.client_code'],
    ['phase','vibesales.phase'], ['gate','vibesales.gate'], ['agent','vibesales.agent'],
    ['server','vibesales.mcp.server'], ['tool','vibesales.mcp.tool'],
    ['model','gen_ai.request.model'], ['purpose','vibesales.llm.purpose'], ['prompt_tokens','gen_ai.usage.input_tokens'],
    ['completion_tokens','gen_ai.usage.output_tokens'],
    ['approval_id','vibesales.approval.id'], ['approval_state','vibesales.approval.state'],
  ] as const) { const value = scalar(from); if (value !== undefined) attributes[to] = value; }
  // TOOL span 的标准工具名（面板工具调用列表按它归类）；vibesales.mcp.tool 保留给我们自己的看板。
  if (!isRun && !isLlm && attributes['vibesales.mcp.tool'] !== undefined) {
    attributes['gen_ai.tool.name'] = attributes['vibesales.mcp.tool'];
  }
  return {
    traceparent: typeof data.traceparent === 'string' ? data.traceparent : undefined,
    // 中文显示名（两协议一致）；tool/phase 取已映射的 vibesales.* 属性。
    name: spanDisplayName(rec.scope, rec.event, attributes['vibesales.mcp.tool'], attributes['vibesales.phase']),
    timestamp: new Date(rec.ts).toISOString(),
    attributes,
  };
}

/** Alibaba Cloud ROA canonical request. Endpoint contains the final AgentLoop resource path. */
export function agentLoopRoaHeaders(endpoint:string,body:string,accessKey:string,accessSecret:string,date:string=new Date().toUTCString(),nonce:string=randomUUID()):Record<string,string>{
  const url=new URL(endpoint),contentMd5=createHash('md5').update(body).digest('base64');
  const acs:Record<string,string>={'x-acs-signature-method':'HMAC-SHA1','x-acs-signature-nonce':nonce,'x-acs-signature-version':'1.0','x-acs-version':'2026-05-20'};
  const canonicalHeaders=Object.keys(acs).sort().map(key=>key+':'+acs[key]+'\n').join('');
  const canonicalResource=url.pathname+(url.search||'');
  const stringToSign=['POST','application/json',contentMd5,'application/json',date].join('\n')+'\n'+canonicalHeaders+canonicalResource;
  const signature=createHmac('sha1',accessSecret).update(stringToSign).digest('base64');
  return {'accept':'application/json','content-type':'application/json','content-md5':contentMd5,'date':date,...acs,'authorization':'acs '+accessKey+':'+signature};
}

/** AgentLoop 唯一出口。网络/签名失败后熔断自身，永不影响业务回包（A12）。 */
export class AgentLoopSink implements TraceSink {
  readonly name = 'agentloop';
  threshold = 'on' as const;
  private enabled = true;
  constructor(private readonly mode:AgentLoopExporterMode,private readonly endpoint?:string,private readonly accessKey?:string,private readonly accessSecret?:string,private readonly sampleRate=1) {
    if(mode==='on'&&(!endpoint||!accessKey||!accessSecret))throw new Error('AgentLoop on mode requires endpoint/access key/access secret');
    if(!Number.isFinite(sampleRate)||sampleRate<0||sampleRate>1)throw new Error('AGENTLOOP_SAMPLE_RATE must be 0.0..1.0');
    if(mode==='on')try{new URL(endpoint!);}catch{throw new Error('AGENTLOOP_ENDPOINT must be a valid URL');}
  }
  emit(rec: TraceRecord): void {
    if (!this.enabled || this.mode==='off' || !this.sampled(rec)) return;
    const body=JSON.stringify(toAgentLoopEnvelope(rec));
    if(this.mode==='stderr'){process.stderr.write('[agentloop] '+body+'\n');return;}
    let headers:Record<string,string>;
    try{headers=agentLoopRoaHeaders(this.endpoint!,body,this.accessKey!,this.accessSecret!,new Date().toUTCString(),randomUUID());}
    catch{this.enabled=false;process.stderr.write('[agentloop-exporter] disabled: signing failed\n');return;}
    void fetch(this.endpoint!, { method: 'POST', headers, body })
      .then(res => { if (!res.ok) throw new Error('AgentLoop HTTP '+res.status); })
      .catch(error => { this.enabled = false; process.stderr.write('[agentloop-exporter] disabled: '+(error instanceof Error?error.message:String(error))+'\n'); });
  }
  isEnabled(): boolean { return this.enabled; }
  private sampled(rec:TraceRecord):boolean {if(this.sampleRate===1)return true;if(this.sampleRate===0)return false;const data=rec.data&&typeof rec.data==='object'&&!Array.isArray(rec.data)?rec.data as Record<string,unknown>:{};const key=String(data.run_id??rec.requestId),bucket=createHash('sha256').update(key).digest().readUInt32BE(0)/0x100000000;return bucket<this.sampleRate;}
}
