/**
 * [P2] PreviewService — v0 预览渲染（命中模板之后；**不属于 P1**）
 *
 * 文档：docs/产品设计.md §2（P1 明确不做 v0）；历史稿 docs/archive/搭建助手v2-by-opus.md §3.3 / §7.1
 *
 * ## 阶段边界
 * - 仅在 P2 `action=hit` 后调用
 * - P1 向导 / Intent **不得**依赖本服务
 *
 * 用 BRIEF.md + meta 渲染业务语言「这版会这样工作」（不读 workflow、不开通 Coze）。
 */

import { Injectable } from '@nestjs/common';
import { CatalogsService } from '../catalogs/catalogs.service';
import { TraceService } from '../common/trace.service';
import {
  CustomOutline,
  TemplateRecord,
  TemplateRejectReason,
  Triage,
  V0Preview,
  WizardSummary,
} from '../common/types';
import { TemplateLoaderService } from '../templates/template-loader.service';

@Injectable()
export class PreviewService {
  constructor(
    private readonly loader: TemplateLoaderService,
    private readonly catalogs: CatalogsService,
    private readonly trace: TraceService,
  ) {}

  /** 按 template_id 构建 v0 预览；找不到模板返回 undefined。 */
  build(templateId?: string): V0Preview | undefined {
    if (!templateId) return undefined;
    const t = this.loader.list().find((x) => x.template_id === templateId);
    if (!t) return undefined;

    const preview: V0Preview = {
      template_id: t.template_id,
      role: this.role(t),
      capabilities: this.catalogs.capabilityNames(t.capabilities),
      main_flow: this.mainFlow(t.brief),
      success_criteria: this.successCriteria(t.brief),
      missing_required: t.params
        .filter((p) => p.required)
        .map((p) => p.label ?? p.key),
    };
    this.trace.step('Preview', 'built', {
      template_id: t.template_id,
      capabilities: preview.capabilities.length,
      main_flow: preview.main_flow.length,
      success_criteria: preview.success_criteria.length,
      missing_required: preview.missing_required.length,
    });
    return preview;
  }

  /**
   * `action=custom` 时的定制轮廓——命中不了标品也不能空手。
   *
   * 纯规则拼装：`summary`（有则用）+ `triage` + `reject_summary`，不调模型、
   * 不读 workflow、不承诺可交付。CLI 走 `--triage` 旁路时没有 summary，
   * 此时只用 triage 降级输出（role/capabilities 会更粗）。
   */
  buildCustomOutline(args: {
    triage: Triage;
    summary?: WizardSummary;
    reject_summary?: TemplateRejectReason[];
    why_user?: string;
  }): CustomOutline {
    const { triage, summary } = args;
    const rejects = args.reject_summary ?? [];
    const channel = this.catalogs.channelName(triage.channel || 'wecom');
    // custom 分支常见于词表外场景（P1 判出 out_of_catalog），那时 scene_id
    // 是英文串，不能直接摆到界面上——用 sceneNameForUser 兜住
    const knownScene = Boolean(this.catalogs.sceneById(triage.scene_id));
    const scene = this.catalogs.sceneNameForUser(triage.scene_id);

    // 归因：先给一句总述（decide 已算好），再逐条列被卡在哪一道闸
    const why_no_template: string[] = [];
    if (args.why_user) why_no_template.push(args.why_user);
    for (const r of rejects.slice(0, 6)) {
      why_no_template.push(`${r.display_name}：${r.detail}`);
    }
    if (why_no_template.length === 0) {
      why_no_template.push(
        knownScene
          ? `标品库里暂时没有覆盖「${scene}」且支持${channel}的现成方案。`
          : `标品库里暂时没有覆盖${scene}且支持${channel}的现成方案。`,
      );
    }

    const role =
      summary?.role_positioning?.trim() ||
      (knownScene
        ? `${scene}场景下的${channel}智能助手`
        : `${channel}智能助手（按你描述的场景定制）`);

    const capabilities =
      summary?.core_capabilities?.filter((c) => c.trim()) ?? [];
    if (capabilities.length === 0) {
      // 无 summary（CLI --triage 旁路）时退回 triage 的 desired_capabilities，
      // 并翻成 capabilities.yaml 的中文名——只取这一个槽位：其余槽位
      // （industry/role 等）是分诊记账字段，摆进「能力」里对用户没有意义。
      capabilities.push(...this.desiredCapabilities(triage));
    }

    const outline: CustomOutline = {
      why_no_template,
      role,
      capabilities,
      business_brief: summary?.business_brief?.trim() || undefined,
      suggestions: this.customSuggestions(rejects, triage),
    };

    this.trace.step('Preview', 'custom_outline.built', {
      scene_id: triage.scene_id,
      has_summary: Boolean(summary),
      why_no_template: outline.why_no_template.length,
      capabilities: outline.capabilities.length,
      suggestions: outline.suggestions.length,
    });
    return outline;
  }

