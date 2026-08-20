/**
 * [P1] WizardService — 向导粗补规则引擎
 *
 * 文档：docs/产品设计.md §3–§6 · docs/工程架构.md §7 · docs/P1-拆分.md §3–§4
 *
 * ## 阶段边界
 * - **本服务属 P1**：只算 next_ask / 粗补是否达标 / missing_slots / inferSceneId
 * - **不调** MatchService、不读模板、不出 v0、不写 Coze
 * - P2 通过消费本服务参与产出的 Triage 衔接
 *
 * ## 职责（模型提议、规则裁决）
 * 给定 triage + scene（含 slots/ask_order），用纯规则算出：
 * - `next_ask`：下一问（选项 ⊆ catalogs，示例 ⊆ typical_prompts）
 * - `can_generate_v0`：粗补是否达标（历史字段名；语义 = 可进入 P2）
 * - `missing_slots`：仍缺的粗补槽位
 */

import { Injectable } from '@nestjs/common';
import { CatalogsService } from '../catalogs/catalogs.service';
import {
  P1_COARSE_READY_THRESHOLD,
  ProductPhase,
} from '../common/product-phase';
import { TraceService } from '../common/trace.service';
import {
  IntentGate,
  BuildPath,
  IntentResult,
  NextAsk,
  Phase1Result,
  SceneCatalogItem,
  SceneSlot,
  Triage,
  MatchResult,
  WizardDetailSupplement,
  WizardNextAction,
  WizardSummary,
} from '../common/types';

/** @deprecated 请用 P1_COARSE_READY_THRESHOLD；保留别名避免外部引用断裂 */
export { P1_COARSE_READY_THRESHOLD as V0_CONFIDENCE_THRESHOLD };

/** 词表内哨兵：行业/角色无法唯一落到装机 scene 时仍可 PASS 并默认走 P3C。 */
export const UNMAPPED_SCENE_ID = 'unmapped';

export interface WizardComputation {
  next_ask: NextAsk | null;
  /** 粗补达标（可进 P2）；字段名历史兼容 */
  can_generate_v0: boolean;
  missing_slots: string[];
}

/**
 * [P1] 全局粗补槽位（scene 未定时：先问行业+角色，再推断 scene）。
 * 推断 scene 后改用该 scene.slots 的 required（如美妆要求能力）。
 */
export const GLOBAL_COARSE_SLOTS: SceneSlot[] = [
  {
    key: 'industry',
    label: '行业',
    source: 'industries',
    required: true,
    why: '决定从哪一类模板里挑',
  },
  {
    key: 'role',
    label: '主要角色/职责',
    source: 'agent_families',
    required: true,
    why: '决定偏客服/销售/招聘等家族',
  },
  {
    key: 'desired_capabilities',
    label: '希望具备的能力',
    source: 'capabilities',
    required: false,
    multi: true,
    why: '决定命中模板能力是否覆盖你的诉求',
  },
];

@Injectable()
export class WizardService {
  constructor(
    private readonly catalogs: CatalogsService,
    private readonly trace: TraceService,
  ) {}

  /**
   * [P1] 计算向导进度。scene 缺失（非法 scene_id）时不产 next_ask、不达标。
   */
  compute(triage: Triage, scene?: SceneCatalogItem): WizardComputation {
    const conf = Number(triage.confidence ?? 0);

    if (!scene) {
      const result: WizardComputation = {
        next_ask: null,
        can_generate_v0: false,
        missing_slots: [],
      };
      this.trace.step('P1.Wizard', 'compute', { scene: undefined, ...result });
      return result;
    }

    if (!scene.slots || scene.slots.length === 0) {
      const result: WizardComputation = {
        next_ask: null,
        can_generate_v0: conf >= P1_COARSE_READY_THRESHOLD,
        missing_slots: [],
      };
      this.trace.step('P1.Wizard', 'compute', { scene: scene.id, ...result });
      return result;
    }

    const ordered = this.orderSlots(scene);
    const missingAll: string[] = [];
    const missingRequired: string[] = [];

    for (const slot of ordered) {
      if (!this.hasValue(triage, slot.key)) {
        missingAll.push(slot.key);
        if (slot.required) missingRequired.push(slot.key);
      }
    }

    // next_ask 只追问 required；可选槽不阻塞 PASS（交互向导可另问一轮选填）
    const firstRequiredMissing = ordered.find(
      (s) => s.required && missingRequired.includes(s.key),
    );

    const next_ask = firstRequiredMissing
      ? this.buildNextAsk(scene, firstRequiredMissing)
      : null;

    const can_generate_v0 =
      conf >= P1_COARSE_READY_THRESHOLD && missingRequired.length === 0;

    const result: WizardComputation = {
      next_ask,
      can_generate_v0,
      missing_slots: missingAll,
    };
    this.trace.step('P1.Wizard', 'compute', {
      scene: scene.id,
      confidence: conf,
      threshold: P1_COARSE_READY_THRESHOLD,
      missing_required: missingRequired,
      next_slot: next_ask?.slot,
      can_generate_v0,
    });
    return result;
  }

