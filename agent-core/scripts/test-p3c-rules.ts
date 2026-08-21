/**
 * P3C Blueprint rules[]：按需装配 + 自检 #14。
 *
 *   npm run test:p3c-rules
 */
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../src/app.module';
import { AgentBlueprint, Guidance, Triage } from '../src/common/types';
import { P3Service } from '../src/p3/p3.service';
import { P3cService } from '../src/p3c/p3c.service';

function baseTriage(overrides: Partial<Triage> = {}): Triage {
  return {
    scene_id: 'beauty_wecom_cs',
    agent_family: 'customer_success',
    channel: 'wecom',
    industry: 'beauty',
    confidence: 0.9,
    reason: 'p3c-rules',
    known_slots: {
      industry: 'beauty',
      role: 'customer_success',
      desired_capabilities: ['faq_retrieve'],
    },
    ...overrides,
  };
}

function experts(skills: unknown, extra: Record<string, unknown> = {}) {
  return {
    persona: { agentsMd: '# 工作准则\n你是客服。', soulMd: '# 身份\n以客服身份提供帮助。' },
    business: {
      conditions: ['仅依据已知业务资料回答'],
      escalationConditions: ['用户明确要求人工'],
    },
    skills,
    tools: [],
    ...extra,
  };
}

function recoverySkill() {
  return {
    name: 'recovery-handling',
    source: 'inline',
    skillMd:
      '---\nname: recovery-handling\ndescription: 当客户说继续、刚才、接着时用于恢复上一轮未完成任务\n---\n# recovery-handling\n当命中续接说法时按恢复态处理。\n',
  };
}

async function main() {
  process.env.ARTIFACT_STORE = process.env.ARTIFACT_STORE ?? 'file';
  const app = await NestFactory.createApplicationContext(AppModule, { logger: false });
  try {
    const p3 = app.get(P3Service);
    const p3c = app.get(P3cService);
    const guidance: Guidance = p3.deriveGuidance(baseTriage());

    const withMemory = await p3c.composeBlueprint({
      runId: '00000000-0000-4000-8000-000000000001',
      clientCode: 'acme_beauty',
      triage: baseTriage({ needs_long_term_memory: true }),
      guidance,
    });
    if (withMemory.rules?.length !== 1 || withMemory.rules[0].ruleCode !== 'recovery-detection') {
      throw new Error('memory signal must emit recovery-detection, got ' + JSON.stringify(withMemory.rules));
    }
    if (withMemory.rules[0].enabled === false || Object.keys(withMemory.rules[0].params ?? {}).length) {
      throw new Error('demand match must enable recovery-detection with empty params');
    }
    const memoryCheck = await p3c.blueprintSelfcheck(withMemory);
    if (!memoryCheck.ok || memoryCheck.checks.length !== 14) {
      throw new Error('memory blueprint selfcheck failed: ' + JSON.stringify(memoryCheck.checks.filter((c) => !c.ok)));
    }

    const noDemand = await p3c.composeBlueprint({
      runId: '00000000-0000-4000-8000-000000000002',
      clientCode: 'acme_beauty',
      triage: baseTriage(),
      guidance,
      experts: experts([{ name: 'human-handoff', source: 'library', ref: 'skill:human-handoff@1' }]),
    });
    if ((noDemand.rules ?? []).length) {
      throw new Error('no demand must omit recovery-detection, got ' + JSON.stringify(noDemand.rules));
    }
    const emptyCheck = await p3c.blueprintSelfcheck(noDemand);
    const item14 = emptyCheck.checks.find((c) => c.id === 14);
    // 空 rules 只 warning、不挡 persist（CheckReport.ok 仍为 true）
    if (!emptyCheck.ok || item14?.ok !== false || item14.severity !== 'warning') {
      throw new Error('empty rules must warning on #14, got ' + JSON.stringify(item14));
    }

    const bySkill = await p3c.composeBlueprint({
      runId: '00000000-0000-4000-8000-000000000003',
      clientCode: 'acme_beauty',
      triage: baseTriage(),
      guidance,
      experts: experts([recoverySkill()]),
    });
    if (bySkill.rules?.[0]?.ruleCode !== 'recovery-detection') {
      throw new Error('recovery-handling skill must emit recovery-detection');
    }
    const skillCheck = await p3c.blueprintSelfcheck(bySkill);
    if (!skillCheck.ok) throw new Error('skill-matched blueprint selfcheck failed');

    const ignored = await p3c.composeBlueprint({
      runId: '00000000-0000-4000-8000-000000000004',
      clientCode: 'acme_beauty',
      triage: baseTriage(),
      guidance,
      experts: experts(
        [{ name: 'human-handoff', source: 'library', ref: 'skill:human-handoff@1' }],
        { rules: [{ ruleCode: 'made-up-rule', enabled: true, params: {} }] },
      ),
    });
    if ((ignored.rules ?? []).some((r) => r.ruleCode === 'made-up-rule') || (ignored.rules ?? []).length) {
      throw new Error('expert-invented rules must be ignored, got ' + JSON.stringify(ignored.rules));
    }

    const unknown: AgentBlueprint = {
      ...noDemand,
      rules: [{ ruleCode: 'not-a-real-rule', enabled: true, params: {} }],
    };
    const unknownCheck = await p3c.blueprintSelfcheck(unknown);
    if (unknownCheck.ok || unknownCheck.checks.find((c) => c.id === 14)?.ok !== false) {
      throw new Error('unknown ruleCode must fail #14');
    }

    const badParam: AgentBlueprint = {
      ...withMemory,
      rules: [{ ruleCode: 'recovery-detection', enabled: true, params: { keywords: ['继续'] } }],
    };
    const badParamCheck = await p3c.blueprintSelfcheck(badParam);
    if (badParamCheck.ok || !String(badParamCheck.checks.find((c) => c.id === 14)?.detail).includes('keywords')) {
      throw new Error('unknown param key must fail #14');
    }

    const duplicate: AgentBlueprint = {
      ...withMemory,
      rules: [
        { ruleCode: 'recovery-detection', enabled: true, params: {} },
        { ruleCode: 'recovery-detection', enabled: true, params: {} },
      ],
    };
    const dupCheck = await p3c.blueprintSelfcheck(duplicate);
    if (dupCheck.ok) throw new Error('duplicate ruleCode must fail selfcheck');

    const unwired: AgentBlueprint = {
      ...noDemand,
      rules: [{ ruleCode: 'profile-completeness', enabled: true, params: { forcedRoundThreshold: 3 } }],
    };
    const unwiredCheck = await p3c.blueprintSelfcheck(unwired);
    const unwired14 = unwiredCheck.checks.find((c) => c.id === 14);
    if (!unwiredCheck.ok || unwired14?.ok !== false || unwired14.severity !== 'warning') {
      throw new Error('unwired enabled rule must warning on #14, got ' + JSON.stringify(unwired14));
    }

    process.stdout.write('[PASS] P3C rules demand matching + selfcheck #14\n');
  } finally {
    await app.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
