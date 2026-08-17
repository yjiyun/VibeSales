/**
 * [P1] IntentService — 搭建类自然语言 → 结构化 Triage + 闸门
 *
 * 文档：docs/产品设计.md §3.3 / §4 · docs/工程架构.md §6.3
 *
 * ## 阶段边界
 * - **本服务属 P1**：只做分诊与过闸，**不选模板**
 * - gate=PASS 时，由 **P2** CLI（`match`）再调 MatchService
 * - 与 WizardService.evaluateGate 共用粗补达标规则
 *
 * ```text
 * utterance → 千问提议 triage → WizardService 规则补 next_ask
 *          → evaluateGate → PASS|ASK|CUSTOM|ERROR
 * ```
 */

import { Injectable } from '@nestjs/common';
import { CatalogsService } from '../catalogs/catalogs.service';
import { TraceService } from '../common/trace.service';
import { IntentResult, RequestContext, Triage } from '../common/types';
import { PromptsService } from '../prompts/prompts.service';
import { QwenService } from '../qwen/qwen.service';
import { WizardService } from '../wizard/wizard.service';

@Injectable()
export class IntentService {
  constructor(
    private readonly qwen: QwenService,
    private readonly prompts: PromptsService,
    private readonly catalogs: CatalogsService,
    private readonly wizard: WizardService,
    private readonly trace: TraceService,
  ) {}

  /**
   * [P1] 分诊入口：拼 scenes 提示词 → 千问 → 规范化 → 闸门。
   */
  async recognize(
    ctx: RequestContext,
    utterance: string,
  ): Promise<IntentResult> {
    this.trace.banner('P1 Intent triage');
    const started = Date.now();
    const text = (utterance ?? '').trim();
    this.trace.step('P1.Intent', 'start', {
      client_code: ctx.client_code,
      utterance: text,
      has_api_key: this.qwen.hasApiKey(),
    });

    if (!text) {
      const result = { gate: 'ERROR' as const, error: 'utterance is empty' };
      this.trace.step('P1.Intent', 'done', {
        ms: Date.now() - started,
        ...result,
      });
      return result;
    }

    if (!this.qwen.hasApiKey()) {
      const result = {
        gate: 'ERROR' as const,
        error:
          'DASHSCOPE_API_KEY is empty; use P1 --triage or P2 match --triage to bypass',
      };
      this.trace.step('P1.Intent', 'done', {
        ms: Date.now() - started,
        ...result,
      });
      return result;
    }

    const scenes = this.catalogs.get().scenes;
    const scenesText = scenes
      .map((s) => {
        const prompts = (s.typical_prompts ?? [])
          .map((p) => `  - ${p}`)
          .join('\n');
        return `- scene_id: ${s.id}
  name: ${s.name}
  summary: ${s.summary}
  agent_family: ${s.agent_family ?? ''}
  industries: ${(s.industries ?? []).join(', ')}
  typical_prompts:
${prompts || '  (none)'}`;
      })
      .join('\n\n');

    const tenantText = JSON.stringify(
      {
        channels: ctx.tenant.channels,
        connectors: ctx.tenant.connectors,
      },
      null,
      2,
    );

    const { system, user } = this.prompts.routerMessages({
      utterance: text,
      scenesText,
      tenantText,
    });

    this.trace.step('P1.Intent', 'qwen.request', {
      purpose: 'scene_router',
      scene_ids: scenes.map((s) => s.id),
      system_chars: system.length,
      user_chars: user.length,
      // 正文照传，落多少由各 sink 的档位决定（terse 只留长度）
      user,
    });

    const llmStarted = Date.now();
    let raw: Partial<Triage>;
    try {
      raw = (await this.qwen.chatJson(system, user, {
        scope: 'P1.Intent',
        purpose: 'scene_router',
      })) as Partial<Triage>;
      this.trace.step('P1.Intent', 'qwen.response', {
        ms: Date.now() - llmStarted,
        raw,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      const result = {
        gate: 'ERROR' as const,
        error: `intent qwen failed: ${msg}`,
      };
      this.trace.step('P1.Intent', 'done', {
        ms: Date.now() - llmStarted,
        ...result,
      });
      return result;
    }

    const triage = this.normalizeTriage(raw);

    // 过空框架：直接 ASK，仍附上规则 next_ask（若有 scene）
    if (this.isVagueUtterance(text)) {
      this.wizard.evaluateGate(triage);
      const result: IntentResult = {
        gate: 'ASK',
        triage,
        ask_user:
          this.wizard.renderAsk(triage) ||
          '请补充行业（如美妆/教育）以及主要职责（如招聘接待、销售答疑推品）',
      };
      this.trace.step('P1.Intent', 'gate', {
        ms: Date.now() - started,
        ...result,
        reason: 'vague_utterance',
      });
      return result;
    }

    const gated = this.wizard.evaluateGate(triage);
    this.trace.step('P1.Intent', 'gate', {
      ms: Date.now() - started,
      gate: gated.gate,
      triage: gated.triage,
      ask_user: gated.ask_user,
    });
    return gated;
  }

  private normalizeTriage(raw: Partial<Triage>): Triage {
    return {
      scene_id: String(raw.scene_id ?? '').trim(),
      agent_family: raw.agent_family
        ? String(raw.agent_family)
        : undefined,
      channel: raw.channel ? String(raw.channel) : 'wecom',
      industry: raw.industry ? String(raw.industry) : undefined,
      confidence: Number(raw.confidence ?? 0),
      reason: String(raw.reason ?? ''),
      known_slots: (raw.known_slots as Record<string, unknown>) ?? {},
      missing_slots: Array.isArray(raw.missing_slots)
        ? raw.missing_slots.map(String)
        : [],
      ask_user: raw.ask_user ? String(raw.ask_user) : '',
      risk_flags: Array.isArray(raw.risk_flags)
        ? raw.risk_flags.map(String)
        : [],
      needs_long_term_memory: raw.needs_long_term_memory === true,
      needs_skill_evolution: raw.needs_skill_evolution === true,
    };
  }

  /** 装机描述过糊 → 强制 ASK（不进 P2）。 */
  private isVagueUtterance(utterance: string): boolean {
    const compact = utterance.replace(/\s+/g, '');
    return (
      /^(做个机器人|做一个机器人|帮我做个机器人|搭个机器人|随便做个)$/i.test(
        compact,
      ) ||
      (utterance.length < 8 &&
        /机器人|助手|智能体/.test(utterance) &&
        !/招聘|美妆|客服|销售|工作流/.test(utterance))
    );
  }
}
