/**
 * OtlpTraceSink — AgentLoop 的 OTLP/HTTP 出口（最终方案第二版：OTLP 标准直发为准）
 *
 * ## 文档
 * - `docs/agentteams/agentLoop/abutment/AgentLoop接入设计方案-最终.md`（§1 参数、§5 属性、§7 迁移）
 * - `docs/agentteams/agentLoop/instruction/AgentLoop-nodejs-manual接入配置.md`
 *
 * ## 与 AgentLoopSink（ROA）的关系
 * 二者都消费同一条 {@link TraceRecord}，共用 {@link toAgentLoopEnvelope} 的属性映射
 * （run_id 聚合 / pre_run 排除 / Worker 披露 / 凭证过滤都在那里，`test:agentloop-aggregation` 校它）。
 * 区别只在“怎么发”：ROA 自拼 envelope + HMAC-SHA1 POST OpenAPI；本 sink 发标准 OTLP/HTTP JSON。
 * 协议由 `AGENTLOOP_PROTOCOL=otlp|roa` 选择（见 TraceService），OTLP 为默认，ROA 留作回滚。
 *
 * ## 面板识别（P0-1 已实证）
 * span 名 = `gen_ai.operation.name` ∈ {invoke_agent, chat, execute_tool}；Resource 带
 * `service.name` / `acs.arms.service.feature=genai_app` / `acs.cms.workspace`；同会话共享
 * `gen_ai.session.id`（= run_id）。这套属性经零依赖探针验证可被 AI Agent 面板解析。
 *
 * A12：网络/构造失败只熔断自身，永不影响业务回包。瞬时失败走冷却退避并自动恢复，
 * 只有鉴权/配置类致命错才永久熔断（见 {@link OtlpTraceSink} 的熔断策略说明）。
 */

import { createHash, randomBytes } from 'crypto';
import { AgentLoopEnvelope, toAgentLoopEnvelope } from './agentloop-sink';
import { TraceRecord, TraceSink } from './trace-sink';
import { spanDisplayName } from './span-aliases';

export interface OtlpSinkConfig {
  mode: 'stderr' | 'on';
  endpoint?: string;
  /** OTLP 请求头（含 x-arms-license-key / x-arms-project / x-cms-workspace）。 */
  headers?: Record<string, string>;
  serviceName: string;
  /** OTEL_RESOURCE_ATTRIBUTES 里除 service.name 外的项，key=value 逗号分隔。 */
  resourceAttributes?: string;
  sampleRate?: number;
}

const hex = (bytes: number) => randomBytes(bytes).toString('hex');
const nowNano = (ms: number) => String(BigInt(ms) * 1_000_000n);

/** W3C traceparent → { traceId, spanId }；非法返回 undefined。 */
function parseTraceparent(tp: unknown): { traceId: string; spanId: string } | undefined {
  if (typeof tp !== 'string') return undefined;
  const m = /^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$/.exec(tp.trim());
  return m ? { traceId: m[1], spanId: m[2] } : undefined;
}

/** 无 traceparent 时按 run_id/requestId 派生稳定 traceId，保证同会话仍聚合到一棵树。 */
function traceIdFromKey(key: string): string {
  return createHash('sha256').update(key).digest('hex').slice(0, 32);
}

/** 一个属性值 → OTLP AnyValue。 */
function anyValue(value: string | number | boolean) {
  if (typeof value === 'boolean') return { boolValue: value };
  if (typeof value === 'number') return Number.isInteger(value) ? { intValue: String(value) } : { doubleValue: value };
  return { stringValue: String(value) };
}

function kv(key: string, value: string | number | boolean) {
  return { key, value: anyValue(value) };
}

function parseResourceAttributes(raw?: string): Array<{ key: string; value: unknown }> {
  if (!raw) return [];
  return raw
    .split(',')
    .map((pair) => pair.trim())
    .filter(Boolean)
    .map((pair) => {
      const idx = pair.indexOf('=');
      return idx > 0 ? kv(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim()) : null;
    })
    .filter((x): x is ReturnType<typeof kv> => x !== null);
}

/**
 * TraceRecord → 一条 OTLP/HTTP JSON `resourceSpans`（单 span）。
 * 属性沿用 {@link toAgentLoopEnvelope}，span 名取 `gen_ai.operation.name`。
 * 导出为纯函数，便于单测断言（与 test:agentloop-aggregation 同风格）。
 */
