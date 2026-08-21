import { RUN_SPAN_SCOPE, toAgentLoopEnvelope } from '../src/common/agentloop-sink';
import { sanitizeToolIo } from '../src/mcp/mcp.controller';

// 无 env 依赖：直接喂 TraceRecord，断言 Qwen chat 正文映射到面板认的 gen_ai.input/output.messages。
const rec = (event: string, data: Record<string, unknown>) =>
  ({ kind: 'step', ts: Date.now(), seq: 1, flow: 'P1', requestId: 'req-1', scope: 'QwenService.chatJson', event, data, deltaMs: 1, totalMs: 1, level: 'info' } as any);

// 1) prompt 记录（system/user）→ input.messages；正文里的引号/换行被 JSON 正确转义。
const promptEnv = toAgentLoopEnvelope(rec('chatJson.prompt', { run_id: 'run-1', system: 'sys', user: 'hi\n"quoted"' }));
const inMsg = promptEnv.attributes['gen_ai.input.messages'];
if (typeof inMsg !== 'string') throw new Error('input.messages missing on prompt span');
const inParsed = JSON.parse(inMsg);
if (inParsed[0].role !== 'user' || inParsed[0].parts[0].type !== 'text' || inParsed[0].parts[0].content !== 'hi\n"quoted"')
  throw new Error('input.messages shape/content mismatch: ' + inMsg);

// 2) done 记录（parsed 对象）→ output.messages，object 被 JSON 序列化，带 finish_reason。
const doneEnv = toAgentLoopEnvelope(rec('chatJson.done', { run_id: 'run-1', parsed: { gate: 'PASS' }, content_chars: 5 }));
const outMsg = doneEnv.attributes['gen_ai.output.messages'];
if (typeof outMsg !== 'string') throw new Error('output.messages missing on done span');
const outParsed = JSON.parse(outMsg);
if (outParsed[0].role !== 'assistant' || outParsed[0].finish_reason !== 'stop' || outParsed[0].parts[0].content !== '{"gate":"PASS"}')
  throw new Error('output.messages shape/content mismatch: ' + outMsg);

// 3) 工具类 span（TOOL）：面板"输入/输出"读 gen_ai.tool.call.arguments/result（TOOL 类 span 的正主），
// messages 一并保留兼容旧视图；span.kind 必须是 TOOL，否则面板不知道该从哪个属性读正文。
const toolEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 2, flow: 'mcp', requestId: 'req-2', scope: 'MCP', event: 'tool.call', data: { run_id: 'run-1', tool: 'match', tool_input: { clientCode: 'acme', triage: { scene_id: 'beauty' } } }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if (toolEnv.attributes['gen_ai.span.kind'] !== 'TOOL') throw new Error('tool span must declare span.kind=TOOL, got ' + toolEnv.attributes['gen_ai.span.kind']);
if (toolEnv.attributes['gen_ai.operation.name'] !== 'execute_tool') throw new Error('tool span operation.name must stay execute_tool');
if (toolEnv.attributes['gen_ai.tool.name'] !== 'match') throw new Error('tool span must carry gen_ai.tool.name');
// 这一条是本次线上症状的回归：只写 messages 时控制台输入/输出栏是空的。
if (toolEnv.attributes['gen_ai.tool.call.arguments'] !== '{"clientCode":"acme","triage":{"scene_id":"beauty"}}')
  throw new Error('tool span must put input into gen_ai.tool.call.arguments: ' + toolEnv.attributes['gen_ai.tool.call.arguments']);
const toolIn = toolEnv.attributes['gen_ai.input.messages'];
if (typeof toolIn !== 'string') throw new Error('tool span missing input.messages from tool_input');
const toolInParsed = JSON.parse(toolIn);
if (toolInParsed[0].role !== 'tool' || toolInParsed[0].parts[0].content !== '{"clientCode":"acme","triage":{"scene_id":"beauty"}}')
  throw new Error('tool input.messages shape/content mismatch: ' + toolIn);
const toolResEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 3, flow: 'mcp', requestId: 'req-3', scope: 'MCP', event: 'tool.result', data: { run_id: 'run-1', tool: 'match', tool_output: { action: 'hit', build_path: 'P3' } }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if (toolResEnv.attributes['gen_ai.tool.call.result'] !== '{"action":"hit","build_path":"P3"}')
  throw new Error('tool span must put output into gen_ai.tool.call.result: ' + toolResEnv.attributes['gen_ai.tool.call.result']);
const toolOut = toolResEnv.attributes['gen_ai.output.messages'];
if (typeof toolOut !== 'string') throw new Error('tool span missing output.messages from tool_output');
const toolOutParsed = JSON.parse(toolOut);
if (toolOutParsed[0].role !== 'tool' || toolOutParsed[0].finish_reason !== 'stop' || toolOutParsed[0].parts[0].content !== '{"action":"hit","build_path":"P3"}')
  throw new Error('tool output.messages shape/content mismatch: ' + toolOut);

// 3c) 二级阶段 span（mcp.service.mark 的 step_output）同样要落 tool.call.result——
// 线上截图里 P3C·蓝图落库 / P4·请求审批 就是这类，只有输出侧。
const stepEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 6, flow: 'mcp', requestId: 'req-6', scope: 'P3C', event: 'blueprint.persisted', data: { run_id: 'run-1', tool: 'persistBlueprint', phase: 'P3C', step_output: { blueprint_id: 'bp_9179' } }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if (stepEnv.attributes['gen_ai.tool.call.result'] !== '{"blueprint_id":"bp_9179"}')
  throw new Error('phase span must map step_output to gen_ai.tool.call.result: ' + stepEnv.attributes['gen_ai.tool.call.result']);
if ('gen_ai.tool.call.arguments' in stepEnv.attributes)
  throw new Error('phase span without tool_input must not fabricate tool.call.arguments');

// 3d) 运行级 AGENT span（McpService.finishRunSpan）：trace 顶部的输入/输出摘要与 Agents 数只认它。
const runEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 7, flow: 'mcp', requestId: 'req-7', scope: RUN_SPAN_SCOPE, event: 'finished', data: { run_id: 'run-1', client_code: 'acme', agent_name: 'agt-1', run_status: 'SUCCEEDED', run_input: { scene_id: 'beauty' }, run_output: { status: 'SUCCEEDED' }, ms: 4200 }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if (runEnv.attributes['gen_ai.operation.name'] !== 'invoke_agent') throw new Error('run span must be invoke_agent');
if (runEnv.attributes['gen_ai.span.kind'] !== 'AGENT') throw new Error('run span must declare span.kind=AGENT');
if (runEnv.attributes['gen_ai.agent.name'] !== 'agt-1') throw new Error('run span must carry gen_ai.agent.name');
const runIn = JSON.parse(String(runEnv.attributes['gen_ai.input.messages']));
if (runIn[0].role !== 'user' || runIn[0].parts[0].content !== '{"scene_id":"beauty"}') throw new Error('run span input.messages mismatch');
const runOut = JSON.parse(String(runEnv.attributes['gen_ai.output.messages']));
if (runOut[0].role !== 'assistant' || runOut[0].finish_reason !== 'stop' || runOut[0].parts[0].content !== '{"status":"SUCCEEDED"}')
  throw new Error('run span output.messages mismatch');
if ('gen_ai.tool.call.result' in runEnv.attributes) throw new Error('run span must not use tool.call.* keys');

// 3e) LLM span 的 span.kind = LLM（面板据此读 messages，而非 tool.call.*）。
if (promptEnv.attributes['gen_ai.span.kind'] !== 'LLM') throw new Error('Qwen span must declare span.kind=LLM');
if ('gen_ai.tool.call.arguments' in promptEnv.attributes) throw new Error('LLM span must not use tool.call.* keys');

// 3a) 工具 span 无 tool_input/tool_output 时（如纯状态推进）不写空正文。
const bareToolEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 4, flow: 'mcp', requestId: 'req-4', scope: 'P4', event: 'bind.done', data: { run_id: 'run-1', tool: 'bindProject' }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if ('gen_ai.input.messages' in bareToolEnv.attributes || 'gen_ai.output.messages' in bareToolEnv.attributes)
  throw new Error('tool span without tool_input/tool_output must not emit empty messages');
