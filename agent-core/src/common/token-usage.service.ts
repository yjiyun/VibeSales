/**
 * TokenUsageService — 大模型 token 用量记账
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 在整条链路中的位置
 * ```text
 * P1.Intent / P1.WizardLlm / P2.Decide
 *        └─ QwenService.chatJson(system, user, { scope, purpose })   ← 唯一 LLM 出口
 *               └─ tokens.record(...)  → logs/token.log  (kind=llm_call)
 * 向导/命令/Web 请求结束
 *        └─ tokens.summary() → logs/token.log (kind=summary) + stderr 一行汇总
 * ```
 *
 * 只做加总与落盘，不参与任何裁决；无 usage 字段时按 0 计并标记 `usage_missing`。
 *
 * ## 记账粒度 = 一次流程
 * 计数器存在 {@link FlowContextService} 的 FlowState 里，不在本单例上：
 * CLI 一个进程一条流程（行为与之前一致），Web 每个请求一份，
 * 因此 `summary()` 拿到的一定是「本次请求」的用量，并发不串账。
 */

import { Injectable } from '@nestjs/common';
import {
  FlowContextService,
  FlowTokenState,
  TokenBucket,
  TokenCallRecord,
  emptyTokenBucket,
} from './flow-context';
import { LogService } from './log.service';
import { beijingDate } from './time';
import { TokenLedger, TokenLedgerService } from './token-ledger.service';
import { TraceService } from './trace.service';

export type { TokenBucket, TokenCallRecord } from './flow-context';

/** OpenAI 兼容 usage 字段（DashScope 同构）。 */
export interface RawUsage {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

/** 单次调用的归属信息。 */
export interface TokenCallMeta {
  /** 调用方模块，如 `P1.WizardLlm` */
  scope?: string;
  /** 调用用途，如 `echoIndustry` / `scene_router` / `template_decide` */
  purpose?: string;
  model?: string;
  ms?: number;
}

export interface TokenSummary extends TokenBucket {
  flow: string;
  request_id: string;
  errors: number;
  usage_missing: number;
  llm_ms: number;
  /** 按 `scope/purpose` 分组，便于看清哪个节点最耗 token */
  by_node: Record<string, TokenBucket>;
  by_model: Record<string, TokenBucket>;
}

function add(bucket: TokenBucket, rec: TokenCallRecord): void {
  bucket.calls += 1;
  bucket.prompt_tokens += rec.prompt_tokens;
  bucket.completion_tokens += rec.completion_tokens;
  bucket.total_tokens += rec.total_tokens;
}

@Injectable()
export class TokenUsageService {
  constructor(
    private readonly log: LogService,
    private readonly flows: FlowContextService,
    private readonly trace: TraceService,
    private readonly ledger: TokenLedgerService,
  ) {}

  /** 记一次成功调用；返回归一化后的记录（供 trace 展示）。 */
  record(
    usage: RawUsage | undefined | null,
    meta: TokenCallMeta = {},
  ): TokenCallRecord {
    const st = this.state();
    const prompt = num(usage?.prompt_tokens);
    const completion = num(usage?.completion_tokens);
    const total = num(usage?.total_tokens) || prompt + completion;
    const rec: TokenCallRecord = {
      scope: meta.scope ?? 'unknown',
      purpose: meta.purpose ?? 'unknown',
      model: meta.model ?? 'unknown',
      prompt_tokens: prompt,
      completion_tokens: completion,
      total_tokens: total,
      ms: meta.ms,
    };
    if (!usage) {
      rec.usage_missing = true;
      st.usageMissing += 1;
    }

    st.records.push(rec);
    add(st.total, rec);
    add(bucket(st.byNode, `${rec.scope}/${rec.purpose}`), rec);
    add(bucket(st.byModel, rec.model), rec);
    if (typeof meta.ms === 'number' && Number.isFinite(meta.ms)) {
      st.llmMs += meta.ms;
    }

    this.log.token('llm_call', {
      seq: st.records.length,
      ...rec,
      running_total_tokens: st.total.total_tokens,
      day_total_tokens: this.liveDayTotal(st.total.total_tokens),
    });
    // AgentLoop/OTel 是可控层 1/3/4 的权威账本；stock Worker 用量仅在平台提供时并入（A22）。
    this.trace.step('Qwen', 'usage', {
      model: rec.model,
      prompt_tokens: rec.prompt_tokens,
      completion_tokens: rec.completion_tokens,
      scope: rec.scope,
      purpose: rec.purpose,
    });
    return rec;
  }

  /** 记一次失败调用（无 usage，仍需在 token.log 留痕）。 */
  recordError(message: string, meta: TokenCallMeta = {}): void {
    const st = this.state();
    st.errors += 1;
    this.log.token('llm_error', {
      seq: st.records.length,
      scope: meta.scope ?? 'unknown',
      purpose: meta.purpose ?? 'unknown',
      model: meta.model ?? 'unknown',
      ms: meta.ms,
      error: message,
      running_total_tokens: st.total.total_tokens,
    });
  }

  callCount(): number {
    return this.state().records.length;
  }

  totals(): TokenBucket {
    return { ...this.state().total };
  }

