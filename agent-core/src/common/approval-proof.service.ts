import { Injectable } from '@nestjs/common';
import { createHmac, timingSafeEqual } from 'crypto';

export interface ApprovalProof { run_id:string;approval_id:string;actor:string;decision:'APPROVE'|'DENY';exp:number }

@Injectable()
export class ApprovalProofService {
 issue(input:Omit<ApprovalProof,'exp'>):string {const payload:ApprovalProof={...input,exp:Date.now()+15*60_000};const encoded=Buffer.from(JSON.stringify(payload)).toString('base64url');return encoded+'.'+this.sign(encoded);}
 /**
  * proof 经 Matrix Room 由 Leader 转给 Worker，Worker 复制时常带入换行、空格、Markdown
  * 反引号/围栏，甚至把 `proof=` 前缀一起抄进来。这些都不改变签名内容，却让 split('.')
  * 多出片段而被判 malformed —— 实测 Worker 会误以为是「平台 proof 格式不对」并放弃推进。
  * 这里只做无损归一化（剥掉包装与所有空白字符），签名与过期校验一律不放松。
  */
 private normalize(token:string):string {
  let value=String(token??'').trim();
  value=value.replace(/^```[a-z]*\s*/i,'').replace(/```$/,'').trim();
  value=value.replace(/^(?:approval_)?proof\s*[=:]\s*/i,'');
  value=value.replace(/^[`'"<]+/,'').replace(/[`'">]+$/,'');
  return value.replace(/[\s​-‍﻿]+/g,'');
 }
 verify(token:string):ApprovalProof {const [encoded,signature,...rest]=this.normalize(token).split('.');if(!encoded||!signature||rest.length)throw new Error('approval proof malformed');const expected=Buffer.from(this.sign(encoded)),actual=Buffer.from(signature);if(expected.length!==actual.length||!timingSafeEqual(expected,actual))throw new Error('approval proof invalid');let payload:ApprovalProof;try{payload=JSON.parse(Buffer.from(encoded,'base64url').toString('utf8'));}catch{throw new Error('approval proof payload invalid');}if(!['APPROVE','DENY'].includes(payload.decision)||!payload.run_id||!payload.approval_id||!payload.actor||payload.exp<Date.now())throw new Error('approval proof expired or incomplete');return payload;}
 private sign(value:string){const secret=process.env.PIPELINE_APPROVAL_SIGNING_SECRET?.trim();if(!secret||secret.length<32)throw new Error('PIPELINE_APPROVAL_SIGNING_SECRET must be at least 32 characters');return createHmac('sha256',secret).update(value).digest('base64url');}
}
