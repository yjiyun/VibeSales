/**
 * [Web] WizardSessionService — 向导会话状态机（HTTP 版）
 *
 * 文档：`docs/工程架构.md` §13 Web 层
 *
 * ## 与 CLI 的关系
 * CLI（`p1-wizard`）用 readline 顺序等输入；HTTP 是无状态请求，因此把「等输入」
 * 显式化为一个可保存的停顿点（`WizardStage`），每次 `answer` 推进一格：
 *
 * ```text
 * POST /api/wizard/sessions           → S1_INDUSTRY（发第一问）
 * POST /api/wizard/sessions/:id/answer→ 推进一格，回「说了什么 + 下一问 + 运行情况」
 *        S1_INDUSTRY → S2_GOALS → S3_BRIEF → S4_CTA
 *              ├─ 先看看效果   → DONE（Phase1Result）
 *              └─ 继续补充细节 → S5_DETAIL ×5 → S5_CTA → DONE
 * ```
 *
 * ## 复用而非复制
 * - 答案解析：`wizard-input.ts`（与 CLI 同一套序号/名称/跳过/退出判定）
 * - 话术底稿：`WizardSpeech`；润色与出题：`WizardLlmReceptionist`
 * - 收尾产物：`WizardService.buildPhase1Result`（与 CLI 同一 triage / 闸门 / 回包）
 *
 * 因此 Web 不含任何业务裁决，只做「会话推进 + 组包」。
 *
 * ## 每回合的运行情况
 * 整回合跑在 `FlowContextService.run({ collectEvents: true })` 里：同一次
 * `trace.step` 同时进 stderr、`logs/app.log` 和回包 `runtime.events`，
 * 所以前端运行面板看到的顺序、序号与日志文件完全一致。
 */

import { Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { CatalogsService } from '../catalogs/catalogs.service';
import { FlowContextService, FlowState } from '../common/flow-context';
import { charCount, digestText } from '../common/input-digest';
import { buildRequestContext } from '../common/request-context';
import { ProductPhase } from '../common/product-phase';
import {
  TokenSummary,
  TokenUsageService,
} from '../common/token-usage.service';
import { TraceService } from '../common/trace.service';
import {
  CatalogOption,
  EndToEndResult,
  Phase1Result,
  RequestContext,
  WizardDetailSupplement,
  WizardNextAction,
  WizardSummary,
} from '../common/types';
import { MatchService } from '../match/match.service';
import { PreviewService } from '../preview/preview.service';
import { TenantService } from '../tenant/tenant.service';
import {
  isGenerate,
  isQuit,
  isSkip,
  looksLikeMultiField,
  parseDetailIntent,
  parseMulti,
  parseNextAction,
  pickOne,
} from '../wizard/wizard-input';
import {
  DetailPromptItem,
  WizardLlmReceptionist,
} from '../wizard/wizard-llm-receptionist.service';
import { WizardSpeech, COLLECT_DETAIL_KEYS } from '../wizard/wizard-speech';
import { WizardService } from '../wizard/wizard.service';
import { resolveWizardLlmModel } from '../common/wizard-models';
import {
  AnswerBody,
  CreateSessionBody,
  PreviewTurn,
  TemplateTurn,
  WizardCollect,
  WizardCollectItem,
  WizardMessage,
  WizardQuestion,
  WizardRuntime,
  WizardStage,
  WizardStatus,
  WizardTurn,
} from './web.types';

/** trace scope：与 CLI 的 `P1.Wizard` 区分，便于在 app.log 里筛 Web 入口。 */
const SCOPE = 'Web.Wizard';

/** 会话上限与存活时长（DEMO 用内存态；重启即清空）。 */
const MAX_SESSIONS = 200;
const SESSION_TTL_MS = 2 * 60 * 60 * 1000;

/** 自由描述行业的哨兵值（对应 CLI 的「0. 其他 → 直接描述」）。 */
const FREE_INDUSTRY = '__free__';

/** 服务端保存的会话（内存）。 */
interface Session {
  id: string;
  ctx: RequestContext;
  /** 会话级 LLM 偏好（无 Key 时实际仍会降级） */
  llm: boolean;
  /** 向导接待员模型（允许名单内） */
  model: string;
  stage: WizardStage;
  industryId?: string;
  industryName?: string;
  goalIds: string[];
  /** 用户原始业务简述（structureBrief 改写前）；P3C 信号以此为准 */
  rawBrief?: string;
  brief?: string;
  summary?: WizardSummary;
  detail?: WizardDetailSupplement;
  detailPrompts?: DetailPromptItem[];
  detailIndex: number;
  nextAction?: WizardNextAction;
  result?: Phase1Result;
  abortedAt?: string;
  /**
   * 改写回流：用户从 DONE / S6_REVISE 跳回某一步重答时为 true，
   * 写完该槽后直接回 toSummary，而不是往下一问走。
   */
  revising?: boolean;
  /** 会话累计 token（每回合 FlowState 独立，故在此手工累加） */
  tokenSession: TokenSummary | null;
  msgSeq: number;
  createdAt: number;
  updatedAt: number;
}

@Injectable()
export class WizardSessionService {
  private readonly sessions = new Map<string, Session>();
  /** 每回合起点（用 FlowState 做键，随作用域一起回收） */
  private readonly turnStart = new WeakMap<FlowState, number>();

  constructor(
    private readonly tenants: TenantService,
    private readonly catalogs: CatalogsService,
    private readonly wizard: WizardService,
    private readonly llm: WizardLlmReceptionist,
    private readonly match: MatchService,
    private readonly previews: PreviewService,
    private readonly trace: TraceService,
    private readonly tokens: TokenUsageService,
    private readonly flows: FlowContextService,
  ) {}

  /** 新建会话并返回第一问（行业）。 */
  async create(body: CreateSessionBody): Promise<WizardTurn> {
    const clientCode = (body.client_code ?? '').trim();
    if (!clientCode) throw new NotFoundException('client_code is required');

    return this.withFlow(undefined, async () => {
      this.trace.banner('Web wizard session.create');
      const tenant = this.tenants.resolve(clientCode, body.tenant);
      const ctx = buildRequestContext(tenant);
      const session: Session = {
        id: randomUUID(),
        ctx,
        llm: body.llm !== false,
        model: resolveWizardLlmModel(body.model),
        stage: 'S1_INDUSTRY',
        goalIds: [],
        detailIndex: 0,
        tokenSession: null,
        msgSeq: 0,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      };
      this.trace.bind({ session_id: session.id, client_code: session.ctx.client_code });
      // 会话级偏好要在本回合立即生效（否则首问的 LLM 状态显示会不准）
      this.flows.current().preferLlm = session.llm;
      this.flows.current().preferModel = session.model;
      this.sweep();
      this.sessions.set(session.id, session);

      this.trace.step(SCOPE, 'session.create', {
        session_id: session.id,
        client_code: ctx.client_code,
        llm: this.llm.isActive() ? 'on' : 'off',
        channels: ctx.tenant.channels,
      });

      const messages = [
        this.msg(session, 'speech', WizardSpeech.welcome()),
        this.msg(
          session,
          'notice',
          `租户 client_code=**${ctx.client_code}**；LLM 接待员=**${this.llm.isActive() ? '开' : '关'}**。`,
        ),
      ];
      this.trace.step(SCOPE, 'S1_industry.ask');
      return this.turn(session, messages, this.industryQuestion());
    });
  }

  /** 读取会话现状（不推进；events 只含本次读取的少量打点）。 */
  async snapshot(sessionId: string): Promise<WizardTurn> {
    const session = this.require(sessionId);
    return this.withFlow(session, async () => {
      this.trace.step(SCOPE, 'session.snapshot', {
        session_id: session.id,
        stage: session.stage,
      });
      return this.turn(session, [], await this.questionFor(session));
    });
  }

  /**
   * 推进一格。
   * 任一阶段收到退出词（q / 退出 / exit）即终止会话并结算 token。
   * DONE 后仍接受自由输入改写（见 stepRevise），只有 ABORTED 才真正终止。
   */
  async answer(sessionId: string, body: AnswerBody): Promise<WizardTurn> {
    const session = this.require(sessionId);
    if (session.stage === 'ABORTED') {
      return this.snapshot(sessionId);
    }

    return this.withFlow(session, async () => {
      this.trace.banner(`Web wizard ${session.stage}`);
      const raw = this.rawOf(body);
      // 摘要而非仅字数：排查「这句话为什么没解析成行业」时，日志本身要能读出用户说了什么
      this.trace.step(SCOPE, 'answer.received', {
        session_id: session.id,
        stage: session.stage,
        values: body.values,
        value_labels: this.pickedLabels(session, body.values),
        text_chars: charCount(body.text),
        text_digest: digestText(body.text),
      });

      if (isQuit(raw)) {
        const at = session.stage;
        session.stage = 'ABORTED';
        session.abortedAt = at;
        this.trace.step(SCOPE, 'aborted', { at });
        return this.turn(session, [
          this.msg(session, 'notice', '已退出向导。需要重新开始随时新建会话。'),
        ]);
      }

      switch (session.stage) {
        case 'S1_INDUSTRY':
        case 'S1_INDUSTRY_FREE':
          return this.stepIndustry(session, raw, body);
        case 'S2_GOALS':
          return this.stepGoals(session, raw, body);
        case 'S3_BRIEF':
          return this.stepBrief(session, raw);
        case 'S4_CTA':
          return this.stepCta(session, raw, 'S4');
        case 'S5_DETAIL':
          return this.stepDetail(session, raw);
        case 'S5_CTA':
          return this.stepCta(session, raw, 'S5');
        case 'DONE':
        case 'S6_REVISE':
          return this.stepRevise(session, raw, body);
        default:
          return this.turn(session, [], await this.questionFor(session));
      }
    });
  }

  /**
   * [P2] 向导结束后按需生成预览：复用 P2 MatchService + PreviewService。
   * 只有向导已完成（有 triage）才允许调用。
   */
  async preview(sessionId: string): Promise<PreviewTurn> {
    const session = this.require(sessionId);
    if (!session.result) {
      throw new NotFoundException('wizard not finished: no triage yet');
    }
    return this.withFlow(session, async (state) => {
      const startedAt = Date.now();
      this.trace.banner('Web P2 preview');
      const result = await this.runMatch(session);
      return {
        session_id: session.id,
        client_code: session.ctx.client_code,
        result,
        runtime: this.runtime(session, state, startedAt, {
          stage: 'preview',
          match_action: result.match?.action,
        }),
      } satisfies PreviewTurn;
    });
  }

  /**
   * [P2] 跑一次「Match → 产物」，不带回合外壳。
   *
   * 独立成方法是因为它有两个调用点：显式 `POST .../preview`（重跑按钮）
   * 与 §8.7 的 CTA 直串（`stepRevise` 里「先看看效果」）。后者已经在
   * 一个 flow 内，不能再套 `withFlow`。
   *
   * 编排只发生在本 Web 入口层：P2 侧不反向依赖 P1 模块（C13）。
   */
  private async runMatch(session: Session): Promise<EndToEndResult> {
    const triage = session.result!.triage;
    const matchResult = await this.match.run(session.ctx, triage);
    const isHit = matchResult.action === 'hit';
    const v0_preview = isHit
      ? this.previews.build(matchResult.template_id)
      : undefined;
    // custom 也要有产物：用 P1 的 summary + triage 组织「如果定制会是这样」
    const custom_outline = isHit
      ? undefined
      : this.previews.buildCustomOutline({
          triage,
          summary: session.summary,
          reject_summary: matchResult.reject_summary,
          why_user: matchResult.why_user,
        });
    this.trace.step(SCOPE, 'preview.done', {
      action: matchResult.action,
      via: matchResult.via,
      template_id: matchResult.template_id,
    });
    return {
      phase: ProductPhase.P2_TEMPLATE_MATCH,
      client_code: session.ctx.client_code,
      request_id: session.ctx.request_id,
      triage,
      gate: session.result!.gate,
      match: matchResult,
      v0_preview,
      custom_outline,
    };
  }

  /**
   * 「使用模板」按需生成：前端只有用户真的点了按钮才会调用。
   * 结合会话里的行业与已选目标让模型写一段可直接改的业务简述；
   * 模型不可用或产出不合结构时退回 `WizardSpeech.briefTemplate()`。
   */
  async briefTemplate(sessionId: string): Promise<TemplateTurn> {
    const session = this.require(sessionId);
    return this.withFlow(session, async (state) => {
      const startedAt = Date.now();
      this.trace.banner('Web brief template');
      const goalNames = session.goalIds
        .map((id) => this.catalogs.optionsFor('business_goals').find((o) => o.id === id)?.name)
        .filter((n): n is string => !!n);
      const generated = await this.llm.briefTemplate({
        industryName: session.industryName,
        goalNames,
      });
      const template_text =
        generated ?? WizardSpeech.briefTemplate(session.industryName);
      this.trace.step(SCOPE, 'brief_template.done', {
        by_llm: !!generated,
        chars: template_text.length,
      });
      return {
        session_id: session.id,
        template_text,
        by_llm: !!generated,
        runtime: this.runtime(session, state, startedAt, {
          stage: 'brief_template',
        }),
      } satisfies TemplateTurn;
    });
  }

  // ==========================================================================
  // 各阶段推进
  // ==========================================================================

  private async stepIndustry(
    session: Session,
    raw: string,
    body: AnswerBody,
  ): Promise<WizardTurn> {
    const flat = this.catalogs.optionsFor('industries');
    const picked = (body.values ?? [])[0];

    // 「都不匹配 → 直接描述」：先给自由输入框，下一回合再归一
    if (session.stage === 'S1_INDUSTRY' && this.wantsFree(picked, raw)) {
      session.stage = 'S1_INDUSTRY_FREE';
      this.trace.step(SCOPE, 'S1_industry.free');
      return this.turn(
        session,
        [this.msg(session, 'speech', '好的，直接说说你们主要做什么就行。')],
        {
          stage: 'S1_INDUSTRY_FREE',
          key: 'industry',
          title: '你们具体是做什么业务的？',
          hint: '一句话即可，例如「做进口母婴用品的线上零售」。',
          input: 'text',
        },
      );
    }

    let industryId = picked && flat.some((o) => o.id === picked) ? picked : null;
    if (!industryId) industryId = pickOne(flat, raw);
    if (!industryId && raw.trim()) {
      // 词表匹配不中才动用模型归一（模型只能从词表里选，选不出兜底 general）
      industryId =
        (await this.llm.normalizeIndustry(raw, flat)) ?? 'general';
    }
    if (!industryId) {
      return this.turn(
        session,
        [this.msg(session, 'notice', '请选择一个行业，或点「都不匹配」直接描述。')],
        this.industryQuestion(),
      );
    }

    session.industryId = industryId;
    session.industryName =
      flat.find((o) => o.id === industryId)?.name ?? industryId;
    this.trace.step(SCOPE, 'S1_industry.done', {
      industry_id: industryId,
      industry_name: session.industryName,
    });

    const echo = await this.llm.echoIndustry(session.industryName);
    this.trace.step(SCOPE, 'S2_goals.ask');
    return this.advanceAfter(
      session,
      [this.msg(session, 'echo', echo, this.llm.isActive())],
      'S2_GOALS',
      () => this.goalsQuestion(),
    );
  }

  private async stepGoals(
    session: Session,
    raw: string,
    body: AnswerBody,
  ): Promise<WizardTurn> {
    const opts = this.catalogs.optionsFor('business_goals');
    const fromValues = (body.values ?? []).filter((v) =>
      opts.some((o) => o.id === v),
    );
    const goalIds =
      fromValues.length > 0
        ? fromValues
        : isSkip(raw)
          ? []
          : parseMulti(opts, raw);

    if (goalIds.length === 0 && !isSkip(raw)) {
      return this.turn(
        session,
        [this.msg(session, 'notice', '请至少选择一项业务目标，或选择跳过。')],
        this.goalsQuestion(),
      );
    }

    session.goalIds = goalIds;
    const goalNames = goalIds
      .map((id) => this.catalogs.businessGoalById(id)?.name ?? id)
      .filter(Boolean);
    this.trace.step(SCOPE, 'S2_goals.done', {
      goal_ids: goalIds,
      goal_names: goalNames,
    });

    const echo = await this.llm.echoGoals(goalNames);
    this.trace.step(SCOPE, 'S3_brief.ask');
    return this.advanceAfter(
      session,
      [
        this.msg(
          session,
          'echo',
          echo,
          this.llm.isActive() && goalNames.length > 0,
        ),
      ],
      'S3_BRIEF',
      () => this.briefQuestion(session),
    );
  }

  private async stepBrief(session: Session, raw: string): Promise<WizardTurn> {
    const rawBrief = isSkip(raw) ? '' : raw.trim();
    let brief = rawBrief;
    if (rawBrief) {
      session.rawBrief = rawBrief;
      brief = await this.llm.structureBrief(
        rawBrief,
        session.industryName ?? '',
      );
    } else {
      session.rawBrief = undefined;
    }
    session.brief = brief || undefined;
    this.trace.step(SCOPE, 'S3_brief.done', {
      raw_chars: rawBrief.length,
      structured_chars: brief.length,
      structured_by_llm: rawBrief.length > 0 && this.llm.isActive(),
    });

    return this.toSummary(session, session.detail ? 'S5' : 'S4');
  }

  /** 生成/更新总结并进入 CTA。 */
  private async toSummary(
    session: Session,
    at: 'S4' | 'S5',
    prefix: WizardMessage[] = [],
  ): Promise<WizardTurn> {
    let summary = this.wizard.buildSummary({
      industryId: session.industryId ?? 'general',
      goalIds: session.goalIds,
      businessBrief: session.brief,
    });
    if (at === 'S5' && session.detail) {
      summary = this.wizard.mergeDetailIntoSummary(summary, session.detail);
    }
    summary = await this.llm.polishSummary(summary);
    session.summary = summary;

    const messages: WizardMessage[] = [...prefix];
    if (at === 'S5') {
      messages.push(this.msg(session, 'speech', WizardSpeech.afterDetail()));
    }
    messages.push(this.msg(session, 'speech', WizardSpeech.summaryIntro()));
    messages.push(
      this.msg(
        session,
        'summary',
        WizardSpeech.formatSummary(summary),
        this.llm.isActive(),
      ),
    );

    session.stage = at === 'S4' ? 'S4_CTA' : 'S5_CTA';
    session.revising = false;
    this.trace.step(SCOPE, `${at}_summary.ready`, {
      polished_by_llm: this.llm.isActive(),
      role_positioning: summary.role_positioning,
      capabilities: summary.core_capabilities,
    });
    return this.turn(session, messages, this.ctaQuestion(session.stage));
  }

  private async stepCta(
    session: Session,
    raw: string,
    at: 'S4' | 'S5',
  ): Promise<WizardTurn> {
    // 用户可能不只表态，还把内容一起给了（「补充信息：\n主要客户：…」）
    const intent = parseDetailIntent(raw);
    if (at === 'S4' && intent) {
      this.trace.step(SCOPE, 'S4_summary.done', {
        cta: 'continue_detail',
        free_chars: intent.body.length,
      });
      return this.startDetail(session, intent.body);
    }
    if (at === 'S5' && intent?.body) {
      // S5 之后不再二次细补，但正文不能丢：归类进还空着的字段后收尾
      const absorbed = await this.absorbFreeText(session, intent.body);
      if (absorbed.length > 0) {
        session.revising = true;
        return this.toSummary(session, 'S5', [
          this.msg(session, 'speech', WizardSpeech.detailExtracted(absorbed)),
        ]);
      }
    }

    const action = parseNextAction(raw);
    if (!action) {
      return this.turn(
        session,
        [
          this.msg(
            session,
            'notice',
            '请选择「先看看效果」或「继续补充细节」。',
          ),
        ],
        this.ctaQuestion(session.stage),
      );
    }

    // S5 之后不再二次细补：继续补充等同于结束（与 CLI 一致）
    if (at === 'S4' && action === 'continue_detail') {
      this.trace.step(SCOPE, 'S4_summary.done', { cta: action });
      return this.startDetail(session);
    }

    const nextAction: WizardNextAction =
      at === 'S5' && action === 'continue_detail' ? 'done' : action;
    this.trace.step(SCOPE, `${at}_summary.done`, { cta: action, nextAction });
    return this.finish(session, nextAction, at === 'S4' ? 'S1_SUMMARY' : 'S1_DETAIL');
  }

  /**
   * 进入细补环节。
   *
   * @param freeText 用户在表达「补充信息」意图时顺手给出的正文；
   *   有正文 → 先 LLM 提取字段并回显，再从第一个未填项继续追问（全填满则直接收尾）；
   *   无正文 → 先给可补字段的文本清单做引导，再问第一项。
   *   将来「上传文档」解析出的文本也走这里，无需再开分支。
   */
  private async startDetail(
    session: Session,
    freeText = '',
  ): Promise<WizardTurn> {
    session.detail = {};
    session.detailIndex = 0;
    session.detailPrompts = await this.llm.detailPrompts(session.summary!);
    session.stage = 'S5_DETAIL';
    this.trace.step(SCOPE, 'S5_detail.start', {
      keys: session.detailPrompts.map((p) => p.key),
      prompts_by_llm: this.llm.isActive(),
      free_chars: freeText.length,
    });

    const messages: WizardMessage[] = [];
    if (freeText) {
      const absorbed = await this.absorbFreeText(session, freeText);
      if (absorbed.length > 0) {
        messages.push(
          this.msg(session, 'speech', WizardSpeech.detailExtracted(absorbed)),
        );
      } else {
        // 提取不出来（无模型 / 模型没听懂）：正文不丢，落进业务简述后继续追问
        this.appendBrief(session, freeText);
        messages.push(
          this.msg(
            session,
            'notice',
            '我先把这段记到业务简述里了，下面再逐项确认一下。',
          ),
        );
      }
    } else {
      messages.push(
        this.msg(
          session,
          'speech',
          WizardSpeech.detailGuide(
            COLLECT_DETAIL_KEYS,
            session.industryName ?? '你的行业',
          ),
        ),
      );
    }

    const next = this.nextDetailIndex(session, 0);
    if (next < 0) return this.afterDetail(session, messages);
    session.detailIndex = next;
    return this.turn(session, messages, this.detailQuestion(session));
  }

  /**
   * 把一段自由文本按细补五字段归类写入 session.detail。
   *
   * 只写「当前还空着」的字段，避免覆盖用户此前逐项答过的内容。
   * @returns 实际写入的字段（label + value），供回显；提取失败返回空数组
   */
  private async absorbFreeText(
    session: Session,
    text: string,
  ): Promise<Array<{ label: string; value: string }>> {
    const patch = await this.llm.extractDetail(text, {
      industryName: session.industryName,
      detail: session.detail,
    });
    if (!patch) return [];

    const applied: Array<{ label: string; value: string }> = [];
    const detail: WizardDetailSupplement = { ...(session.detail ?? {}) };
    for (const key of COLLECT_DETAIL_KEYS) {
      const value = patch[key];
      if (!value || detail[key]) continue;
      detail[key] = value;
      applied.push({
        label: WizardSpeech.collectMeta(`detail.${key}`).label,
        value,
      });
    }
    if (applied.length === 0) return [];
    session.detail = detail;
    this.trace.step(SCOPE, 'S5_detail.extract', {
      keys: applied.map((a) => a.label),
      chars: text.length,
    });
    return applied;
  }

  /**
   * 从 `from` 起找第一个还没填的细补字段下标；全部填满返回 -1。
   * 提取命中的字段不再重复追问。
   */
  private nextDetailIndex(session: Session, from: number): number {
    const prompts = session.detailPrompts ?? [];
    const detail = session.detail ?? {};
    for (let i = from; i < prompts.length; i += 1) {
      if (!detail[prompts[i].key]) return i;
    }
    return -1;
  }

  /** 提取不出结构化字段时的保底：把原文并进业务简述，信息不丢。 */
  private appendBrief(session: Session, text: string): void {
    const prev = (session.brief ?? '').trim();
    session.brief = prev ? `${prev}\n${text}` : text;
  }

  private async stepDetail(
    session: Session,
    raw: string,
  ): Promise<WizardTurn> {
    const prompts = session.detailPrompts ?? [];
    const current = prompts[session.detailIndex];

    if (isGenerate(raw)) {
      this.trace.step(SCOPE, 'S5_detail.early_finish', {
        answered: Object.keys(session.detail ?? {}),
      });
      return this.afterDetail(session);
    }
    const messages: WizardMessage[] = [];
    if (current && !isSkip(raw)) {
      const text = raw.trim();
      // 一次说了多个字段（含换行 / 多个字段线索词）→ 先按字段归类，别整段塞进当前项
      const absorbed = looksLikeMultiField(text)
        ? await this.absorbFreeText(session, text)
        : [];
      if (absorbed.length > 0) {
        messages.push(
          this.msg(session, 'speech', WizardSpeech.detailExtracted(absorbed)),
        );
        // 提取没覆盖到当前项时，仍把原文留给它，避免这一问白问
        if (!(session.detail ?? {})[current.key]) {
          session.detail = { ...(session.detail ?? {}), [current.key]: text };
        }
      } else {
        session.detail = { ...(session.detail ?? {}), [current.key]: text };
      }
    }

    if (session.revising) {
      // 改写只重答当前这一项，然后直接回总结
      return this.afterDetail(session, messages);
    }

    const next = this.nextDetailIndex(session, session.detailIndex + 1);
    if (next >= 0) {
      session.detailIndex = next;
      this.trace.step(SCOPE, 'S5_detail.next', {
        index: next,
        key: prompts[next].key,
      });
      return this.turn(session, messages, this.detailQuestion(session));
    }
    return this.afterDetail(session, messages);
  }

  private async afterDetail(
    session: Session,
    prefix: WizardMessage[] = [],
  ): Promise<WizardTurn> {
    this.trace.step(SCOPE, 'S5_detail.done', {
      filled_keys: Object.keys(session.detail ?? {}),
    });
    return this.toSummary(session, 'S5', prefix);
  }

  /**
   * 收尾：交给 WizardService 出 Phase1Result（与 CLI 同一收口）。
   *
   * `nextAction=preview` 时不再只给一句「请点结果卡按钮」，而是同一回合直接
   * 串 P2（§8.7）：CTA 即生成，按钮退化为「重跑」。
   */
  private async finish(
    session: Session,
    nextAction: WizardNextAction,
    stage: Phase1Result['stage'],
  ): Promise<WizardTurn> {
    // 优先用 rawBrief（structureBrief 改写前原文），再扫 brief/summary。
    const sourceBrief = [
      session.rawBrief ?? '',
      session.brief ?? '',
      session.summary?.business_brief ?? '',
    ].join('\n');
    const signals = this.wizard.inferP3cSignals(sourceBrief);
    const result = this.wizard.buildPhase1Result({
      clientCode: session.ctx.client_code,
      requestId: session.ctx.request_id,
      channel: session.ctx.tenant.channels[0] ?? 'wecom',
      stage,
      industryId: session.industryId ?? 'general',
      goalIds: session.goalIds,
      summary: session.summary!,
      detail: session.detail,
      nextAction,
      sourceBrief,
      needsLongTermMemory: signals.needs_long_term_memory,
      needsSkillEvolution: signals.needs_skill_evolution,
    });
    session.result = result;
    session.nextAction = nextAction;
    session.revising = false;
    session.stage = 'DONE';

    this.trace.step(SCOPE, 'done', {
      stage: result.stage,
      gate: result.gate,
      next_action: result.next_action,
      scene_id: result.triage.scene_id,
      can_generate_v0: result.triage.can_generate_v0,
      missing_slots: result.triage.missing_slots,
    });

    if (nextAction !== 'preview') {
      return this.turn(session, [
        this.msg(session, 'notice', WizardSpeech.doneHint()),
      ]);
    }
    return this.enterMatch(session, [
      this.msg(session, 'notice', WizardSpeech.previewHandoff()),
    ]);
  }

  /**
   * [P2] 进入 P2：同一回合跑完 Match + 产物，把 match 卡随回包一起给前端。
   *
   * gate ≠ PASS 时先补一句人话再跑，避免用户看到静默白跑（§8.2③）。
   * 任何异常都降级成一条 notice：P1 的成果不能因为 P2 失败而丢。
   */
  private async enterMatch(
    session: Session,
    prefix: WizardMessage[],
  ): Promise<WizardTurn> {
    const messages = [...prefix];
    const gate = session.result?.gate;
    if (gate && gate !== 'PASS') {
      messages.push(
        this.msg(session, 'notice', WizardSpeech.matchGateNotice(gate)),
      );
    }
    try {
      const preview = await this.runMatch(session);
      return this.turn(session, messages, undefined, preview);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.trace.step(SCOPE, 'preview.failed', { error: msg });
      messages.push(
        this.msg(session, 'notice', WizardSpeech.matchFailed()),
      );
      return this.turn(session, messages);
    }
  }

  /**
   * DONE / S6_REVISE 的改写入口。
   * - 有模型：自由文本 → revisePatch → 写回槽位 → toSummary
   * - 无模型 / 解析不出：S6_REVISE 单选「要改哪一项」→ 跳回对应步骤
   */
  private async stepRevise(
    session: Session,
    raw: string,
    body: AnswerBody,
  ): Promise<WizardTurn> {
    // S6_REVISE：用户点了「要改哪一项」
    if (session.stage === 'S6_REVISE') {
      const picked = (body.values ?? [])[0] || raw.trim();
      if (!picked) {
        return this.turn(
          session,
          [this.msg(session, 'notice', '请选择要修改的一项。')],
          this.reviseQuestion(session),
        );
      }
      return this.jumpToReviseTarget(session, picked);
    }

    // DONE：CTA 快捷操作仍可用（「先看看效果」/「继续补充细节」）
    const cta = parseNextAction(raw);
    if (cta === 'preview') {
      // §8.7：CTA 即生成。已在 DONE 无需重新 finish，直接跑 P2 并推卡
      this.trace.step(SCOPE, 'revise.preview_cta', { stage: session.stage });
      return this.enterMatch(session, [
        this.msg(session, 'notice', WizardSpeech.previewHandoff()),
      ]);
    }

    // 「补充信息」意图：带正文就先提取，不能把用户写的内容丢掉
    const intent = parseDetailIntent(raw);
    if (intent && !session.detail) {
      return this.startDetail(session, intent.body);
    }
    if (intent && intent.body) {
      // 细补过一轮后又粘一段：直接归类到还空着的字段
      const absorbed = await this.absorbFreeText(session, intent.body);
      if (absorbed.length > 0) {
        session.revising = true;
        return this.toSummary(session, 'S5', [
          this.msg(session, 'speech', WizardSpeech.detailExtracted(absorbed)),
        ]);
      }
    }

    const text = raw.trim();
    if (!text) {
      return this.turn(session, [
        this.msg(session, 'notice', WizardSpeech.doneHint()),
      ]);
    }

    // 尝试模型抽取补丁
    const industries = this.catalogs.optionsFor('industries');
    const goals = this.catalogs.optionsFor('business_goals');
    const patch = await this.llm.revisePatch(text, {
      industryId: session.industryId,
      industryName: session.industryName,
      goalIds: session.goalIds,
      brief: session.brief,
      detail: session.detail,
      industries,
      goals,
    });

    if (patch && this.applyRevisePatch(session, patch, industries, goals)) {
      this.trace.step(SCOPE, 'revise.patch_applied', {
        keys: Object.keys(patch).filter(
          (k) => (patch as Record<string, unknown>)[k] != null,
        ),
      });
      session.revising = true;
      return this.toSummary(session, session.detail ? 'S5' : 'S4');
    }

    // 兜底：让用户点选要改哪一项（纯规则路径）
    this.trace.step(SCOPE, 'revise.fallback_pick', {
      llm: this.llm.isActive(),
      raw_chars: text.length,
    });
    session.stage = 'S6_REVISE';
    return this.turn(
      session,
      [
        this.msg(session, 'speech', WizardSpeech.revisePickIntro()),
      ],
      this.reviseQuestion(session),
    );
  }

  /** 把 patch 写回 session；至少一个字段成功写回才返回 true。 */
  private applyRevisePatch(
    session: Session,
    patch: {
      industry_id?: string;
      goal_ids?: string[];
      business_brief?: string;
      detail?: Partial<WizardDetailSupplement>;
    },
    industries: CatalogOption[],
    goals: CatalogOption[],
  ): boolean {
    let applied = false;
    if (patch.industry_id && industries.some((o) => o.id === patch.industry_id)) {
      session.industryId = patch.industry_id;
      session.industryName =
        industries.find((o) => o.id === patch.industry_id)?.name ??
        patch.industry_id;
      applied = true;
    }
    if (patch.goal_ids && patch.goal_ids.length > 0) {
      const valid = patch.goal_ids.filter((id) => goals.some((o) => o.id === id));
      if (valid.length > 0) {
        session.goalIds = valid;
        applied = true;
      }
    }
    if (typeof patch.business_brief === 'string' && patch.business_brief.trim()) {
      session.brief = patch.business_brief.trim();
      session.rawBrief = session.brief;
      applied = true;
    }
    if (patch.detail && typeof patch.detail === 'object') {
      const next: WizardDetailSupplement = { ...(session.detail ?? {}) };
      let detailHit = false;
      for (const key of COLLECT_DETAIL_KEYS) {
        const v = patch.detail[key];
        if (typeof v === 'string' && v.trim()) {
          next[key] = v.trim();
          detailHit = true;
        }
      }
      if (detailHit) {
        session.detail = next;
        applied = true;
      }
    }
    return applied;
  }

  /** 从 S6_REVISE 跳回对应步骤重问。 */
  private async jumpToReviseTarget(
    session: Session,
    target: string,
  ): Promise<WizardTurn> {
    session.revising = true;
    this.trace.step(SCOPE, 'revise.jump', { target });

    if (target === 'industry') {
      session.stage = 'S1_INDUSTRY';
      return this.turn(
        session,
        [this.msg(session, 'speech', '好的，我们重新选一下行业。')],
        this.industryQuestion(),
      );
    }
    if (target === 'business_goals') {
      session.stage = 'S2_GOALS';
      return this.turn(
        session,
        [this.msg(session, 'speech', '好的，我们重新选一下业务目标。')],
        this.goalsQuestion(),
      );
    }
    if (target === 'business_brief') {
      session.stage = 'S3_BRIEF';
      return this.turn(
        session,
        [this.msg(session, 'speech', '好的，再简单说说主要业务吧。')],
        this.briefQuestion(session),
      );
    }
    if (target.startsWith('detail.')) {
      const key = target.slice('detail.'.length) as keyof WizardDetailSupplement;
      if (!COLLECT_DETAIL_KEYS.includes(key)) {
        return this.turn(
          session,
          [this.msg(session, 'notice', '不认识这项，请重新选择。')],
          this.reviseQuestion(session),
        );
      }
      if (!session.summary) {
        // 极端：没有 summary 时不能进细补，退回行业
        session.stage = 'S1_INDUSTRY';
        return this.turn(
          session,
          [this.msg(session, 'notice', '请先完成基本信息收集。')],
          this.industryQuestion(),
        );
      }
      if (!session.detailPrompts) {
        session.detailPrompts = await this.llm.detailPrompts(session.summary);
      }
      session.detail = session.detail ?? {};
      session.detailIndex = session.detailPrompts.findIndex((p) => p.key === key);
      if (session.detailIndex < 0) session.detailIndex = 0;
      session.stage = 'S5_DETAIL';
      return this.turn(
        session,
        [this.msg(session, 'speech', `好的，我们重新补充「${WizardSpeech.collectMeta(target).label}」。`)],
        this.detailQuestion(session),
      );
    }

    return this.turn(
      session,
      [this.msg(session, 'notice', '请选择要修改的一项。')],
      this.reviseQuestion(session),
    );
  }

  /**
   * 写完一槽后的分流：改写回流直接回总结；否则推进到下一阶段。
   */
  private async advanceAfter(
    session: Session,
    messages: WizardMessage[],
    nextStage: WizardStage,
    nextQuestion: () => WizardQuestion,
  ): Promise<WizardTurn> {
    if (session.revising) {
      return this.toSummary(session, session.detail ? 'S5' : 'S4');
    }
    session.stage = nextStage;
    return this.turn(session, messages, nextQuestion());
  }

  // ==========================================================================
  // 问题组装（选项一律来自词表）
  // ==========================================================================

  private industryQuestion(): WizardQuestion {
    const groups = this.catalogs.industriesGrouped();
    return {
      stage: 'S1_INDUSTRY',
      key: 'industry',
      title: '你所在的行业是什么？',
      hint: '先选一个最接近的，我会据此判断 Agent 更适合解决哪些问题。都不匹配可以直接描述。',
      input: 'single',
      options_from: 'industries',
      options: this.catalogs.optionsFor('industries'),
      groups,
      allow_free: true,
      quick_replies: [{ label: '都不匹配，我直接描述', value: FREE_INDUSTRY }],
    };
  }

  private goalsQuestion(): WizardQuestion {
    return {
      stage: 'S2_GOALS',
      key: 'business_goals',
      title: '你更希望 AI 先帮你处理哪类业务？',
      hint: '这会决定 Agent 更偏接待、转化、答疑还是转人工分流。可多选。',
      input: 'multi',
      options_from: 'business_goals',
      options: this.catalogs.optionsFor('business_goals'),
      skippable: true,
      quick_replies: [{ label: '暂时跳过', value: '跳过' }],
    };
  }

  private briefQuestion(session: Session): WizardQuestion {
    return {
      stage: 'S3_BRIEF',
      key: 'business_brief',
      title: '简单说说你主要卖什么、或者主要做什么业务。',
      hint: '可以按「主要业务：…… / 服务对象：……」来写；也可以留空跳过。',
      input: 'text',
      skippable: true,
      examples: this.briefExamples(session),
      // 兜底底稿先给上（离线 / 接口失败也能填）；点按钮时再走 LLM 现场生成
      template_text: WizardSpeech.briefTemplate(session.industryName),
      template_on_demand: true,
      quick_replies: [{ label: '跳过', value: '跳过' }],
    };
  }

  private ctaQuestion(stage: WizardStage): WizardQuestion {
    const options: CatalogOption[] = [
      {
        id: 'preview',
        name: '🚀 先看看效果',
        description: '按当前信息进入模板匹配 / 预览',
      },
      {
        id: 'continue_detail',
        name: '📝 继续补充细节',
        description:
          stage === 'S5_CTA'
            ? '细节已补充过，选此项将直接完成'
            : '补主打产品、客户、禁止事项、转人工规则',
      },
    ];
    return {
      stage,
      key: 'next_action',
      title: '接下来你想怎么继续？',
      input: 'single',
      options,
    };
  }

  private detailQuestion(session: Session): WizardQuestion {
    const p = (session.detailPrompts ?? [])[session.detailIndex];
    return {
      stage: 'S5_DETAIL',
      key: `detail.${p.key}`,
      title: p.title,
      hint: p.hint,
      input: 'text',
      skippable: true,
      examples: p.example_reply ? [p.example_reply] : undefined,
      template_text:
        p.example_reply ??
        WizardSpeech.detailTemplate(p.key, session.industryName),
      quick_replies: [
        { label: '跳过这项', value: '跳过' },
        { label: '够了，直接生成', value: '生成' },
      ],
    };
  }

  /** DONE 后改写兜底：单选「要改哪一项」。 */
  private reviseQuestion(session: Session): WizardQuestion {
    const options: CatalogOption[] = [
      { id: 'industry', name: '行业', description: session.industryName },
      {
        id: 'business_goals',
        name: '业务目标',
        description:
          session.goalIds.length > 0
            ? session.goalIds
                .map((id) => this.catalogs.businessGoalById(id)?.name ?? id)
                .join('、')
            : '（未选）',
      },
      {
        id: 'business_brief',
        name: '业务简述',
        description: session.brief?.trim() || '（未填）',
      },
    ];
    for (const key of COLLECT_DETAIL_KEYS) {
      const meta = WizardSpeech.collectMeta(`detail.${key}`);
      const promptTitle = session.detailPrompts?.find((p) => p.key === key)?.title;
      options.push({
        id: `detail.${key}`,
        name: promptTitle || meta.label,
        description: session.detail?.[key]?.trim() || '（未填）',
      });
    }
    return {
      stage: 'S6_REVISE',
      key: 'revise_target',
      title: '想改哪一项？',
      hint: '选一项后我会重新问你；回答完会更新总结。',
      input: 'single',
      options,
    };
  }

  /** 当前阶段应展示的问题（快照/重放用）。 */
  private async questionFor(
    session: Session,
  ): Promise<WizardQuestion | undefined> {
    switch (session.stage) {
      case 'S1_INDUSTRY':
        return this.industryQuestion();
      case 'S1_INDUSTRY_FREE':
        return {
          stage: 'S1_INDUSTRY_FREE',
          key: 'industry',
          title: '你们具体是做什么业务的？',
          input: 'text',
        };
      case 'S2_GOALS':
        return this.goalsQuestion();
      case 'S3_BRIEF':
        return this.briefQuestion(session);
      case 'S4_CTA':
      case 'S5_CTA':
        return this.ctaQuestion(session.stage);
      case 'S5_DETAIL':
        return this.detailQuestion(session);
      case 'S6_REVISE':
        return this.reviseQuestion(session);
      default:
        return undefined;
    }
  }

  /** 描述示例取自行业下场景的 typical_prompts（没有行业就全局取前几条）。 */
  private briefExamples(session: Session): string[] {
    const scenes = this.catalogs.get().scenes;
    const pool = session.industryId
      ? scenes.filter((s) => (s.industries ?? []).includes(session.industryId!))
      : scenes;
    return (pool.length > 0 ? pool : scenes)
      .flatMap((s) => s.typical_prompts ?? [])
      .slice(0, 3);
  }

  // ==========================================================================
  // 组包与基础设施
  // ==========================================================================

  /**
   * 在独立流程作用域内执行一回合：
   * 每请求一份 FlowState（序号 / 计时 / token 记账 / 事件采集互不干扰）。
   */
  private withFlow<T>(
    session: Session | undefined,
    fn: (state: FlowState) => Promise<T>,
  ): Promise<T> {
    const requestId = randomUUID();
    return this.flows.run(
      {
        flow: 'web',
        request_id: requestId,
        collectEvents: true,
        preferLlm: session?.llm,
        preferModel: session?.model,
      },
      async (state) => {
        // 本回合起点单独记：banner() 会重置 state.sectionStart，不能拿它算总耗时
        this.turnStart.set(state, Date.now());
        this.trace.setFlow('web', requestId);
        if (session) this.trace.bind({ session_id: session.id, client_code: session.ctx.client_code });
        return fn(state);
      },
    );
  }

  /**
   * @param preview [P2] 本回合顺带跑出的 P2 产物（CTA 直串时非空，见 §8.7）
   */
  private turn(
    session: Session,
    messages: WizardMessage[],
    question?: WizardQuestion,
    preview?: EndToEndResult,
  ): WizardTurn {
    session.updatedAt = Date.now();
    const state = this.flows.current();
    const status: WizardStatus =
      session.stage === 'DONE'
        ? 'done'
        : session.stage === 'ABORTED'
          ? 'aborted'
          : 'asking';
    return {
      session_id: session.id,
      client_code: session.ctx.client_code,
      stage: session.stage,
      status,
      messages,
      question: status === 'asking' ? question : undefined,
      summary: session.summary,
      detail: session.detail,
      next_action: session.nextAction,
      result: session.result,
      preview,
      collect: this.buildCollect(session),
      aborted_at: session.abortedAt,
      runtime: this.runtime(session, state, this.turnStart.get(state) ?? Date.now(), {
        stage: session.stage,
        status,
        ...(preview ? { match_action: preview.match?.action } : {}),
      }),
    };
  }

  /**
   * 信息收集投影：进度 + 清单。
   * 主线 3 项计入分母；细补 5 项标 optional，未进入细补时为 pending。
   */
  private buildCollect(session: Session): WizardCollect {
    const items: WizardCollectItem[] = [];

    const industryDone = !!session.industryId;
    items.push(
      this.collectItem('industry', {
        status: this.itemStatus(session, 'industry', industryDone),
        value: session.industryName,
        optional: false,
      }),
    );

    const goalsDone =
      session.stage !== 'S1_INDUSTRY' &&
      session.stage !== 'S1_INDUSTRY_FREE' &&
      (session.goalIds.length > 0 || this.stagePast(session, 'S2_GOALS'));
    const goalsValue =
      session.goalIds.length > 0
        ? session.goalIds
            .map((id) => this.catalogs.businessGoalById(id)?.name ?? id)
            .join('、')
        : this.stagePast(session, 'S2_GOALS')
          ? '（已跳过）'
          : undefined;
    items.push(
      this.collectItem('business_goals', {
        status: this.itemStatus(
          session,
          'business_goals',
          goalsDone,
          session.goalIds.length === 0 && this.stagePast(session, 'S2_GOALS'),
        ),
        value: goalsValue,
        optional: false,
      }),
    );

    const briefPast = this.stagePast(session, 'S3_BRIEF');
    const briefDone = briefPast || !!session.brief;
    items.push(
      this.collectItem('business_brief', {
        status: this.itemStatus(
          session,
          'business_brief',
          briefDone,
          briefPast && !session.brief,
        ),
        value: session.brief?.trim() || (briefPast ? '（已跳过）' : undefined),
        optional: false,
      }),
    );

    for (const key of COLLECT_DETAIL_KEYS) {
      const fullKey = `detail.${key}`;
      const filled = !!session.detail?.[key]?.trim();
      const enteredDetail =
        session.stage === 'S5_DETAIL' ||
        session.stage === 'S5_CTA' ||
        !!session.detailPrompts;
      const currentDetail =
        session.stage === 'S5_DETAIL' &&
        (session.detailPrompts ?? [])[session.detailIndex]?.key === key;

      let status: WizardCollectItem['status'] = 'pending';
      if (filled) status = 'done';
      else if (currentDetail) status = 'current';
      else if (enteredDetail && this.detailIndexPast(session, key))
        status = 'skipped';

      const prompt = session.detailPrompts?.find((p) => p.key === key);
      const meta = WizardSpeech.collectMeta(fullKey);
      items.push({
        key: fullKey,
        label: prompt?.title || meta.label,
        why: prompt?.hint || meta.why,
        status,
        value: session.detail?.[key]?.trim() || undefined,
        optional: true,
      });
    }

    const core = items.filter((i) => !i.optional);
    const total = core.length;
    const doneCount = core.filter(
      (i) => i.status === 'done' || i.status === 'skipped',
    ).length;
    const current = items.find((i) => i.status === 'current');
    // 进行中：step = 已完成数（当前项不算）；全部完成：step = total
    const allCoreDone = doneCount >= total;
    const step = allCoreDone ? total : doneCount;

    return {
      step,
      total,
      current_key: current?.key,
      items,
    };
  }

  private collectItem(
    key: string,
    opts: {
      status: WizardCollectItem['status'];
      value?: string;
      optional: boolean;
    },
  ): WizardCollectItem {
    const meta = WizardSpeech.collectMeta(key);
    return {
      key,
      label: meta.label,
      why: meta.why,
      status: opts.status,
      value: opts.value,
      optional: opts.optional,
    };
  }

  /** 该槽是否已走过（stage 排在它之后，或已到 CTA/DONE）。 */
  private stagePast(session: Session, stage: WizardStage): boolean {
    const order: WizardStage[] = [
      'S1_INDUSTRY',
      'S1_INDUSTRY_FREE',
      'S2_GOALS',
      'S3_BRIEF',
      'S4_CTA',
      'S5_DETAIL',
      'S5_CTA',
      'S6_REVISE',
      'DONE',
    ];
    const cur = order.indexOf(session.stage);
    const target = order.indexOf(stage);
    if (cur < 0 || target < 0) return false;
    // S1_INDUSTRY_FREE 与 S1 同档；S2 之后才算 goals 走过
    if (stage === 'S2_GOALS') {
      return (
        cur > order.indexOf('S2_GOALS') ||
        session.stage === 'S3_BRIEF' ||
        cur >= order.indexOf('S3_BRIEF')
      );
    }
    if (stage === 'S3_BRIEF') {
      return cur > order.indexOf('S3_BRIEF');
    }
    return cur > target;
  }

  private itemStatus(
    session: Session,
    key: string,
    done: boolean,
    skipped = false,
  ): WizardCollectItem['status'] {
    const currentKey = this.currentCollectKey(session);
    if (currentKey === key) return 'current';
    if (skipped) return 'skipped';
    if (done) return 'done';
    return 'pending';
  }

  private currentCollectKey(session: Session): string | undefined {
    switch (session.stage) {
      case 'S1_INDUSTRY':
      case 'S1_INDUSTRY_FREE':
        return 'industry';
      case 'S2_GOALS':
        return 'business_goals';
      case 'S3_BRIEF':
        return 'business_brief';
      case 'S5_DETAIL': {
        const p = (session.detailPrompts ?? [])[session.detailIndex];
        return p ? `detail.${p.key}` : undefined;
      }
      default:
        return undefined;
    }
  }

  private detailIndexPast(
    session: Session,
    key: keyof WizardDetailSupplement,
  ): boolean {
    const prompts = session.detailPrompts ?? [];
    const idx = prompts.findIndex((p) => p.key === key);
    if (idx < 0) return false;
    if (session.stage === 'S5_CTA' || session.stage === 'DONE') return true;
    return session.detailIndex > idx;
  }

  /**
   * 本回合运行情况：事件流（与 app.log 同源）+ token 用量。
   * `tokens.summary()` 同时把 kind=summary 落进 `logs/token.log`。
   */
  private runtime(
    session: Session,
    state: FlowState,
    startedAt: number,
    extra: Record<string, unknown>,
  ): WizardRuntime {
    const token = this.tokens.summary({
      session_id: session.id,
      ...extra,
    });
    session.tokenSession = mergeToken(session.tokenSession, token);
    return {
      flow: state.flow,
      request_id: state.request_id,
      events: this.flows.events(),
      events_dropped: state.eventsDropped,
      token,
      token_session: session.tokenSession,
      llm: this.llm.isActive(),
      took_ms: Math.max(0, Date.now() - startedAt),
    };
  }

  private msg(
    session: Session,
    kind: WizardMessage['kind'],
    content: string,
    byLlm = false,
  ): WizardMessage {
    session.msgSeq += 1;
    return {
      id: `${session.id.slice(0, 8)}-${session.msgSeq}`,
      role: 'assistant',
      kind,
      content,
      by_llm: byLlm || undefined,
    };
  }

  private rawOf(body: AnswerBody): string {
    const text = (body.text ?? '').trim();
    if (text) return text;
    const values = body.values ?? [];
    return values.length > 0 ? values.join(',') : '';
  }

  /**
   * 点选项时把词表 id 翻成人可读名称，供 `answer.received` 摘要用。
   * 日志里 `values:["beauty_medical"]` 还得回查词表，`value_labels:["医美"]` 一眼就懂。
   * @returns 名称数组；未点选项时 undefined（打点里该字段直接消失）
   */
  private pickedLabels(
    session: Session,
    values: string[] | undefined,
  ): string[] | undefined {
    if (!values?.length) return undefined;
    const byStage = (id: string): string => {
      switch (session.stage) {
        case 'S1_INDUSTRY':
        case 'S1_INDUSTRY_FREE':
          if (id === FREE_INDUSTRY) return '都不匹配，直接描述';
          return (
            this.catalogs.optionsFor('industries').find((o) => o.id === id)
              ?.name ?? id
          );
        case 'S2_GOALS':
          return this.catalogs.businessGoalById(id)?.name ?? id;
        case 'S4_CTA':
        case 'S5_CTA':
          if (id === 'preview') return '先看看效果';
          if (id === 'continue_detail') return '继续补充细节';
          return id;
        case 'S6_REVISE':
          // 改写目标：主线三项写死（与 reviseQuestion 一致），细补五项查话术表
          if (id === 'industry') return '行业';
          if (id === 'business_goals') return '业务目标';
          if (id === 'business_brief') return '业务简述';
          return id.startsWith('detail.')
            ? WizardSpeech.collectMeta(id).label
            : id;
        default:
          return id;
      }
    };
    return values.map(byStage);
  }

  private wantsFree(picked: string | undefined, raw: string): boolean {
    if (picked === FREE_INDUSTRY) return true;
    const t = raw.trim();
    if (t === FREE_INDUSTRY || t === '0') return true;
    return false;
  }

  private require(sessionId: string): Session {
    const s = this.sessions.get(sessionId);
    if (!s) throw new NotFoundException(`session not found: ${sessionId}`);
    return s;
  }

  /** 过期与超量清理（DEMO 内存态，避免长跑吃内存）。 */
  private sweep(): void {
    const now = Date.now();
    for (const [id, s] of this.sessions) {
      if (now - s.updatedAt > SESSION_TTL_MS) this.sessions.delete(id);
    }
    while (this.sessions.size >= MAX_SESSIONS) {
      const oldest = [...this.sessions.entries()].sort(
        (a, b) => a[1].updatedAt - b[1].updatedAt,
      )[0];
      if (!oldest) break;
      this.sessions.delete(oldest[0]);
    }
  }
}

/** 会话累计 token：把本回合汇总并入历史（by_node / by_model 同步累加）。 */
function mergeToken(
  acc: TokenSummary | null,
  turn: TokenSummary | null,
): TokenSummary | null {
  if (!turn) return acc;
  if (!acc) return { ...turn, by_node: { ...turn.by_node }, by_model: { ...turn.by_model } };
  const merged: TokenSummary = {
    ...acc,
    calls: acc.calls + turn.calls,
    prompt_tokens: acc.prompt_tokens + turn.prompt_tokens,
    completion_tokens: acc.completion_tokens + turn.completion_tokens,
    total_tokens: acc.total_tokens + turn.total_tokens,
    errors: acc.errors + turn.errors,
    usage_missing: acc.usage_missing + turn.usage_missing,
    llm_ms: acc.llm_ms + turn.llm_ms,
    by_node: { ...acc.by_node },
    by_model: { ...acc.by_model },
  };
  for (const [k, v] of Object.entries(turn.by_node)) {
    const cur = merged.by_node[k];
    merged.by_node[k] = cur
      ? {
          calls: cur.calls + v.calls,
          prompt_tokens: cur.prompt_tokens + v.prompt_tokens,
          completion_tokens: cur.completion_tokens + v.completion_tokens,
          total_tokens: cur.total_tokens + v.total_tokens,
        }
      : { ...v };
  }
  for (const [k, v] of Object.entries(turn.by_model)) {
    const cur = merged.by_model[k];
    merged.by_model[k] = cur
      ? {
          calls: cur.calls + v.calls,
          prompt_tokens: cur.prompt_tokens + v.prompt_tokens,
          completion_tokens: cur.completion_tokens + v.completion_tokens,
          total_tokens: cur.total_tokens + v.total_tokens,
        }
      : { ...v };
  }
  return merged;
}
