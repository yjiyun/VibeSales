/**
 * [P1] WizardLlmReceptionist — 向导接待员（大模型增强）
 *
 * 文档：docs/工程架构.md §8 LLM 边界 · docs/产品设计.md §3.3
 *
 * ## 职责
 * - 回声话术、业务简述结构化、总结润色、细补出题、自由描述→行业归一
 * - **不**决定下一问槽位、**不**改闸门、**不**选模板
 *
 * ## 降级
 * - `--no-llm`（CLI）/ 会话 `llm=false`（Web）/ 无 DASHSCOPE_API_KEY / 调用失败
 *   → 全部回退 WizardSpeech / 规则底稿
 *
 * ## 开关作用域
 * CLI 用进程级 `setPreferLlm`；Web 用流程级 `FlowState.preferLlm`（每请求一份），
 * 因此两个会话一个开一个关也不会互相影响。
 *
 * 文案源：`prompts/receptionist.yaml`（经 PromptsService）。
 */

import { Injectable } from '@nestjs/common';
import { FlowContextService } from '../common/flow-context';
import { TraceService } from '../common/trace.service';
import {
  CatalogOption,
  WizardDetailSupplement,
  WizardSummary,
} from '../common/types';
import { PromptsService } from '../prompts/prompts.service';
import { QwenService, ChatJsonMeta } from '../qwen/qwen.service';
import { WizardSpeech } from './wizard-speech';

/** 日志 / token 记账里的模块名（trace scope 与 token.log 分组一致）。 */
const SCOPE = 'P1.WizardLlm';

export interface DetailPromptItem {
  key: keyof WizardDetailSupplement;
  title: string;
  hint: string;
  example_reply?: string;
}

@Injectable()
export class WizardLlmReceptionist {
  /**
   * 进程级默认开关（CLI `--no-llm` 关掉）。
   * Web 侧不改这个字段，而是把偏好写进本次流程状态（`FlowState.preferLlm`），
   * 否则并发请求会互相翻开关。
   */
  private preferLlm = true;

  constructor(
    private readonly qwen: QwenService,
    private readonly prompts: PromptsService,
    private readonly trace: TraceService,
    private readonly flows: FlowContextService,
  ) {}

  setPreferLlm(on: boolean): void {
    this.preferLlm = on;
  }

  private llmMeta(purpose: string): ChatJsonMeta {
    return { scope: SCOPE, purpose, model: this.flows.current().preferModel };
  }

  /** 当前是否会尝试调模型：流程级偏好优先，其次进程级默认，最后要求有 Key。 */
  isActive(): boolean {
    const scoped = this.flows.current().preferLlm;
    const prefer = scoped === undefined ? this.preferLlm : scoped;
    return prefer && this.qwen.hasApiKey();
  }

