import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'pg';
import { SkillDefinition } from '../common/types';

export interface SkillCandidate { name:string; ref:string; industries:string[]; scenarios:string[] }

/** Layer-2 Skill 市场真源；production 只查 agentscope_skills，local 使用同 schema 的内置种子。 */
@Injectable()
export class SkillCatalogService implements OnModuleDestroy {
  private readonly pool?:Pool;
  private readonly local:SkillCandidate[]=[
    {name:'human-handoff',ref:'skill:human-handoff@1',industries:['*'],scenarios:['*']},
    {name:'product-recommend',ref:'skill:product-recommend@2',industries:['beauty','retail'],scenarios:['beauty_wecom_cs']},
    {name:'after-sales',ref:'skill:after-sales@1',industries:['beauty','retail'],scenarios:['beauty_wecom_cs']},
  ];
  constructor(){if((process.env.ARTIFACT_STORE??'file')==='postgres'){const url=process.env.DATABASE_URL?.trim();if(!url)throw new Error('DATABASE_URL required for PostgreSQL Skill catalog');this.pool=new Pool({connectionString:url,max:Number(process.env.DATABASE_POOL_SIZE??10),ssl:process.env.DATABASE_SSL==='1'?{rejectUnauthorized:true}:undefined});}}
  async list(industry:string|undefined,scenarios:string[]):Promise<SkillCandidate[]>{
    const all=this.pool?await this.readDatabase():this.local;
    return all.filter(s=>s.industries.includes('*')||!industry||s.industries.includes(industry)||s.scenarios.includes('*')||scenarios.some(x=>s.scenarios.includes(x)));
  }
  async referencesExist(skills:SkillDefinition[]):Promise<boolean>{
    const library=skills.filter(s=>s.source==='library');if(!library.length)return true;
    const available=this.pool?await this.readDatabase():this.local;const refs=new Set(available.map(s=>s.ref));
    return library.every(s=>Boolean(s.ref)&&refs.has(String(s.ref)));
  }
  async onModuleDestroy(){await this.pool?.end();}
  private async readDatabase():Promise<SkillCandidate[]>{
    const client=await this.pool!.connect();try{await client.query('begin');await client.query('set local role worker_p3c');const result=await client.query<{name:string;version:number;industries:string[];scenarios:string[]}>("select distinct on(name) name,version,industries,scenarios from agentscope_skills where active=true order by name,version desc");await client.query('commit');return result.rows.map(r=>({name:r.name,ref:'skill:'+r.name+'@'+Number(r.version),industries:r.industries??[],scenarios:r.scenarios??[]}));}catch(error){await client.query('rollback');throw error;}finally{client.release();}
  }
}