  /**
   * [P1] 把 compute 结果写回 triage，并裁决闸门（与 Intent 共用，保证两条入口一致）。
   *
   * - scene 不在词表 → CUSTOM
   * - 粗补未齐 / 置信不足 → ASK
   * - 否则 PASS（可交给 P2）
   */
  evaluateGate(triage: Triage): IntentResult {
    const sceneIds = this.catalogs.get().sceneIds;
    const scene = this.catalogs.sceneById(triage.scene_id);

    if (!triage.scene_id || !sceneIds.has(triage.scene_id) || !scene) {
      const w = this.compute(triage, undefined);
      triage.next_ask = w.next_ask;
      triage.can_generate_v0 = false;
      triage.missing_slots = w.missing_slots;
      return {
        gate: 'CUSTOM',
        triage,
        ask_user: `无法映射到已知场景（scene_id=${triage.scene_id || 'empty'}）`,
      };
    }

    const w = this.compute(triage, scene);
    triage.next_ask = w.next_ask;
    triage.can_generate_v0 = w.can_generate_v0;
    triage.missing_slots = w.missing_slots;

    if (!w.can_generate_v0) {
      return {
        gate: 'ASK',
        triage,
        ask_user:
          triage.ask_user?.trim() ||
          this.renderAsk(triage) ||
          '请继续补充行业、角色或能力诉求',
      };
    }

    return { gate: 'PASS', triage };
  }

  /** [P1] 全局粗补槽位序列（scene 未定时）。 */
  globalCoarse(): SceneSlot[] {
    return GLOBAL_COARSE_SLOTS;
  }

  /**
   * [P1] 取下一个待问的全局粗补槽位。
   */
  nextCoarseSlot(
    collected: Record<string, unknown>,
    includeOptional = true,
  ): SceneSlot | null {
    for (const spec of GLOBAL_COARSE_SLOTS) {
      if (!includeOptional && !spec.required) continue;
      if (!this.nonEmpty(collected[spec.key])) return spec;
    }
    return null;
  }

  /**
   * [P1] scene 已推断后：按该 scene.slots 取下一个仍缺的 required（或可选）槽。
   * 用于美妆等「能力必填」场景：全局粗补跳过能力后，这里再追问。
   */
  nextSceneSlot(
    triage: Triage,
    scene: SceneCatalogItem,
    requiredOnly = true,
  ): SceneSlot | null {
    const ordered = this.orderSlots(scene);
    for (const slot of ordered) {
      if (requiredOnly && !slot.required) continue;
      if (!this.hasValue(triage, slot.key)) return slot;
    }
    return null;
  }

  /** [P1] 为一个粗补槽位组装 next_ask。 */
  buildCoarseAsk(slot: SceneSlot, collected: Record<string, unknown>): NextAsk {
    return {
      slot: slot.key,
      label: slot.label,
      why: slot.why,
      multi: slot.multi,
      options_from: slot.source,
      options: this.catalogs.optionsFor(slot.source),
      example_from: 'scenes.typical_prompts',
      examples: this.coarseExamples(collected),
    };
  }

  /**
   * [P1] 由已收集粗槽确定性推断 scene_id（免 LLM）：
   * 1) agent_family===role 且 industries 覆盖 industry
   * 2) 仅 industries 覆盖 industry，且命中恰好一个 scene（edu 挂招聘/留学时不猜）
   * 不再按 role 回退到第一个客服 scene（会把汽车误映射成美妆）。
   * 都不中 → undefined，由 buildTriageFromUxCollect 写成 unmapped。
   */
  inferSceneId(collected: Record<string, unknown>): string | undefined {
    const role = this.str(collected.role);
    const industry = this.str(collected.industry);
    const scenes = this.catalogs
      .get()
      .scenes.filter(
        (s) => s.id !== UNMAPPED_SCENE_ID && s.status !== 'deprecated',
      );

    const covers = (s: { industries?: string[]; agent_family?: string }) =>
      this.catalogs.sceneCoversIndustry(s, industry);

    const exact = scenes.find((s) => s.agent_family === role && covers(s));
    if (exact) return exact.id;

    const byIndustry = scenes.filter((s) => covers(s));
    if (byIndustry.length === 1) return byIndustry[0].id;
    return undefined;
  }

