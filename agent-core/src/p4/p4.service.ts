import { Injectable } from '@nestjs/common'; import { createHash } from 'crypto';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';
import { AgentBlueprint, BuildPath, CheckReport } from '../common/types';
import { FlowPlatformClient } from './flow-platform.client';
import { AgentRuntimeClient } from './agent-runtime.client';
import { FlowPackageCodec } from '../common/flow-package';
import { runtimeSafeId } from '../common/runtime-id';

@Injectable()
export class P4Service {
  constructor(private readonly store: ArtifactStoreService,private readonly flows:FlowPlatformClient,private readonly runtime:AgentRuntimeClient) {}

  async import(input: { runId: string; clientCode: string; path: BuildPath; payload: unknown; check?: CheckReport }) {
    const run=await this.store.getRun(input.runId);
    if(run.client_code!==input.clientCode||!['WAITING_HUMAN','RUNNING'].includes(run.status)||run.current_phase!=='P4'||run.build_path!==input.path)throw new Error('P4 import run identity/state/path mismatch');
    const approval=await this.store.latestArtifact<Record<string,unknown>>(input.runId,'approval');
    if(approval?.payload?.action!=='P4_IMPORT'||!['APPROVED','PROCESSING'].includes(String(approval.payload.status))||typeof approval.payload.actor!=='string'||!approval.payload.actor.trim())throw new Error('P4 import requires persisted Human APPROVED decision');
    if (input.check && !input.check.ok) throw new Error('artifact selfcheck failed; import denied');
    if ((input.path === 'P3' || input.path === 'P3B') && input.check?.subject_hash !== FlowPackageCodec.subjectHash(input.payload as any)) throw new Error('FlowPackage selfcheck subject mismatch');
    if (input.path === 'P3C') {
      const bp = input.payload as AgentBlueprint;
      if(input.check?.subject_hash!==this.p3cHash(bp))throw new Error('Blueprint selfcheck subject mismatch');
      const existing = await this.store.findBlueprint(bp.blueprintId, input.clientCode);
      const draft = existing ?? await this.store.persistBlueprint(input.runId, bp);
      const staged = await this.store.stageBlueprint(draft.blueprint_id, input.clientCode);
      const actor = String((await this.store.latestArtifact<Record<string, unknown>>(input.runId, 'approval'))?.payload?.actor ?? 'p4-import');
      await this.runtime.ingest(staged.payload, actor);
      return { kind: 'blueprint' as const, external_id: staged.blueprint_id, status: 'STAGED' as const };
    }
    return this.flows.import(input.runId,input.clientCode,input.path,input.payload);
  }

  private p3cHash(bp:AgentBlueprint):string{const canonical=(value:any):any=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;const{version:_,...content}=bp;return createHash('sha256').update(JSON.stringify(canonical(content))).digest('hex');}

  async bindProject(input: { clientCode: string; userId: string; runtimeAgentId?: string; blueprintId?: string; externalId: string; path: BuildPath; actor?: string }) {
    if (input.path === 'P3C') {
      if (!input.blueprintId || !input.runtimeAgentId) throw new Error('P3C binding requires blueprintId/runtimeAgentId');
      const userId = runtimeSafeId(input.userId);
      const binding = await this.store.bind({ client_code: input.clientCode, user_id: userId, runtime_agent_id: input.runtimeAgentId, blueprint_id: input.blueprintId });
      const current = await this.store.findBlueprint(input.blueprintId, input.clientCode);
      if (current?.status === 'STAGED') {
        await this.store.publishBlueprint(input.blueprintId, input.clientCode, (input.actor ?? '').trim() || userId);
      } else if (current?.status !== 'PUBLISHED') {
        throw new Error('P3C binding requires STAGED or PUBLISHED blueprint');
      }
      return binding;
    }
    return { client_code: input.clientCode, project_id: input.externalId, bound: true };
  }

  async dryRun(input: { path: BuildPath; payload: unknown; externalId?:string; userId?:string }) {
    if (input.path === 'P3C') {
      const bp = input.payload as AgentBlueprint;
      if(!input.userId)throw new Error('P3C dry-run requires userId');
      return { ...(await this.runtime.dryRun(bp,input.userId)), runtime_agent_id: bp.runtimeAgentId };
    }
    if(!input.externalId)throw new Error('workflow dry-run requires externalId');
    return this.flows.dryRun(input.externalId,input.payload);
  }
}
