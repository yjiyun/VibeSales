/**
 * TraceService — 全链路打点的唯一入口（事件流 + 多 sink 分发）
 *
 * ## 文档
 * - `agent-core/README.md` 日志一节
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 在整条链路中的位置
 * 横切关注点：Catalogs / Loader / CLI / Tenant / Intent / Filter / Rank / Decide / Qwen
 * 均通过 `trace.step` 输出中间数据；**任何 sink 都不写 stdout**，以免污染结果 JSON。
 *
 * ```text
 * trace.step(scope, event, data)          ← 业务侧唯一 API
 *   └─ 算一条 TraceRecord（ts / seq / +Δ / Σ / level）
 *        ├─ StderrSink        LOG_STDERR = off | on | verbose
 *        ├─ LogService(file)  LOG_FILE   = off | on | verbose
 *        └─ FlowState.events                （仅 Web 请求，随回包给前端运行面板）
 * ```
 *
 * ## 一次打点、一组时序、多个去处
 * `seq` / `+Δms` / `Σms` 只算一次（存在 {@link FlowContextService} 的 FlowState 里），
 * 所有 sink 共用同一组数值——stderr 的 `#17` 与 app.log 的 `#17` 必然是同一条。
 * 并发 HTTP 请求各有独立 FlowState，因此不会串号。
 *
 * ## 详略归 sink，不归业务
 * 业务代码照常把字段传进来（含提示词、模型原文），**由各 sink 的档位决定落多少**：
 * `on` 省略正文只留长度，`verbose` 全量。想只落文件不刷屏，把 stderr 调 off 即可。
 * 正文类打点用 `trace.step(scope, event, data, { verbose: true })` 标注，
 * 只有 verbose 档位的 sink 才会输出。
 *
 * ## 档位与兼容
 * ```text
 * stderr  LOG_STDERR=off|on|verbose   ← 旧：DEMO_TRACE=0|1|full、--trace-off / --trace-full
 * 文件    LOG_FILE  =off|on|verbose   ← 旧：LOG_ENABLED=0|1、LOG_LEVEL=debug
 * ```
 * 旧写法一律继续生效（读到时不报错，直接映射）。
 */

import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { FlowContextService, FlowLevel, FlowState } from './flow-context';
import { LogService } from './log.service';
import { STDERR_TERSE, redact } from './redact';
import { StderrSink } from './stderr-sink';
import { beijingTimestamp } from './time';
import {
  parseThreshold,
  SinkThreshold,
  TraceKind,
  TraceRecord,
  TraceSink,
} from './trace-sink';
import { AgentLoopSink } from './agentloop-sink';
import { OtlpTraceSink } from './otlp-sink';

/** step 的可选修饰。 */
export interface StepOptions {
  /** 只在 verbose 档位输出（提示词全文、模型原始返回等） */
  verbose?: boolean;
}

@Injectable()
export class TraceService {
  private readonly stderr: StderrSink;
  private readonly sinks: TraceSink[];

  constructor(
    private readonly config: ConfigService,
    private readonly log: LogService,
    private readonly flows: FlowContextService,
  ) {
    // 优先级：CLI 参数 > LOG_STDERR > DEMO_TRACE（旧名）> 默认 on
    const fromArgv = peekTraceFromArgv();
    this.stderr = new StderrSink(
      parseThreshold(
        fromArgv ??
          this.config.get<string>('LOG_STDERR') ??
          this.config.get<string>('DEMO_TRACE'),
        'on',
      ),
    );
    this.sinks = [this.stderr, this.log];
    const agentLoopSink = this.buildAgentLoopSink();
    if (agentLoopSink) this.sinks.push(agentLoopSink);
  }