  /** [P1] 把 next_ask 渲染成可读追问（DEMO 确定性拼装）。 */
  renderAsk(triage: Triage): string {
    const na = triage.next_ask;
    if (!na) return '';
    const parts: string[] = [];
    const label = na.label ?? na.slot;
    parts.push(na.why ? `${label}？（${na.why}）` : `${label}？`);
    if (na.options.length > 0) {
      const opts = na.options.map((o) => o.name).join(' / ');
      parts.push(
        `可选：${opts}${na.multi ? '（可多选）' : ''}；都不匹配可直接描述`,
      );
    }
    if (na.examples.length > 0) {
      parts.push(`你也可以照着说：「${na.examples[0]}」`);
    }
    return parts.join('\n');
  }

  /** 闸门是否为「可进入 P2」。 */
  isPass(gate: IntentGate): boolean {
    return gate === 'PASS';
  }

  /**
   * [A1] P3/P3B/P3C 唯一分流真源。Worker/Leader 只能消费本结果，不得自裁。
   * 默认 P3C；仅行业对齐且能力覆盖的 hit（dag_fit=high）且无否决时走 P3。
   * 记忆 / 自演进 / 多轮工具信号是否决 P3，不是 P3C 入场券。P3B 不由默认路由返回。
   */
  decideBuildPath(triage: Triage, match: MatchResult): BuildPath {
    if (
      triage.needs_long_term_memory === true ||
      triage.needs_skill_evolution === true ||
      match.needs_multi_turn_tooling === true
    ) {
      return 'P3C';
    }
    if (match.action === 'hit' && match.dag_fit === 'high') return 'P3';
    return 'P3C';
  }

  // ==========================================================================
  // [P1 UX v2] 行业 → 业务目标 → 总结（docs/产品设计.md §6）
  // ==========================================================================

  /**
   * 由所选 business_goals 推导：capabilities、agent_family、角色话术线索。
   */
  deriveFromGoals(goalIds: string[]): {
    capabilityIds: string[];
    agentFamily: string;
    roleHints: string[];
    goalLabels: Array<{ id: string; name: string }>;
  } {
    const cap = new Set<string>();
    const familyVotes = new Map<string, number>();
    const roleHints: string[] = [];
    const goalLabels: Array<{ id: string; name: string }> = [];

    for (const id of goalIds) {
      const g = this.catalogs.businessGoalById(id);
      if (!g) continue;
      goalLabels.push({ id: g.id, name: g.name });
      for (const c of g.capability_hints ?? []) cap.add(c);
      if (g.agent_family_hint) {
        familyVotes.set(
          g.agent_family_hint,
          (familyVotes.get(g.agent_family_hint) ?? 0) + 1,
        );
      }
      if (g.summary_role_hint) roleHints.push(g.summary_role_hint);
    }

    let agentFamily = 'customer_success';
    let best = 0;
    for (const [f, n] of familyVotes) {
      if (n > best) {
        best = n;
        agentFamily = f;
      }
    }

    return {
      capabilityIds: [...cap],
      agentFamily,
      roleHints: [...new Set(roleHints)],
      goalLabels,
    };
  }

  /**
   * 组装 WizardSummary（规则；不调模型）。
   */
  buildSummary(input: {
    industryId: string;
    goalIds: string[];
    businessBrief?: string;
  }): WizardSummary {
    const indOpt = this.catalogs
      .optionsFor('industries')
      .find((o) => o.id === input.industryId);
    const derived = this.deriveFromGoals(input.goalIds);
    const industryName = indOpt?.name ?? input.industryId;
    const role_positioning =
      derived.roleHints.length > 0
        ? `${industryName}行业的智能助手，侧重${derived.roleHints.slice(0, 3).join('、')}`
        : `${industryName}行业的智能客服与业务接待助手`;

    const current_focus =
      derived.goalLabels.length > 0
        ? derived.goalLabels
            .slice(0, 3)
            .map((g) => g.name)
            .join('；')
        : '先完成最小必要分流，再把用户推进到合适的咨询或人工路径';

    return {
      industry: {
        id: input.industryId,
        name: industryName,
        group: indOpt?.group,
      },
      business_goals: derived.goalLabels,
      business_brief: input.businessBrief?.trim() || undefined,
      role_positioning,
      core_capabilities: this.catalogs.capabilityNames(derived.capabilityIds),
      current_focus,
      knowledge_packs_planned: [
        '首版知识库占位：支撑基础答疑与业务咨询',
        '建议后续补齐：价格/活动/政策/售后等强业务信息',
      ],
    };
  }

