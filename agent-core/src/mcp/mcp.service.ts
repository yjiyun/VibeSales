import { Injectable } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';
import { AgentBlueprint, BuildPath, CheckReport, Guidance, MatchResult, Triage } from '../common/types';
import { ProductPhase } from '../common/product-phase';
import { ApprovalProofService } from '../common/approval-proof.service';
import { MatchService } from '../match/match.service';
import { P3Service } from '../p3/p3.service';
import { P3bService } from '../p3b/p3b.service';
import { P3cService } from '../p3c/p3c.service';
import { P4Service } from '../p4/p4.service';
import { PreviewService } from '../preview/preview.service';
import { TenantService } from '../tenant/tenant.service';
import { WizardService } from '../wizard/wizard.service';
import { TraceService } from '../common/trace.service';
import { RUN_SPAN_SCOPE } from '../common/agentloop-sink';

interface ToolDefinition { name:string;description:string;inputSchema:Record<string,unknown> }
interface TaskContext { runId:string;clientCode:string;requestId:string;userId?:string }
const triageSchema={type:'object',additionalProperties:true,required:['scene_id','channel','industry','confidence','known_slots'],properties:{scene_id:{type:'string',minLength:1},agent_family:{type:'string'},channel:{type:'string',minLength:1},industry:{type:'string',minLength:1},confidence:{type:'number',minimum:0,maximum:1},reason:{type:'string'},needs_long_term_memory:{type:'boolean'},needs_multi_turn_tooling:{type:'boolean'},needs_skill_evolution:{type:'boolean'},known_slots:{type:'object',additionalProperties:true,required:['industry','role','desired_capabilities'],properties:{industry:{type:'string'},role:{type:'string'},desired_capabilities:{type:'array',items:{type:'string'},minItems:1}}}}};
const objectSchema=(required:string[]=[],properties:Record<string,unknown>={})=>({type:'object',additionalProperties:true,required:[...required,'_ctx'],properties:{...properties,_ctx:{type:'object',additionalProperties:true,required:['run_id','client_code'],properties:{run_id:{type:'string',format:'uuid'},client_code:{type:'string',minLength:1},request_id:{type:'string'},traceparent:{type:'string'}}}}});

@Injectable()
export class McpService {
 /** 已补过运行级 span 的 run，避免终态后的重复调用重复上报（进程内，重启即忘，重复一条无害）。 */
 private readonly finishedRuns=new Set<string>();
 constructor(private readonly wizard:WizardService,private readonly match:MatchService,private readonly preview:PreviewService,private readonly tenant:TenantService,private readonly p3:P3Service,private readonly p3b:P3bService,private readonly p3c:P3cService,private readonly p4:P4Service,private readonly store:ArtifactStoreService,private readonly proofs:ApprovalProofService,private readonly trace:TraceService){}

 // 二级打点：MCP 工具内部子步骤 span（controller 的 tool.call/result 是入口，这里是阶段内部）。
 // scope 用阶段名（P1..P4，与契约 §2.2 child span 命名一致），显式带 run_id/client_code/request_id/phase/tool——
 // 不依赖 correlation（MCP 平面无请求级 ALS，root FlowState 的 correlation 会被并发污染）。上报失败不影响主流程（A12）。
 private mark(phase:string,tool:string,event:string,ctx:TaskContext,data:Record<string,unknown>={}):void{
  // 业务 data（gate/action/count/blueprint_id/approval 等）即"这一步产出了什么"——
  // 打包进 step_output，让 agentloop-sink 映射到面板"输出"列（二级 span 无 tool.call/result 的入参返回值）。
  // 剔除 undefined，避免 {"gate":undefined} 这种噪声；全空则不带 step_output。
  const summary:Record<string,unknown>={};for(const[k,v]of Object.entries(data))if(v!==undefined)summary[k]=v;
  const payload:Record<string,unknown>={run_id:ctx.runId,client_code:ctx.clientCode,request_id:ctx.requestId,phase,tool,...data};
  if(Object.keys(summary).length)payload.step_output=summary;
  try{this.trace.step(phase,event,payload);}catch{/* A12：旁路 */}
 }

