/**
 * [P1] P1WizardCommand — 交互式向导（对齐竞品 3chat + LLM 接待员）
 *
 * 文档：docs/产品设计.md §6 · docs/P1-拆分.md §5 · docs/工程架构.md §8
 *
 * ```text
 * S0 开场 → S1 行业 → S2 业务目标 → S3 业务简述
 *        → S4 总结（可 LLM 润色）+ CTA
 *              ├─ 先看看效果 → next_action=preview
 *              └─ 继续补充细节 → S5（可 LLM 出题）→ 更新总结
 * ```
 *
 * 有 DASHSCOPE_API_KEY 时默认启用接待员 LLM；`--no-llm` 强制纯规则。
 *
 * ```bash
 * npm run cli -- p1-wizard --client-code acme_beauty
 * npm run cli -- p1-wizard --client-code acme_beauty --no-llm
 * ```
 */

import { Command, CommandRunner, Option } from 'nest-commander';
import * as readline from 'readline';
import { CatalogsService } from '../catalogs/catalogs.service';
import { charCount, digestText } from '../common/input-digest';
import { LogService } from '../common/log.service';
import { buildRequestContext } from '../common/request-context';
import { TokenUsageService } from '../common/token-usage.service';
import { TraceService, peekTraceFromArgv } from '../common/trace.service';
import {
  Phase1Result,
  WizardDetailSupplement,
  WizardNextAction,
  WizardSummary,
} from '../common/types';
import { TenantService } from '../tenant/tenant.service';
import {
  isGenerate,
  isQuit,
  isSkip,
  looksLikeMultiField,
  matchOption,
  norm,
  parseMulti,
  parseNextAction,
} from './wizard-input';
import { WizardLlmReceptionist } from './wizard-llm-receptionist.service';
import { WizardSpeech } from './wizard-speech';
import { WizardService } from './wizard.service';

interface WizardOptions {
  clientCode?: string;
  tenant?: string;
  trace?: boolean;
  noLlm?: boolean;
}

@Command({
  name: 'p1-wizard',
  aliases: ['wizard'],
  description:
    '[P1] Wizard UX + optional LLM receptionist (use --no-llm to force rules)',
})
export class P1WizardCommand extends CommandRunner {
  private rl!: readline.Interface;
  private lineQueue: string[] = [];
  private pending: ((s: string) => void) | null = null;
  private closed = false;

  constructor(
    private readonly tenants: TenantService,
    private readonly catalogs: CatalogsService,
    private readonly wizard: WizardService,
    private readonly llm: WizardLlmReceptionist,
    private readonly trace: TraceService,
    private readonly log: LogService,
    private readonly tokens: TokenUsageService,
  ) {
    super();
  }

