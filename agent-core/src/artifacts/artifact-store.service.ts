import { Injectable, OnModuleDestroy } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import { randomUUID } from 'crypto';
import { Pool, PoolClient, QueryResultRow } from 'pg';
import {
  AgentBinding, AgentBlueprint, AgentBlueprintRecord, Artifact, ArtifactKind,
  RunRecord,
} from '../common/types';
import { ProductPhase } from '../common/product-phase';
import { BlobStore, isBlobRef } from './blob-store';

export interface StoreData {
  runs: RunRecord[];
  artifacts: Artifact[];
  blueprints: AgentBlueprintRecord[];
  bindings: AgentBinding[];
}
type DbRole = 'worker_p1'|'worker_p2'|'worker_p3'|'worker_p3b'|'worker_p3c'|'worker_p4'|'blueprint_admin';

/**
 * ArtifactStore 唯一入口。默认 file 后端供离线 DEMO；ARTIFACT_STORE=postgres 时所有
 * 操作直接落 PostgreSQL，缺 DATABASE_URL 会启动失败，绝不静默降级。
 * BLUEPRINT_STORE=postgres 可在 file 模式下把 Blueprint/绑定镜像到同一份 PG，
 * 让 production runtime 的 JDBC 投影读到确认发布的 PUBLISHED 行。
 */
@Injectable()
export class ArtifactStoreService implements OnModuleDestroy {
  private data: StoreData = { runs: [], artifacts: [], blueprints: [], bindings: [] };
  private readonly file: string;
  private readonly pool?: Pool;
  /** file 模式下把 PUBLISHED 绑定镜像到 PG，供 production runtime JDBC 投影。 */
  private readonly blueprintPool?: Pool;
  private readonly blobs?: BlobStore;

  constructor() {
    this.file = path.resolve(process.env.ARTIFACT_STORE_FILE ?? 'var/agentteams-store.json');
    const mode = (process.env.ARTIFACT_STORE ?? 'file').toLowerCase();
    const blueprintMode = (process.env.BLUEPRINT_STORE ?? '').toLowerCase();
    if (mode === 'postgres') {
      const connectionString = process.env.DATABASE_URL?.trim();
      if (!connectionString) throw new Error('DATABASE_URL is required when ARTIFACT_STORE=postgres');
      this.pool = new Pool({ connectionString, max: Number(process.env.DATABASE_POOL_SIZE ?? 10), ssl: process.env.DATABASE_SSL === '1' ? { rejectUnauthorized: true } : undefined });
      this.blobs = new BlobStore();
    } else if (mode === 'file') {
      this.data = this.load();
      if (blueprintMode === 'postgres') {
        const connectionString = process.env.DATABASE_URL?.trim();
        if (!connectionString) throw new Error('DATABASE_URL is required when BLUEPRINT_STORE=postgres');
        this.blueprintPool = new Pool({ connectionString, max: Number(process.env.DATABASE_POOL_SIZE ?? 10), ssl: process.env.DATABASE_SSL === '1' ? { rejectUnauthorized: true } : undefined });
      } else if (blueprintMode && blueprintMode !== 'file') {
        throw new Error('BLUEPRINT_STORE must be postgres or file');
      }
    } else {
      throw new Error('ARTIFACT_STORE must be file or postgres');
    }
  }

  async onModuleDestroy(): Promise<void> { await this.pool?.end(); await this.blueprintPool?.end(); }

  async health():Promise<{database:boolean;blob:boolean}>{if(!this.pool)return{database:true,blob:true};let database=false;try{await this.pool.query('select 1');database=true;}catch{}return{database,blob:await this.blobs!.health()};}

  async createRun(clientCode: string): Promise<RunRecord> {
    const now = new Date().toISOString();
    const run: RunRecord = { run_id: randomUUID(), client_code: clientCode, status: 'RUNNING', current_phase: ProductPhase.P1_WIZARD_INTENT, created_at: now, updated_at: now };
    if (!this.pool) { this.data.runs.push(run); this.flush(); return run; }
    const result = await this.tenantQuery<RunRecord>(clientCode, 'insert into run(run_id,client_code,status,current_phase,created_at,updated_at) values($1,$2,$3,$4,$5,$5) returning *', [run.run_id, clientCode, run.status, run.current_phase, now]);
    return this.runRow(result.rows[0]);
  }