export function toOtlpResourceSpans(
  rec: TraceRecord,
  cfg: Pick<OtlpSinkConfig, 'serviceName' | 'resourceAttributes'>,
): unknown {
  const env: AgentLoopEnvelope = toAgentLoopEnvelope(rec);
  const attrs = env.attributes;
  const runKey = String(attrs['agentteams.run_id'] ?? attrs['agentteams.session_id'] ?? rec.requestId);
  const tp = parseTraceparent(env.traceparent);
  const traceId = tp?.traceId ?? traceIdFromKey(runKey);
  const spanId = tp?.spanId ?? hex(8);

  // span 名（面板"Span 名称"列）用中文别名，一眼看出是哪个阶段的哪一步；
  // 类型分类仍由 gen_ai.operation.name（chat/execute_tool/invoke_agent）承担，是另一个字段，不受影响。
  const displayName = spanDisplayName(rec.scope, rec.event, attrs['agentteams.mcp.tool'], attrs['agentteams.phase']);
  const spanAttributes = Object.entries(attrs).map(([k, v]) => kv(k, v));
  if (!('gen_ai.session.id' in attrs)) spanAttributes.push(kv('gen_ai.session.id', runKey));

  const start = rec.ts;
  const end = rec.ms && rec.ms > 0 ? rec.ts + rec.ms : rec.ts;

  const span: Record<string, unknown> = {
    traceId,
    spanId,
    name: displayName,
    kind: 1, // INTERNAL
    startTimeUnixNano: nowNano(start),
    endTimeUnixNano: nowNano(end),
    attributes: spanAttributes,
    status: { code: rec.level === 'warn' ? 2 : 1 }, // ERROR : OK
  };

  return {
    resourceSpans: [
      {
        resource: {
          attributes: [kv('service.name', cfg.serviceName), ...parseResourceAttributes(cfg.resourceAttributes)],
        },
        scopeSpans: [{ scope: { name: 'agentloop.nest', version: '1.0.0' }, spans: [span] }],
      },
    ],
  };
}

/** 冷却档位（ms）：连续失败越多冷得越久，上限 5 分钟。冷却期只丢 span，不排队、不重发。 */
const COOLDOWN_LADDER_MS = [5_000, 15_000, 60_000, 300_000];
/** 判定"配置/鉴权错，重试也没用"的状态码：这类才永久熔断。 */
const FATAL_STATUS = new Set([400, 401, 403, 404, 413]);

/**
 * AgentLoop OTLP 出口。失败只熔断自身，不影响业务（A12）。
 *
 * ## 熔断策略（为什么不是"一次失败就永久关"）
 * 早先的实现里首次 fetch reject 即 `enabled=false` 且永不恢复。线上踩到的后果：Nest 进程起来
 * 3 分半后一次瞬时 `fetch failed`（网络抖动，端点本身是好的），此后整个进程生命周期内所有 span
 * 被静默丢弃 —— 控制台上 `vibe-sales-nest` 直接没有链路数据，而日志里只有一行
 * `[agentloop-otlp] disabled: fetch failed`，两小时后才有人发现。
 *
 * 现在按错误性质分两类：
 * - **瞬时**（网络 reject / 5xx / 429）→ 进冷却（{@link COOLDOWN_LADDER_MS} 阶梯退避），
 *   冷却结束自动放行；一次成功即清零计数，回到常态。
 * - **致命**（{@link FATAL_STATUS}：licenseKey 错、project/workspace 写错、路径不对）→ 永久熔断，
 *   因为重试改变不了结果，继续打只是刷日志。构造期的参数校验仍然直接抛（fail-fast）。
 *
 * 状态变化都往 stderr 写一行，便于 `grep agentloop-otlp` 看出"熔断过但恢复了"而不是"从此静音"。
 */
export class OtlpTraceSink implements TraceSink {
  readonly name = 'agentloop-otlp';
  threshold = 'on' as const;
  /** 永久熔断（仅致命错误）。 */
  private enabled = true;
  /** 冷却截止时间戳；<= now 表示不在冷却。 */
  private cooldownUntil = 0;
  private consecutiveFailures = 0;
  private droppedWhileCooling = 0;
  private readonly sampleRate: number;

