import { Injectable } from '@nestjs/common';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';
import { ProductPhase } from '../common/product-phase';
import { AgentBlueprint, CheckReport } from '../common/types';
import { P3Service } from '../p3/p3.service';
import { P3bService } from '../p3b/p3b.service';
import { P3cService } from '../p3c/p3c.service';
import { P4Service } from '../p4/p4.service';
import { WizardService } from '../wizard/wizard.service';
import { MatchService } from '../match/match.service';
import { TenantService } from '../tenant/tenant.service';
import { buildRequestContext } from '../common/request-context';
import { TraceService } from '../common/trace.service';
import { randomUUID } from 'crypto';

@Injectable()
export class PipelineService {
  constructor(private readonly store: ArtifactStoreService, private readonly wizard: WizardService, private readonly match: MatchService, private readonly tenants: TenantService, private readonly p3: P3Service, private readonly p3b: P3bService, private readonly p3c: P3cService, private readonly p4: P4Service, private readonly trace: TraceService) {}

  /**
   * platform 向导已产出 PASS 的 Phase1Result 时，把 P1 产物写入本 run 并推进到 P2。
   * 不跑匹配；P2 仍由 template-match 经 MCP 调用。gate 以 Nest 重算为准。
   */
  async ingestPassedPhase1(runId: string, clientCode: string, phase1: Record<string, unknown>) {
    const supplied = phase1 as { client_code?: string; triage?: import('../common/types').Triage; stage?: string; summary?: unknown };
    if (supplied.client_code && supplied.client_code !== clientCode) throw new Error('Phase1Result client_code must match run tenant');
    if (!supplied.triage || typeof supplied.triage !== 'object' || Array.isArray(supplied.triage)) throw new Error('Phase1Result.triage must be a JSON object');
    const gated = this.wizard.evaluateGate({ ...supplied.triage });
    if (gated.gate !== 'PASS') throw new Error('Phase1Result gate must be PASS after Nest recompute');
    const out = { ...phase1, phase: 'P1', client_code: clientCode, gate: gated.gate, triage: gated.triage, ask_user: gated.ask_user };
    await this.store.putArtifact(runId, 'wizard_state', out, 'wizard-intent');
    await this.store.putArtifact(runId, 'triage', gated.triage, 'wizard-intent');
    return this.store.updateRun(runId, { status: 'RUNNING', current_phase: ProductPhase.P2_TEMPLATE_MATCH });
  }

  /** P1→P4 的权威入口：只接收向导采集字段，不接受预造 Triage/Match。 */
  async executeFromWizardSubmission(input: {
    clientCode: string; userId: string; channel: string; industryId: string;
    goalIds: string[]; businessBrief?: string; needsLongTermMemory?: boolean;
    needsSkillEvolution?: boolean;
  }) {
    const run = await this.store.createRun(input.clientCode);
    this.trace.bind({ run_id: run.run_id, client_code: input.clientCode });
    const traceparent = this.traceparent();
    this.trace.setFlow('agentteams-pipeline');
    this.trace.step('AgentTeams', 'task.start', { run_id: run.run_id, client_code: input.clientCode, phase: 'P1', traceparent });
    try {
      const summary = this.wizard.buildSummary({ industryId: input.industryId, goalIds: input.goalIds, businessBrief: input.businessBrief });
      const phase1 = this.wizard.buildPhase1Result({
        clientCode: input.clientCode, channel: input.channel, stage: 'S1_SUMMARY',
        industryId: input.industryId, goalIds: input.goalIds, summary,
        nextAction: 'preview', needsLongTermMemory: input.needsLongTermMemory,
        needsSkillEvolution: input.needsSkillEvolution,
      });
      await this.store.putArtifact(run.run_id, 'wizard_state', phase1, 'wizard-intent');
      await this.store.putArtifact(run.run_id, 'triage', phase1.triage, 'wizard-intent');
      if (phase1.gate !== 'PASS') {
        await this.store.updateRun(run.run_id, { status: phase1.gate === 'ASK' ? 'WAITING_HUMAN' : 'ABORTED' });
        return { run_id: run.run_id, gate: phase1.gate, ask_user: phase1.ask_user, path: 'EARLY_EXIT' as const };
      }
      await this.store.updateRun(run.run_id, { current_phase: ProductPhase.P2_TEMPLATE_MATCH });
      const tenant = this.tenants.resolve(input.clientCode);
      const match = await this.match.run(buildRequestContext(tenant), phase1.triage);
      await this.store.putArtifact(run.run_id, 'match_result', match, 'template-match');
      return await this.continueAfterP2(run.run_id, input.clientCode, input.userId, phase1.triage, match);
    } catch (error) {
      await this.store.updateRun(run.run_id, { status: 'FAILED' });
      throw error;
    }
  }