if ('gen_ai.tool.call.arguments' in bareToolEnv.attributes || 'gen_ai.tool.call.result' in bareToolEnv.attributes)
  throw new Error('tool span without tool_input/tool_output must not emit empty tool.call.*');

// 3b) 回归：token-only 的 usage 记录（无 system/user/parsed）不得凭空长出 messages 属性。
const usageEnv = toAgentLoopEnvelope(rec('usage', { session_id: 'session-1', model: 'qwen-plus', purpose: 'triage', prompt_tokens: 3, completion_tokens: 4 }));
if ('gen_ai.input.messages' in usageEnv.attributes || 'gen_ai.output.messages' in usageEnv.attributes)
  throw new Error('token-only usage record must not gain messages attributes');
if (usageEnv.attributes['gen_ai.system'] !== 'dashscope' || usageEnv.attributes['gen_ai.request.model'] !== 'qwen-plus')
  throw new Error('regression: usage attrs changed');

// 4) sanitizeToolIo：剔除 _ctx 与 approval.proof（HMAC 审批签名不得上报），其余原样保留。
const clean = sanitizeToolIo({ runId: 'r1', clientCode: 'acme', _ctx: { run_id: 'r1', traceparent: 'tp' }, approval: { approval_id: 'a1', decision: 'APPROVE', proof: 'SECRET.HMAC.TOKEN' } }) as Record<string, any>;
if ('_ctx' in clean) throw new Error('sanitizeToolIo must strip _ctx');
if (clean.approval.proof !== undefined) throw new Error('sanitizeToolIo must strip approval.proof');
if (clean.approval.approval_id !== 'a1' || clean.approval.decision !== 'APPROVE') throw new Error('sanitizeToolIo must keep non-proof approval fields');
if (clean.runId !== 'r1' || clean.clientCode !== 'acme') throw new Error('sanitizeToolIo must keep business fields');
if (JSON.stringify(clean).includes('SECRET.HMAC.TOKEN')) throw new Error('proof token leaked through sanitizeToolIo');

// 5) 采集开关关闭时不写正文（LLM 与工具 span 都不写），但 token/属性不受影响。
process.env.OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT = 'false';
const offEnv = toAgentLoopEnvelope(rec('chatJson.prompt', { run_id: 'run-1', system: 'sys', user: 'hi', prompt_tokens: 3 }));
if ('gen_ai.input.messages' in offEnv.attributes) throw new Error('capture=false must not emit input.messages');
if (offEnv.attributes['gen_ai.system'] !== 'dashscope') throw new Error('capture=false must not drop non-content attributes');
const offToolEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 5, flow: 'mcp', requestId: 'req-5', scope: 'MCP', event: 'tool.call', data: { run_id: 'run-1', tool: 'match', tool_input: { clientCode: 'acme' } }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if ('gen_ai.input.messages' in offToolEnv.attributes) throw new Error('capture=false must not emit tool input.messages');
if ('gen_ai.tool.call.arguments' in offToolEnv.attributes) throw new Error('capture=false must not emit tool.call.arguments');
const offRunEnv = toAgentLoopEnvelope({ kind: 'step', ts: Date.now(), seq: 8, flow: 'mcp', requestId: 'req-8', scope: RUN_SPAN_SCOPE, event: 'finished', data: { run_id: 'run-1', agent_name: 'agt-1', run_input: { a: 1 }, run_output: { b: 2 } }, deltaMs: 0, totalMs: 0, level: 'info' } as any);
if ('gen_ai.input.messages' in offRunEnv.attributes || 'gen_ai.output.messages' in offRunEnv.attributes)
  throw new Error('capture=false must not emit run span messages');
if (offRunEnv.attributes['gen_ai.agent.name'] !== 'agt-1') throw new Error('capture=false must keep agent.name (non-content)');
delete process.env.OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT;

process.stdout.write('[PASS] Nest maps LLM/AGENT messages + TOOL tool.call.arguments|result with explicit gen_ai.span.kind, strips _ctx/proof, gates by capture toggle\n');