  constructor(private readonly cfg: OtlpSinkConfig) {
    this.sampleRate = cfg.sampleRate ?? 1;
    if (!Number.isFinite(this.sampleRate) || this.sampleRate < 0 || this.sampleRate > 1) {
      throw new Error('AGENTLOOP_SAMPLE_RATE must be 0.0..1.0');
    }
    if (cfg.mode === 'on') {
      if (!cfg.endpoint) throw new Error('AgentLoop OTLP on mode requires OTEL_EXPORTER_OTLP_TRACES_ENDPOINT');
      try {
        new URL(cfg.endpoint);
      } catch {
        throw new Error('OTEL_EXPORTER_OTLP_TRACES_ENDPOINT must be a valid URL');
      }
    }
  }

  emit(rec: TraceRecord): void {
    if (!this.enabled || !this.sampled(rec)) return;
    if (this.cooling()) {
      this.droppedWhileCooling += 1;
      return;
    }
    let body: string;
    try {
      body = JSON.stringify(toOtlpResourceSpans(rec, this.cfg));
    } catch {
      // 编码失败是数据形状问题，重试同样失败：只丢这一条，不动熔断状态。
      process.stderr.write('[agentloop-otlp] drop: encode failed\n');
      return;
    }
    if (this.cfg.mode === 'stderr') {
      process.stderr.write('[agentloop-otlp] ' + body + '\n');
      return;
    }
    void fetch(this.cfg.endpoint!, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(this.cfg.headers ?? {}) },
      body,
    })
      .then((res) => {
        if (res.ok) {
          this.onSuccess();
          return;
        }
        if (FATAL_STATUS.has(res.status)) {
          this.disablePermanently('HTTP ' + res.status + '（配置/鉴权错，重试无用）');
          return;
        }
        this.onTransientFailure('HTTP ' + res.status);
      })
      .catch((error) => {
        this.onTransientFailure(error instanceof Error ? error.message : String(error));
      });
  }

  /** 仍可能上报（未永久熔断）。冷却中也算 enabled —— 它会自己恢复。 */
  isEnabled(): boolean {
    return this.enabled;
  }

  /** 当前是否在冷却期内（诊断/自测用）。 */
  isCooling(): boolean {
    return this.enabled && this.cooling();
  }

  private cooling(): boolean {
    if (this.cooldownUntil === 0) return false;
    if (Date.now() < this.cooldownUntil) return true;
    this.cooldownUntil = 0;
    const dropped = this.droppedWhileCooling;
    this.droppedWhileCooling = 0;
    process.stderr.write('[agentloop-otlp] retrying after cooldown, dropped ' + dropped + ' spans\n');
    return false;
  }

  private onSuccess(): void {
    if (this.consecutiveFailures > 0) {
      process.stderr.write('[agentloop-otlp] recovered after ' + this.consecutiveFailures + ' failures\n');
    }
    this.consecutiveFailures = 0;
    this.cooldownUntil = 0;
  }

  private onTransientFailure(reason: string): void {
    const step = Math.min(this.consecutiveFailures, COOLDOWN_LADDER_MS.length - 1);
    this.consecutiveFailures += 1;
    const cooldown = COOLDOWN_LADDER_MS[step];
    this.cooldownUntil = Date.now() + cooldown;
    process.stderr.write(
      '[agentloop-otlp] cooling ' + cooldown + 'ms after failure #' + this.consecutiveFailures + ': ' + reason + '\n',
    );
  }

  private disablePermanently(reason: string): void {
    this.enabled = false;
    process.stderr.write('[agentloop-otlp] disabled: ' + reason + '\n');
  }

  private sampled(rec: TraceRecord): boolean {
    if (this.sampleRate === 1) return true;
    if (this.sampleRate === 0) return false;
    const data =
      rec.data && typeof rec.data === 'object' && !Array.isArray(rec.data)
        ? (rec.data as Record<string, unknown>)
        : {};
    const key = String(data.run_id ?? rec.requestId);
    const bucket = createHash('sha256').update(key).digest().readUInt32BE(0) / 0x100000000;
    return bucket < this.sampleRate;
  }
}
