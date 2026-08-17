import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../src/app.module';
import { ArtifactStoreService } from '../src/artifacts/artifact-store.service';
import { PipelineService } from '../src/orchestration/pipeline.service';
import * as fs from 'fs';
import * as path from 'path';
import { FlowPackageCodec } from '../src/common/flow-package';
import { runtimeSafeId } from '../src/common/runtime-id';

const wizardSubmission = {
  channel: 'wecom', industryId: 'beauty',
  goalIds: ['faq_deflect','present_recommend','collect_escalate'],
  businessBrief: '为美妆客户提供产品答疑、推荐与必要的人工升级',
};

async function main() {
  const app = await NestFactory.createApplicationContext(AppModule, { logger: false });
  const store = app.get(ArtifactStoreService); const pipeline = app.get(PipelineService);
  await store.reset();
  const p3 = await pipeline.executeFromWizardSubmission({ clientCode: 'acme_beauty', userId: 'user-p3', ...wizardSubmission });
  const p3b = await pipeline.executeFromWizardSubmission({ clientCode: 'acme_beauty_missing_kb', userId: 'user-p3b', ...wizardSubmission });
  const p3c = await pipeline.executeFromWizardSubmission({ clientCode: 'acme_beauty_missing_kb', userId: 'user-p3c', ...wizardSubmission, needsLongTermMemory: true });
  for (const [name, result, expected] of [['P3', p3, 'P3'], ['P3B', p3b, 'P3B'], ['P3C', p3c, 'P3C']] as const) {
    if (result.path !== expected || result.gate !== 'PASS' || result.status !== 'WAITING_HUMAN') throw new Error(name + ' did not pause for approval');
    if (name === 'P3' && (!('match' in result) || result.match.action !== 'hit')) throw new Error('P2 must naturally hit for P3');
    if (name !== 'P3' && (!('match' in result) || result.match.action !== 'custom')) throw new Error('P2 must naturally custom for ' + name);
    const approved = await pipeline.decideApproval(result.run_id, 'human-reviewer', true);
    if (approved.status !== 'SUCCEEDED' || !approved.dry_run.ok) throw new Error(name + ' approved P4 failed');
    if (name !== 'P3C') {
      const checks = approved.selfcheck?.checks ?? [];
      if (checks.length !== 11 || checks.some(c => !c.ok)) throw new Error(name + ' did not pass all 11 flow checks');
      if (approved.imported.kind !== 'workflow') throw new Error(name + ' used non-workflow importer');
      const zipPath = String((approved.imported.receipt as any)?.package ?? '');
      if (!zipPath || !fs.existsSync(zipPath)) throw new Error(name + ' import receipt has no package.zip');
      const decoded = FlowPackageCodec.unzip(fs.readFileSync(zipPath));
      if (!decoded.manifestYaml.includes('type: Workflow') || !decoded.workflowFile.endsWith('.yaml')) throw new Error(name + ' package tree invalid');
    }
    process.stdout.write('[PASS] ' + name + ' run=' + result.run_id + ' P1→P2→' + expected + '→approve→P4\n');
  }
  const snapshot = await store.snapshot();
  for (const run of [p3,p3b,p3c]) {
    const state = snapshot.artifacts.find(a => a.run_id === run.run_id && a.kind === 'wizard_state') as any;
    if (!state || state.payload?.phase !== 'P1' || state.payload?.gate !== 'PASS' || state.payload?.triage?.scene_id !== 'beauty_wecom_cs') throw new Error('P1 Phase1Result missing or invalid');
  }
  process.stdout.write('[PASS] user wizard submission → P1 Phase1Result → P2, no prebuilt Triage/Match bypass\n');
  const bp = snapshot.blueprints[0];
  if (!bp || bp.status !== 'PUBLISHED') throw new Error('P3C blueprint was not published');
  const bound = await store.resolvePublished(bp.client_code, 'user-p3c', bp.runtime_agent_id);
  if (!bound || bound.status !== 'PUBLISHED') throw new Error('P3C sandbox PUBLISHED binding missing');
  if (runtimeSafeId('@developer:local') !== 'developer_local') throw new Error('runtimeSafeId drifted from sandbox chat');
  const broken = JSON.parse(JSON.stringify(bp.payload)); broken.tools.allow = ['crm_query'];
  const p3cSvc = app.get((await import('../src/p3c/p3c.service')).P3cService);
  const check = await p3cSvc.blueprintSelfcheck(broken);
  if (check.checks.find(c => c.id === 9)?.ok !== false) throw new Error('blueprint selfcheck #9 must fail');
  process.stdout.write('[PASS] P3C selfcheck #9 blocks missing builtin tools\n');
  const expertResults = snapshot.artifacts.filter(a => a.run_id === p3c.run_id && a.kind === 'expert_result');
  if (expertResults.length !== 4) throw new Error('P3C four expert results missing');
  const starts = expertResults.map(a => Number((a.payload as any).startedAt));
  if (Math.max(...starts) - Math.min(...starts) > 100) throw new Error('P3C experts did not start in one time window');
  if (new Set(expertResults.map(a => (a.payload as any).role)).size !== 4) throw new Error('P3C expert roles are not orthogonal');
  process.stdout.write('[PASS] P3C four experts dispatched in one parallel window with isolated results\n');
  const deniedPending = await pipeline.executeFromWizardSubmission({ clientCode: 'acme_beauty_missing_kb', userId: 'user-denied', ...wizardSubmission });
  const denied = await pipeline.decideApproval(deniedPending.run_id, 'human-reviewer', false);
  if (denied.status !== 'ABORTED' || !denied.rolled_back) throw new Error('approval deny did not abort');
  if (await store.latestArtifact(deniedPending.run_id, 'import_result')) throw new Error('denied run executed P4 side effect');
  const evidence = (await store.snapshot()).artifacts.filter(a => a.run_id === deniedPending.run_id && a.kind === 'evidence');
  if (evidence.length < 2) throw new Error('approval audit evidence missing');
  process.stdout.write('[PASS] Human deny → ABORTED, no P4 side effect, audit evidence retained\n');
  const exportPath = process.env.BLUEPRINT_EXPORT_PATH?.trim();
  if (exportPath) {
    fs.mkdirSync(path.dirname(path.resolve(exportPath)), { recursive: true });
    fs.writeFileSync(path.resolve(exportPath), JSON.stringify(bp.payload, null, 2));
    process.stdout.write('[PASS] exported Node-generated Blueprint for Java runtime: ' + path.resolve(exportPath) + '\n');
  }
  await app.close();
}
main().then(() => process.exit(0)).catch(err => { console.error(err); process.exit(1); });
