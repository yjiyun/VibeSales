/**
 * [P2] MatchService — 模板匹配流水线编排
 *
 * 文档：docs/产品设计.md §2（阶段划分）· docs/工程架构.md §6.4
 *
 * ## 阶段边界
 * - **本服务属 P2**：只消费 Triage，不回头改 scene / 不问粗补槽
 * - 与 P1 解耦：主路径与 `--triage` 旁路共用本服务（C3）
 *
 * ```text
 * P1 Phase1Result.triage（或 --triage）
 *         │
 *         ▼
 * MatchService.run
 *         ├─ TemplateLoader.list()
 *         ├─ TemplateFilter.filter()
 *         ├─ TemplateRank.rank(Top-K)
 *         └─ DecideService.decide() → MatchResult
 * ```
 */

import { Injectable } from '@nestjs/common';
import { TraceService } from '../common/trace.service';
import {
  MatchResult,
  RequestContext,
  Triage,
} from '../common/types';
import { TemplateFilterService } from '../templates/template-filter.service';
import { TemplateLoaderService } from '../templates/template-loader.service';
import { TemplateRankService } from '../templates/template-rank.service';
import { DecideService } from './decide.service';

@Injectable()
export class MatchService {
  constructor(
    private readonly loader: TemplateLoaderService,
    private readonly filter: TemplateFilterService,
    private readonly rank: TemplateRankService,
    private readonly decide: DecideService,
    private readonly trace: TraceService,
  ) {}

  /**
   * 执行「过滤 → 排序 → 裁决」。
   * 输入 triage必须已含合法/旁路 triage；本方法不再做意图分诊。
   */
  async run(ctx: RequestContext, triage: Triage): Promise<MatchResult> {
    this.trace.banner('Match pipeline');
    const started = Date.now();
    this.trace.step('Match', 'start', {
      client_code: ctx.client_code,
      request_id: ctx.request_id,
      triage,
    });

    const all = this.loader.list();
    this.trace.step('Match', 'templates.loaded', {
      count: all.length,
      ids: all.map((t) => t.template_id),
    });

    const { passed, reject_reasons, reject_summary } = this.filter.filter(
      all,
      ctx,
      triage,
    );
    const topk = this.rank.rank(passed, triage, 5);
    const result = await this.decide.decide({
      ctx,
      triage,
      topk,
      reject_reasons,
      reject_summary,
    });

    this.trace.step('Match', 'done', {
      ms: Date.now() - started,
      action: result.action,
      via: result.via,
      template_id: result.template_id,
      why: result.why,
    });
    return result;
  }
}