  private traceparent(): string {
    const hex = (bytes: number) => Array.from(crypto.getRandomValues(new Uint8Array(bytes))).map(v => v.toString(16).padStart(2,'0')).join('');
    return '00-' + hex(16) + '-' + hex(8) + '-01';
  }

  private async continueAfterP2(runId: string, clientCode: string, userId: string, triage: import('../common/types').Triage, match: import('../common/types').MatchResult) {
    try {
      const buildPath = this.wizard.decideBuildPath(triage, match);
      await this.store.updateRun(runId, { build_path: buildPath, current_phase: buildPath === 'P3' ? ProductPhase.P3_TEMPLATE_PERSONALIZE : buildPath === 'P3B' ? ProductPhase.P3B_FLOW_GENERATE : ProductPhase.P3C_BLUEPRINT_COMPOSE });
      const guidance = this.p3.deriveGuidance(triage);
      await this.store.putArtifact(runId, 'guidance', guidance, 'template-personalize');
      let payload: unknown; let check: CheckReport | undefined;
      if (buildPath === 'P3') {
        payload = this.p3.personalize(match, guidance);
        check = this.p3b.selfcheck(payload as any);
        await this.store.putArtifact(runId, 'personalized_package', payload, 'template-personalize');
        await this.store.putArtifact(runId, 'flow_check', check, 'template-personalize');
      } else if (buildPath === 'P3B') {
        payload = this.p3b.generate(triage, guidance); check = this.p3b.selfcheck(payload as any);
        await this.store.putArtifact(runId, 'flow_yaml', payload, 'flow-generate');
        await this.store.putArtifact(runId, 'flow_check', check, 'flow-generate');
      } else if (buildPath === 'P3C') {
        const dispatched = await this.p3c.dispatchExperts(triage, guidance, clientCode);
        await this.store.putArtifact(runId, 'expert_dispatch', {
          batchId: dispatched.batchId,
          mentions: dispatched.experts.map(e => ({ role: e.role, at: e.startedAt })),
        }, 'blueprint-compose');
        for (const expert of dispatched.experts) {
          await this.store.putArtifact(runId, 'expert_result', expert, expert.role);
        }
        const blueprint = await this.p3c.composeBlueprint({ runId, clientCode, triage, guidance });
        payload = blueprint;
        check = await this.p3c.blueprintSelfcheck(blueprint);
        await this.store.putArtifact(runId, 'blueprint_check', check, 'blueprint-compose');
        const persisted = await this.p3c.persistBlueprint(runId, blueprint);
        await this.store.putArtifact(runId, 'blueprint', persisted.payload, 'blueprint-compose');
      } else throw new Error('unsupported build path');
      if (check && !check.ok) throw new Error('selfcheck failed: '+check.checks.filter(c=>!c.ok).map(c=>'#'+c.id).join(','));
      const approvalId = randomUUID();
      await this.store.putArtifact(runId, 'approval', {
        approval_id: approvalId, action: 'P4_IMPORT', status: 'PENDING', requested_at: new Date().toISOString(),
        reason: '导入/暂存产物会改变外部或发布状态，需 Human 审批', user_id: userId,
      }, 'flow-import-run');
      await this.store.putArtifact(runId, 'evidence', {
        event: 'APPROVAL_REQUESTED', phase: 'P4', path: buildPath,
        selfcheck_ok: check?.ok ?? true, at: new Date().toISOString(),
      }, 'flow-import-run');
      await this.store.updateRun(runId, { status: 'WAITING_HUMAN', current_phase: ProductPhase.P4_IMPORT_RUN });
      return { run_id: runId, gate: 'PASS' as const, match, path: buildPath, status: 'WAITING_HUMAN' as const, approval_required: true, approval_id: approvalId, selfcheck: check };
    } catch (error) {
      await this.store.updateRun(runId, { status: 'FAILED' }); throw error;
    }
  }