  async run(_passed: string[], options: WizardOptions): Promise<void> {
    // 向导是交互界面：默认静默 stderr 打点以免刷屏，除非显式 --trace / --verbose。
    // 文件日志不受影响（LOG_FILE），因此事后仍有完整流水可查。
    const fromArgv = peekTraceFromArgv();
    if (fromArgv !== null) {
      this.trace.applyEnv(fromArgv);
    } else if (!options.trace) {
      this.trace.setEnabled(false);
    }

    const noLlm =
      Boolean(options.noLlm) ||
      process.argv.includes('--no-llm') ||
      process.env.WIZARD_NO_LLM === '1';
    this.llm.setPreferLlm(!noLlm);

    const clientCode = (options.clientCode ?? '').trim();
    if (!clientCode) {
      process.stderr.write('缺少必填 --client-code\n');
      process.exitCode = 1;
      return;
    }

    const tenant = this.tenants.resolve(clientCode, options.tenant);
    const ctx = buildRequestContext(tenant);

    // 本次向导为一个 flow，后续所有 step 都带 flow/req 前缀
    this.trace.setFlow('p1-wizard', ctx.request_id);
    this.trace.banner('P1 wizard');
    this.trace.step('P1.Wizard', 'start', {
      client_code: ctx.client_code,
      llm: this.llm.isActive() ? 'on' : 'off',
      no_llm: noLlm,
      channels: ctx.tenant.channels,
    });

    this.initIO();

    try {
      this.line(WizardSpeech.welcome());
      this.line(
        `（租户 client_code=${ctx.client_code}；LLM 接待员=${this.llm.isActive() ? '开' : '关'}；随时 q 退出）\n`,
      );

      this.trace.step('P1.Wizard', 'S1_industry.ask');
      const industryId = await this.askIndustry();
      if (!industryId) return this.abort('S1_INDUSTRY');
      const industryName =
        this.catalogs.optionsFor('industries').find((o) => o.id === industryId)
          ?.name ?? industryId;
      this.trace.step('P1.Wizard', 'S1_industry.done', {
        industry_id: industryId,
        industry_name: industryName,
      });
      this.line(`\n${await this.llm.echoIndustry(industryName)}\n`);

      const goalIds = await this.askBusinessGoals();
      if (goalIds === null) return this.abort('S2_GOALS');
      const goalNames = goalIds
        .map((id) => this.catalogs.businessGoalById(id)?.name ?? id)
        .filter(Boolean);
      this.trace.step('P1.Wizard', 'S2_goals.done', {
        goal_ids: goalIds,
        goal_names: goalNames,
      });
      this.line(`\n${await this.llm.echoGoals(goalNames)}\n`);

      let brief = await this.askBusinessBrief();
      if (brief === null) return this.abort('S3_BRIEF');
      const briefRawChars = brief.trim().length;
      if (brief.trim()) {
        brief = await this.llm.structureBrief(brief, industryName);
      }
      this.trace.step('P1.Wizard', 'S3_brief.done', {
        raw_chars: briefRawChars,
        structured_chars: brief.trim().length,
        structured_by_llm: briefRawChars > 0 && this.llm.isActive(),
      });

      let summary = this.wizard.buildSummary({
        industryId,
        goalIds,
        businessBrief: brief || undefined,
      });
      summary = await this.llm.polishSummary(summary);
      let detail: WizardDetailSupplement | undefined;

      this.printSummary(summary);
      this.line(WizardSpeech.summaryCta());

      const action = await this.askNextAction();
      if (action === null) return this.abort('S4_SUMMARY_CTA');
      this.trace.step('P1.Wizard', 'S4_summary.done', {
        cta: action,
        polished_by_llm: this.llm.isActive(),
        role_positioning: summary.role_positioning,
      });

      let next_action: WizardNextAction = action;
      let stage: Phase1Result['stage'] = 'S1_SUMMARY';

      if (action === 'continue_detail') {
        const filled = await this.runDetail(summary);
        if (filled === null) return this.abort('S5_DETAIL');
        detail = filled;
        this.trace.step('P1.Wizard', 'S5_detail.done', {
          filled_keys: Object.keys(filled),
          prompts_by_llm: this.llm.isActive(),
        });
        summary = this.wizard.mergeDetailIntoSummary(summary, filled);
        summary = await this.llm.polishSummary(summary);
        this.line(`\n${WizardSpeech.afterDetail()}\n`);
        this.printSummary(summary);
        this.line(WizardSpeech.summaryCta());
        const again = await this.askNextAction();
        if (again === null) return this.abort('S5_SUMMARY_CTA');
        next_action = again === 'continue_detail' ? 'done' : again;
        stage = 'S1_DETAIL';
        if (next_action === 'preview') {
          this.line(`\n${WizardSpeech.previewHandoff()}`);
        }
      } else {
        this.line(`\n${WizardSpeech.previewHandoff()}`);
      }

      // 收口交给 WizardService：与 Web 层同一套 triage/闸门/回包逻辑
      const out = this.wizard.buildPhase1Result({
        clientCode: ctx.client_code,
        requestId: ctx.request_id,
        channel: ctx.tenant.channels[0] ?? 'wecom',
        stage,
        industryId,
        goalIds,
        summary,
        detail,
        nextAction: next_action,
      });

      this.trace.step('P1.Wizard', 'done', {
        stage: out.stage,
        gate: out.gate,
        next_action: out.next_action,
        scene_id: out.triage.scene_id,
        can_generate_v0: out.triage.can_generate_v0,
        missing_slots: out.triage.missing_slots,
      });

      this.line(
        `\n✅ [P1] stage=${out.stage} gate=${out.gate} next_action=${out.next_action} scene=${out.triage.scene_id || '-'} llm=${this.llm.isActive() ? 'on' : 'off'}`,
      );
      this.reportTokens({ stage: out.stage, gate: out.gate });
      this.emitPhase1(out);
    } finally {
      this.rl.close();
    }
  }

