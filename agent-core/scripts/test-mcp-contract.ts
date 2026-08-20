import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppMcpModule } from '../src/app-mcp.module';
import { agentForMcpServer, McpController } from '../src/mcp/mcp.controller';
import { agentLoopRoaHeaders, toAgentLoopEnvelope } from '../src/common/agentloop-sink';
import { TraceService } from '../src/common/trace.service';
import { TraceRecord, TraceSink } from '../src/common/trace-sink';
import { randomUUID } from 'crypto';

async function main() {
  process.env.MCP_SERVER_TOKEN = 'mcp-contract-token-0123456789';
  const app = await NestFactory.createApplicationContext(AppMcpModule, { logger: false });
  const controller = app.get(McpController);
  // 捕获所有 trace 记录，验证 MCP 工具内部的二级打点确实上报（controller 的 tool.call/result 之外的阶段内 span）。
  const captured: TraceRecord[] = [];
  const capture: TraceSink = { name: 'capture', threshold: 'verbose', emit: (rec) => { captured.push(rec); } };
  app.get(TraceService).addSink(capture);
  let denied=false;try{await controller.rpc('chatflows-p1',{jsonrpc:'2.0',id:0,method:'initialize'},{});}catch(error:any){denied=error?.getStatus?.()===401;}
  if(!denied)throw new Error('MCP must reject missing Bearer token');
  const auth={authorization:'Bearer '+process.env.MCP_SERVER_TOKEN};
  const expected: Record<string, number> = {
    'chatflows-p1': 3, 'chatflows-p2': 2, 'chatflows-p3': 3,
    'chatflows-p3b': 2, 'chatflows-p3c': 6, 'chatflows-p4': 3,
  };
  for (const [server, count] of Object.entries(expected)) {
    if(!['wizard-intent','template-match','template-personalize','flow-generate','blueprint-compose','flow-import-run'].includes(agentForMcpServer(server)))throw new Error(server+' invalid AgentLoop identity');
    const init: any = await controller.rpc(server, { jsonrpc: '2.0', id: 1, method: 'initialize' }, auth);
    if (init.result.protocolVersion !== '2025-03-26') throw new Error(server + ' initialize failed');
    const listed: any = await controller.rpc(server, { jsonrpc: '2.0', id: 2, method: 'tools/list' }, auth);
    if (listed.result.tools.length !== count) throw new Error(server + ' tool count mismatch');
    if(listed.result.tools.some((tool:any)=>!tool.inputSchema?.required?.includes('_ctx')||!tool.inputSchema?.properties?._ctx?.required?.includes('run_id')||!tool.inputSchema?.properties?._ctx?.required?.includes('client_code')))throw new Error(server+' tool schema does not require task context');
  }
  const triage = { scene_id: 'beauty_wecom_cs', agent_family: 'customer_success', channel: 'wecom', industry: 'beauty', confidence: 0.9, reason: 'contract', known_slots: { industry: 'beauty', role: 'customer_success', desired_capabilities: ['faq_retrieve'] } };
  const runId=randomUUID();
  const called: any = await controller.rpc('chatflows-p1', { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'ask', arguments: { triage, _ctx: { run_id: runId, client_code: 'acme', request_id: 'req-1', traceparent: '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01' } } } }, auth);
  if (called.error || called.result.isError) throw new Error('MCP tools/call failed');
  if(!/^[0-9a-f-]{36}$/.test(called.result._meta?.request_id)||called.result._meta.request_id==='req-1')throw new Error('Nest did not generate/echo authoritative request_id');
  // 二级打点：P1.ask 内部应打出 gate.evaluated（scope=P1），带 run_id/phase/tool，且区别于 controller 的 MCP.tool.call。
  const p1Gate=captured.find(r=>r.scope==='P1'&&r.event==='gate.evaluated');
  if(!p1Gate)throw new Error('P1 ask did not emit gate.evaluated sub-step span');
  const gd=p1Gate.data as Record<string,unknown>;
  if(gd.run_id!==runId||gd.phase!=='P1'||gd.tool!=='ask'||!('gate' in gd))throw new Error('P1 gate.evaluated span missing run_id/phase/tool/gate: '+JSON.stringify(gd));
  const p1ToolCall=captured.find(r=>r.scope==='MCP'&&r.event==='tool.call');
  if(!p1ToolCall)throw new Error('controller-level MCP.tool.call span missing (entry span regressed)');
  // 工具节点面板输入：tool.call 带 tool_input（含 triage），且经 sanitizeToolIo 去掉 _ctx；经信封映射到 gen_ai.input.messages。
  const tcData=p1ToolCall.data as Record<string,any>;
  if(!tcData.tool_input||'_ctx' in tcData.tool_input||JSON.stringify(tcData.tool_input.triage?.scene_id)!=='"beauty_wecom_cs"')throw new Error('MCP.tool.call missing sanitized tool_input: '+JSON.stringify(tcData.tool_input));
  const p1CallEnv=toAgentLoopEnvelope(p1ToolCall);
  const p1InMsg=p1CallEnv.attributes['gen_ai.input.messages'];
  if(typeof p1InMsg!=='string'||JSON.parse(p1InMsg)[0].role!=='tool'||!p1InMsg.includes('beauty_wecom_cs'))throw new Error('tool.call did not map to gen_ai.input.messages: '+p1InMsg);
  const p1ToolResult=captured.find(r=>r.scope==='MCP'&&r.event==='tool.result');
  const p1ResEnv=p1ToolResult?toAgentLoopEnvelope(p1ToolResult):undefined;
  if(!p1ResEnv||typeof p1ResEnv.attributes['gen_ai.output.messages']!=='string')throw new Error('tool.result did not map to gen_ai.output.messages');
  const missing: any = await controller.rpc('chatflows-p1', { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'ask', arguments: { triage } } }, auth);
  if (!missing.error?.message.includes('_ctx.run_id')) throw new Error('MCP must reject missing run_id');
  const nonObject:any=await controller.rpc('chatflows-p1',{jsonrpc:'2.0',id:40,method:'tools/call',params:{name:'ask',arguments:{triage:'not-json',_ctx:{run_id:randomUUID(),client_code:'acme'}}}},auth);
  if(!nonObject.error?.message.includes('triage must be a JSON object'))throw new Error('P1 accepted non-object triage');
  // Web 已完成的 Phase1Result 必须被 gate 重算后原样保留（stage / summary 不得被 MCP 丢掉）。
  const completedRun=randomUUID(),phase1={phase:'P1',client_code:'acme',stage:'S1_SUMMARY',gate:'PASS',triage,summary:{industry:{id:'beauty',name:'美妆'},business_goals:[],role_positioning:'客服',core_capabilities:['问答'],current_focus:'答疑',knowledge_packs_planned:[]}};
  const reused:any=await controller.rpc('chatflows-p1',{jsonrpc:'2.0',id:41,method:'tools/call',params:{name:'buildPhase1Result',arguments:{clientCode:'acme',triage,phase1Result:phase1,_ctx:{run_id:completedRun,client_code:'acme',request_id:'reuse-1'}}}},auth);
  if(reused.error||reused.result?.content?.[0]?.text==null||!reused.result.content[0].text.includes('S1_SUMMARY')||!reused.result.content[0].text.includes('summary'))throw new Error('completed Web Phase1Result was not gate-verified and preserved');
  const p4Bypass:any=await controller.rpc('chatflows-p4',{jsonrpc:'2.0',id:5,method:'tools/call',params:{name:'import',arguments:{runId,clientCode:'acme',path:'P3B',payload:{},_ctx:{run_id:runId,client_code:'acme'}}}},auth);
  if(!p4Bypass.error?.message.includes('build_path'))throw new Error('P4 MCP import bypassed run state gate');
  const confused:any=await controller.rpc('chatflows-p4',{jsonrpc:'2.0',id:6,method:'tools/call',params:{name:'import',arguments:{runId:randomUUID(),clientCode:'acme',path:'P3B',payload:{},_ctx:{run_id:runId,client_code:'acme'}}}},auth);
  if(!confused.error?.message.includes('runId must match'))throw new Error('MCP accepted mismatched run identity');
  const envelope = toAgentLoopEnvelope({ kind:'step', ts:Date.now(), seq:1, flow:'mcp', requestId:'req-1', scope:'MCP', event:'tool.call', data:{run_id:'run-contract',client_code:'acme',traceparent:'00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'}, deltaMs:0,totalMs:0,level:'info',verboseOnly:false });
  if (envelope.attributes['agentteams.run_id'] !== 'run-contract' || !envelope.traceparent) throw new Error('AgentLoop mapping failed');
  if(envelope.attributes['agentteams.trace.link']!=='traceparent')throw new Error('AgentLoop did not record the trace linkage mode');
  // pre-run 向导用量不得混入 run 总量；run 用量必须显式声明 stock Worker 用量不可得（A22）。
  const usageEnvelope=toAgentLoopEnvelope({kind:'step',ts:Date.now(),seq:2,flow:'web',requestId:'req-2',scope:'Qwen',event:'usage',data:{session_id:'session-1',client_code:'acme',model:'qwen-plus',purpose:'triage',prompt_tokens:3,completion_tokens:4},deltaMs:0,totalMs:0,level:'info',verboseOnly:false});
  if(usageEnvelope.attributes['gen_ai.system']!=='dashscope'||usageEnvelope.attributes['gen_ai.request.model']!=='qwen-plus'||usageEnvelope.attributes['agentteams.llm.purpose']!=='triage'||usageEnvelope.attributes['agentteams.session_id']!=='session-1'||usageEnvelope.attributes['agentteams.request_id']!=='req-2'||usageEnvelope.attributes['agentteams.run_id']!==undefined||usageEnvelope.attributes['agentteams.usage.scope']!=='pre_run'||usageEnvelope.attributes['agentteams.worker_usage_available']!==undefined)throw new Error('AgentLoop pre-run GenAI attribution missing or falsely run-bound');
  const runUsage=toAgentLoopEnvelope({kind:'step',ts:Date.now(),seq:3,flow:'mcp',requestId:'req-3',scope:'Qwen',event:'usage',data:{run_id:'run-contract',model:'qwen-plus',prompt_tokens:5,completion_tokens:6},deltaMs:0,totalMs:0,level:'info',verboseOnly:false});
  if(runUsage.attributes['agentteams.usage.scope']!=='run'||runUsage.attributes['agentteams.worker_usage_available']!==false)throw new Error('AgentLoop run usage did not disclose unavailable Worker usage');
  const mcpMeta=toAgentLoopEnvelope({kind:'step',ts:Date.now(),seq:4,flow:'mcp',requestId:'req-4',scope:'MCP',event:'tool.result',data:{run_id:'run-contract',server:'chatflows-p4',tool:'import',approval_id:'approval-1',approval_state:'pending_approval'},deltaMs:0,totalMs:0,level:'info',verboseOnly:false});
  if(mcpMeta.attributes['agentteams.mcp.server']!=='chatflows-p4'||mcpMeta.attributes['agentteams.mcp.tool']!=='import'||mcpMeta.attributes['agentteams.approval.id']!=='approval-1'||mcpMeta.attributes['agentteams.approval.state']!=='pending_approval')throw new Error('AgentLoop MCP/approval attribution missing');
  const signed=agentLoopRoaHeaders('https://agentloop.example/v1/spans?project=demo','{}','test-key','test-secret','Wed, 13 Aug 2026 07:30:00 GMT','nonce-1');
  if(signed.authorization!=='acs test-key:d/N2sblTLy+lcGpke7fF52+W5Vo='||signed['x-acs-version']!=='2026-05-20'||!signed['content-md5'])throw new Error('AgentLoop ROA signing vector failed: '+JSON.stringify(signed));
  process.stdout.write('[PASS] authenticated MCP contracts, mandatory run_id, P4 Human gate, AgentLoop OTel mapping\n');
  await app.close();
}
main().then(() => process.exit(0)).catch(error => { console.error(error); process.exit(1); });
