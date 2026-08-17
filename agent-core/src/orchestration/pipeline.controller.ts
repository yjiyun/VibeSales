import { Body, Controller, Get, Headers, HttpException, Param, Post } from '@nestjs/common';
import { timingSafeEqual } from 'crypto';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';
import { ApprovalProofService } from '../common/approval-proof.service';
import { PipelineService } from './pipeline.service';

/** Authenticated control-plane edge. Business execution here is local-mode only. */
@Controller('api/v1/pipeline')
export class PipelineController {
 constructor(private readonly store:ArtifactStoreService,private readonly proofs:ApprovalProofService,private readonly pipeline:PipelineService){}

 @Get('health')
 health(@Headers() headers:Record<string,string|undefined>){const actor=this.authorize(headers,['orchestrator','admin']);return{ok:true,actor,mode:this.mode()};}

 @Post('runs')
 async createRun(@Headers() headers:Record<string,string|undefined>,@Body() body:Record<string,unknown>){
  const actor=this.authorize(headers,['orchestrator','admin']),clientCode=this.required(body.client_code,'client_code');
  if(!/^[A-Za-z0-9_-]+$/.test(clientCode))throw new HttpException('client_code invalid',400);
  let run=await this.store.createRun(clientCode);
  if(body.phase1_result!==undefined){
   if(!body.phase1_result||typeof body.phase1_result!=='object'||Array.isArray(body.phase1_result))throw new HttpException('phase1_result must be a JSON object',400);
   try{run=await this.pipeline.ingestPassedPhase1(run.run_id,clientCode,body.phase1_result as Record<string,unknown>);}
   catch(error){throw new HttpException(error instanceof Error?error.message:'phase1_result rejected',400);}
  }
  return{run_id:run.run_id,client_code:run.client_code,status:run.status,current_phase:run.current_phase,actor};
 }

 @Post('start')
 async start(@Headers() headers:Record<string,string|undefined>,@Body() body:Record<string,unknown>){
  if(this.mode()!=='local')throw new HttpException('pipeline/start is local-mode only; use agent-manager in platform mode',409);
  const actor=this.authorize(headers,['orchestrator','admin']),clientCode=this.required(body.client_code,'client_code'),channel=this.required(body.channel,'channel'),industryId=this.required(body.industry_id,'industry_id'),goalIds=Array.isArray(body.goal_ids)?body.goal_ids.map(String):[];
  if(!/^[A-Za-z0-9_-]+$/.test(clientCode)||!goalIds.length)throw new HttpException('client_code or goal_ids invalid',400);
  return this.pipeline.executeFromWizardSubmission({clientCode,userId:actor,channel,industryId,goalIds,businessBrief:typeof body.business_brief==='string'?body.business_brief:undefined,needsLongTermMemory:body.needs_long_term_memory===true,needsSkillEvolution:body.needs_skill_evolution===true});
 }

 @Get(':runId')
 async get(@Headers() headers:Record<string,string|undefined>,@Param('runId') runId:string){this.authorize(headers,['orchestrator','human','admin']);const id=this.required(runId,'runId');return{run:await this.store.getRun(id),artifacts:await this.store.listArtifacts(id)};}

 @Post(':runId/abort')
 async abort(@Headers() headers:Record<string,string|undefined>,@Param('runId') runId:string,@Body() body:Record<string,unknown>){
  const actor=this.authorize(headers,['admin']),reason=this.required(body.reason,'reason');
  if(reason.length>500)throw new HttpException('reason too long',400);
  try{const run=await this.store.abortRun(this.required(runId,'runId'));return{run_id:run.run_id,status:run.status,actor,reason};}
  catch(error){throw new HttpException(error instanceof Error?error.message:'run abort failed',409);}
 }

 @Post(':runId/approval')
 async decide(@Headers() headers:Record<string,string|undefined>,@Param('runId') runId:string,@Body() body:Record<string,unknown>){
  const actor=this.authorize(headers,['human','admin']);if(typeof body.approved!=='boolean')throw new HttpException('approved must be boolean',400);const approvalId=this.required(body.approval_id,'approval_id');
  const run=await this.store.getRun(this.required(runId,'runId'));if(run.status!=='WAITING_HUMAN'||run.current_phase!=='P4')throw new HttpException('run is not waiting for P4 approval',409);
  const pending=await this.store.latestArtifact<Record<string,unknown>>(runId,'approval');if(pending?.payload?.approval_id!==approvalId||pending.payload.status!=='PENDING')throw new HttpException('approval request is missing or already decided',409);
  const decision=body.approved?'APPROVE':'DENY',approval={approval_id:approvalId,actor,decision,proof:this.proofs.issue({run_id:runId,approval_id:approvalId,actor,decision})};
  if(this.mode()==='local'){
   try{await this.store.transitionApproval(runId,approvalId,'PENDING',{...pending.payload,approval_id:approvalId,status:'PROCESSING',actor,decision,decided_at:new Date().toISOString()});}catch{throw new HttpException('approval request is missing or already decided',409);}
   try{
    const result=await this.pipeline.decideApproval(runId,actor,body.approved);
    return{run_id:runId,status:result.status,approval,result};
   }catch(error){
    if(error instanceof HttpException)throw error;
    throw new HttpException(error instanceof Error?error.message:'approval failed',502);
   }
  }
  return{run_id:runId,status:'SIGNED',approval};
 }

 private mode(){const mode=(process.env.ORCHESTRATION_MODE??'local').trim().toLowerCase();if(mode!=='local'&&mode!=='platform')throw new HttpException('ORCHESTRATION_MODE must be local or platform',503);return mode;}
 private authorize(headers:Record<string,string|undefined>,roles:string[]):string{const expected=process.env.PIPELINE_CONTROL_TOKEN?.trim();if(!expected)throw new HttpException('pipeline control disabled',503);const supplied=(headers.authorization??'').replace(/^Bearer\s+/i,'');const a=Buffer.from(expected),b=Buffer.from(supplied);if(a.length!==b.length||!timingSafeEqual(a,b))throw new HttpException('unauthorized',401);if(!roles.includes(headers['x-role']??''))throw new HttpException('role not allowed',403);return this.required(headers['x-actor'],'X-Actor');}
 private required(value:unknown,name:string){if(typeof value!=='string'||!value.trim())throw new HttpException(name+' required',400);return value.trim();}
}