  /**
   * 用户中途 q 退出：留下退出位置，并照样结算 token 总量（半途也已花钱）。
   */
  private abort(at: string): void {
    this.trace.step('P1.Wizard', 'aborted', { at });
    this.reportTokens({ aborted_at: at });
  }

  /**
   * 向导结束（正常或中断）后的 token 使用总量：
   * 落 `logs/token.log`（kind=summary）+ 累计台账，并打两行人可读汇总到 stderr
   * （本次 / 工程累计）。`--no-llm` 时无任何调用，summary() 返回 null，不打印噪声。
   */
  private reportTokens(extra?: Record<string, unknown>): void {
    const snap = this.tokens.summary(extra);
    if (!snap) return;
    process.stderr.write(`\n${this.tokens.formatOneLine(snap)}\n`);
    process.stderr.write(`${this.tokens.formatAllTime()}\n`);
    process.stderr.write(
      `         明细见 ${this.log.logDir()}/token.log（request_id=${snap.request_id}）\n`,
    );
  }

  private printSummary(summary: WizardSummary): void {
    this.line(`\n${WizardSpeech.summaryIntro()}`);
    this.line(WizardSpeech.formatSummary(summary));
  }

  private async askIndustry(): Promise<string | null> {
    this.line(`\n${WizardSpeech.askIndustry()}\n`);
    const wizard = this.catalogs.wizardIndustryOptions();
    const all = this.catalogs.optionsFor('industries');
    wizard.forEach((o, i) => {
      const desc = o.description ? ` — ${o.description}` : '';
      this.line(`  ${i + 1}. ${o.name}${desc}`);
    });
    this.line('  0. 其他 → 直接描述');

    while (true) {
      const ans = await this.ask('> ', 'S1_INDUSTRY');
      const n = norm(ans);
      if (isQuit(ans)) return null;
      if (n === '0') {
        const free = await this.ask('  请直接描述行业：', 'S1_INDUSTRY_FREE');
        if (!free.trim()) continue;
        const hit = matchOption(all, free);
        if (hit) return hit;
        const normalized = await this.llm.normalizeIndustry(free, all);
        return normalized ?? 'general';
      }
      const num = Number(n);
      if (Number.isInteger(num) && num >= 1 && num <= wizard.length) {
        return wizard[num - 1].id;
      }
      const hit = matchOption(all, ans);
      if (hit) return hit;
      if (ans.trim()) {
        const normalized = await this.llm.normalizeIndustry(ans, all);
        return normalized ?? 'general';
      }
      this.line('  （请输入选项序号）');
    }
  }

  private async askBusinessGoals(): Promise<string[] | null> {
    this.line(`\n${WizardSpeech.askBusinessGoals()}\n`);
    const opts = this.catalogs.optionsFor('business_goals');
    opts.forEach((o, i) => {
      const desc = o.description ? ` — ${o.description}` : '';
      this.line(`  ${i + 1}. ${o.name}${desc}`);
    });
    this.line('  （可多选，用逗号分隔，如 1,3,5；至少选 1 项更佳）');

    while (true) {
      const ans = await this.ask('> ', 'S2_GOALS');
      if (isQuit(ans)) return null;
      if (isSkip(ans)) return [];
      const ids = parseMulti(opts, ans);
      if (ids.length > 0) return ids;
      this.line('  （请至少选择一项，或输入序号）');
    }
  }

  private async askBusinessBrief(): Promise<string | null> {
    this.line(`\n${WizardSpeech.askBusinessBrief()}`);
    const ans = await this.ask('> ', 'S3_BRIEF');
    if (isQuit(ans)) return null;
    if (isSkip(ans)) return '';
    return ans.trim();
  }

  private async askNextAction(): Promise<WizardNextAction | null> {
    while (true) {
      const ans = await this.ask('> ', 'CTA');
      if (isQuit(ans)) return null;
      const action = parseNextAction(ans);
      if (action) return action;
      this.line('  （请输入 1=先看看效果，或 2=继续补充细节）');
    }
  }

