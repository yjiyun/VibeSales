import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import * as fs from 'fs';
import * as path from 'path';
import { AppModule } from '../src/app.module';
import { P3Service } from '../src/p3/p3.service';
import { P3cService } from '../src/p3c/p3c.service';
import { UNMAPPED_SCENE_ID, WizardService } from '../src/wizard/wizard.service';

const cases = [
  { industryId:'beauty', goalIds:['faq_deflect','present_recommend','collect_escalate'], clientCode:'smoke_beauty', scene:'beauty_wecom_cs' },
  { industryId:'agriculture', goalIds:['faq_deflect','guide_usage','collect_escalate'], clientCode:'smoke_agriculture', scene:'agri_drone_wecom_cs' },
  { industryId:'edu', goalIds:['intake_qualify','present_recommend','handoff_sales'], clientCode:'smoke_edu', scene:'edu_abroad_wecom_sales' },
  { industryId:'recruiting', goalIds:['intake_qualify','faq_deflect','collect_escalate'], clientCode:'smoke_recruiting', scene:'hr_recruit_wecom_faq' },
] as const;

async function main() {
  const output = process.env.BLUEPRINT_SMOKE_DIR?.trim();
  if (!output) throw new Error('BLUEPRINT_SMOKE_DIR required');
  fs.mkdirSync(output, { recursive: true });
  const app = await NestFactory.createApplicationContext(AppModule, { logger:false });
  const wizard=app.get(WizardService),p3=app.get(P3Service),p3c=app.get(P3cService);
  for (const c of cases) {
    const summary=wizard.buildSummary({industryId:c.industryId,goalIds:[...c.goalIds],businessBrief:'行业 P3C 跨会话智能体冒烟'});
    const p1=wizard.buildPhase1Result({clientCode:c.clientCode,channel:'wecom',stage:'S1_SUMMARY',industryId:c.industryId,goalIds:[...c.goalIds],summary,nextAction:'preview',needsLongTermMemory:true});
    if(p1.gate!=='PASS'||p1.triage.scene_id!==c.scene)throw new Error(c.industryId+' P1 mapping failed: '+p1.gate+'/'+p1.triage.scene_id);
    const bp=await p3c.composeBlueprint({runId:'smoke_'+c.industryId,clientCode:c.clientCode,triage:p1.triage,guidance:p3.deriveGuidance(p1.triage)});
    const check=await p3c.blueprintSelfcheck(bp);if(!check.ok||check.checks.length!==14)throw new Error(c.industryId+' Blueprint selfcheck failed');
    const recovery=bp.rules?.find((r)=>r.ruleCode==='recovery-detection');
    if(!recovery||recovery.enabled===false)throw new Error(c.industryId+' expected recovery-detection from needsLongTermMemory');
    fs.writeFileSync(path.join(output,c.industryId+'.json'),JSON.stringify({...bp,version:1},null,2));
  }
  const autoSummary=wizard.buildSummary({industryId:'auto',goalIds:['faq_deflect','collect_escalate'],businessBrief:'汽车售后客服'});
  const autoP1=wizard.buildPhase1Result({clientCode:'smoke_auto',channel:'wecom',stage:'S1_SUMMARY',industryId:'auto',goalIds:['faq_deflect','collect_escalate'],summary:autoSummary,nextAction:'preview'});
  if(autoP1.gate!=='PASS'||autoP1.triage.scene_id!==UNMAPPED_SCENE_ID)throw new Error('auto P1 must PASS unmapped, got '+autoP1.gate+'/'+autoP1.triage.scene_id);
  await app.close();
  process.stdout.write('[PASS] Node P1/P3C exported 4 industry Blueprint smokes; auto→unmapped\n');
}
main().then(()=>process.exit(0)).catch(error=>{console.error(error);process.exit(1);});