 list(server:string):ToolDefinition[]{const definitions:Record<string,ToolDefinition[]>={
  'chatflows-p1':[this.tool('ask','规则计算下一问与 gate',['triage'],{triage:triageSchema}),this.tool('revise','整体修订 Triage 后重算 gate',['triage','patch'],{triage:triageSchema,patch:{type:'object',additionalProperties:true}}),this.tool('buildPhase1Result','构建稳定 Phase1Result',['clientCode','triage'],{clientCode:{type:'string',minLength:1},triage:triageSchema,stage:{type:'string'},phase1Result:{type:'object',additionalProperties:true}})],
  'chatflows-p2':[this.tool('match','模板过滤、排序与裁决',['clientCode','triage'],{clientCode:{type:'string',minLength:1},triage:triageSchema}),this.tool('preview','渲染命中模板 v0 预览',['templateId'])],
  'chatflows-p3':[this.tool('deriveGuidance','派生五字段指导',['triage']),this.tool('injectSections','按标注注入模板章节',['match','guidance']),this.tool('writeBack','校验并打包模板回写结果',['package'])],
  'chatflows-p3b':[this.tool('generate','生成可导入工作流 YAML',['triage','guidance']),this.tool('selfcheck','执行 11 项结构自检',['yaml'])],
  'chatflows-p3c':[this.tool('listSkillCandidates','按行业与场景筛选 Skill',['scenarios']),this.tool('listToolCandidates','按租户筛选 MCP 工具',['clientCode']),this.tool('renderPersona','确定性渲染 AGENTS/SOUL；guidance 可省略或传 guidance@vN，省略时回读已落库的 guidance',[]),this.tool('submitExpertResult','提交单个专家产出（禁止覆盖同 role 已提交结果）',['role','payload'],{role:{type:'string',enum:['persona-expert','business-expert','skill-expert','tool-expert']},payload:{description:'专家产出对象或其 JSON'}}),this.tool('composeBlueprint','合并四位专家产出为 Blueprint；专家参数可省略，省略时从已提交的 expert_result 回读',[]),this.tool('blueprintSelfcheck','执行 14 项 Blueprint 自检；blueprint 可省略，省略时回读 composeBlueprint 暂存的 blueprint_draft',[]),this.tool('persistBlueprint','自检后整体覆盖写 DRAFT Blueprint；blueprint 可省略，省略时回读 blueprint_draft',['runId'])],
  'chatflows-p4':[this.tool('import','审批闸门后导入工作流或暂存 Blueprint；path 可省略（服务端用 run.build_path 回读 Nest 产物，不读共享目录文件）',['runId','clientCode'],{approval:{type:'object',additionalProperties:true,required:['approval_id','decision','proof'],properties:{approval_id:{type:'string',minLength:1},decision:{type:'string',enum:['APPROVE','DENY']},proof:{type:'string',minLength:1}}}}),this.tool('bindProject','绑定项目或运行时智能体（P3C 路径的 userId 由服务端从批准该 run 的 Human 派生，传入值被忽略）；path 可省略',['clientCode','externalId']),this.tool('dryRun','执行产物冒烟；path 可省略',[])],
 };const tools=definitions[server];if(!tools)throw new Error('unknown MCP server: '+server);return tools;}

 async call(server:string,name:string,a:Record<string,any>):Promise<unknown>{
  if(!this.list(server).some(t=>t.name===name))throw new Error('unknown tool: '+server+'.'+name);const ctx=this.context(a);await this.store.ensureRun(ctx.runId,ctx.clientCode);
  let result:unknown,failure:unknown;
  try{result=await this.dispatch(server,name,a,ctx);}catch(error){failure=error;}
  await this.finishRunSpan(ctx,failure===undefined?result:{error:failure instanceof Error?failure.message:String(failure)});
  if(failure!==undefined)throw failure;
  return result;
 }

 private dispatch(server:string,name:string,a:Record<string,any>,ctx:TaskContext):Promise<unknown>{
  if(server==='chatflows-p1')return this.callP1(name,a,ctx);
  if(server==='chatflows-p2')return this.callP2(name,a,ctx);
  if(server==='chatflows-p3')return this.callP3(name,a,ctx);
  if(server==='chatflows-p3b')return this.callP3b(name,a,ctx);
  if(server==='chatflows-p3c')return this.callP3c(name,a,ctx);
  return this.callP4(name,a,ctx);
 }

 /**
  * 运行级 AGENT span：run 走到终态（SUCCEEDED/FAILED/ABORTED）时补一条，整条 run 一次。
  *
  * 为什么必须有它：AgentLoop 面板顶部的「输入 / 输出」摘要与「Agents 数」只认 AGENT/ENTRY
  * 类 span（loongsuite otel-util-genai 的 GenAiSpanKindValues），叶子 execute_tool 上的正文
  * 再全也不会被汇总——以前 Nest 只发平铺的工具 span，于是控制台上每个节点点开能看到
  * gen_ai.output.messages 有值，可 trace 级的输入/输出栏永远是「–」，Agents 数永远是 0。
  * 这条 span 把「用户最初要什么」（triage/wizard_state）与「最终产出什么」（终态与产物）
  * 放在同一棵树的 AGENT 节点上，摘要才有东西可读。
  *
  * 终态由 run 表判定，不靠调用方传，因此 P1 gate 中止 / P2 EARLY_EXIT / P4 拒绝 /
  * dryRun 成功 / failP4 打死这五条路径都自动覆盖，无需逐处埋点。上报失败不影响主流程（A12）。
  */
 private async finishRunSpan(ctx:TaskContext,output:unknown):Promise<void>{
  try{
   if(this.finishedRuns.has(ctx.runId))return;
   const run=await this.store.getRun(ctx.runId);
   if(!['SUCCEEDED','FAILED','ABORTED'].includes(run.status))return;
   // 进程内去重：终态 run 的后续调用会被各阶段的状态校验拒掉，但那条失败路径同样会走到这里，
   // 不挡住就会给同一个 run 重复发运行级 span。上限兜底，避免长命进程里无界增长。
   if(this.finishedRuns.size>5000)this.finishedRuns.clear();
   this.finishedRuns.add(ctx.runId);
   const triage=await this.store.latestArtifact<Triage>(ctx.runId,'triage').catch(()=>undefined);
   const wizard=triage?undefined:await this.store.latestArtifact<Record<string,unknown>>(ctx.runId,'wizard_state').catch(()=>undefined);
   const blueprint=await this.store.latestArtifact<AgentBlueprint>(ctx.runId,'blueprint').catch(()=>undefined);
   const started=Date.parse(run.created_at);
   this.trace.step(RUN_SPAN_SCOPE,'finished',{
    run_id:ctx.runId,client_code:ctx.clientCode,request_id:ctx.requestId,
    agent_name:blueprint?.payload?.runtimeAgentId??'vibe-sales-builder',
    run_status:run.status,build_path:run.build_path,
    run_input:triage?.payload??wizard?.payload,
    run_output:{status:run.status,build_path:run.build_path,result:output},
    ms:Number.isFinite(started)?Math.max(1,Date.now()-started):undefined,
   });
  }catch{/* A12：旁路，观测失败不影响业务回包 */}
 }