  private async runDetail(
    summary: WizardSummary,
  ): Promise<WizardDetailSupplement | null> {
    this.line(`\n${WizardSpeech.detailIntro()}\n`);
    const prompts = await this.llm.detailPrompts(summary);
    const detail: WizardDetailSupplement = {};
    for (const p of prompts) {
      // 前一问可能已经把这项一起说了（整段描述被拆到多个字段）
      if (detail[p.key]) continue;
      this.line(`\n### ${p.title}`);
      this.line(p.hint);
      if (p.example_reply) this.line(`  示例：「${p.example_reply}」`);
      const ans = await this.ask('> ', `detail.${p.key}`);
      if (isQuit(ans)) return null;
      if (isGenerate(ans)) break;
      if (isSkip(ans)) continue;

      const text = ans.trim();
      // 一次说了多个字段 → 与 Web 走同一个提取入口，别整段塞进当前项
      const patch = looksLikeMultiField(text)
        ? await this.llm.extractDetail(text, {
            industryName: summary.industry.name,
            detail,
          })
        : null;
      if (patch) {
        const hit: string[] = [];
        for (const [key, value] of Object.entries(patch) as Array<
          [keyof WizardDetailSupplement, string]
        >) {
          if (!value || detail[key]) continue;
          detail[key] = value;
          hit.push(WizardSpeech.collectMeta(`detail.${key}`).label);
        }
        if (hit.length > 0) {
          this.line(`  已归类到：${hit.join('、')}`);
          if (!detail[p.key]) detail[p.key] = text;
          continue;
        }
      }
      detail[p.key] = text;
    }
    return detail;
  }

  private emitPhase1(out: Phase1Result): void {
    this.line('\n[P1] Phase1Result JSON：');
    process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  }

  private initIO(): void {
    this.rl = readline.createInterface({ input: process.stdin });
    this.rl.on('line', (l) => {
      if (this.pending) {
        const r = this.pending;
        this.pending = null;
        r(l);
      } else {
        this.lineQueue.push(l);
      }
    });
    this.rl.on('close', () => {
      this.closed = true;
      if (this.pending) {
        const r = this.pending;
        this.pending = null;
        r('');
      }
    });
  }

  /**
   * 读一行用户输入，并打一条带摘要的 `input.received`。
   * 与 Web 的 `Web.Wizard/answer.received` 对称：日志能读出用户在哪一步说了什么。
   * @param at 阶段标识（S1_INDUSTRY / detail.<key> …），用于日志定位
   */
  private ask(q: string, at?: string): Promise<string> {
    process.stdout.write(q);
    const traced = (line: string): string => {
      this.trace.step('P1.Wizard', 'input.received', {
        at: at ?? '-',
        text_chars: charCount(line),
        text_digest: digestText(line),
      });
      return line;
    };
    if (this.lineQueue.length > 0) {
      return Promise.resolve(traced(this.lineQueue.shift() as string));
    }
    if (this.closed) return Promise.resolve(traced(''));
    return new Promise((resolve) => {
      this.pending = (line) => resolve(traced(line));
    });
  }

  private line(s: string): void {
    process.stdout.write(`${s}\n`);
  }

  @Option({
    flags: '--client-code <code>',
    description: 'SaaS tenant client_code (required)',
  })
  parseClientCode(val: string): string {
    return val;
  }

  @Option({ flags: '--tenant <path>', description: 'Override tenant JSON path' })
  parseTenant(val: string): string {
    return val;
  }

  @Option({ flags: '--trace', description: 'Keep trace logs on (stderr)' })
  parseTrace(): boolean {
    return true;
  }

  @Option({
    flags: '--verbose',
    description: 'Verbose stderr trace including prompt bodies',
  })
  parseVerbose(): boolean {
    return true;
  }

  @Option({
    flags: '--quiet',
    description: 'Silence stderr trace (default for the wizard)',
  })
  parseQuiet(): boolean {
    return true;
  }

  @Option({
    flags: '--no-llm',
    description: 'Force rule-only wizard speech (no Qwen receptionist)',
  })
  parseNoLlm(): boolean {
    return true;
  }
}
