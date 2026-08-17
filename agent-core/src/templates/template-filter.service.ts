/**
 * TemplateFilterService — 模板硬过滤（规则可控，不调模型）
 *
 * ## 文档
 * - `docs/工程架构.md` §6.4 匹配数据流（硬过滤顺序）、§4 硬约束
 *
 * ## 在整条链路中的位置
 * ```text
 * Intent / --triage  →  triage
 * Tenant             →  ctx.tenant
 * TemplateLoader     →  all templates
 *         │
 *         ▼
 * TemplateFilterService  ◄── 本文件
 *         │  passed + reject_reasons
 *         ▼
 * TemplateRankService → DecideService
 * ```
 *
 * ## 过滤顺序（必须按序，命中即剔除并记原因）
 * 1. triage.scene_id ∈ template.scene_ids
 * 2. channels：模板渠道含 triage.channel，且与 tenant.channels 有交集
 *    （缺省按 wecom 对齐）
 * 3. connectors_required ⊆ tenant.connectors（required 为空则通过）
 * 4. stability ∈ {draft,beta,ga}（DEMO 全开；非法稳定性剔除）
 *
 * 不读 workflow YAML；不在此步做关键词打分（交给 Rank）。
 */

import { Injectable } from '@nestjs/common';
import { CatalogsService } from '../catalogs/catalogs.service';
import { TraceService } from '../common/trace.service';
import {
  RequestContext,
  TemplateRecord,
  TemplateRejectReason,
  Triage,
} from '../common/types';

/**
 * 硬过滤结果：通过的模板 + 每条剔除原因。
 *
 * 两份剔除记录一条对一条、顺序一致，只是受众不同：
 * - `reject_reasons` 英文串，给 trace / CLI / 验收 T3
 * - `reject_summary` 结构化中文，给界面讲「为什么没有现成模板」
 *
 * 归因在这里产生而不是事后从字符串反解——哪一道闸剔的，只有本服务知道。
 */
export interface FilterOutcome {
  passed: TemplateRecord[];
  reject_reasons: string[];
  reject_summary: TemplateRejectReason[];
}

@Injectable()
export class TemplateFilterService {
  constructor(
    private readonly catalogs: CatalogsService,
    private readonly trace: TraceService,
  ) {}

  /**
   * 对全库模板做硬过滤。
   * @param all 来自 TemplateLoader.list()
   * @param ctx 含 client_code 与租户 channels/connectors
   * @param triage 分诊结果（旁路或 Intent 产出）
   */
  filter(
    all: TemplateRecord[],
    ctx: RequestContext,
    triage: Triage,
  ): FilterOutcome {
    const reject_reasons: string[] = [];
    const reject_summary: TemplateRejectReason[] = [];
    const passed: TemplateRecord[] = [];

    /** 一次剔除同时记两份：英文给工程，中文给界面。 */
    const reject = (
      t: TemplateRecord,
      kind: TemplateRejectReason['kind'],
      reason: string,
      detail: string,
    ): void => {
      reject_reasons.push(`${t.template_id}: ${reason}`);
      reject_summary.push({
        kind,
        template_id: t.template_id,
        display_name: t.display_name,
        detail,
      });
    };

    /**
     * 用户层要说的「你的需求场景」。
     * 词表内 → 「美妆企微销售客服场景」；词表外（P1 判出 out_of_catalog）→
     * 「你描述的场景」，不能把英文 scene_id 摆到界面上。
     */
    const wantScene = this.catalogs.sceneById(triage.scene_id)
      ? `${this.catalogs.sceneName(triage.scene_id)}场景`
      : '你描述的场景';

    // DEMO：无 channel 信息时按 wecom 对齐（与 C5 / channels.yaml default 一致）
    const triageChannel = (triage.channel || 'wecom').trim() || 'wecom';
    const tenantChannels =
      ctx.tenant.channels.length > 0 ? ctx.tenant.channels : ['wecom'];

    this.trace.step('Filter', 'start', {
      client_code: ctx.client_code,
      input_count: all.length,
      triage_scene: triage.scene_id,
      triage_channel: triageChannel,
      tenant_channels: tenantChannels,
      tenant_connectors: ctx.tenant.connectors,
    });

    for (const t of all) {
      // 1) 场景必须命中
      if (!t.scene_ids.includes(triage.scene_id)) {
        reject(
          t,
          'scene',
          `scene_id mismatch (need ${triage.scene_id})`,
          `场景不符：这套方案面向「${this.catalogs.sceneName(
            t.scene_ids[0],
          )}」，不覆盖${wantScene}`,
        );
        continue;
      }

      // 2) 渠道：模板既要覆盖 triage 渠道，又要与租户已开通渠道有交集
      const channelOk =
        t.channels.includes(triageChannel) &&
        t.channels.some((c) => tenantChannels.includes(c));
      if (!channelOk) {
        reject(
          t,
          'channel',
          `channel no overlap (template=${t.channels.join(',')}, triage=${triageChannel}, tenant=${tenantChannels.join(',')})`,
          `渠道不符：这套方案支持「${this.catalogs
            .channelNames(t.channels)
            .join('、')}」，你需要的是「${this.catalogs.channelName(
            triageChannel,
          )}」`,
        );
        continue;
      }

      // 3) 租户必须具备模板声明的必选连接器（如谷雨缺 knowledge_base → T3 custom）
      const missingConnectors = t.connectors_required.filter(
        (c) => !ctx.tenant.connectors.includes(c),
      );
      if (missingConnectors.length > 0) {
        reject(
          t,
          'connector',
          `missing connectors [${missingConnectors.join(', ')}]`,
          `缺连接器：${this.catalogs.connectorNames(missingConnectors).join('、')}（需先在你的账号下开通）`,
        );
        continue;
      }

      // 4) 稳定性白名单（DEMO 三档全开）
      if (!['draft', 'beta', 'ga'].includes(t.stability)) {
        reject(
          t,
          'stability',
          'stability blocked',
          `成熟度不满足上线要求（${t.stability}）`,
        );
        continue;
      }

      passed.push(t);
    }

    this.trace.step('Filter', 'done', {
      passed: passed.map((t) => t.template_id),
      passed_count: passed.length,
      reject_reasons,
    });

    return { passed, reject_reasons, reject_summary };
  }
}