 private async callP1(name:string,a:Record<string,any>,ctx:TaskContext){const run=await this.store.getRun(ctx.runId);if(run.current_phase!==ProductPhase.P1_WIZARD_INTENT||!['RUNNING','WAITING_HUMAN'].includes(run.status))throw new Error('P1 run is not active');await this.store.updateRun(ctx.runId,{status:'RUNNING'});
  // Web 已完成的 Phase1Result 允许原样带入（stage / summary 等不丢），但租户与 gate 仍由 Nest 重算。
  const supplied=a.phase1Result===undefined?undefined:this.objectArgument<Record<string,any>>(a.phase1Result,'phase1Result');
  if(supplied&&supplied.client_code!==ctx.clientCode)throw new Error('Phase1Result client_code must match _ctx.client_code');
  const triage=this.objectArgument<Triage>(supplied?.triage??a.triage,'triage'),patch=name==='revise'?this.objectArgument<Record<string,unknown>>(a.patch,'patch'):undefined;const source=name==='revise'?{...triage,...patch,known_slots:{...(triage.known_slots??{}),...((patch?.known_slots as Record<string,unknown>|undefined)??{})}}:triage;const gated=this.wizard.evaluateGate(source as Triage);this.mark('P1',name,'gate.evaluated',ctx,{gate:gated.gate,stage:supplied?.stage??a.stage});const out=name==='buildPhase1Result'?{...(supplied??{}),phase:'P1',client_code:ctx.clientCode,request_id:ctx.requestId,stage:supplied?.stage??a.stage??'S1_COARSE',gate:gated.gate,triage:gated.triage,ask_user:gated.ask_user}:gated;await this.store.putArtifact(ctx.runId,'wizard_state',out,'wizard-intent');if(name==='buildPhase1Result'&&gated.triage)await this.store.putArtifact(ctx.runId,'triage',gated.triage,'wizard-intent');if(name!=='buildPhase1Result'){await this.store.updateRun(ctx.runId,{status:gated.gate==='ASK'?'WAITING_HUMAN':'RUNNING'});return out;}this.mark('P1',name,'phase1.built',ctx,{gate:gated.gate});if(gated.gate==='PASS')await this.store.updateRun(ctx.runId,{status:'RUNNING',current_phase:ProductPhase.P2_TEMPLATE_MATCH});else await this.store.updateRun(ctx.runId,{status:gated.gate==='ASK'?'WAITING_HUMAN':'ABORTED'});return out;}

 private async callP2(name:string,a:Record<string,any>,ctx:TaskContext){if(name==='preview'){const match=await this.requireArtifact<MatchResult>(ctx.runId,'match_result');if(match.action!=='hit'||!match.template_id||match.template_id!==String(a.templateId))throw new Error('preview requires persisted matching hit');return this.preview.build(match.template_id);}const run=await this.store.getRun(ctx.runId);if(run.current_phase!==ProductPhase.P2_TEMPLATE_MATCH||run.status!=='RUNNING')throw new Error('P2 run is not active');const state=await this.requireArtifact<any>(ctx.runId,'wizard_state');if(state.phase!=='P1'||state.gate!=='PASS'||state.client_code!==ctx.clientCode)throw new Error('P2 requires complete persisted P1 PASS');const triage=await this.requireArtifact<Triage>(ctx.runId,'triage');const tenant=this.tenant.resolve(ctx.clientCode,a.tenantPath);const result=await this.match.run({client_code:tenant.client_code,tenant,request_id:ctx.requestId},triage);await this.store.putArtifact(ctx.runId,'match_result',result,'template-match');this.mark('P2',name,'match.done',ctx,{action:result.action,template_id:result.template_id,via:result.via});const path=this.wizard.decideBuildPath(triage,result);this.mark('P2',name,'path.decided',ctx,{build_path:path});if(path==='EARLY_EXIT'){await this.store.updateRun(ctx.runId,{build_path:path,status:'ABORTED',current_phase:ProductPhase.P2_TEMPLATE_MATCH});return{...result,build_path:path,status:'ABORTED'};}await this.store.updateRun(ctx.runId,{build_path:path,current_phase:path==='P3'?ProductPhase.P3_TEMPLATE_PERSONALIZE:path==='P3B'?ProductPhase.P3B_FLOW_GENERATE:ProductPhase.P3C_BLUEPRINT_COMPOSE});return{...result,build_path:path};}

