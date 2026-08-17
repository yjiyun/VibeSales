import { Injectable } from '@nestjs/common';
import { randomInt } from 'crypto';
import { FlowPackage, FlowPackageCodec } from '../common/flow-package';
import { CheckReport, Guidance, Triage } from '../common/types';

@Injectable()
export class P3bService {
  generate(triage:Triage,guidance:Guidance):FlowPackage {
    const id=String(Date.now()*100+randomInt(10,99));
    const workflow:Record<string,unknown>={schema_version:'1.0.0',name:'generated-'+triage.scene_id,id:Number(id),description:'AgentTeams P3B generated flow',mode:'workflow',icon:'',nodes:[
      {id:'100001',type:'start',title:'开始',position:{x:0,y:0},parameters:{node_outputs:{query:{type:'string',value:null}}}},
      {id:'200001',type:'llm',title:'业务回复',position:{x:320,y:0},parameters:{llmParam:{modelName:'qwen-plus',modelType:1,systemPrompt:{type:'string',value:{type:'literal',content:'角色：'+guidance.role+'\n语气：'+guidance.tone+'\n条件：'+[...guidance.conditions,...guidance.escalation_conditions].join('；')}}},node_inputs:[{name:'query',input:{type:'string',value:{ref_node:'100001',path:'query'}}}],node_outputs:{answer:{type:'string',value:null}}}},
      {id:'900001',type:'end',title:'结束',position:{x:640,y:0},parameters:{content:{type:'string',value:{type:'literal',content:'{{answer}}'}},node_inputs:[{name:'answer',input:{type:'string',value:{ref_node:'200001',path:'answer'}}}]}},
    ],edges:[{source_node:'100001',target_node:'200001',source_port:'default'},{source_node:'200001',target_node:'900001',source_port:'default'}]};
    return FlowPackageCodec.create(workflow);
  }
  selfcheck(value:FlowPackage|string):CheckReport{return FlowPackageCodec.selfcheck(value);}
}
