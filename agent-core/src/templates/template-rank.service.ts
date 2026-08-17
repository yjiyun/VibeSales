/**
 * TemplateRankService — 过滤后候选的规则排序，截断 Top-K
 *
 * ## 文档
 * - `docs/工程架构.md` §6.4：stability（ga>beta>draft）→ 可选关键词分 → Top 5
 *
 * ## 在整条链路中的位置
 * ```text
 * TemplateFilterService.passed
 *         │
 *         ▼
 * TemplateRankService  ◄── 本文件：打分排序，默认 Top 5
 *         │
 *         ▼
 * DecideService
 *   - 0 候选 → custom
 *   - 1 候选 → via=rule 直接命中（本服务仍会返回 1 条）
 *   - 2+    → 有 Key 则千问在 Top-K 内裁决；无 Key 则 rule_fallback 取第一名
 * ```
 *
 * 只做轻量规则分，不调模型；关键词命中来自 triage 的 industry / agent_family /
 * reason 分词 / desired_capabilities，在 search_text、display_name、brief 中查找。
 */

import { Injectable } from '@nestjs/common';
import { TraceService } from '../common/trace.service';
import { Stability, TemplateRecord, Triage } from '../common/types';

/** 稳定性基础分：ga > beta > draft（执行计划 T4）。 */
const STABILITY_SCORE: Record<Stability, number> = {
  ga: 300,
  beta: 200,
  draft: 100,
};

@Injectable()
export class TemplateRankService {
  constructor(private readonly trace: TraceService) {}

  /**
   * 对硬过滤通过的候选打分，按分降序取前 topK（默认 5）。
   * @returns 已排序的模板列表，供 Decide 分支使用
   */
  rank(candidates: TemplateRecord[], triage: Triage, topK = 5): TemplateRecord[] {
    const scored = candidates.map((t) => ({
      template_id: t.template_id,
      stability: t.stability,
      score: this.score(t, triage),
      t,
    }));
    scored.sort((a, b) => b.score - a.score);
    const top = scored.slice(0, topK);

    this.trace.step('Rank', 'done', {
      input_count: candidates.length,
      topK,
      scored: scored.map(({ template_id, stability, score }) => ({
        template_id,
        stability,
        score,
      })),
      topk: top.map((x) => x.template_id),
    });

    return top.map((x) => x.t);
  }

  /**
   * 单模板得分 = 稳定性基础分 + 关键词命中加分（每命中一项 +10）。
   * brief 仅用于本地打分，不会整份送进千问（Decide 侧会再截断）。
   */
  private score(t: TemplateRecord, triage: Triage): number {
    let s = STABILITY_SCORE[t.stability] ?? 0;
    const hay = `${t.search_text}\n${t.display_name}\n${t.brief}`.toLowerCase();
    const needles: string[] = [];
    if (triage.industry) needles.push(triage.industry.toLowerCase());
    if (triage.agent_family) needles.push(triage.agent_family.toLowerCase());
    if (triage.reason) {
      for (const token of triage.reason.split(/[\s,，、]+/).filter(Boolean)) {
        if (token.length >= 2) needles.push(token.toLowerCase());
      }
    }
    const desired = triage.known_slots?.desired_capabilities;
    if (Array.isArray(desired)) {
      for (const d of desired) needles.push(String(d).toLowerCase());
    }
    for (const n of needles) {
      if (n && hay.includes(n)) s += 10;
    }
    return s;
  }
}