  /**
   * 新向导收集结果 → Triage（尽量映射 scene；映射不到则 unmapped + risk_flags，仍可 PASS）。
   */
  buildTriageFromUxCollect(
    channel: string,
    collected: {
      industry: string;
      business_goals: string[];
      business_brief?: string;
      role?: string;
    },
  ): Triage {
    const derived = this.deriveFromGoals(collected.business_goals ?? []);
    // 招聘簇强制 hr_recruit，便于命中现有模板
    let role = collected.role || derived.agentFamily;
    if (
      collected.industry === 'recruiting' ||
      collected.industry === 'hr_services'
    ) {
      role = 'hr_recruit';
    }

    const inferred = this.inferSceneId({
      industry: collected.industry,
      role,
    });
    const scene_id = inferred || UNMAPPED_SCENE_ID;

    const risk_flags: string[] = [];
    if (scene_id === UNMAPPED_SCENE_ID) risk_flags.push('no_template_scene');

    return {
      scene_id,
      agent_family: role,
      channel,
      industry: collected.industry,
      confidence: scene_id !== UNMAPPED_SCENE_ID ? 0.88 : 0.75,
      reason: `向导 UX：行业=${collected.industry}；目标=${(collected.business_goals ?? []).join(',')}`,
      known_slots: {
        industry: collected.industry,
        role,
        business_goals: collected.business_goals ?? [],
        business_brief: collected.business_brief,
        desired_capabilities: derived.capabilityIds,
      },
      missing_slots: [],
      risk_flags,
    };
  }

  /**
   * 把「继续补充细节」的五个字段并回总结（规则拼装，不调模型）。
   * CLI 与 Web 共用，保证两条入口的总结文案一致。
   */
  mergeDetailIntoSummary(
    summary: WizardSummary,
    detail: WizardDetailSupplement,
  ): WizardSummary {
    const parts: string[] = [];
    if (summary.business_brief) parts.push(summary.business_brief);
    if (detail.flagship_products) {
      parts.push(`主打产品：${detail.flagship_products}`);
    }
    if (detail.primary_customers) {
      parts.push(`主要客户：${detail.primary_customers}`);
    }
    const next: WizardSummary = { ...summary };
    next.business_brief = parts.join('；') || summary.business_brief;
    if (detail.goals) {
      next.current_focus = `${summary.current_focus}；补充目标：${detail.goals}`;
    }
    if (detail.prohibitions) {
      next.knowledge_packs_planned = [
        ...summary.knowledge_packs_planned,
        `服务边界/禁止：${detail.prohibitions}`,
      ];
    }
    if (detail.escalate_scenes) {
      next.current_focus = `${next.current_focus}；转人工：${detail.escalate_scenes}`;
    }
    return next;
  }

  /**
   * [A1] 从业务简述/细补文本规则抽取「否决 P3」信号（无 LLM）。
   * 命中则不得走模板改写（P3），默认仍是 P3C；不是 P3C 入场券。
   * 显式入参与文本推断取 OR；闸门后会再次写回，避免 Object.assign 语义歧义。
   */
  inferP3cSignals(text: string): {
    needs_long_term_memory: boolean;
    needs_skill_evolution: boolean;
  } {
    const body = (text ?? '').trim();
    return {
      needs_long_term_memory:
        /记住|跨会话|偏好|画像|任务板|挂起|好的继续/.test(body),
      needs_skill_evolution: /越用越准|自己积累话术/.test(body),
    };
  }

  /** 拼出可做信号扫描的用户侧文本（简述 + 细补 + 可选原文 brief）。 */
  private p3cSignalCorpus(
    summary: WizardSummary,
    detail?: WizardDetailSupplement,
    extraBrief?: string,
  ): string {
    const parts = [
      extraBrief ?? '',
      summary.business_brief ?? '',
      summary.current_focus ?? '',
    ];
    if (detail) {
      for (const v of Object.values(detail)) {
        if (typeof v === 'string' && v.trim()) parts.push(v);
      }
    }
    return parts.join('\n');
  }

