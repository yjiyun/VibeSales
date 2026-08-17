/**
 * [P2] P2MatchCommand — CLI 子命令 `match`（模板匹配入口）
 *
 * 文档：docs/产品设计.md §2（阶段划分）· docs/工程架构.md §10 CLI
 *
 * ## 阶段边界
 * - 本命令属 **P2**：消费 PASS 级 Triage → MatchResult（+ 可选 v0）
 * - `--utterance` 会先跑 **P1** Intent；若闸门非 PASS 则**不进入匹配**
 * - `--triage` 旁路：跳过 Intent，直接 Match（应用 P1 产出的 JSON 做联调）
 *
 * ```bash
 * # P2 旁路（无 Key）：喂 P1 PASS triage
 * npm run cli -- match --client-code acme_edu --triage fixtures/p1/recruit/pass-full.json
 * # 含 P1 意图（需 Key）
 * npm run cli -- match --client-code acme_beauty --utterance fixtures/p1/beauty/utterance-pass.txt
 * ```
 */

import { Logger } from '@nestjs/common';
import { Command, CommandRunner, Option } from 'nest-commander';
import * as fs from 'fs';
import * as path from 'path';
import { CatalogsService } from '../catalogs/catalogs.service';
import { LogService } from '../common/log.service';
import { ProductPhase } from '../common/product-phase';
import { buildRequestContext } from '../common/request-context';
import { TokenUsageService } from '../common/token-usage.service';
import { TraceService, peekTraceFromArgv } from '../common/trace.service';
import { EndToEndResult, Triage } from '../common/types';
import { IntentService } from '../intent/intent.service';
import { MatchService } from '../match/match.service';
import { PreviewService } from '../preview/preview.service';
import { TenantService } from '../tenant/tenant.service';
import { TemplateLoaderService } from '../templates/template-loader.service';
import { WizardService } from '../wizard/wizard.service';

interface MatchOptions {
  clientCode?: string;
  utterance?: string;
  triage?: string;
  tenant?: string;
  listTemplates?: boolean;
}

@Command({
  name: 'match',
  aliases: ['p2-match'],
  description:
    '[P2] Template match (optional P1 intent via --utterance; or --triage bypass)',
})
export class P2MatchCommand extends CommandRunner {
  private readonly logger = new Logger(P2MatchCommand.name);

  constructor(
    private readonly tenants: TenantService,
    private readonly intent: IntentService,
    private readonly match: MatchService,
    private readonly templates: TemplateLoaderService,
    private readonly preview: PreviewService,
    private readonly wizard: WizardService,
    private readonly catalogs: CatalogsService,
    private readonly trace: TraceService,
    private readonly log: LogService,
    private readonly tokens: TokenUsageService,
  ) {
    super();
  }