  async echoIndustry(industryName: string): Promise<string> {
    const fallback = WizardSpeech.echoIndustry(industryName);
    if (!this.isActive()) return fallback;
    try {
      const { system, user } = this.prompts.echoIndustry(industryName);
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('echoIndustry'))) as { echo?: string };
      const echo = String(raw.echo ?? '').trim();
      return echo || fallback;
    } catch (err) {
      this.trace.step(SCOPE, 'echoIndustry.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return fallback;
    }
  }

  async echoGoals(goalNames: string[]): Promise<string> {
    const fallback = WizardSpeech.echoGoals(goalNames);
    if (!this.isActive() || goalNames.length === 0) return fallback;
    try {
      const goalLines = goalNames.map((n, i) => `${i + 1}. ${n}`).join('\n');
      const { system, user } = this.prompts.echoGoals(goalLines);
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('echoGoals'))) as { echo?: string };
      const echo = String(raw.echo ?? '').trim();
      return echo || fallback;
    } catch (err) {
      this.trace.step(SCOPE, 'echoGoals.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return fallback;
    }
  }

  /**
   * 将自由业务描述整理成一句 business_brief（可含产品/客群）。
   * 失败则原样返回。
   */
  async structureBrief(
    rawBrief: string,
    industryName: string,
  ): Promise<string> {
    const trimmed = rawBrief.trim();
    if (!trimmed || !this.isActive()) return trimmed;
    try {
      const { system, user } = this.prompts.structureBrief({
        industryName,
        rawBrief: trimmed,
      });
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('structureBrief'))) as {
        business_brief?: string;
        products?: string;
        customers?: string;
      };
      const brief = String(raw.business_brief ?? '').trim();
      if (!brief) return trimmed;
      const parts = [brief];
      if (raw.products?.trim()) parts.push(`主打：${raw.products.trim()}`);
      if (raw.customers?.trim()) parts.push(`客群：${raw.customers.trim()}`);
      return parts.join('；');
    } catch (err) {
      this.trace.step(SCOPE, 'structureBrief.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return trimmed;
    }
  }

  /** 润色总结中的角色定位与当前重点；其它字段保持不变。 */
  async polishSummary(summary: WizardSummary): Promise<WizardSummary> {
    if (!this.isActive()) return summary;
    try {
      const { system, user } = this.prompts.polishSummary(
        JSON.stringify(summary, null, 2),
      );
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('polishSummary'))) as { role_positioning?: string; current_focus?: string };
      return {
        ...summary,
        role_positioning:
          String(raw.role_positioning ?? '').trim() ||
          summary.role_positioning,
        current_focus:
          String(raw.current_focus ?? '').trim() || summary.current_focus,
      };
    } catch (err) {
      this.trace.step(SCOPE, 'polishSummary.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return summary;
    }
  }

  /**
   * 细补五字段出题；失败则回退 WizardSpeech.detailFieldPrompt。
   */
  async detailPrompts(
    summary: WizardSummary,
  ): Promise<DetailPromptItem[]> {
    const fallback = DETAIL_KEYS.map((key) => {
      const base = WizardSpeech.detailFieldPrompt(key, summary.industry.name);
      return { key, ...base } as DetailPromptItem;
    });
    if (!this.isActive()) return fallback;

    try {
      const { system, user } = this.prompts.detailPrompts(
        JSON.stringify(summary, null, 2),
      );
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('detailPrompts'))) as { prompts?: DetailPromptItem[] };

      const list = Array.isArray(raw.prompts) ? raw.prompts : [];
      const byKey = new Map(
        list
          .filter((p) => p && DETAIL_KEYS.includes(p.key))
          .map((p) => [p.key, p] as const),
      );
      return DETAIL_KEYS.map((key) => {
        const hit = byKey.get(key);
        const fb = fallback.find((f) => f.key === key)!;
        if (!hit) return fb;
        return {
          key,
          title: String(hit.title ?? fb.title).trim() || fb.title,
          hint: String(hit.hint ?? fb.hint).trim() || fb.hint,
          example_reply: hit.example_reply
            ? String(hit.example_reply).trim()
            : undefined,
        };
      });
    } catch (err) {
      this.trace.step(SCOPE, 'detailPrompts.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return fallback;
    }
  }

  /**
   * 自由描述 → 词表内 industry id；非法则 null（调用方再兜底 general）。
   */
  async normalizeIndustry(
    freeText: string,
    options: CatalogOption[],
  ): Promise<string | null> {
    if (!freeText.trim() || !this.isActive()) return null;
    try {
      const optionsLines = options
        .map(
          (o) =>
            `- ${o.id}: ${o.name}${o.group ? `（${o.group}）` : ''}`,
        )
        .join('\n');
      const { system, user } = this.prompts.normalizeIndustry({
        freeText,
        optionsLines,
      });
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('normalizeIndustry'))) as { industry_id?: string };
      const id = String(raw.industry_id ?? '').trim();
      if (options.some((o) => o.id === id)) return id;
      return null;
    } catch (err) {
      this.trace.step(SCOPE, 'normalizeIndustry.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return null;
    }
  }

  /**
   * 「使用模板」按钮：结合行业与已选目标生成一段可直接改的业务简述文本。
   *
   * 只在用户点击时按需调用；模型不可用或产出不合结构时返回 null，
   * 调用方退回 `WizardSpeech.briefTemplate()` 的规则底稿。
   */
  async briefTemplate(ctx: {
    industryName?: string;
    goalNames?: string[];
  }): Promise<string | null> {
    if (!this.isActive()) return null;
    try {
      const goals = (ctx.goalNames ?? []).filter((g) => g?.trim());
      const { system, user } = this.prompts.briefTemplate({
        industryName: ctx.industryName ?? '（未指定）',
        goalLines:
          goals.length > 0
            ? goals.map((g) => `- ${g}`).join('\n')
            : '- （未选择）',
      });
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('briefTemplate'))) as { main_business?: string; target_customers?: string };
      const business = oneLine(raw.main_business);
      const customers = oneLine(raw.target_customers);
      if (!business || !customers) {
        this.trace.step(SCOPE, 'briefTemplate.fallback', {
          reason: 'incomplete',
        });
        return null;
      }
      const text = `主要业务：${business}\n服务对象：${customers}`;
      this.trace.step(SCOPE, 'briefTemplate.ok', { chars: text.length });
      return text;
    } catch (err) {
      this.trace.step(SCOPE, 'briefTemplate.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return null;
    }
  }

  /**
   * 补充信息环节：用户一次性用自然语言给了若干信息，从中抽取细补五字段。
   *
   * 与 `revisePatch` 的区别：这里只认 detail 五键（不动行业/目标/简述），
   * 供 Web 与 CLI 的细补环节共用；未命中或模型不可用时返回 null，
   * 调用方退回「原样写入当前追问字段」的既有行为。
   */
  async extractDetail(
    text: string,
    ctx: { industryName?: string; detail?: WizardDetailSupplement },
  ): Promise<Partial<WizardDetailSupplement> | null> {
    if (!text.trim() || !this.isActive()) return null;
    try {
      const { system, user } = this.prompts.extractDetail({
        industryName: ctx.industryName ?? '',
        detailJson: JSON.stringify(ctx.detail ?? {}),
        text,
      });
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('extractDetail'))) as { detail?: Partial<WizardDetailSupplement> };

      const src = raw.detail ?? (raw as Partial<WizardDetailSupplement>);
      if (!src || typeof src !== 'object') return null;

      const detail: Partial<WizardDetailSupplement> = {};
      for (const key of DETAIL_KEYS) {
        const v = src[key];
        if (typeof v !== 'string') continue;
        const value = v.trim();
        // 过滤模型偶发的占位式回答
        if (!value || EMPTY_ANSWERS.has(value)) continue;
        detail[key] = value;
      }
      const hit = Object.keys(detail).length;
      this.trace.step(SCOPE, 'extractDetail.ok', {
        keys: Object.keys(detail),
        chars: text.length,
      });
      return hit > 0 ? detail : null;
    } catch (err) {
      this.trace.step(SCOPE, 'extractDetail.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return null;
    }
  }

  /**
   * DONE 后自由改写：从用户原话抽取要对 session 打的补丁。
   * industry_id / goal_ids 必须命中词表，否则丢弃；全部失败返回 null（调用方退回单选兜底）。
   */
  async revisePatch(
    text: string,
    ctx: {
      industryId?: string;
      industryName?: string;
      goalIds: string[];
      brief?: string;
      detail?: WizardDetailSupplement;
      industries: CatalogOption[];
      goals: CatalogOption[];
    },
  ): Promise<{
    industry_id?: string;
    goal_ids?: string[];
    business_brief?: string;
    detail?: Partial<WizardDetailSupplement>;
  } | null> {
    if (!text.trim() || !this.isActive()) return null;
    try {
      const { system, user } = this.prompts.revisePatch({
        industryId: ctx.industryId ?? '',
        industryName: ctx.industryName ?? '',
        goalIdsJson: JSON.stringify(ctx.goalIds),
        brief: ctx.brief ?? '',
        detailJson: JSON.stringify(ctx.detail ?? {}),
        industriesLines: ctx.industries
          .map((o) => `- ${o.id}: ${o.name}`)
          .join('\n'),
        goalsLines: ctx.goals.map((o) => `- ${o.id}: ${o.name}`).join('\n'),
        text,
      });
      const raw = (await this.qwen.chatJson(system, user, this.llmMeta('revisePatch'))) as {
        industry_id?: string;
        goal_ids?: string[];
        business_brief?: string;
        detail?: Partial<WizardDetailSupplement>;
      };

      const patch: {
        industry_id?: string;
        goal_ids?: string[];
        business_brief?: string;
        detail?: Partial<WizardDetailSupplement>;
      } = {};

      const industryId = String(raw.industry_id ?? '').trim();
      if (industryId && ctx.industries.some((o) => o.id === industryId)) {
        patch.industry_id = industryId;
      }

      if (Array.isArray(raw.goal_ids)) {
        const valid = raw.goal_ids
          .map((id) => String(id).trim())
          .filter((id) => ctx.goals.some((o) => o.id === id));
        if (valid.length > 0) patch.goal_ids = valid;
      }

      const brief = String(raw.business_brief ?? '').trim();
      if (brief) patch.business_brief = brief;

      if (raw.detail && typeof raw.detail === 'object') {
        const detail: Partial<WizardDetailSupplement> = {};
        for (const key of DETAIL_KEYS) {
          const v = raw.detail[key];
          if (typeof v === 'string' && v.trim()) detail[key] = v.trim();
        }
        if (Object.keys(detail).length > 0) patch.detail = detail;
      }

      return Object.keys(patch).length > 0 ? patch : null;
    } catch (err) {
      this.trace.step(SCOPE, 'revisePatch.fallback', {
        error: err instanceof Error ? err.message : String(err),
      });
      return null;
    }
  }
}

/** 模板文本要能直接落进单行输入：去掉换行、字段前缀与包裹引号。 */
function oneLine(value: unknown): string {
  if (typeof value !== 'string') return '';
  const text = value
    .replace(/\s+/g, ' ')
    .replace(/^(主要业务|服务对象)\s*[:：]\s*/, '')
    .replace(/^["“'']|["”'']$/g, '')
    .trim();
  return EMPTY_ANSWERS.has(text) ? '' : text;
}

/** 模型偶发的占位式回答：当成「没提到」丢弃，避免污染总结。 */
const EMPTY_ANSWERS = new Set([
  '无',
  '未提及',
  '未提供',
  '没有',
  '暂无',
  '不详',
  'N/A',
  'n/a',
  'null',
  '-',
]);

const DETAIL_KEYS: Array<keyof WizardDetailSupplement> = [
  'flagship_products',
  'primary_customers',
  'prohibitions',
  'goals',
  'escalate_scenes',
];