  /** Human 对同一个 run_id 作出决定；批准才执行 P4，拒绝原地终止并留审计证据。 */
  async decideApproval(runId: string, actor: string, approved: boolean) {
    if (!actor.trim()) throw new Error('approval actor is required');
    const run = await this.store.getRun(runId);
    if (run.status !== 'WAITING_HUMAN' || !run.build_path || run.build_path === 'EARLY_EXIT') {
      throw new Error('run is not waiting for P4 approval');
    }
    const pendingApproval = await this.store.latestArtifact<Record<string, unknown>>(runId, 'approval');
    const userId = String(pendingApproval?.payload.user_id ?? actor);
    await this.store.putArtifact(runId, 'approval', {
      approval_id: String(pendingApproval?.payload.approval_id ?? ''), action: 'P4_IMPORT', status: approved ? 'APPROVED' : 'DENIED', actor,
      decided_at: new Date().toISOString(),
    }, 'flow-import-run');
    if (!approved) {
      await this.store.putArtifact(runId, 'evidence', {
        event: 'APPROVAL_DENIED', actor, rollback: 'NO_SIDE_EFFECT_EXECUTED', at: new Date().toISOString(),
      }, 'flow-import-run');
      await this.store.updateRun(runId, { status: 'ABORTED' });
      return { run_id: runId, status: 'ABORTED' as const, rolled_back: true };
    }
    const path = run.build_path;
    await this.store.updateRun(runId, { status:'RUNNING', current_phase:ProductPhase.P4_IMPORT_RUN });
    let stage: 'LOAD_ARTIFACT'|'IMPORT'|'BIND'|'DRY_RUN'|'PERSIST_EVIDENCE' = 'LOAD_ARTIFACT';
    try {
      const sourceKind = path === 'P3' ? 'personalized_package' : path === 'P3B' ? 'flow_yaml' : 'blueprint';
      const payload = (await this.store.latestArtifact<unknown>(runId, sourceKind))?.payload;
      if (payload == null) throw new Error('approved artifact is missing: ' + sourceKind);
      const checkKind = path === 'P3' || path === 'P3B' ? 'flow_check' : path === 'P3C' ? 'blueprint_check' : undefined;
      const check = checkKind ? (await this.store.latestArtifact<CheckReport>(runId, checkKind))?.payload : undefined;
      stage = 'IMPORT';
      const imported = await this.p4.import({ runId, clientCode: run.client_code, path, payload, check });
      const blueprint = path === 'P3C' ? payload as AgentBlueprint : undefined;
      stage = 'BIND';
      const binding = await this.p4.bindProject({ clientCode: run.client_code, userId, runtimeAgentId: blueprint?.runtimeAgentId, blueprintId: blueprint?.blueprintId, externalId: imported.external_id, path, actor });
      stage = 'DRY_RUN';
      const dryRun = await this.p4.dryRun({ path, payload, externalId: imported.external_id, userId });
      stage = 'PERSIST_EVIDENCE';
      await this.store.putArtifact(runId, 'import_result', { imported, binding }, 'flow-import-run');
      await this.store.putArtifact(runId, 'dry_run', dryRun, 'flow-import-run');
      await this.store.putArtifact(runId, 'evidence', {
        event: 'P4_EXECUTED', actor, external_id: imported.external_id, dry_run_ok: dryRun.ok,
        at: new Date().toISOString(),
      }, 'flow-import-run');
      await this.store.updateRun(runId, { status: 'SUCCEEDED' });
      return { run_id: runId, path, status: 'SUCCEEDED' as const, imported, binding, dry_run: dryRun, selfcheck: check };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      try {
        await this.store.putArtifact(runId, 'evidence', { event:'P4_FAILED', actor, stage, error:message.slice(0,500), at:new Date().toISOString() }, 'flow-import-run');
      } finally {
        await this.store.updateRun(runId, { status:'FAILED' });
      }
      throw error;
    }
  }
}
