/**
 * [P1] P1Command — 非交互评估：喂 triage/slots → Phase1Result
 *
 * 用于 fixtures 回归（美妆顾问 / 招聘助手），无需 API Key、无需 Match。
 *
 * ```bash
 * npm run cli -- p1 --client-code acme_beauty --triage fixtures/p1/beauty/pass-full.json
 * npm run cli -- p1 --client-code acme_edu --slots fixtures/p1/recruit/slots-pass.json
 * npm run test:p1
 * ```
 */

import { Command, CommandRunner, Option } from 'nest-commander';
import * as fs from 'fs';
import * as path from 'path';
import { LogService } from '../common/log.service';
import { ProductPhase } from '../common/product-phase';
import { buildRequestContext } from '../common/request-context';
import { TraceService, peekTraceFromArgv } from '../common/trace.service';
import { Phase1Result, Triage } from '../common/types';
import { TenantService } from '../tenant/tenant.service';
import { WizardService } from '../wizard/wizard.service';

interface P1Options {
  clientCode?: string;
  triage?: string;
  slots?: string;
  tenant?: string;
  expectGate?: string;
}

@Command({
  name: 'p1',
  description:
    '[P1] Evaluate coarse triage/slots → Phase1Result (no match; fixture-friendly)',
})
export class P1Command extends CommandRunner {
  constructor(
    private readonly tenants: TenantService,
    private readonly wizard: WizardService,
    private readonly trace: TraceService,
    private readonly log: LogService,
  ) {
    super();
  }

  async run(_passed: string[], options: P1Options): Promise<void> {
    const fromArgv = peekTraceFromArgv();
    if (fromArgv !== null) this.trace.applyEnv(fromArgv);

    const clientCode = (options.clientCode ?? '').trim();
    if (!clientCode) {
      this.fail('Missing required --client-code');
      return;
    }

    const hasTriage = Boolean(options.triage?.trim());
    const hasSlots = Boolean(options.slots?.trim());
    if (!hasTriage && !hasSlots) {
      this.fail('Provide --triage <path|json> or --slots <path|json>');
      return;
    }

    const tenant = this.tenants.resolve(clientCode, options.tenant);
    const ctx = buildRequestContext(tenant);
    // 本次评估为一个 flow（序号归零，stderr + 文件同源）
    this.trace.setFlow('p1', ctx.request_id);
    this.trace.banner('P1 evaluate');

    let triage: Triage;
    if (hasSlots) {
      triage = this.fromSlots(ctx.tenant.channels[0] ?? 'wecom', options.slots!);
    } else {
      triage = this.loadTriage(options.triage!);
    }

    // 补 industry 进 known_slots，便于 hasValue
    triage.known_slots = {
      industry: triage.industry,
      role: triage.agent_family ?? (triage.known_slots as any)?.role,
      ...(triage.known_slots ?? {}),
    };

    const gated = this.wizard.evaluateGate(triage);
    const out: Phase1Result = {
      phase: ProductPhase.P1_WIZARD_INTENT,
      client_code: ctx.client_code,
      request_id: ctx.request_id,
      stage: 'S1_COARSE',
      gate: gated.gate,
      triage: gated.triage!,
      ask_user: gated.ask_user,
    };

    this.trace.step('P1', 'evaluate.done', {
      gate: out.gate,
      scene_id: out.triage.scene_id,
      can_generate_v0: out.triage.can_generate_v0,
      missing_slots: out.triage.missing_slots,
      next_ask: out.triage.next_ask?.slot,
    });

    const expect = (options.expectGate ?? '').trim().toUpperCase();
    if (expect && expect !== out.gate) {
      process.stderr.write(
        JSON.stringify(
          {
            error: `expected gate=${expect}, got=${out.gate}`,
            result: out,
          },
          null,
          2,
        ) + '\n',
      );
      process.exitCode = 1;
      return;
    }

    process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
  }

  /**
   * slots JSON：{ industry, role, desired_capabilities?, channel?, confidence? }
   * 由规则 inferSceneId 组装 triage（免 LLM）。
   */
  private fromSlots(defaultChannel: string, input: string): Triage {
    const raw = this.parseJson(input) as Record<string, unknown>;
    const collected = {
      industry: raw.industry,
      role: raw.role ?? raw.agent_family,
      desired_capabilities: raw.desired_capabilities ?? [],
    };
    const scene_id =
      (raw.scene_id ? String(raw.scene_id) : undefined) ??
      this.wizard.inferSceneId(collected);

    if (!scene_id) {
      return {
        scene_id: '',
        industry: collected.industry ? String(collected.industry) : undefined,
        agent_family: collected.role ? String(collected.role) : undefined,
        channel: String(raw.channel ?? defaultChannel),
        confidence: Number(raw.confidence ?? 0.9),
        reason: 'slots: cannot infer scene',
        known_slots: collected,
        missing_slots: [],
      };
    }

    return {
      scene_id,
      industry: collected.industry ? String(collected.industry) : undefined,
      agent_family: collected.role ? String(collected.role) : undefined,
      channel: String(raw.channel ?? defaultChannel),
      confidence: Number(raw.confidence ?? 0.9),
      reason: String(raw.reason ?? `slots→${scene_id}`),
      known_slots: {
        industry: collected.industry,
        role: collected.role,
        desired_capabilities: collected.desired_capabilities,
      },
      missing_slots: [],
      risk_flags: [],
    };
  }

  private loadTriage(input: string): Triage {
    const t = this.parseJson(input) as Triage;
    if (!t.scene_id && !(t as any).industry) {
      throw new Error('triage JSON must include scene_id (or use --slots)');
    }
    return {
      scene_id: String(t.scene_id ?? ''),
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

  private parseJson(input: string): unknown {
    const trimmed = input.trim();
    if (trimmed.startsWith('{')) return JSON.parse(trimmed);
    const p = path.resolve(trimmed);
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  }

  private fail(msg: string): void {
    process.stderr.write(`${JSON.stringify({ error: msg }, null, 2)}\n`);
    process.exitCode = 1;
  }

  @Option({
    flags: '--client-code <code>',
    description: 'SaaS tenant client_code (required)',
  })
  parseClientCode(val: string): string {
    return val;
  }

  @Option({
    flags: '--triage <pathOrJson>',
    description: '[P1] triage JSON path or inline JSON',
  })
  parseTriage(val: string): string {
    return val;
  }

  @Option({
    flags: '--slots <pathOrJson>',
    description:
      '[P1] coarse slots JSON (industry/role/capabilities); infer scene without LLM',
  })
  parseSlots(val: string): string {
    return val;
  }

  @Option({ flags: '--tenant <path>', description: 'Override tenant JSON path' })
  parseTenant(val: string): string {
    return val;
  }

  @Option({
    flags: '--expect-gate <PASS|ASK|CUSTOM>',
    description: 'Assert gate (non-zero exit on mismatch); for fixtures',
  })
  parseExpectGate(val: string): string {
    return val;
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
    description: 'Verbose stderr trace (include prompt bodies)',
  })
  parseVerbose(): boolean {
    return true;
  }

  @Option({ flags: '--trace-off', description: 'Alias of --quiet' })
  parseTraceOff(): boolean {
    return true;
  }

  @Option({ flags: '--trace-full', description: 'Alias of --verbose' })
  parseTraceFull(): boolean {
    return true;
  }
}