  async run(_passed: string[], options: MatchOptions): Promise<void> {
    try {
      this.applyTraceFlags();

      if (options.listTemplates) {
        const list = this.templates.list().map((t) => ({
          template_id: t.template_id,
          display_name: t.display_name,
          scene_ids: t.scene_ids,
          channels: t.channels,
        }));
        this.trace.step('P2.CLI', 'list-templates', {
          count: list.length,
          list,
        });
        process.stdout.write(`${JSON.stringify(list, null, 2)}\n`);
        return;
      }

      const clientCode = (options.clientCode ?? '').trim();
      if (!clientCode) {
        this.fail('Missing required --client-code');
        return;
      }

      this.trace.banner('P2 CLI match');
      const cliStarted = Date.now();
      this.trace.step('P2.CLI', 'start', {
        client_code: clientCode,
        path: options.triage ? 'triage_bypass' : 'utterance',
        has_triage: Boolean(options.triage?.trim()),
        has_utterance: Boolean(options.utterance?.trim()),
      });

      const tenant = this.tenants.resolve(clientCode, options.tenant);
      const ctx = buildRequestContext(tenant);
      // 本次匹配为一个 flow（后续所有 step 带 flow/req 前缀）
      this.trace.setFlow('p2-match', ctx.request_id);
      this.trace.step('P2.CLI', 'request_context', {
        client_code: ctx.client_code,
        request_id: ctx.request_id,
        tenant: ctx.tenant,
      });

      const hasTriage = Boolean(options.triage?.trim());
      const hasUtterance = Boolean(options.utterance?.trim());
      if (!hasTriage && !hasUtterance) {
        this.fail('Provide --triage <path|json> or --utterance <text>');
        return;
      }

      let triage: Triage | undefined;
      let utterance: string | undefined;
      let gate: EndToEndResult['gate'];
      let ask_user: string | undefined;

      if (hasTriage) {
        triage = this.loadTriage(options.triage!);
        // 附带填充 P1 向导字段，便于对照；不改变 Match 输入语义
        this.enrichTriageWizard(triage);
        this.trace.step('P2.CLI', 'triage.bypass', triage);
      } else {
        utterance = this.resolveUtterance(options.utterance!);
        this.trace.step('P2.CLI', 'utterance.resolved', { utterance });
        // 先跑 P1 Intent；非 PASS 不进 Match
        const intentResult = await this.intent.recognize(ctx, utterance);
        gate = intentResult.gate;
        ask_user = intentResult.ask_user;
        triage = intentResult.triage;

        if (intentResult.gate === 'ERROR') {
          const out: EndToEndResult = {
            phase: ProductPhase.P2_TEMPLATE_MATCH,
            client_code: ctx.client_code,
            request_id: ctx.request_id,
            utterance,
            gate: 'ERROR',
            error: intentResult.error,
            triage,
          };
          this.trace.step('P2.CLI', 'end', {
            ms: Date.now() - cliStarted,
            gate: 'ERROR',
            stopped_at: 'P1',
          });
          this.reportTokens({ gate: 'ERROR', stopped_at: 'P1' });
          process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
          process.exitCode = 1;
          return;
        }

        if (intentResult.gate === 'ASK' || intentResult.gate === 'CUSTOM') {
          const out: EndToEndResult = {
            phase: ProductPhase.P2_TEMPLATE_MATCH,
            client_code: ctx.client_code,
            request_id: ctx.request_id,
            utterance,
            triage,
            gate: intentResult.gate,
            ask_user,
            // 未进 P2 Match：不伪造 match
          };
          this.trace.step('P2.CLI', 'end', {
            ms: Date.now() - cliStarted,
            gate: intentResult.gate,
            stopped_at: 'P1',
          });
          this.reportTokens({ gate: intentResult.gate, stopped_at: 'P1' });
          process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
          return;
        }
      }

      const matchResult = await this.match.run(ctx, triage!);
      const isHit = matchResult.action === 'hit';
      const v0_preview = isHit
        ? this.preview.build(matchResult.template_id)
        : undefined;
      // custom 也要有产物；CLI 没有 P1 summary，轮廓按 triage 降级拼装
      const custom_outline = isHit
        ? undefined
        : this.preview.buildCustomOutline({
            triage: triage!,
            reject_summary: matchResult.reject_summary,
            why_user: matchResult.why_user,
          });
      const out: EndToEndResult = {
        phase: ProductPhase.P2_TEMPLATE_MATCH,
        client_code: ctx.client_code,
        request_id: ctx.request_id,
        utterance,
        triage,
        gate: gate ?? 'PASS',
        ask_user,
        match: matchResult,
        v0_preview,
        custom_outline,
      };
      this.trace.step('P2.CLI', 'end', {
        ms: Date.now() - cliStarted,
        gate: out.gate,
        match_action: matchResult.action,
        template_id: matchResult.template_id,
      });
      this.reportTokens({
        gate: out.gate,
        match_action: matchResult.action,
        template_id: matchResult.template_id,
      });
      process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.logger.error(msg);
      this.trace.step('P2.CLI', 'fatal', { error: msg });
      this.reportTokens({ fatal: msg });
      process.stderr.write(`${JSON.stringify({ error: msg }, null, 2)}\n`);
      process.exitCode = 1;
    }
  }