 private async callP3(name:string,a:Record<string,any>,ctx:TaskContext){if(name==='deriveGuidance'){const run=await this.store.getRun(ctx.runId);if(!['P3','P3B','P3C'].includes(String(run.build_path))||run.status!=='RUNNING')throw new Error('guidance requires active build path');const guidance=this.p3.deriveGuidance(await this.requireArtifact(ctx.runId,'triage'));await this.store.putArtifact(ctx.runId,'guidance',guidance,'template-personalize');this.mark('P3',name,'guidance.derived',ctx,{role:(guidance as any)?.role});return guidance;}await this.requirePath(ctx.runId,'P3');if(name==='injectSections'){const matched=await this.requireArtifact<MatchResult>(ctx.runId,'match_result');const pkg=this.p3.personalize(matched,await this.guidance(ctx,'template-personalize'));await this.store.putArtifact(ctx.runId,'personalized_package',pkg,'template-personalize');this.mark('P3',name,'sections.injected',ctx,{template_id:matched.template_id});return pkg;}const pkg=await this.requireArtifact<any>(ctx.runId,'personalized_package');const check=this.p3b.selfcheck(pkg);await this.store.putArtifact(ctx.runId,'flow_check',check,'template-personalize');this.mark('P3',name,'selfcheck',ctx,{ok:check.ok,failed:check.ok?undefined:check.checks.filter(c=>!c.ok).map(c=>c.id).join(',')});if(!check.ok)throw new Error('P3 selfcheck failed');await this.readyForP4(ctx.runId);return {package:pkg,check};}

 private async callP3b(name:string,a:Record<string,any>,ctx:TaskContext){await this.requirePath(ctx.runId,'P3B');if(name==='generate'){const pkg=this.p3b.generate(await this.requireArtifact(ctx.runId,'triage'),await this.requireArtifact(ctx.runId,'guidance'));await this.store.putArtifact(ctx.runId,'flow_yaml',pkg,'flow-generate');this.mark('P3B',name,'flow.generated',ctx,{format:(pkg as any)?.format,workflow_file:(pkg as any)?.workflowFile});return pkg;}const pkg=await this.requireArtifact<any>(ctx.runId,'flow_yaml');const check=this.p3b.selfcheck(pkg);await this.store.putArtifact(ctx.runId,'flow_check',check,'flow-generate');this.mark('P3B',name,'selfcheck',ctx,{ok:check.ok,failed:check.ok?undefined:check.checks.filter(c=>!c.ok).map(c=>c.id).join(',')});if(!check.ok)throw new Error('P3B selfcheck failed');await this.readyForP4(ctx.runId);return check;}

 private static readonly EXPERT_ROLES=['persona-expert','business-expert','skill-expert','tool-expert'] as const;

 private async callP3c(name:string,a:Record<string,any>,ctx:TaskContext){
  await this.requirePath(ctx.runId,'P3C');
  if(name==='listSkillCandidates'){const skills=await this.p3c.listSkillCandidates(a.industry,a.scenarios??[]);this.mark('P3C',name,'skills.listed',ctx,{count:Array.isArray(skills)?skills.length:undefined});return skills;}
  if(name==='listToolCandidates'){const tools=await this.p3c.listToolCandidates(ctx.clientCode);this.mark('P3C',name,'tools.listed',ctx,{count:Array.isArray(tools)?tools.length:undefined});return tools;}
  if(name==='renderPersona'){const guidance=await this.resolveGuidance(ctx.runId,a.guidance);const persona=this.p3c.renderPersona(guidance);this.mark('P3C',name,'persona.rendered',ctx,{agents_md_chars:typeof (persona as any)?.agentsMd==='string'?(persona as any).agentsMd.length:undefined,soul_chars:typeof (persona as any)?.soulMd==='string'?(persona as any).soulMd.length:undefined});return persona;}
  if(name==='submitExpertResult'){
   const role=String(a.role??'');
   if(!(McpService.EXPERT_ROLES as readonly string[]).includes(role))throw new Error('role must be one of '+McpService.EXPERT_ROLES.join(', '));
   if(a.payload===undefined||a.payload===null)throw new Error('payload is required');
   const payload=this.parseMaybeJson(a.payload);
   if(payload===null||payload===undefined||(typeof payload!=='object'&&typeof payload!=='string'))throw new Error('payload must be an object or JSON object/array');
   const existing=await this.store.listArtifacts(ctx.runId,'expert_result');
   if(existing.some(art=>art.written_by===role))throw new Error('expert result already submitted for role='+role+'; overwrite refused');
   const art=await this.store.putArtifact(ctx.runId,'expert_result',{role,payload},role);
   this.mark('P3C',name,'expert.submitted',ctx,{role,version:art.version});
   return{role,submitted:true,version:art.version};
  }
  if(name==='composeBlueprint'){
   const triage=await this.requireArtifact<Triage>(ctx.runId,'triage'),guidance=await this.requireArtifact<Guidance>(ctx.runId,'guidance'),batchId=randomUUID();
   // 四个专家产物常以 JSON 字符串传入；不解析的话 Array.isArray(skills) 为 false 会静默退回
   // 库默认值、persona.agentsMd 变 undefined，composeBlueprint 于是产出「空壳」Blueprint
   // （agentsMd 只剩「业务规则：」、skills 只有 human-handoff），且不报任何错。
   // 也可全部省略：从 submitExpertResult 已落库的 expert_result 按 role 回读。
   const experts=await this.resolveExpertInputs(ctx.runId,a);
   const expertInputs=[['persona-expert',experts.persona],['business-expert',experts.business],['skill-expert',experts.skills],['tool-expert',experts.tools]] as const;
   await this.store.putArtifact(ctx.runId,'expert_dispatch',{batchId,roles:expertInputs.map(([role])=>role)},'blueprint-compose');
   // 仅在调用方显式传入专家产物时再写一遍；已由 submitExpertResult 落库的不覆盖。
   const submitted=await this.store.listArtifacts(ctx.runId,'expert_result');
   for(const[role,payload]of expertInputs){
    if(submitted.some(art=>art.written_by===role))continue;
    await this.store.putArtifact(ctx.runId,'expert_result',{role,batchId,payload},role);
   }
   this.mark('P3C',name,'experts.collected',ctx,{batch_id:batchId,roles:expertInputs.map(([role])=>role).join(',')});
   const blueprint=await this.p3c.composeBlueprint({runId:ctx.runId,clientCode:ctx.clientCode,triage,guidance,experts});
   await this.store.putArtifact(ctx.runId,'blueprint_draft',blueprint,'blueprint-compose');
   this.mark('P3C',name,'blueprint.composed',ctx,{runtime_agent_id:(blueprint as any)?.runtimeAgentId,skills:Array.isArray((blueprint as any)?.skills)?(blueprint as any).skills.length:undefined,rules:Array.isArray((blueprint as any)?.rules)?(blueprint as any).rules.length:0});
   return blueprint;
  }
  if(name==='blueprintSelfcheck'){
   const bp=await this.resolveBlueprint(ctx.runId,a.blueprint);
   const check=await this.p3c.blueprintSelfcheck(bp);
   await this.store.putArtifact(ctx.runId,'blueprint_check',check,'blueprint-compose');
   this.mark('P3C',name,'selfcheck',ctx,{ok:check.ok,failed:check.ok?undefined:check.checks.filter(c=>!c.ok).map(c=>c.id).join(',')});
   return check;
  }
  const bp=await this.resolveBlueprint(ctx.runId,a.blueprint);
  const record=await this.p3c.persistBlueprint(ctx.runId,bp);
  await this.store.putArtifact(ctx.runId,'blueprint',record.payload,'blueprint-compose');
  this.mark('P3C',name,'blueprint.persisted',ctx,{blueprint_id:(record as any)?.payload?.blueprintId??(bp as any)?.blueprintId});
  await this.readyForP4(ctx.runId);
  return record;
 }

