/**
 * P3C 分流信号：business_brief 规则抽取回归。
 * 对照 fixtures/wizard-e2e 主用例 / 对照用例简述。
 *
 *   npm run test:p3c-signals
 */
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../src/app.module';
import { WizardService } from '../src/wizard/wizard.service';

async function main() {
  const app = await NestFactory.createApplicationContext(AppModule, { logger: false });
  try {
    const wizard = app.get(WizardService);
    const p3cBrief =
      '我们是美妆品牌，要在企业微信做私聊客服：自动回答护肤咨询、按肤质推荐产品、复杂或投诉转人工。客户下次再来要跨会话记住肤质和偏好，挂起的推荐任务说「好的继续」能接着聊。';
    const p3Brief =
      '我们是美妆品牌，要在企业微信做私聊客服：自动回答护肤咨询、按肤质推荐产品、复杂或投诉转人工。';

    const s1 = wizard.inferP3cSignals(p3cBrief);
    if (!s1.needs_long_term_memory) throw new Error('p3c brief must set needs_long_term_memory');
    const s2 = wizard.inferP3cSignals(p3Brief);
    if (s2.needs_long_term_memory) throw new Error('p3 brief must NOT set needs_long_term_memory');

    const summaryP3c = wizard.buildSummary({
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      businessBrief: p3cBrief,
    });
    const phase1c = wizard.buildPhase1Result({
      clientCode: 'acme_beauty',
      channel: 'wecom',
      stage: 'S1_SUMMARY',
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      summary: summaryP3c,
      nextAction: 'preview',
    });
    if (phase1c.gate !== 'PASS') throw new Error('expected PASS, got ' + phase1c.gate);
    if (phase1c.triage.scene_id !== 'beauty_wecom_cs') throw new Error('scene mismatch');
    if (phase1c.triage.needs_long_term_memory !== true) {
      throw new Error('phase1c missing memory flag');
    }

    const summaryP3 = wizard.buildSummary({
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      businessBrief: p3Brief,
    });
    const phase1 = wizard.buildPhase1Result({
      clientCode: 'acme_beauty',
      channel: 'wecom',
      stage: 'S1_SUMMARY',
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      summary: summaryP3,
      nextAction: 'preview',
    });
    if (phase1.triage.needs_long_term_memory === true) {
      throw new Error('phase1 must not set memory');
    }

    const pathC = wizard.decideBuildPath(phase1c.triage, {
      client_code: 'acme_beauty',
      action: 'hit',
      template_id: 'guyu',
    });
    if (pathC !== 'P3C') throw new Error('expected P3C, got ' + pathC);

    const pathP3 = wizard.decideBuildPath(phase1.triage, {
      client_code: 'acme_beauty',
      action: 'hit',
      template_id: 'guyu',
    });
    if (pathP3 !== 'P3') throw new Error('expected P3, got ' + pathP3);

    const forced = wizard.buildPhase1Result({
      clientCode: 'acme_beauty',
      channel: 'wecom',
      stage: 'S1_SUMMARY',
      industryId: 'beauty',
      goalIds: ['faq_deflect'],
      summary: summaryP3,
      nextAction: 'preview',
      needsLongTermMemory: true,
    });
    if (forced.triage.needs_long_term_memory !== true) {
      throw new Error('explicit flag ignored');
    }

    // LLM 改写 summary 丢触发词时，仍可用 sourceBrief / 显式入参保住 P3C。
    const polished = wizard.buildSummary({
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      businessBrief: '美妆企微客服，负责答疑、推品与转人工。',
    });
    const fromSource = wizard.buildPhase1Result({
      clientCode: 'acme_beauty',
      channel: 'wecom',
      stage: 'S1_SUMMARY',
      industryId: 'beauty',
      goalIds: ['faq_deflect', 'present_recommend', 'collect_escalate'],
      summary: polished,
      nextAction: 'preview',
      sourceBrief: p3cBrief,
    });
    if (fromSource.triage.needs_long_term_memory !== true) {
      throw new Error('sourceBrief must preserve memory signal after polish');
    }

    process.stdout.write('[PASS] p3c signal inference + decideBuildPath\n');
  } finally {
    await app.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