  /**
   * 向导收尾：收集结果 → Triage → 闸门 → Phase1Result。
   *
   * 这是 P1 的**唯一收口**，CLI（`p1-wizard`）与 Web（`POST /api/wizard/.../answer`）
   * 都走这里，因此两端产出的 JSON 结构与闸门判定完全一致。
   * 映射不到装机模板场景时写入 unmapped 并仍可 PASS（默认 P3C），不再因无 scene 藏「开始生成」。
   */
  buildPhase1Result(input: {
    clientCode: string;
    requestId?: string;
    channel: string;
    stage: Phase1Result['stage'];
    industryId: string;
    goalIds: string[];
    summary: WizardSummary;
    detail?: WizardDetailSupplement;
    nextAction: WizardNextAction;
    needsLongTermMemory?: boolean;
    needsSkillEvolution?: boolean;
    /** 用户原始简述（防 LLM 改写 summary.business_brief 丢触发词） */
    sourceBrief?: string;
  }): Phase1Result {
    const triage = this.buildTriageFromUxCollect(input.channel, {
      industry: input.industryId,
      business_goals: input.goalIds,
      business_brief: input.summary.business_brief,
    });
    const inferred = this.inferP3cSignals(
      this.p3cSignalCorpus(input.summary, input.detail, input.sourceBrief),
    );
    const applySignals = () => {
      triage.needs_long_term_memory =
        input.needsLongTermMemory === true || inferred.needs_long_term_memory;
      triage.needs_skill_evolution =
        input.needsSkillEvolution === true || inferred.needs_skill_evolution;
    };
    applySignals();

    let gate: IntentGate;
    if (triage.scene_id) {
      const gated = this.evaluateGate(triage);
      gate = gated.gate;
      Object.assign(triage, gated.triage);
      applySignals();
    } else {
      gate = 'CUSTOM';
      triage.can_generate_v0 = false;
      triage.ask_user =
        '当前行业/目标尚无对应装机模板场景；总结已完成，可继续补充细节或走定制。';
    }

    return {
      phase: ProductPhase.P1_WIZARD_INTENT,
      client_code: input.clientCode,
      request_id: input.requestId,
      stage: input.stage,
      gate,
      triage,
      summary: input.summary,
      detail: input.detail,
      next_action: input.nextAction,
      ask_user:
        input.nextAction === 'preview'
          ? '请将本 JSON 交给 P2：match --triage <本文件或其中 triage>'
          : triage.ask_user,
    };
  }

  private coarseExamples(collected: Record<string, unknown>): string[] {
    const industry = this.str(collected.industry);
    const scenes = this.catalogs.get().scenes;
    const mappable = scenes.filter((s) => s.id !== UNMAPPED_SCENE_ID);
    const pool = industry
      ? mappable.filter((s) => this.catalogs.sceneCoversIndustry(s, industry))
      : mappable;
    const src = (pool.length > 0 ? pool : mappable).flatMap(
      (s) => s.typical_prompts ?? [],
    );
    return src.slice(0, 3);
  }

  private str(v: unknown): string {
    return v === undefined || v === null ? '' : String(v);
  }

  private orderSlots(scene: SceneCatalogItem): SceneSlot[] {
    const slots = scene.slots ?? [];
    const order = scene.ask_order;
    if (!order || order.length === 0) return slots;
    const byKey = new Map(slots.map((s) => [s.key, s]));
    const out: SceneSlot[] = [];
    for (const key of order) {
      const s = byKey.get(key);
      if (s) {
        out.push(s);
        byKey.delete(key);
      }
    }
    for (const s of byKey.values()) out.push(s);
    return out;
  }

  private hasValue(triage: Triage, key: string): boolean {
    const known = (triage.known_slots ?? {}) as Record<string, unknown>;
    if (this.nonEmpty(known[key])) return true;

    const top = triage as unknown as Record<string, unknown>;
    if (this.nonEmpty(top[key])) return true;

    if (key === 'role' && this.nonEmpty(top.agent_family)) return true;
    if (key === 'industry' && this.nonEmpty(known.industry)) return true;

    return false;
  }

  private nonEmpty(v: unknown): boolean {
    if (v === undefined || v === null) return false;
    if (typeof v === 'string') return v.trim().length > 0;
    if (Array.isArray(v)) return v.length > 0;
    return true;
  }

  private buildNextAsk(scene: SceneCatalogItem, slot: SceneSlot): NextAsk {
    return {
      slot: slot.key,
      label: slot.label,
      why: slot.why,
      multi: slot.multi,
      options_from: slot.source,
      options: this.catalogs.optionsFor(slot.source),
      example_from: `scenes.${scene.id}.typical_prompts`,
      examples: (scene.typical_prompts ?? []).slice(0, 3),
    };
  }
}