  /** 当前累计用量快照（不落盘）。 */
  snapshot(): TokenSummary {
    const st = this.state();
    return {
      flow: this.log.currentFlow(),
      request_id: this.log.currentRequestId(),
      ...st.total,
      errors: st.errors,
      usage_missing: st.usageMissing,
      llm_ms: st.llmMs,
      by_node: mapToObject(st.byNode),
      by_model: mapToObject(st.byModel),
    };
  }

  /**
   * 流程结束时的总量：写 token.log（kind=summary）+ app.log + 工程累计台账，
   * 并返回汇总对象。无任何 LLM 调用（如 `--no-llm`）时返回 null，不写噪声行。
   *
   * 台账（`logs/token-total.json`）在此处收口：`summary` 是「一次流程结束」的
   * 唯一信号，三个入口（CLI p1-wizard / p2-match、Web 每回合）都经过这里。
   */
  summary(extra?: Record<string, unknown>): TokenSummary | null {
    const st = this.state();
    if (st.records.length === 0 && st.errors === 0) return null;
    const snap = this.snapshot();

    // 先记账再写日志：这样 summary 行里的当天/累计量是「含本次」的最终值，
    // 而不是差本次一笔的中间值——按天核对时不用再自己补加。
    const ledger = this.ledger.add({
      flow: snap.flow,
      calls: snap.calls,
      prompt_tokens: snap.prompt_tokens,
      completion_tokens: snap.completion_tokens,
      total_tokens: snap.total_tokens,
      errors: snap.errors,
      llm_ms: snap.llm_ms,
      by_node: snap.by_node,
      by_model: snap.by_model,
    });
    // 台账关闭或写失败时不编数字：字段直接缺席，比给个假值好排查
    const day = ledger ? this.ledger.dayTotal(Date.now(), ledger) : null;
    const totals =
      ledger && day
        ? {
            day: beijingDate(),
            day_total_tokens: day.total_tokens,
            day_flows: day.flows,
            all_time_total_tokens: ledger.all_time.total_tokens,
          }
        : {};

    this.log.token('summary', { ...snap, ...(extra ?? {}), ...totals });
    // 走 trace 而非直写文件：这条汇总同样要出现在 stderr / Web 事件流里
    this.trace.step('Token', 'summary', { ...snap, ...(extra ?? {}) });
    return snap;
  }

  /**
   * 当天累计（含本流程尚未结账的部分），给 token.log 的每行做纵向坐标。
   *
   * 台账只在流程结束（`summary`）时才落一次，所以流程进行中的 `llm_call` 行
   * 必须自己把「已结账的今天」＋「本流程跑到现在」加起来，否则同一天里
   * 一行一行看下去会看到当天总量停着不动。
   *
   * 并发说明：Web 多个请求同时在跑时，各自只加自己的在途量，
   * 看不见彼此的在途部分——它们结账后会补齐，`summary` 行的当天量是准的。
   * 成本：每次 LLM 调用多一次小文件 readFileSync，相对秒级的模型往返可忽略。
   */
  private liveDayTotal(running: number): number {
    return this.ledger.dayTotal().total_tokens + running;
  }

  /** 工程累计用量（跨进程、跨重启）；供 CLI 收尾与诊断打印。 */
  allTime(): TokenLedger {
    return this.ledger.read();
  }

  /** 工程累计的一行摘要。 */
  formatAllTime(): string {
    return this.ledger.formatOneLine();
  }

  /** 人眼可读的一行汇总（CLI 收尾打印）。 */
  formatOneLine(snap: TokenSummary): string {
    const nodes = Object.entries(snap.by_node)
      .sort((a, b) => b[1].total_tokens - a[1].total_tokens)
      .map(([k, v]) => `${k}=${v.total_tokens}(${v.calls})`)
      .join(' ');
    const warn = snap.errors ? ` errors=${snap.errors}` : '';
    const missing = snap.usage_missing
      ? ` usage_missing=${snap.usage_missing}`
      : '';
    return (
      `🧮 [token] calls=${snap.calls} total=${snap.total_tokens} ` +
      `(prompt=${snap.prompt_tokens} completion=${snap.completion_tokens}) ` +
      `llm=${snap.llm_ms}ms${warn}${missing}` +
      (nodes ? `\n         by_node: ${nodes}` : '')
    );
  }

  /** 测试/多次运行复位（只清当前流程）。 */
  reset(): void {
    const st = this.state();
    st.records = [];
    st.errors = 0;
    st.usageMissing = 0;
    st.llmMs = 0;
    Object.assign(st.total, emptyTokenBucket());
    st.byNode.clear();
    st.byModel.clear();
  }

  private state(): FlowTokenState {
    return this.flows.current().tokens;
  }
}

function bucket(map: Map<string, TokenBucket>, key: string): TokenBucket {
  const hit = map.get(key);
  if (hit) return hit;
  const created = emptyTokenBucket();
  map.set(key, created);
  return created;
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}

function mapToObject(
  map: Map<string, TokenBucket>,
): Record<string, TokenBucket> {
  const out: Record<string, TokenBucket> = {};
  for (const [k, v] of map) out[k] = { ...v };
  return out;
}