 /** 显式参数优先；缺省时从 expert_result 按 written_by=role 回读，缺哪个点名哪个。 */
 private async resolveExpertInputs(runId:string,a:Record<string,any>):Promise<{persona:unknown;business:unknown;skills:unknown;tools:unknown}>{
  const fromArgs={
   persona:a.persona===undefined?undefined:this.parseMaybeJson(a.persona),
   business:a.business===undefined?undefined:this.parseMaybeJson(a.business),
   skills:a.skills===undefined?undefined:this.parseMaybeJson(a.skills),
   tools:a.tools===undefined?undefined:this.parseMaybeJson(a.tools),
  };
  const needStore=Object.values(fromArgs).some(v=>v===undefined||v===null);
  if(!needStore)return fromArgs as {persona:unknown;business:unknown;skills:unknown;tools:unknown};
  const arts=await this.store.listArtifacts(runId,'expert_result');
  const latestByRole=new Map<string,unknown>();
  for(const art of arts){
   const role=art.written_by;
   if(!(McpService.EXPERT_ROLES as readonly string[]).includes(role))continue;
   const body=art.payload as Record<string,unknown>|undefined;
   const payload=body&&typeof body==='object'&&'payload' in body?body.payload:body;
   latestByRole.set(role,payload);
  }
  const roleKey:{role:typeof McpService.EXPERT_ROLES[number];key:'persona'|'business'|'skills'|'tools'}[]=[
   {role:'persona-expert',key:'persona'},{role:'business-expert',key:'business'},
   {role:'skill-expert',key:'skills'},{role:'tool-expert',key:'tools'},
  ];
  const missing:string[]=[];
  const out:{persona:unknown;business:unknown;skills:unknown;tools:unknown}={persona:null,business:null,skills:null,tools:null};
  for(const{role,key}of roleKey){
   const value=fromArgs[key]!==undefined&&fromArgs[key]!==null?fromArgs[key]:latestByRole.get(role);
   if(value===undefined||value===null)missing.push(role);
   else out[key]=value;
  }
  if(missing.length)throw new Error('P3C requires four expert results; missing: '+missing.join(', '));
  return out;
 }

 /** 显式 guidance 优先；指针 guidance@vN 或省略时回读 deriveGuidance 已落库的 guidance。 */
 private async resolveGuidance(runId:string,value:unknown):Promise<Guidance>{
  const pointer=typeof value==='string'&&/^guidance@v\d+$/i.test(value.trim());
  if(value!==undefined&&value!==null&&value!==''&&!pointer)return this.asObject(value,'guidance') as unknown as Guidance;
  const art=await this.store.latestArtifact<Guidance>(runId,'guidance');
  if(!art)throw new Error('guidance omitted and guidance artifact missing; wait for deriveGuidance');
  return art.payload;
 }

 /** 显式 blueprint 优先；缺省时回读 composeBlueprint 暂存的 blueprint_draft。 */
 private async resolveBlueprint(runId:string,value:unknown):Promise<AgentBlueprint>{
  if(value!==undefined&&value!==null&&!(typeof value==='string'&&!value.trim()))return this.requireBlueprint(value);
  const draft=await this.store.latestArtifact<AgentBlueprint>(runId,'blueprint_draft');
  if(!draft)throw new Error('blueprint omitted and blueprint_draft missing; call composeBlueprint first');
  return this.requireBlueprint(draft.payload);
 }

