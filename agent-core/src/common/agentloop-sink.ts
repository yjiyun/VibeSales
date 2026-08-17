/**
 * 可选集成：AgentLoop 观测出口（OTel GenAI 语义）。
 *
 * AGENTLOOP_EXPORTER=off（默认）时本模块完全不参与主链路；置为 on 才会外呼，
 * 并需要云网关的 ROA 签名凭证（AGENTLOOP_ACCESS_KEY / ACCESS_SECRET）。
 * 不使用该云服务的部署保持关闭即可，或替换为自建 OTel Collector。
 */
import { createHash, createHmac, randomUUID } from 'crypto';
import { TraceRecord, TraceSink } from './trace-sink';

export interface AgentLoopEnvelope {
  traceparent?: string;
  name: string;
  timestamp: string;
  attributes: Record<string, string | number | boolean>;
}

export type AgentLoopExporterMode = 'off' | 'stderr' | 'on';

/** TraceRecord → OTel GenAI / agentteams 属性；业务代码仍只调用 trace.step。 */
export function toAgentLoopEnvelope(rec: TraceRecord): AgentLoopEnvelope {
  const data = rec.data && typeof rec.data === 'object' && !Array.isArray(rec.data)
    ? rec.data as Record<string, unknown> : {};
  const scalar = (key: string) => {
    const value = data[key];
    return ['string','number','boolean'].includes(typeof value) ? value as string|number|boolean : undefined;
  };
  const attributes: Record<string, string | number | boolean> = {
    'gen_ai.operation.name': rec.scope.includes('Qwen') ? 'chat' : 'execute_tool',
    'agentteams.request_id': rec.requestId,
    'agentteams.flow': rec.flow,
    'agentteams.scope': rec.scope,
    'agentteams.event': rec.event,
    'agentteams.seq': rec.seq,
    'agentteams.trace.link': typeof data.traceparent === 'string' ? 'traceparent' : 'run_id_fallback',
  };
  if (rec.scope.includes('Qwen')) {
    attributes['gen_ai.system'] = 'dashscope';
    // A22：只有可控层 1/3/4 的用量能归到 run；pre-run 向导用量不得混入 run 总量，
    // 且 stock Worker 用量在平台不提供时必须显式声明不可用。
    const runId = scalar('run_id');
    attributes['agentteams.usage.scope'] = runId ? 'run' : 'pre_run';
    if (runId) attributes['agentteams.worker_usage_available'] = false;
  }
  for (const [from, to] of [
    ['run_id','agentteams.run_id'], ['session_id','agentteams.session_id'], ['client_code','agentteams.client_code'],
    ['phase','agentteams.phase'], ['gate','agentteams.gate'], ['agent','agentteams.agent'],
    ['server','agentteams.mcp.server'], ['tool','agentteams.mcp.tool'],
    ['model','gen_ai.request.model'], ['purpose','agentteams.llm.purpose'], ['prompt_tokens','gen_ai.usage.input_tokens'],
    ['completion_tokens','gen_ai.usage.output_tokens'],
    ['approval_id','agentteams.approval.id'], ['approval_state','agentteams.approval.state'],
  ] as const) { const value = scalar(from); if (value !== undefined) attributes[to] = value; }
  return {
    traceparent: typeof data.traceparent === 'string' ? data.traceparent : undefined,
    name: rec.scope + '.' + rec.event,
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
