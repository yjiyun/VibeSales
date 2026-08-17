import { Injectable } from '@nestjs/common';
import { parse, stringify } from 'yaml';
import { FlowPackage, FlowPackageCodec } from '../common/flow-package';
import { Guidance, MatchResult, Triage } from '../common/types';

@Injectable()
export class P3Service {
  deriveGuidance(triage:Triage):Guidance {const slots=triage.known_slots??{};return {role:String(slots.role??triage.agent_family??'业务智能助手'),tone:'professional',reply_length:'medium',conditions:['仅依据已知业务资料回答','信息不足时先澄清'],escalation_conditions:['用户明确要求人工','涉及投诉、赔付或高风险决策']};}
  personalize(match:MatchResult,guidance:Guidance):FlowPackage {
    if(match.action!=='hit'||!match.template_id||!match.workflow_path)throw new Error('P3 requires a matched template package');
    const pkg=FlowPackageCodec.fromTemplate(match.workflow_path),doc=parse(pkg.workflowYaml) as any;
    const addition='\n\n[AgentTeams 个性化指导]\n角色：'+guidance.role+'\n语气：'+guidance.tone+'\n回复长度：'+guidance.reply_length+'\n必须遵循：'+guidance.conditions.join('；')+'\n转人工：'+guidance.escalation_conditions.join('；');
    let changed=0;for(const node of doc.nodes??[]){if(node.type!=='llm')continue;const params=node.parameters??{};if(Array.isArray(params.llmParam)){const field=params.llmParam.find((x:any)=>x?.name==='systemPrompt'&&x?.input?.type==='string');if(field&&typeof field.input.value==='string'){field.input.value+=addition;changed++;}continue;}const llm=params.llmParam;if(!llm)continue;if(typeof llm.systemPrompt==='string'){llm.systemPrompt+=addition;changed++;}else if(llm.systemPrompt?.value&&typeof llm.systemPrompt.value.content==='string'){llm.systemPrompt.value.content+=addition;changed++;}}
    if(changed===0)throw new Error('matched template has no safely writable LLM systemPrompt');
    return {...pkg,workflowYaml:stringify(doc,{indent:2})};
  }
}