  /**
   * triage.known_slots.desired_capabilities → 中文能力名。
   *
   * 槽位值可能是数组或单串（P1 规则粗补 / 模型抽取都可能给），统一成数组后
   * 走 capabilities.yaml 翻译；词表外的 id 由 `capabilityNames` 原样退回。
   */
  private desiredCapabilities(triage: Triage): string[] {
    const raw = triage.known_slots?.desired_capabilities;
    const ids = (Array.isArray(raw) ? raw : [raw])
      .filter((v) => v != null && String(v).trim())
      .map((v) => String(v).trim())
      .slice(0, 8);
    return ids.length > 0 ? this.catalogs.capabilityNames(ids) : [];
  }

  /**
   * 下一步建议：按剔除类型给可操作的动作。
   *
   * 只给「补条件 / 走定制」两类，**不引导回 P1 换场景**——那属于 P1 的编排，
   * P2 不反向驱动上一阶段（C13）。
   */
  private customSuggestions(
    rejects: TemplateRejectReason[],
    triage: Triage,
  ): string[] {
    const out: string[] = [];
    const missing = rejects.filter((r) => r.kind === 'connector');
    if (missing.length > 0) {
      out.push(
        `先开通所缺连接器，最接近的一套是「${missing[0].display_name}」，补齐后可直接复用。`,
      );
    }
    if (rejects.some((r) => r.kind === 'channel')) {
      out.push(
        `确认落地渠道；改用${this.catalogs.channelName(
          triage.channel || 'wecom',
        )}以外的已支持渠道也可能直接命中标品。`,
      );
    }
    out.push('按上面的轮廓走定制搭建，先做一个最小可用版本再逐步补能力。');
    out.push('把业务细节（主推产品、常见问题、禁语、转人工规则）整理好，定制时直接用。');
    return out;
  }

  /** 角色定位：BRIEF 一句话定位优先，退回 display_name。 */
  private role(t: TemplateRecord): string {
    const oneLiner = this.sectionBody(t.brief, /一句话定位/);
    const firstLine = oneLiner
      .split('\n')
      .map((l) => l.trim())
      .find((l) => l.length > 0);
    return firstLine || t.display_name;
  }

  /** 主路径：取「触发与主路径」里的有序步骤（数字/项目符号行）。 */
  private mainFlow(brief: string): string[] {
    const body = this.sectionBody(brief, /触发与主路径/);
    return this.bulletLines(body).slice(0, 8);
  }

  /** 成功判据：取「成功判据」章节要点。 */
  private successCriteria(brief: string): string[] {
    const body = this.sectionBody(brief, /成功判据/);
    return this.bulletLines(body).slice(0, 8);
  }

  /**
   * 取 `## N. 标题` 到下一个 `## ` 之间的正文（标题用正则匹配，容忍编号/空格差异）。
   */
  private sectionBody(brief: string, titlePattern: RegExp): string {
    if (!brief) return '';
    const lines = brief.split('\n');
    let start = -1;
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (/^#{2,3}\s/.test(l) && titlePattern.test(l)) {
        start = i + 1;
        break;
      }
    }
    if (start < 0) return '';
    const out: string[] = [];
    for (let i = start; i < lines.length; i++) {
      if (/^#{2,3}\s/.test(lines[i])) break;
      out.push(lines[i]);
    }
    return out.join('\n').trim();
  }

  /**
   * 从章节正文抽「要点行」：有序列表 `1.`、无序 `-`/`*`，去掉 Markdown 粗体/序号，
   * 折叠空白。忽略表格与空行。
   */
  private bulletLines(body: string): string[] {
    const out: string[] = [];
    for (const raw of body.split('\n')) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('|')) continue; // 跳过表格
      const m = line.match(/^(?:\d+\.|[-*])\s+(.*)$/);
      if (!m) continue;
      const text = m[1]
        .replace(/\*\*/g, '')
        .replace(/`/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      if (text) out.push(text);
    }
    return out;
  }
}