  async ensureRun(runId:string,clientCode:string):Promise<RunRecord>{
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(runId))throw new Error('_ctx.run_id must be UUID v4');
    if(!/^[A-Za-z0-9_-]+$/.test(clientCode))throw new Error('_ctx.client_code invalid');
    // 报错要自带「谁跟谁不匹配」：只说 "run tenant mismatch" 时，Worker 会把它跟同一次
    // 调用里的其它失败混为一谈（实测把它当成 approval proof malformed，然后猜 proof 格式、
    // 试十几种参数变体，始终没意识到该改的是 clientCode）。
    if(!this.pool){const found=this.data.runs.find(r=>r.run_id===runId);if(found){if(found.client_code!==clientCode)throw new Error('run tenant mismatch: run '+runId+' belongs to clientCode='+found.client_code+', but the call passed clientCode='+clientCode+'; pass the run\'s own clientCode');return structuredClone(found);}const now=new Date().toISOString();const run:RunRecord={run_id:runId,client_code:clientCode,status:'RUNNING',current_phase:ProductPhase.P1_WIZARD_INTENT,created_at:now,updated_at:now};this.data.runs.push(run);this.flush();return structuredClone(run);}
    const now=new Date().toISOString();await this.tenantQuery(clientCode,'insert into run(run_id,client_code,status,current_phase,created_at,updated_at) values($1,$2,\'RUNNING\',\'P1\',$3,$3) on conflict(run_id) do nothing',[runId,clientCode,now]);const run=await this.getRun(runId);if(run.client_code!==clientCode)throw new Error('run tenant mismatch: run '+runId+' belongs to clientCode='+run.client_code+', but the call passed clientCode='+clientCode+'; pass the run\'s own clientCode');return run;
  }

  async updateRun(runId: string, patch: Partial<Pick<RunRecord, 'status' | 'current_phase' | 'build_path'>>): Promise<RunRecord> {
    if (!this.pool) { const run = this.mustRun(runId); Object.assign(run, patch, { updated_at: new Date().toISOString() }); this.flush(); return structuredClone(run); }
    const current = await this.getRun(runId);
    const result = await this.tenantQuery<RunRecord>(current.client_code, 'update run set status=$2,current_phase=$3,build_path=$4,updated_at=now() where run_id=$1 returning *', [runId, patch.status ?? current.status, patch.current_phase ?? current.current_phase, patch.build_path ?? current.build_path ?? null]);
    return this.runRow(result.rows[0]);
  }

  /** Atomically stop a non-terminal run. Used only by the authenticated control plane. */
  async abortRun(runId:string):Promise<RunRecord>{
    const terminal=new Set<RunRecord['status']>(['SUCCEEDED','FAILED','ABORTED']);
    if(!this.pool){const run=this.mustRun(runId);if(terminal.has(run.status))throw new Error('run is already terminal: '+run.status);run.status='ABORTED';run.updated_at=new Date().toISOString();this.flush();return structuredClone(run);}
    const current=await this.getRun(runId);
    const result=await this.tenantQuery<RunRecord>(current.client_code,"update run set status='ABORTED',updated_at=now() where run_id=$1 and status not in ('SUCCEEDED','FAILED','ABORTED') returning *",[runId]);
    if(!result.rows[0])throw new Error('run is already terminal');
    return this.runRow(result.rows[0]);
  }

  async putArtifact<T>(runId: string, kind: ArtifactKind, payload: T, writtenBy: string): Promise<Artifact<T>> {
    const run = await this.getRun(runId);
    if (!this.pool) {
      const version = 1 + Math.max(0, ...this.data.artifacts.filter(a => a.run_id === runId && a.kind === kind).map(a => a.version));
      const artifact: Artifact<T> = { artifact_id: randomUUID(), run_id: runId, client_code: run.client_code, kind, version, payload, written_by: writtenBy, created_at: new Date().toISOString() };
      this.data.artifacts.push(artifact as Artifact); this.flush(); return structuredClone(artifact);
    }
    return this.transaction(run.client_code, async client => {
      await client.query('select pg_advisory_xact_lock(hashtext($1))', [runId + ':' + kind]);
      const versionResult = await client.query<{ next: number }>('select coalesce(max(version),0)+1 as next from artifact where run_id=$1 and kind=$2', [runId, kind]);
      const version = Number(versionResult.rows[0].next);
      const storedPayload = this.isLargeKind(kind) ? await this.blobs!.put(runId, kind, version, payload) : payload;
      const result = await client.query<Artifact<T>>('insert into artifact(artifact_id,run_id,client_code,kind,version,payload,written_by) values($1,$2,$3,$4,$5,$6::jsonb,$7) returning *', [randomUUID(), runId, run.client_code, kind, version, JSON.stringify(storedPayload), writtenBy]);
      const row = this.artifactRow<T>(result.rows[0]);
      return { ...row, payload };
    }, this.writerRole(writtenBy));
  }

  /** Atomic append-only approval state transition; serializes proof consumption per run. */
  async transitionApproval(runId:string,approvalId:string,expected:string|null,payload:Record<string,unknown>):Promise<Artifact<Record<string,unknown>>>{
    const run=await this.getRun(runId);
    const validate=(current:Artifact<Record<string,unknown>>|undefined)=>{const status=current?.payload?.status;if(expected===null?current!==undefined:status!==expected||current?.payload?.approval_id!==approvalId)throw new Error('approval credential mismatch or already consumed');};
    if(!this.pool){const current=this.data.artifacts.filter(a=>a.run_id===runId&&a.kind==='approval').sort((a,b)=>b.version-a.version)[0] as Artifact<Record<string,unknown>>|undefined;validate(current);const version=(current?.version??0)+1,artifact:Artifact<Record<string,unknown>>={artifact_id:randomUUID(),run_id:runId,client_code:run.client_code,kind:'approval',version,payload:structuredClone(payload),written_by:'flow-import-run',created_at:new Date().toISOString()};this.data.artifacts.push(artifact);this.flush();return structuredClone(artifact);}
    return this.transaction(run.client_code,async client=>{await client.query('select pg_advisory_xact_lock(hashtext($1))',[runId+':approval']);const currentResult=await client.query<Artifact<Record<string,unknown>>>('select * from artifact where run_id=$1 and kind=\'approval\' order by version desc limit 1',[runId]);const current=currentResult.rows[0]?this.artifactRow<Record<string,unknown>>(currentResult.rows[0]):undefined;validate(current);const version=(current?.version??0)+1;const result=await client.query<Artifact<Record<string,unknown>>>('insert into artifact(artifact_id,run_id,client_code,kind,version,payload,written_by) values($1,$2,$3,\'approval\',$4,$5::jsonb,\'flow-import-run\') returning *',[randomUUID(),runId,run.client_code,version,JSON.stringify(payload)]);return this.artifactRow<Record<string,unknown>>(result.rows[0]);},'worker_p4');
  }

  async latestArtifact<T>(runId: string, kind: ArtifactKind): Promise<Artifact<T> | undefined> {
    if (!this.pool) return structuredClone(this.data.artifacts.filter(a => a.run_id === runId && a.kind === kind).sort((a,b) => b.version-a.version)[0]) as Artifact<T> | undefined;
    const run = await this.getRun(runId);
    const result = await this.tenantQuery<Artifact<T>>(run.client_code, 'select * from artifact where run_id=$1 and kind=$2 order by version desc limit 1', [runId, kind]);
    if (!result.rows[0]) return undefined;
    const row = this.artifactRow<T>(result.rows[0]);
    return isBlobRef(row.payload) ? { ...row, payload: await this.blobs!.get<T>(row.payload) } : row;
  }

  async listArtifacts(runId:string,kind?:ArtifactKind):Promise<Artifact[]>{
    if(!this.pool)return structuredClone(this.data.artifacts.filter(a=>a.run_id===runId&&(!kind||a.kind===kind)).sort((a,b)=>a.created_at.localeCompare(b.created_at)));
    const run=await this.getRun(runId),params:unknown[]=[runId];let sql='select * from artifact where run_id=$1';if(kind){sql+=' and kind=$2';params.push(kind);}sql+=' order by created_at,version';const result=await this.tenantQuery<Artifact>(run.client_code,sql,params);return Promise.all(result.rows.map(async value=>{
      const row=this.artifactRow(value);
      if(!isBlobRef(row.payload))return row;
      try{return{...row,payload:await this.blobs!.get(row.payload)};}
      catch{return row;}
    }));
  }

  async getRun(runId: string): Promise<RunRecord> {
    if (!this.pool) return structuredClone(this.mustRun(runId));
    const tenant = await this.pool.query<{client_code:string}>('select lookup_run_client($1) as client_code', [runId]);
    if (!tenant.rows[0]?.client_code) throw new Error('run not found: ' + runId);
    const result = await this.tenantQuery<RunRecord>(tenant.rows[0].client_code,'select * from run where run_id=$1', [runId]);
    if (!result.rows[0]) throw new Error('run not found: ' + runId);
    return this.runRow(result.rows[0]);
  }

  async persistBlueprint(runId: string, blueprint: AgentBlueprint): Promise<AgentBlueprintRecord> {
    const run = await this.getRun(runId);
    if (run.client_code !== blueprint.clientCode) throw new Error('blueprint clientCode does not match run tenant');
    if (!this.pool) {
      const prior = this.data.blueprints.filter(b => b.client_code === blueprint.clientCode && b.runtime_agent_id === blueprint.runtimeAgentId);
      if (prior.some(b => b.payload.runtime.isolationScope !== blueprint.runtime.isolationScope)) throw new Error('runtime isolationScope is immutable after first Blueprint');
      const version = 1 + Math.max(0, ...prior.map(b => b.version)); const now = new Date().toISOString(); const payload = structuredClone({ ...blueprint, version });
      const record: AgentBlueprintRecord = { blueprint_id: blueprint.blueprintId, client_code: blueprint.clientCode, runtime_agent_id: blueprint.runtimeAgentId, version, status: 'DRAFT', payload, source_run_id: runId, written_by: 'blueprint-compose', created_at: now, updated_at: now };
      this.data.blueprints.push(record); this.flush();
      await this.mirrorPersistDraft(run, record);
      return structuredClone(record);
    }
    return this.transaction(run.client_code, async client => {
      await client.query('select pg_advisory_xact_lock(hashtext($1))', [blueprint.clientCode + ':' + blueprint.runtimeAgentId]);
      const next = await client.query<{ next: number }>('select coalesce(max(version),0)+1 as next from agent_blueprint where client_code=$1 and runtime_agent_id=$2', [blueprint.clientCode, blueprint.runtimeAgentId]);
      const prior = await client.query<{ isolation_scope: string }>("select payload->'runtime'->>'isolationScope' as isolation_scope from agent_blueprint where client_code=$1 and runtime_agent_id=$2 order by version desc limit 1", [blueprint.clientCode, blueprint.runtimeAgentId]);
      if (prior.rows[0] && prior.rows[0].isolation_scope !== blueprint.runtime.isolationScope) throw new Error('runtime isolationScope is immutable after first Blueprint');
      const version = Number(next.rows[0].next); const payload = { ...blueprint, version };
      const result = await client.query<AgentBlueprintRecord>('insert into agent_blueprint(blueprint_id,client_code,runtime_agent_id,version,status,payload,source_run_id,written_by) values($1,$2,$3,$4,\'DRAFT\',$5::jsonb,$6,$7) returning *', [blueprint.blueprintId, blueprint.clientCode, blueprint.runtimeAgentId, version, JSON.stringify(payload), runId, 'blueprint-compose']);
      return this.blueprintRow(result.rows[0]);
    }, 'worker_p3c');
  }

  async stageBlueprint(blueprintId: string, clientCode: string): Promise<AgentBlueprintRecord> {
    return this.changeBlueprintStatus(blueprintId, clientCode, 'DRAFT', 'STAGED', 'worker_p4');
  }

  async publishBlueprint(blueprintId: string, clientCode: string, actor: string): Promise<AgentBlueprintRecord> {
    if(!actor.trim())throw new Error('publish actor required');
    if (!this.pool) {
      const stored = this.mustBlueprint(blueprintId, clientCode);
      if (stored.status !== 'STAGED') throw new Error('only STAGED can be published; got ' + stored.status);
      for (const other of this.data.blueprints) if (other !== stored && other.client_code === clientCode && other.runtime_agent_id === stored.runtime_agent_id && other.status === 'PUBLISHED') other.status = 'RETIRED';
      stored.status = 'PUBLISHED'; stored.updated_at = new Date().toISOString(); this.appendBlueprintAudit(stored,'BLUEPRINT_PUBLISHED',actor);this.flush();
      await this.mirrorPublish(stored, actor);
      return structuredClone(stored);
    }
    const bp = await this.getBlueprint(blueprintId, clientCode);
    if (bp.status !== 'STAGED') throw new Error('only STAGED can be published; got ' + bp.status);
    return this.transaction(clientCode, async client => {
      await client.query("update agent_blueprint set status='RETIRED',updated_at=now() where client_code=$1 and runtime_agent_id=$2 and status='PUBLISHED'", [clientCode, bp.runtime_agent_id]);
      const result = await client.query<AgentBlueprintRecord>("update agent_blueprint set status='PUBLISHED',updated_at=now() where blueprint_id=$1 and client_code=$2 and status='STAGED' returning *", [blueprintId, clientCode]);
      if (!result.rows[0]) throw new Error('blueprint publish race');const published=this.blueprintRow(result.rows[0]);await this.insertBlueprintAudit(client,published,'BLUEPRINT_PUBLISHED',actor);return published;
    }, 'blueprint_admin');
  }

  async rollbackBlueprint(clientCode:string,runtimeAgentId:string,version:number,actor:string):Promise<AgentBlueprintRecord>{
    if(!actor.trim())throw new Error('rollback actor required');
    if(!this.pool){const target=this.data.blueprints.find(b=>b.client_code===clientCode&&b.runtime_agent_id===runtimeAgentId&&b.version===version);if(!target)throw new Error('rollback version not found');for(const current of this.data.blueprints)if(current.client_code===clientCode&&current.runtime_agent_id===runtimeAgentId&&current.status==='PUBLISHED')current.status='RETIRED';target.status='PUBLISHED';target.updated_at=new Date().toISOString();this.appendBlueprintAudit(target,'BLUEPRINT_ROLLED_BACK',actor);this.flush();return structuredClone(target);}
    return this.transaction(clientCode,async client=>{const target=await client.query<AgentBlueprintRecord>('select * from agent_blueprint where client_code=$1 and runtime_agent_id=$2 and version=$3 for update',[clientCode,runtimeAgentId,version]);if(!target.rows[0])throw new Error('rollback version not found');await client.query("update agent_blueprint set status='RETIRED',updated_at=now() where client_code=$1 and runtime_agent_id=$2 and status='PUBLISHED'",[clientCode,runtimeAgentId]);const result=await client.query<AgentBlueprintRecord>("update agent_blueprint set status='PUBLISHED',updated_at=now() where client_code=$1 and runtime_agent_id=$2 and version=$3 and status in ('STAGED','RETIRED') returning *",[clientCode,runtimeAgentId,version]);if(!result.rows[0])throw new Error('rollback target must be STAGED or RETIRED');const rolledBack=this.blueprintRow(result.rows[0]);await this.insertBlueprintAudit(client,rolledBack,'BLUEPRINT_ROLLED_BACK',actor);return rolledBack;},'blueprint_admin');
  }

  private appendBlueprintAudit(bp:AgentBlueprintRecord,event:'BLUEPRINT_PUBLISHED'|'BLUEPRINT_ROLLED_BACK',actor:string){const version=1+Math.max(0,...this.data.artifacts.filter(a=>a.run_id===bp.source_run_id&&a.kind==='evidence').map(a=>a.version));this.data.artifacts.push({artifact_id:randomUUID(),run_id:bp.source_run_id,client_code:bp.client_code,kind:'evidence',version,payload:{event,actor,blueprint_id:bp.blueprint_id,version:bp.version,at:new Date().toISOString()},written_by:'blueprint-admin',created_at:new Date().toISOString()});}
  private async insertBlueprintAudit(client:PoolClient,bp:AgentBlueprintRecord,event:'BLUEPRINT_PUBLISHED'|'BLUEPRINT_ROLLED_BACK',actor:string){await client.query('select pg_advisory_xact_lock(hashtext($1))',[bp.source_run_id+':evidence']);const next=await client.query<{next:number}>('select coalesce(max(version),0)+1 as next from artifact where run_id=$1 and kind=\'evidence\'',[bp.source_run_id]);await client.query('insert into artifact(artifact_id,run_id,client_code,kind,version,payload,written_by) values($1,$2,$3,\'evidence\',$4,$5::jsonb,\'blueprint-admin\')',[randomUUID(),bp.source_run_id,bp.client_code,Number(next.rows[0].next),JSON.stringify({event,actor,blueprint_id:bp.blueprint_id,version:bp.version,at:new Date().toISOString()})]);}

  async findBlueprint(blueprintId: string, clientCode: string): Promise<AgentBlueprintRecord | undefined> {
    if (!this.pool) return structuredClone(this.data.blueprints.find(b => b.blueprint_id === blueprintId && b.client_code === clientCode));
    const result = await this.tenantQuery<AgentBlueprintRecord>(clientCode, 'select * from agent_blueprint where blueprint_id=$1 and client_code=$2', [blueprintId, clientCode]);
    return result.rows[0] ? this.blueprintRow(result.rows[0]) : undefined;
  }

  async bind(input: AgentBinding): Promise<AgentBinding> {
    const bp = await this.getBlueprint(input.blueprint_id, input.client_code);
    if (bp.status !== 'STAGED' && bp.status !== 'PUBLISHED') throw new Error('binding requires STAGED or PUBLISHED blueprint');
    if (!this.pool) { const idx=this.data.bindings.findIndex(b=>b.client_code===input.client_code&&b.user_id===input.user_id&&b.runtime_agent_id===input.runtime_agent_id); if(idx>=0)this.data.bindings[idx]=input;else this.data.bindings.push(input);this.flush(); await this.mirrorBind(input); return structuredClone(input); }
    const result = await this.tenantQuery<AgentBinding>(input.client_code, 'insert into agent_binding(client_code,user_id,runtime_agent_id,blueprint_id,projected_version,projected_at) values($1,$2,$3,$4,$5,$6) on conflict(client_code,user_id,runtime_agent_id) do update set blueprint_id=excluded.blueprint_id,projected_version=excluded.projected_version,projected_at=excluded.projected_at returning *', [input.client_code,input.user_id,input.runtime_agent_id,input.blueprint_id,input.projected_version??null,input.projected_at??null], 'worker_p4');
    return result.rows[0];
  }

  async resolvePublished(clientCode: string, userId: string, runtimeAgentId: string): Promise<AgentBlueprintRecord | undefined> {
    if (!this.pool) { const binding=this.data.bindings.find(b=>b.client_code===clientCode&&b.user_id===userId&&b.runtime_agent_id===runtimeAgentId); return structuredClone(binding?this.data.blueprints.find(b=>b.blueprint_id===binding.blueprint_id&&b.status==='PUBLISHED'):undefined); }
    const result=await this.tenantQuery<AgentBlueprintRecord>(clientCode, "select b.* from agent_binding a join agent_blueprint b on b.blueprint_id=a.blueprint_id where a.client_code=$1 and a.user_id=$2 and a.runtime_agent_id=$3 and b.status='PUBLISHED'", [clientCode,userId,runtimeAgentId]);
    return result.rows[0]?this.blueprintRow(result.rows[0]):undefined;
  }

  async snapshot(): Promise<StoreData> {
    if (!this.pool) return structuredClone(this.data);
    const [runs,artifacts,blueprints,bindings]=await Promise.all([this.pool.query<RunRecord>('select * from run order by created_at'),this.pool.query<Artifact>('select * from artifact order by created_at'),this.pool.query<AgentBlueprintRecord>('select * from agent_blueprint order by created_at'),this.pool.query<AgentBinding>('select * from agent_binding')]);
    const hydrated = await Promise.all(artifacts.rows.map(async a => {
      const row = this.artifactRow(a);
      return isBlobRef(row.payload) ? { ...row, payload: await this.blobs!.get(row.payload) } : row;
    }));
    return {runs:runs.rows.map(r=>this.runRow(r)),artifacts:hydrated,blueprints:blueprints.rows.map(b=>this.blueprintRow(b)),bindings:bindings.rows};
  }

  async reset(): Promise<void> {
    if (!this.pool) { this.data={runs:[],artifacts:[],blueprints:[],bindings:[]};this.flush();return; }
    if (process.env.ALLOW_STORE_RESET !== '1') throw new Error('postgres reset requires ALLOW_STORE_RESET=1');
    await this.pool.query('truncate agent_binding,agent_blueprint,artifact,run cascade');
  }

  private async changeBlueprintStatus(id:string,clientCode:string,from:string,to:string,role?:DbRole):Promise<AgentBlueprintRecord>{
    if(!this.pool){const bp=this.mustBlueprint(id,clientCode);if(bp.status!==from)throw new Error('only '+from+' can be '+to.toLowerCase()+'; got '+bp.status);bp.status=to as AgentBlueprintRecord['status'];bp.updated_at=new Date().toISOString();this.flush();await this.mirrorChangeStatus(bp,from,to,role);return structuredClone(bp);}
    const result=await this.tenantQuery<AgentBlueprintRecord>(clientCode,'update agent_blueprint set status=$3,updated_at=now() where blueprint_id=$1 and client_code=$2 and status=$4 returning *',[id,clientCode,to,from],role);if(!result.rows[0])throw new Error('only '+from+' can transition to '+to);return this.blueprintRow(result.rows[0]);
  }
  private async getBlueprint(id:string,client:string){const bp=await this.findBlueprint(id,client);if(!bp)throw new Error('blueprint not found for tenant: '+id);return bp;}
  private async tenantQuery<T extends QueryResultRow>(clientCode:string,sql:string,params:unknown[],role?:DbRole){return this.transaction(clientCode,c=>c.query<T>(sql,params),role);}
  private async transaction<T>(clientCode:string,fn:(client:PoolClient)=>Promise<T>,role?:DbRole):Promise<T>{const client=await this.pool!.connect();try{await client.query('begin');await client.query("select set_config('app.client_code',$1,true)",[clientCode]);if(role)await client.query('set local role '+role);const value=await fn(client);await client.query('commit');return value;}catch(error){await client.query('rollback');throw error;}finally{client.release();}}
  private runRow(row:any):RunRecord{return {...row,created_at:new Date(row.created_at).toISOString(),updated_at:new Date(row.updated_at).toISOString()};}
  private artifactRow<T>(row:any):Artifact<T>{return {...row,version:Number(row.version),payload:typeof row.payload==='string'?JSON.parse(row.payload):row.payload,created_at:new Date(row.created_at).toISOString()};}
  private blueprintRow(row:any):AgentBlueprintRecord{return {...row,version:Number(row.version),payload:typeof row.payload==='string'?JSON.parse(row.payload):row.payload,created_at:new Date(row.created_at).toISOString(),updated_at:new Date(row.updated_at).toISOString()};}
  private isLargeKind(kind:ArtifactKind){return new Set<ArtifactKind>(['personalized_package','flow_yaml','flow_check','blueprint_draft','evidence']).has(kind);}
  private writerRole(writtenBy:string):DbRole{const roles:Record<string,DbRole>={'wizard-intent':'worker_p1','template-match':'worker_p2','template-personalize':'worker_p3','flow-generate':'worker_p3b','blueprint-compose':'worker_p3c','persona-expert':'worker_p3c','business-expert':'worker_p3c','skill-expert':'worker_p3c','tool-expert':'worker_p3c','flow-import-run':'worker_p4'};const role=roles[writtenBy];if(!role)throw new Error('unknown artifact writer role: '+writtenBy);return role;}
  private mustRun(id:string){const r=this.data.runs.find(x=>x.run_id===id);if(!r)throw new Error('run not found: '+id);return r;}
  private mustBlueprint(id:string,client:string){const b=this.data.blueprints.find(x=>x.blueprint_id===id&&x.client_code===client);if(!b)throw new Error('blueprint not found for tenant: '+id);return b;}
  private load():StoreData{if(!fs.existsSync(this.file))return {runs:[],artifacts:[],blueprints:[],bindings:[]};return JSON.parse(fs.readFileSync(this.file,'utf8')) as StoreData;}
  private flush(){fs.mkdirSync(path.dirname(this.file),{recursive:true});const temp=this.file+'.tmp';fs.writeFileSync(temp,JSON.stringify(this.data,null,2));fs.renameSync(temp,this.file);}

  private async txBlueprint<T>(clientCode:string,role:DbRole|undefined,fn:(client:PoolClient)=>Promise<T>):Promise<T>{
    const pool=this.blueprintPool;if(!pool)throw new Error('BLUEPRINT_STORE pool missing');
    const client=await pool.connect();
    try{await client.query('begin');await client.query("select set_config('app.client_code',$1,true)",[clientCode]);if(role)await client.query('set local role '+role);const value=await fn(client);await client.query('commit');return value;}
    catch(error){await client.query('rollback');throw error;}
    finally{client.release();}
  }
  private async ensurePgRun(run:RunRecord):Promise<void>{
    await this.txBlueprint(run.client_code,undefined,async client=>{
      await client.query('insert into run(run_id,client_code,status,current_phase,build_path,created_at,updated_at) values($1,$2,$3,$4,$5,$6,$7) on conflict(run_id) do nothing',[run.run_id,run.client_code,run.status,run.current_phase,run.build_path??null,run.created_at,run.updated_at]);
    });
  }
  /**
   * ARTIFACT_STORE=file + BLUEPRINT_STORE=postgres 的混合模式下，版本号是用**内存**里的
   * this.data.blueprints 算的 max+1，而 PG 才是跨进程的真源。Nest 一重启内存就空，会重新
   * 从 v1 开始，撞上 PG 里已有的 (client_code, runtime_agent_id, version) 唯一键；原来的
   * `on conflict(blueprint_id)` 只覆盖主键，管不到这个唯一键，于是抛 duplicate key，
   * Worker 只看到「duplicate_key_constraint_violation」，反复换 version 重试也没用
   * （版本根本不由它决定）。所以镜像时以 PG 的 max(version)+1 为准，并把权威版本回写到
   * 内存记录与 payload，让后续 stage/publish/binding 看到同一个版本。
   */
  private async mirrorPersistDraft(run:RunRecord,record:AgentBlueprintRecord):Promise<void>{
    if(!this.blueprintPool)return;
    await this.ensurePgRun(run);
    await this.txBlueprint(record.client_code,'worker_p3c',async client=>{
      await client.query('select pg_advisory_xact_lock(hashtext($1))',[record.client_code+':'+record.runtime_agent_id]);
      const existing=await client.query<{version:number}>('select version from agent_blueprint where blueprint_id=$1 and client_code=$2',[record.blueprint_id,record.client_code]);
      let version=Number(existing.rows[0]?.version??0);
      if(!version){
        const next=await client.query<{next:number}>('select coalesce(max(version),0)+1 as next from agent_blueprint where client_code=$1 and runtime_agent_id=$2',[record.client_code,record.runtime_agent_id]);
        version=Number(next.rows[0].next);
      }
      record.version=version;
      if(record.payload&&typeof record.payload==='object')(record.payload as {version?:number}).version=version;
      await client.query('insert into agent_blueprint(blueprint_id,client_code,runtime_agent_id,version,status,payload,source_run_id,written_by) values($1,$2,$3,$4,$5,$6::jsonb,$7,$8) on conflict(blueprint_id) do update set version=excluded.version,status=excluded.status,payload=excluded.payload,source_run_id=excluded.source_run_id,updated_at=now()',[record.blueprint_id,record.client_code,record.runtime_agent_id,version,record.status,JSON.stringify(record.payload),record.source_run_id,record.written_by]);
    });
    this.flush();
  }
  private async mirrorChangeStatus(bp:AgentBlueprintRecord,from:string,to:string,role?:DbRole):Promise<void>{
    if(!this.blueprintPool)return;
    await this.txBlueprint(bp.client_code,role,async client=>{
      const result=await client.query('update agent_blueprint set status=$3,updated_at=now() where blueprint_id=$1 and client_code=$2 and status=$4 returning blueprint_id',[bp.blueprint_id,bp.client_code,to,from]);
      if(!result.rows[0])throw new Error('PG blueprint mirror: only '+from+' can transition to '+to);
    });
  }
  private async mirrorPublish(bp:AgentBlueprintRecord,actor:string):Promise<void>{
    if(!this.blueprintPool)return;
    const run=this.data.runs.find(r=>r.run_id===bp.source_run_id);
    if(run)await this.ensurePgRun(run);
    await this.txBlueprint(bp.client_code,'blueprint_admin',async client=>{
      await client.query("update agent_blueprint set status='RETIRED',updated_at=now() where client_code=$1 and runtime_agent_id=$2 and status='PUBLISHED' and blueprint_id<>$3",[bp.client_code,bp.runtime_agent_id,bp.blueprint_id]);
      const result=await client.query<AgentBlueprintRecord>("update agent_blueprint set status='PUBLISHED',updated_at=now() where blueprint_id=$1 and client_code=$2 and status='STAGED' returning *",[bp.blueprint_id,bp.client_code]);
      if(!result.rows[0])throw new Error('PG blueprint mirror publish race');
      await this.insertBlueprintAudit(client,this.blueprintRow(result.rows[0]),'BLUEPRINT_PUBLISHED',actor);
    });
  }
  private async mirrorBind(input:AgentBinding):Promise<void>{
    if(!this.blueprintPool)return;
    await this.txBlueprint(input.client_code,'worker_p4',async client=>{
      await client.query('insert into agent_binding(client_code,user_id,runtime_agent_id,blueprint_id,projected_version,projected_at) values($1,$2,$3,$4,$5,$6) on conflict(client_code,user_id,runtime_agent_id) do update set blueprint_id=excluded.blueprint_id,projected_version=excluded.projected_version,projected_at=excluded.projected_at',[input.client_code,input.user_id,input.runtime_agent_id,input.blueprint_id,input.projected_version??null,input.projected_at??null]);
    });
  }
}
