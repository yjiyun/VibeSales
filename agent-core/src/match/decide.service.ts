/**
 * DecideService — Top-K 候选裁决（规则优先，多候选时可选千问）
 *
 * ## 文档
 * - `docs/工程架构.md` §4 硬约束 C6/C7/C8、§6.4 匹配数据流
 *
 * ## 在整条链路中的位置
 * ```text
 * Rank Top-K
 *     │
 *     ▼
 * DecideService.decide  ◄── 本文件
 *     ├─ 0 候选 → action=custom
 *     ├─ 1 候选 → via=rule 直接 hit
 *     └─ 2+    → 有 Key 调千问（template_id 必须 ∈ topk，否则 rule_fallback）
 *                无 Key → via=rule_fallback 取第一名
 * ```
 *
 * 送给模型的是 meta_summary + 截断 BRIEF，**不读 workflow YAML**（C6）。
 */

import { Injectable } from '@nestjs/common';
import * as path from 'path';
import { CatalogsService } from '../catalogs/catalogs.service';
import { TraceService } from '../common/trace.service';
import {
  MatchAlternative,
  MatchResult,
  RequestContext,
  TemplateRecord,
  TemplateRejectReason,
  Triage,
} from '../common/types';
import { PromptsService } from '../prompts/prompts.service';
import { truncateBrief } from '../prompts/truncate-brief';
import { QwenService } from '../qwen/qwen.service';

/** 千问选型 JSON 原始形状（解析后需再校验 template_id ∈ topk）。 */
interface DecideRaw {
  action?: string;
  template_id?: string;
  confidence?: number;
  why?: string;
  diffs?: string[];
  required_params_missing?: string[];
}

@Injectable()
export class DecideService {
  constructor(
    private readonly qwen: QwenService,
    private readonly prompts: PromptsService,
    private readonly catalogs: CatalogsService,
    private readonly trace: TraceService,
  ) {}

  /**
   * 按候选数量分支裁决，始终在结果中带回 client_code 与 reject_reasons。
   *
   * 工程字段（`why` / `reject_reasons` / `candidates_considered`，英文）与
   * 用户字段（`why_user` / `reject_summary` / `alternatives`，中文）并列输出，
   * 后者纯规则拼装，不额外调模型。
   */
  async decide(args: {
    ctx: RequestContext;
    triage: Triage;
    topk: TemplateRecord[];
    reject_reasons: string[];
    reject_summary?: TemplateRejectReason[];
  }): Promise<MatchResult> {
    const { ctx, triage, topk, reject_reasons } = args;
    const reject_summary = args.reject_summary ?? [];
    const client_code = ctx.client_code;
    const considered = topk.map((t) => t.template_id);

    this.trace.step('Decide', 'branch', {
      topk_count: topk.length,
      topk: considered,
      has_api_key: this.qwen.hasApiKey(),
    });

    /** 收口 hit：补齐用户层三字段，避免每条分支各写一遍。 */
    const hit = (
      t: TemplateRecord,
      via: 'rule' | 'qwen' | 'rule_fallback',
      extra: Partial<MatchResult>,
    ): MatchResult =>
      this.hitFromTemplate(client_code, t, via, {
        ...extra,
        why_user:
          extra.why_user ?? this.whyUserForHit(t, via, triage, topk.length),
        reject_summary,
        alternatives: this.alternativesOf(topk, t.template_id),
      });

    if (topk.length === 0) {
      const result: MatchResult = {
        client_code,
        action: 'custom',
        why: 'no candidates after hard filter',
        candidates_considered: [],
        reject_reasons,
        why_user: this.whyUserForCustom(reject_summary),
        reject_summary,
      };
      this.trace.step('Decide', 'result', result);
      return result;
    }

    if (topk.length === 1) {
      const result = hit(topk[0], 'rule', {
        why: 'single candidate after filter',
        candidates_considered: considered,
        reject_reasons,
      });
      this.trace.step('Decide', 'result', result);
      return result;
    }

    if (!this.qwen.hasApiKey()) {
      const result = hit(topk[0], 'rule_fallback', {
        why: 'multi-candidate; no DASHSCOPE_API_KEY, take rank#1',
        candidates_considered: considered,
        reject_reasons,
      });
      this.trace.step('Decide', 'result', result);
      return result;
    }

    try {
      const candidatesText = topk
        .map((t, i) => {
          return `### 候选 ${i + 1}
template_id: ${t.template_id}
display_name: ${t.display_name}
meta 摘要: ${JSON.stringify(t.meta_summary)}
BRIEF:
${truncateBrief(t.brief)}`;
        })
        .join('\n\n');

      const { system, user } = this.prompts.matcherMessages({
        triageJson: JSON.stringify(triage, null, 2),
        tenantJson: JSON.stringify(ctx.tenant, null, 2),
        candidatesText,
      });

      this.trace.step('Decide', 'qwen.request', {
        purpose: 'template_decide',
        system_chars: system.length,
        user_chars: user.length,
        candidates: considered,
        // 正文照传，落多少由各 sink 的档位决定（terse 只留长度）
        user,
      });

      const llmStarted = Date.now();
      let raw: DecideRaw;
      try {
        raw = (await this.qwen.chatJson(system, user, {
          scope: 'P2.Decide',
          purpose: 'template_decide',
        })) as DecideRaw;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        const result = hit(topk[0], 'rule_fallback', {
          why: `qwen decide failed (${msg}); fallback rank#1`,
          candidates_considered: considered,
          reject_reasons,
        });
        this.trace.step('Decide', 'error_fallback', {
          ms: Date.now() - llmStarted,
          error: msg,
          result,
        });
        return result;
      }

      this.trace.step('Decide', 'qwen.response', {
        ms: Date.now() - llmStarted,
        raw,
      });

      if (raw.action === 'custom') {
        const result: MatchResult = {
          client_code,
          action: 'custom',
          via: 'qwen',
          why: raw.why ?? 'model chose custom',
          diffs: raw.diffs,
          required_params_missing: raw.required_params_missing,
          candidates_considered: considered,
          reject_reasons,
          // 有候选但模型判定都不合适：归因来自「候选被判不适用」，不是硬过滤
          why_user:
            '找到了相近的方案，但和你的要求还有明显差距，建议走定制。',
          reject_summary,
          alternatives: this.alternativesOf(topk),
        };
        this.trace.step('Decide', 'result', result);
        return result;
      }

      const tid = String(raw.template_id ?? '');
      const picked = topk.find((t) => t.template_id === tid);
      if (!picked) {
        const result = hit(topk[0], 'rule_fallback', {
          why: `qwen template_id "${tid}" not in topk; fallback rank#1`,
          candidates_considered: considered,
          reject_reasons,
          diffs: raw.diffs,
        });
        this.trace.step('Decide', 'result', result);
        return result;
      }

      const result = hit(picked, 'qwen', {
        why: raw.why ?? 'qwen decide',
        diffs: raw.diffs,
        required_params_missing: raw.required_params_missing,
        candidates_considered: considered,
        reject_reasons,
      });
      this.trace.step('Decide', 'result', result);
      return result;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      const result = hit(topk[0], 'rule_fallback', {
        why: `qwen decide failed (${msg}); fallback rank#1`,
        candidates_considered: considered,
        reject_reasons,
      });
      this.trace.step('Decide', 'error_fallback', { error: msg, result });
      return result;
    }
  }