  /**
   * 装配 AgentLoop 出口 sink（唯一远端出口，A12 旁路）。
   * - `AGENTLOOP_EXPORTER` = off | stderr | on（三档，off 完全关闭）。
   * - `AGENTLOOP_PROTOCOL` = otlp（默认，标准 OTLP/HTTP 直发）| roa（回滚：自建 OpenAPI + ROA 签名）。
   *   见 `docs/agentteams/agentLoop/abutment/AgentLoop接入设计方案-最终.md` §7。
   */
  private buildAgentLoopSink(): TraceSink | undefined {
    const mode = (this.config.get<string>('AGENTLOOP_EXPORTER') ?? 'off').trim().toLowerCase();
    if (!['off', 'stderr', 'on'].includes(mode)) {
      throw new Error('AGENTLOOP_EXPORTER must be off, stderr or on');
    }
    if (mode === 'off') return undefined;

    const protocol = (this.config.get<string>('AGENTLOOP_PROTOCOL') ?? 'otlp').trim().toLowerCase();
    if (!['otlp', 'roa'].includes(protocol)) {
      throw new Error('AGENTLOOP_PROTOCOL must be otlp or roa');
    }
    const sampleRate = Number(this.config.get<string>('AGENTLOOP_SAMPLE_RATE') ?? 1);

    if (protocol === 'roa') {
      // 回滚路径：自建 AgentLoop OpenAPI + ROA HMAC-SHA1 签名。
      return new AgentLoopSink(
        mode as 'stderr' | 'on',
        this.config.get<string>('AGENTLOOP_ENDPOINT')?.trim(),
        this.config.get<string>('AGENTLOOP_ACCESS_KEY')?.trim(),
        this.config.get<string>('AGENTLOOP_ACCESS_SECRET')?.trim(),
        sampleRate,
      );
    }

    // 默认：标准 OTLP/HTTP 直发（OTEL_* + ARMS_LICENSE_KEY，混合命名，见方案 §1.1）。
    return new OtlpTraceSink({
      mode: mode as 'stderr' | 'on',
      endpoint: this.config.get<string>('OTEL_EXPORTER_OTLP_TRACES_ENDPOINT')?.trim(),
      headers: this.otlpHeaders(),
      serviceName: this.config.get<string>('OTEL_SERVICE_NAME')?.trim() || 'vibe-sales-nest',
      resourceAttributes: this.config.get<string>('OTEL_RESOURCE_ATTRIBUTES')?.trim(),
      sampleRate,
    });
  }