 // blueprint 必须是 composeBlueprint 返回的完整对象；Worker 只回传 blueprintId 或裁剪过的
 // 对象时，旧代码直接 as AgentBlueprint 进 selfcheck，在 bp.skills.map() 处抛
 // "Cannot read properties of undefined (reading 'map')"，Worker 只能报「驱动内部 bug」。
 // 这里前置校验并明确指出缺哪些字段，让 Worker 知道要原样回传整个 blueprint JSON。
 // 宽松解析：Worker 把对象/数组 JSON.stringify 后传参时还原成结构；本来就是结构或普通
 // 文本时原样返回。用于 experts 这类既可能是数组也可能是对象的参数。
 private parseMaybeJson(value:unknown):unknown{
  if(typeof value!=='string')return value;
  const text=value.trim();
  if(!text.startsWith('{')&&!text.startsWith('['))return value;
  try{return JSON.parse(text);}catch{return value;}
 }

 // Worker 经常把结构化参数先 JSON.stringify 再作为 MCP 参数传（合法用法），也可能只传一个
 // ID 字符串（错误用法）。统一在这里解析，避免下游直接 as T 断言后崩在 .map()/.join() 上。
 private asObject(value:unknown,label:string):Record<string,unknown>{
  if(typeof value==='string'){
   const text=value.trim();
   if(!text.startsWith('{'))throw new Error(label+' must be the full object (or its JSON), got: '+text.slice(0,80));
   try{value=JSON.parse(text);}catch(error){throw new Error(label+' JSON is not parseable: '+(error instanceof Error?error.message:String(error)));}
  }
  if(!value||typeof value!=='object'||Array.isArray(value))throw new Error(label+' must be an object');
  return value as Record<string,unknown>;
 }

 private requireBlueprint(value:unknown):AgentBlueprint{
  const bp=this.asObject(value,'blueprint') as Partial<AgentBlueprint>;
  const missing:string[]=[];
  if(typeof bp.clientCode!=='string'||!bp.clientCode)missing.push('clientCode');
  if(typeof bp.runtimeAgentId!=='string'||!bp.runtimeAgentId)missing.push('runtimeAgentId');
  if(!Array.isArray(bp.skills))missing.push('skills[]');
  if(!bp.prompt||typeof bp.prompt.agentsMd!=='string'||typeof bp.prompt.soulMd!=='string')missing.push('prompt.agentsMd/soulMd');
  if(!bp.tools||!Array.isArray(bp.tools.allow)||!Array.isArray(bp.tools.mcpServers))missing.push('tools.allow[]/tools.mcpServers[]');
  if(!bp.runtime||typeof bp.runtime.isolationScope!=='string'||typeof bp.runtime.maxContextTokens!=='number')missing.push('runtime.isolationScope/maxContextTokens');
  if(missing.length)throw new Error('blueprint is incomplete, re-send the full composeBlueprint result; missing: '+missing.join(', '));
  return bp as AgentBlueprint;
 }