  /**
   * hit 的中文一句话理由。规则路径也必须有值——用户看到的不能是空白，
   * 也不能是英文调试串（那是 `why` 的职责）。
   */
  private whyUserForHit(
    t: TemplateRecord,
    via: 'rule' | 'qwen' | 'rule_fallback',
    triage: Triage,
    topkCount: number,
  ): string {
    // 用户层文案统一走 sceneNameForUser：hit 时 scene_id 已与模板对齐，
    // 但模板声明的场景也可能没进 scenes.yaml，不能把英文 id 露给用户
    const knownScene = Boolean(this.catalogs.sceneById(triage.scene_id));
    const scene = this.catalogs.sceneNameForUser(triage.scene_id);
    const channel = this.catalogs.channelName(triage.channel || 'wecom');
    // 词表外场景时 scene 已是「你描述的场景」，不能再缀「场景」二字
    const base = knownScene
      ? `「${t.display_name}」覆盖你要的${scene}场景，且已支持${channel}`
      : `「${t.display_name}」覆盖${scene}，且已支持${channel}`;
    if (via === 'qwen') {
      return `${base}；在 ${topkCount} 套候选里它与你的描述最贴近。`;
    }
    if (topkCount === 1) {
      return `${base}；按场景、渠道、连接器逐项筛下来，只有它全部满足。`;
    }
    return `${base}；${topkCount} 套候选中它综合排名第一。`;
  }

  /** 0 候选时的中文归因：优先讲被卡在哪一道闸。 */
  private whyUserForCustom(reject_summary: TemplateRejectReason[]): string {
    if (reject_summary.length === 0) {
      return '现有标品库里还没有可直接复用的方案，建议走定制。';
    }
    // 连接器/渠道是客户侧可补的，优先提示；场景不符说明库里确实没这类方案
    const byKind = (k: TemplateRejectReason['kind']) =>
      reject_summary.filter((r) => r.kind === k);
    if (byKind('connector').length > 0) {
      return '有场景相符的方案，但你的账号还缺它依赖的连接器，暂时不能直接套用。';
    }
    if (byKind('channel').length > 0) {
      return '有场景相符的方案，但它还没覆盖你要用的渠道。';
    }
    return `现有 ${reject_summary.length} 套标品都不是这个场景的方案，建议走定制。`;
  }

  /** Top-K 里未被选中的候选（单候选时为空数组）。 */
  private alternativesOf(
    topk: TemplateRecord[],
    pickedId?: string,
  ): MatchAlternative[] {
    return topk
      .filter((t) => t.template_id !== pickedId)
      .map((t) => ({
        template_id: t.template_id,
        display_name: t.display_name,
        why_not: pickedId ? '同样满足硬性条件，但综合匹配度更低' : undefined,
      }));
  }

  /**
   * 组装 hit 结果：解析 workflow 绝对路径，默认列出 meta 必填 params 缺口。
   */
  private hitFromTemplate(
    client_code: string,
    t: TemplateRecord,
    via: 'rule' | 'qwen' | 'rule_fallback',
    extra: Partial<MatchResult>,
  ): MatchResult {
    const workflow_path = t.coze_export
      ? path.resolve(t.root_path, t.coze_export)
      : undefined;
    const required_params_missing =
      extra.required_params_missing ??
      t.params.filter((p) => p.required).map((p) => p.key);

    return {
      client_code,
      action: 'hit',
      via,
      template_id: t.template_id,
      display_name: t.display_name,
      workflow_path,
      required_params_missing,
      why: extra.why,
      diffs: extra.diffs,
      candidates_considered: extra.candidates_considered,
      reject_reasons: extra.reject_reasons,
      why_user: extra.why_user,
      reject_summary: extra.reject_summary,
      alternatives: extra.alternatives,
    };
  }
}