  /**
   * OTLP 请求头：优先用完整的 OTEL_EXPORTER_OTLP_HEADERS（key=value 逗号分隔，含 ${ARMS_LICENSE_KEY} 已展开）；
   * 未给时按 ARMS_LICENSE_KEY 兜底拼一个只含鉴权头的最小集合。LicenseKey 只从 env 取，不落日志。
   */
  private otlpHeaders(): Record<string, string> {
    const raw = this.config.get<string>('OTEL_EXPORTER_OTLP_HEADERS')?.trim();
    if (raw) {
      const headers: Record<string, string> = {};
      for (const pair of raw.split(',')) {
        const idx = pair.indexOf('=');
        if (idx > 0) headers[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
      }
      return headers;
    }
    const licenseKey = this.config.get<string>('ARMS_LICENSE_KEY')?.trim();
    return licenseKey ? { 'x-arms-license-key': licenseKey } : {};
  }

  /** 追加 sink（Web 层一次请求挂一个 SSE / Memory sink）。 */
  addSink(sink: TraceSink): void {
    this.sinks.push(sink);
  }

  removeSink(sink: TraceSink): void {
    const i = this.sinks.indexOf(sink);
    if (i >= 0) this.sinks.splice(i, 1);
  }

  /** 解析 LOG_STDERR / DEMO_TRACE / CLI 等价字符串，更新 stderr 档位。 */
  applyEnv(raw?: string | null): void {
    this.stderr.threshold = parseThreshold(raw, 'on');
  }

  /** 关/开 stderr 刷屏（文件日志不受影响）。 */
  setEnabled(on: boolean): void {
    this.stderr.threshold = on
      ? this.stderr.threshold === 'off'
        ? 'on'
        : this.stderr.threshold
      : 'off';
  }

  setVerbose(on: boolean): void {
    this.stderr.threshold = on ? 'verbose' : 'on';
  }

  isEnabled(): boolean {
    return this.stderr.threshold !== 'off';
  }

  isVerbose(): boolean {
    return this.stderr.threshold === 'verbose';
  }

  /** stderr 当前档位（诊断用）。 */
  stderrThreshold(): SinkThreshold {
    return this.stderr.threshold;
  }

  /**
   * 标记进入某个流程（CLI 一次命令 / Web 一次请求）：序号归零并留一条 `<flow>.begin`。
   * Web 侧应先由 `FlowContextService.run` 建好隔离状态，再调本方法。
   */
  setFlow(flow: string, requestId?: string): void {
    const state = this.flows.reset(flow, requestId);
    this.dispatch('flow', 'Flow', `${state.flow}.begin`, {
      argv: process.argv.slice(2),
      pid: process.pid,
      cwd: process.cwd(),
    });
  }

  /** 分隔线，便于肉眼扫流水；同时重置段落计时。 */
  banner(title: string): void {
    const state = this.flows.current();
    const now = Date.now();
    state.sectionStart = now;
    state.lastStepAt = now;
    this.dispatch('banner', 'Flow', title, undefined);
  }

  /**
   * 阶段打点：scope=模块名，event=事件名，data=中间数据。
   * data 中的 `ms` 会作为显式耗时展示（适合 LLM / IO）。
   */
  step(
    scope: string,
    event: string,
    data?: unknown,
    opts?: StepOptions,
  ): void {
    this.dispatch('step', scope, event, data, opts?.verbose === true);
  }

  /** 后续所有打点自动继承关联字段，供 AgentLoop 按 run_id 聚合。 */
  bind(values: Record<string, string | undefined>): void {
    this.flows.bindCorrelation(values);
  }

  /**
   * 大段正文（提示词 / 模型原文）：只有 verbose 档位的 sink 才输出。
   * 等价于 `step(scope, event, data, { verbose: true })`。
   */
  detail(scope: string, event: string, data?: unknown): void {
    this.dispatch('step', scope, event, data, true);
  }

  /**
   * 计时包裹：执行 fn，并在完成后打一条带 `ms` 的 step（适合 LLM / IO）。
   * 失败时仍打 step（event 追加 `.error`），再抛出原错误。
   */
  async timed<T>(
    scope: string,
    event: string,
    fn: () => Promise<T>,
    extra?: Record<string, unknown>,
  ): Promise<T> {
    const started = Date.now();
    try {
      const result = await fn();
      this.step(scope, event, { ...(extra ?? {}), ms: Date.now() - started });
      return result;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.step(scope, `${event}.error`, {
        ...(extra ?? {}),
        ms: Date.now() - started,
        error: msg,
      });
      throw err;
    }
  }

  /** 算一条记录并广播；sink 自己决定要不要、以多详细输出。 */
  private dispatch(
    kind: TraceKind,
    scope: string,
    event: string,
    data?: unknown,
    verboseOnly = false,
  ): void {
    const now = Date.now();
    const state = this.flows.current();
    if (!state.sectionStart) state.sectionStart = now;
    if (!state.lastStepAt) state.lastStepAt = now;

    const deltaMs = now - state.lastStepAt;
    const totalMs = now - state.sectionStart;
    state.lastStepAt = now;

    const correlatedData = Object.keys(state.correlation).length
      ? { ...state.correlation, ...(data && typeof data === 'object' && !Array.isArray(data) ? data as Record<string, unknown> : data === undefined ? {} : { value: data }) }
      : data;
    const rec: TraceRecord = {
      kind,
      ts: now,
      seq: this.flows.nextSeq(state),
      flow: state.flow,
      requestId: state.request_id,
      scope,
      event,
      data: correlatedData,
      deltaMs,
      totalMs,
      ms: extractMs(data),
      level: levelOf(event),
      verboseOnly,
    };

    for (const sink of this.sinks) {
      try {
        sink.emit(rec);
      } catch {
        // 日志自身出错不得影响主流程
      }
    }
    this.collect(state, rec);
  }

  /** Web 请求：同一条打点结构化留一份，随回包给前端「运行面板」。 */
  private collect(state: FlowState, rec: TraceRecord): void {
    if (!state.events) return;
    if (rec.verboseOnly) return;
    this.flows.pushEvent(state, {
      seq: rec.seq,
      ts: beijingTimestamp(rec.ts),
      level: rec.level,
      scope: rec.scope,
      event: rec.kind === 'banner' ? 'section' : rec.event,
      delta_ms: rec.deltaMs,
      total_ms: rec.totalMs,
      ms: rec.ms,
      data:
        rec.kind === 'banner'
          ? { title: rec.event }
          : rec.data === undefined
            ? undefined
            : redact(rec.data, STDERR_TERSE),
    });
  }
}

/** `.error` / `.fallback` 结尾的事件升级为 warn，便于 grep 出异常路径。 */
function levelOf(event: string): FlowLevel {
  if (/\.error$|^error/.test(event)) return 'warn';
  if (/\.fallback$/.test(event)) return 'warn';
  return 'info';
}

function extractMs(data: unknown): number | undefined {
  if (data == null || typeof data !== 'object' || Array.isArray(data)) {
    return undefined;
  }
  const ms = (data as Record<string, unknown>).ms;
  return typeof ms === 'number' && Number.isFinite(ms) ? ms : undefined;
}

/**
 * 在 Nest 初始化前读取 argv，使 Catalogs/TemplateLoader 的初始化打点也能被关掉。
 * @returns 档位字符串，或 null 表示未在命令行指定（改读环境变量）
 */
export function peekTraceFromArgv(argv: string[] = process.argv): string | null {
  if (
    argv.includes('--trace-off') ||
    argv.includes('--no-trace') ||
    argv.includes('--quiet')
  ) {
    return 'off';
  }
  if (argv.includes('--trace-full') || argv.includes('--verbose')) {
    return 'verbose';
  }
  if (argv.includes('--trace-on') || argv.includes('--trace')) return 'on';
  return null;
}