 private async callP4(name:string,a:Record<string,any>,ctx:TaskContext){
  const run=await this.store.getRun(ctx.runId);if(!run.build_path||run.build_path==='EARLY_EXIT')throw new Error('P4 requires build_path');
  if(name==='import'){
   const pending=await this.store.latestArtifact<any>(ctx.runId,'approval');
   if(!a.approval){
    if(pending?.payload?.status==='PENDING'){this.mark('P4',name,'approval.pending',ctx,{approval_id:pending.payload.approval_id,approval_state:'pending_approval'});return{status:'pending_approval',approval_id:pending.payload.approval_id,run_id:ctx.runId};}
    if(pending||run.status!=='RUNNING'||run.current_phase!==ProductPhase.P4_IMPORT_RUN)throw new Error('P4 approval cannot be reopened for this run');
    const approvalId=randomUUID();await this.store.transitionApproval(ctx.runId,approvalId,null,{approval_id:approvalId,action:'P4_IMPORT',status:'PENDING',requested_at:new Date().toISOString()});await this.store.updateRun(ctx.runId,{status:'WAITING_HUMAN',current_phase:ProductPhase.P4_IMPORT_RUN});this.mark('P4',name,'approval.requested',ctx,{approval_id:approvalId,approval_state:'pending_approval'});return{status:'pending_approval',approval_id:approvalId,run_id:ctx.runId};
   }
   if(typeof a.approval!=='object'||Array.isArray(a.approval))throw new Error('approval must be an object {approval_id, decision, proof}, not a JSON string or array');
   const decision=a.approval as Record<string,unknown>,proof=this.proofs.verify(String(decision.proof??''));
   const idsMatch=proof.run_id===ctx.runId&&proof.approval_id===pending?.payload?.approval_id&&proof.approval_id===decision.approval_id;
   if(!idsMatch)throw new Error('approval credential mismatch or already consumed');
   if(pending?.payload?.status!=='PENDING'){
    const finished=proof.decision==='APPROVE'?await this.finishedP4(ctx.runId):null;
    if(finished){this.mark('P4',name,'import.replayed',ctx,{approval_id:proof.approval_id,approval_state:'approved',external_id:finished.imported?.external_id,build_path:run.build_path});return finished.imported;}
    throw new Error('approval credential mismatch or already consumed');
   }
   this.mark('P4',name,'approval.verified',ctx,{approval_id:proof.approval_id,decision:proof.decision,actor:proof.actor});
   await this.store.transitionApproval(ctx.runId,proof.approval_id,'PENDING',{...pending.payload,status:proof.decision==='APPROVE'?'PROCESSING':'DENIED',actor:proof.actor,decided_at:new Date().toISOString()});
   if(proof.decision==='DENY'){await this.store.putArtifact(ctx.runId,'evidence',{event:'APPROVAL_DENIED',actor:proof.actor,approval_id:proof.approval_id,at:new Date().toISOString()},'flow-import-run');await this.store.updateRun(ctx.runId,{status:'ABORTED'});this.mark('P4',name,'approval.denied',ctx,{approval_id:proof.approval_id,approval_state:'denied',actor:proof.actor});return{status:'ABORTED',run_id:ctx.runId};}
   await this.store.updateRun(ctx.runId,{status:'RUNNING',current_phase:ProductPhase.P4_IMPORT_RUN});
   try{const{payload,check}=await this.p4Input(ctx.runId,run.build_path);const imported=await this.p4.import({runId:ctx.runId,clientCode:ctx.clientCode,path:run.build_path,payload,check});await this.store.putArtifact(ctx.runId,'import_result',{imported},'flow-import-run');await this.store.transitionApproval(ctx.runId,proof.approval_id,'PROCESSING',{...pending.payload,status:'CONSUMED',actor:proof.actor,consumed_at:new Date().toISOString()});this.mark('P4',name,'import.done',ctx,{approval_id:proof.approval_id,approval_state:'approved',external_id:imported?.external_id,build_path:run.build_path});return imported;}catch(error){return this.failP4(ctx.runId,'IMPORT',error,ctx,{approvalId:proof.approval_id,processingPayload:{...pending.payload,status:'PROCESSING',actor:proof.actor,decided_at:new Date().toISOString()}});}
  }
  if(name==='bindProject'){
   const replayed=await this.finishedP4(ctx.runId);
   if(replayed?.binding){this.mark('P4',name,'bind.replayed',ctx,{external_id:replayed.imported?.external_id,user_id:(replayed.binding as any)?.user_id,build_path:run.build_path});return replayed.binding;}
   await this.requireApprovedP4(ctx.runId);
   // userId 曾经是让 Worker 自由传的参数：Worker 传什么这里就信什么，容易传成裸词
   // "admin"（或干脆漏传变成字面量 "undefined"），落进 binding 表的 user_id 就再也
   // 匹配不上试聊时前端算出来的 runtimeSafeId(auth.actor)（同一个 Human 的完整
   // Matrix ID），于是 BlueprintProjector.projectPublished() 查不到三元组，报
   // "no PUBLISHED blueprint binding"（platform_bug.md §3.23 记录过一次手动改数据
   // 的临时修复，这次同一坑复发，说明治标没治本）。
   // 真正的修法：不再信任 a.userId，binding 的 user_id 强制 = 批准这次 P4 的 Human
   // （approval.actor，同一条 approval artifact 里已经落了完整 Matrix ID），这样
   // 「谁批准、谁绑定、谁试聊」三处天然是同一个规范化字符串，Worker 无从传错。
   const approval=await this.store.latestArtifact<Record<string,unknown>>(ctx.runId,'approval');
   const approvalActor=typeof approval?.payload?.actor==='string'?approval.payload.actor.trim():'';
   if(!approvalActor)throw new Error('bindProject requires a persisted approval.actor to derive userId');
   this.mark('P4',name,'bind.actor_resolved',ctx,{actor:approvalActor});
   try{const saved=await this.requireArtifact<any>(ctx.runId,'import_result'),{payload}=await this.p4Input(ctx.runId,run.build_path);const bp=run.build_path==='P3C'?payload as AgentBlueprint:undefined;const binding=await this.p4.bindProject({clientCode:ctx.clientCode,userId:approvalActor,runtimeAgentId:bp?.runtimeAgentId,blueprintId:bp?.blueprintId,externalId:saved.imported.external_id,path:run.build_path,actor:approvalActor});await this.store.putArtifact(ctx.runId,'import_result',{...saved,binding},'flow-import-run');this.mark('P4',name,'bind.done',ctx,{external_id:saved.imported?.external_id,user_id:(binding as any)?.user_id,build_path:run.build_path});return binding;}catch(error){return this.failP4(ctx.runId,'BIND',error,ctx);}
  }
  const replayedDry=await this.finishedP4(ctx.runId);
  if(replayedDry){this.mark('P4',name,'dryrun.replayed',ctx,{external_id:replayedDry.imported?.external_id,dry_run_ok:replayedDry.dry_run?.ok,run_status:'SUCCEEDED'});return{dry_run:replayedDry.dry_run,status:'SUCCEEDED'};}
  await this.requireApprovedP4(ctx.runId);
  try{const saved=await this.requireArtifact<any>(ctx.runId,'import_result'),{payload,check}=await this.p4Input(ctx.runId,run.build_path);const dryRun=await this.p4.dryRun({path:run.build_path,payload,externalId:saved.imported.external_id,userId:a.userId??saved.binding?.user_id});await this.store.putArtifact(ctx.runId,'dry_run',dryRun,'flow-import-run');await this.store.putArtifact(ctx.runId,'evidence',{event:'P4_EXECUTED',external_id:saved.imported.external_id,dry_run_ok:dryRun.ok,at:new Date().toISOString()},'flow-import-run');await this.store.updateRun(ctx.runId,{status:'SUCCEEDED'});this.mark('P4',name,'dryrun.done',ctx,{external_id:saved.imported?.external_id,dry_run_ok:dryRun.ok,run_status:'SUCCEEDED'});return{dry_run:dryRun,selfcheck:check,status:'SUCCEEDED'};}catch(error){return this.failP4(ctx.runId,'DRY_RUN',error,ctx);}
 }