  /**
   * 本次命令的 token 使用总量：落 `logs/token.log`（kind=summary）+ 累计台账，
   * 并打两行 stderr 汇总（本次 / 工程累计）。
   * 无 LLM 调用（`--triage` 旁路且 Decide 走规则）时不打印。
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

  @Option({
    flags: '--client-code <code>',
    description: 'SaaS tenant client_code (required)',
  })
  parseClientCode(val: string): string {
    return val;
  }

  @Option({
    flags: '--utterance <text>',
    description: '[P1 then P2] Build-intent NL (or path / @path)',
  })
  parseUtterance(val: string): string {
    return val;
  }

  @Option({
    flags: '--triage <pathOrJson>',
    description: '[P2] Bypass P1 intent: triage JSON (e.g. from P1 fixtures)',
  })
  parseTriage(val: string): string {
    return val;
  }

  @Option({
    flags: '--tenant <path>',
    description: 'Override tenant JSON path',
  })
  parseTenant(val: string): string {
    return val;
  }

  @Option({
    flags: '--list-templates',
    description: 'List loaded template_ids and exit',
  })
  parseListTemplates(): boolean {
    return true;
  }

  @Option({
    flags: '--quiet',
    description: 'Silence stderr trace (file log unaffected)',
  })
  parseQuiet(): boolean {
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
    flags: '--trace-off',
    description: 'Alias of --quiet',
  })
  parseTraceOff(): boolean {
    return true;
  }

  @Option({
    flags: '--trace-full',
    description: 'Alias of --verbose',
  })
  parseTraceFull(): boolean {
    return true;
  }

  private applyTraceFlags(): void {
    const fromArgv = peekTraceFromArgv();
    if (fromArgv !== null) {
      this.trace.applyEnv(fromArgv);
    }
  }

  private fail(msg: string): void {
    this.trace.step('P2.CLI', 'fail', { error: msg });
    process.stderr.write(`${JSON.stringify({ error: msg }, null, 2)}\n`);
    process.exitCode = 1;
  }

  /** 附带填充 P1 向导字段（next_ask 等），与 P1 评估一致。 */
  private enrichTriageWizard(triage: Triage): void {
    triage.known_slots = {
      industry: triage.industry,
      role: triage.agent_family ?? (triage.known_slots as Record<string, unknown> | undefined)?.role,
      ...(triage.known_slots ?? {}),
    };
    const gated = this.wizard.evaluateGate(triage);
    Object.assign(triage, gated.triage);
  }

  private loadTriage(input: string): Triage {
    const trimmed = input.trim();
    let raw: unknown;
    if (trimmed.startsWith('{')) {
      raw = JSON.parse(trimmed);
    } else {
      const p = path.resolve(trimmed);
      raw = JSON.parse(fs.readFileSync(p, 'utf8'));
    }
    // 兼容 Phase1Result 包装：{ triage: {...}, gate, ... }
    const root = raw as Record<string, unknown>;
    const t = (root.triage && typeof root.triage === 'object'
      ? root.triage
      : raw) as Triage;
    if (!t.scene_id) {
      throw new Error('triage JSON must include scene_id');
    }
    return {
      scene_id: String(t.scene_id),
      agent_family: t.agent_family,
      channel: t.channel ?? 'wecom',
      industry: t.industry,
      confidence: Number(t.confidence ?? 1),
      reason: String(t.reason ?? 'fixture triage'),
      known_slots: t.known_slots ?? {},
      missing_slots: t.missing_slots ?? [],
      ask_user: t.ask_user ?? '',
      risk_flags: t.risk_flags ?? [],
    };
  }

  private resolveUtterance(input: string): string {
    const trimmed = input.trim();
    if (trimmed.startsWith('@')) {
      return fs.readFileSync(path.resolve(trimmed.slice(1)), 'utf8').trim();
    }
    const asPath = path.resolve(trimmed);
    if (fs.existsSync(asPath) && fs.statSync(asPath).isFile()) {
      return fs.readFileSync(asPath, 'utf8').trim();
    }
    return trimmed;
  }
}