 private context(a:Record<string,any>):TaskContext{const c=a._ctx??{};return{runId:String(c.run_id??''),clientCode:String(c.client_code??''),requestId:String(c.request_id??randomUUID()),userId:c.user_id?String(c.user_id):undefined};}
 private async requireArtifact<T>(runId:string,kind:any):Promise<T>{const a=await this.store.latestArtifact<T>(runId,kind);if(!a)throw new Error('required artifact missing: '+kind);return a.payload;}
 private async requirePath(runId:string,path:BuildPath){const run=await this.store.getRun(runId);if(run.build_path!==path||run.status!=='RUNNING')throw new Error(path+' worker run state/path mismatch');}
 private async guidance(ctx:TaskContext,writer:string){const existing=await this.store.latestArtifact<Guidance>(ctx.runId,'guidance');if(existing)return existing.payload;const value=this.p3.deriveGuidance(await this.requireArtifact(ctx.runId,'triage'));await this.store.putArtifact(ctx.runId,'guidance',value,writer);return value;}
 private async readyForP4(runId:string){await this.store.updateRun(runId,{status:'RUNNING',current_phase:ProductPhase.P4_IMPORT_RUN});}
 private async requireApprovedP4(runId:string){const run=await this.store.getRun(runId),approval=await this.store.latestArtifact<any>(runId,'approval');if(run.status!=='RUNNING'||approval?.payload?.status!=='CONSUMED')throw new Error('P4 continuation requires active run and consumed Human approval');}
 /** HTTP 审批通道（Nest local）可能已经跑完 P4。同一条 proof 再走 MCP 时回放已落库产物，避免 already consumed。 */
 private async finishedP4(runId:string){const run=await this.store.getRun(runId);if(run.status!=='SUCCEEDED')return null;const saved=await this.store.latestArtifact<any>(runId,'import_result'),dry=await this.store.latestArtifact<any>(runId,'dry_run');if(!saved?.payload?.imported||!dry?.payload)return null;return{imported:saved.payload.imported,binding:saved.payload.binding,dry_run:dry.payload};}
 /**
  * 环境/配置没配好（缺 URL、令牌、下游不可达）与「产物不合格」是两回事：前者重试就能过，
  * 把 run 打成 FAILED 会让整条流水线需要人工改库才能续跑（实测 dry-run 少一个 env
  * 就把已 import+bind 成功的 run 判死）。这类错误只留 evidence，让 run 保持可重试。
  * 405 同属这一类：agent-runtime ingest 端点未部署/路由不匹配，是环境缺陷不是产物缺陷。
  */
 private static retriableP4(message:string):boolean{return /required for|are required|ECONNREFUSED|ENOTFOUND|ETIMEDOUT|fetch failed|socket hang up|405|502|503|504/i.test(message);}
 /**
  * IMPORT 阶段失败前，proof 校验通过就已把 approval 从 PENDING 跃迁到 PROCESSING（callP4
  * 第126行），与后续 ingest 是否成功无关。若不在此处回滚，PROCESSING 是终态死胡同：
  * requireApprovedP4 只认 CONSUMED，同一 proof 再也无法重放，只能靠 Manager 重开一轮
  * APPROVAL_REQUIRED。proof 本身是无状态 HMAC 签名+15分钟时效（approval-proof.service.ts），
  * 不是一次性令牌，回滚到 PENDING 后原 proof 在有效期内可以重放，不构成审批绕过。
  */
 private async failP4(runId:string,stage:string,error:unknown,ctx?:TaskContext,rollback?:{approvalId:string;processingPayload:Record<string,unknown>}):Promise<never>{const message=error instanceof Error?error.message:String(error);const retriable=McpService.retriableP4(message);
  if(rollback){try{await this.store.transitionApproval(runId,rollback.approvalId,'PROCESSING',{...rollback.processingPayload,status:'PENDING',decided_at:undefined,rolled_back_at:new Date().toISOString(),rollback_reason:message.slice(0,200)});}catch{/* approval 已被并发消费或已不在 PROCESSING，不覆盖更新的状态 */}}
  try{await this.store.putArtifact(runId,'evidence',{event:retriable?'P4_BLOCKED':'P4_FAILED',stage,error:message.slice(0,500),retriable,at:new Date().toISOString()},'flow-import-run');}finally{if(!retriable)await this.store.updateRun(runId,{status:'FAILED'});}
  // 区分可重试(blocked，run 保持可续跑)与致命(failed，run 打死)——AgentLoop 上要能一眼看出该重试还是该改产物。
  // event 用 .error 结尾，让 levelOf() 归为 warn（trace.service 按事件名推 level，不看 data）。
  if(ctx)this.mark('P4',stage.toLowerCase(),retriable?'blocked.error':'failed.error',ctx,{stage,retriable,run_status:retriable?'RUNNING':'FAILED',error:message.slice(0,200)});
  throw error;}
 private async p4Input(runId:string,path:BuildPath):Promise<{payload:any;check?:CheckReport}>{const kind=path==='P3'?'personalized_package':path==='P3B'?'flow_yaml':'blueprint';const checkKind=path==='P3C'?'blueprint_check':'flow_check';return{payload:await this.requireArtifact(runId,kind),check:await this.requireArtifact(runId,checkKind)};}
 private objectArgument<T extends object>(value:unknown,name:string):T{let parsed=value;if(typeof parsed==='string'){try{parsed=JSON.parse(parsed);}catch{throw new Error(name+' must be a JSON object');}}if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw new Error(name+' must be a JSON object');return parsed as T;}
 private tool(name:string,description:string,required:string[],properties:Record<string,unknown>={}):ToolDefinition{return{name,description,inputSchema:objectSchema(required,properties)};}
}
