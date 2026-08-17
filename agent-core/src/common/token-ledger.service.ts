/**
 * TokenLedgerService — 工程累计 token 台账（跨进程、跨重启）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么需要
 * {@link TokenUsageService} 的粒度是「一次流程」：CLI 一条命令、Web 一个请求。
 * 进程退出即归零，因此「这个工程到今天一共烧了多少 token」这个问题，
 * 原先只能靠 `jq` 全量扫 `logs/token.log` 现算——而 token.log 会按大小滚动，
 * 滚掉的历史就永久丢了。台账把累计量单独落一份，不受滚动影响。
 *
 * ## 两份文件的分工
 * ```text
 * logs/token.log         每次调用 + 每 flow 汇总的明细（会滚动，是事实来源但会过期）
 * logs/token-total.json  累计快照（不滚动，只增不减；本文件负责）
 * ```
 * 快照可随时由 token.log 重算校对：`npm run token:total`（加 `-- --rebuild` 才写回）。
 * 明细已滚掉的部分重算不回来，所以快照是**主**、明细是**证**。
 *
 * ## 为什么按天也放在同一个文件里，而不是「每天一个 total 文件」
 * 决定性理由是**原子性**：日文件方案必然要写两处（`all_time` 在主文件、当天量在日文件），
 * 两次写之间没有原子边界，进程在中间被 kill 就留下「累计涨了、当天没涨」的不自洽账。
 * 台账的全部价值就是可信，用它换 IO 是亏的；单文件里所有桶同在一次 tmp+rename 中落地。
 * 附带好处：跨天查询一次读完；`--rebuild` 只覆盖一个文件，不必在重建路径上删文件。
 *
 * IO 成本并不吃亏——写入频率是「每个 flow 一次」（人在对话，量级每分钟几次），
 * 一天一个桶约 200 字节，一年 ≈ 70KB，这个频率下全量读写无所谓。
 *
 * 日文件方案唯一的真优势是大小有上界，这点用 {@link DAY_RETENTION} 补上：
 * `by_day` 只留最近若干天，而 `by_month` 与 `all_time` **独立累加**（不是由日桶汇总而来），
 * 所以裁剪只丢日粒度、总账永不失真，同时文件大小有了硬上界。
 *
 * ## 并发与原子性
 * 读-改-写全程同步（`readFileSync` → 累加 → `writeFileSync` + `renameSync`）：
 * 单线程事件循环里没有插入点，同进程的并发请求天然串行，不需要额外排队。
 * 同步也让「记完账马上读出来打印」成立——CLI 收尾那行累计不会读到旧值。
 * rename 在同一文件系统上是原子的，因此进程被 kill 时要么旧快照要么新快照，
 * 不会出现半个 JSON。
 *
 * 跨进程（CLI 与 Web 同时跑）没有文件锁：两者都在收尾时各写一次，
 * 极端并发下可能有一次覆盖。DEMO 阶段接受这个代价——真丢了就 `--rebuild` 重算。
 *
 * ## 失败不影响主流程
 * 台账是旁路记账，任何 IO 错误只在 stderr 留一行，不向上抛。
 */

import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';
import { TokenBucket, emptyTokenBucket } from './flow-context';
import { beijingDate, beijingMonth, beijingTimestamp } from './time';

/** 台账文件名（与 token.log 同目录）。 */
export const LEDGER_FILE = 'token-total.json';

/** 结构版本：将来改字段时用于识别旧文件。 */
export const LEDGER_SCHEMA = 1;

/** 一次 flow 的用量投递（由 TokenUsageService.summary 调用）。 */
export interface LedgerEntry {
  flow: string;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  errors: number;
  llm_ms: number;
  by_node: Record<string, TokenBucket>;
  by_model: Record<string, TokenBucket>;
}

/** 累计桶：在 TokenBucket 之上多记 flow 数与 LLM 等待时长。 */
export interface LedgerTotal extends TokenBucket {
  /** 累计流程数（CLI 命令次数 + Web 请求次数） */
  flows: number;
  errors: number;
  llm_ms: number;
}

/** `logs/token-total.json` 的完整结构。 */
export interface TokenLedger {
  schema: number;
  /** 最近一次更新（北京时间） */
  updated_at: string;
  /** 台账建立时间（北京时间） */
  first_at: string;
  all_time: LedgerTotal;
  /** key = `2026-07-29`（北京时间自然日）；只保留最近 {@link DAY_RETENTION} 天 */
  by_day: Record<string, LedgerTotal>;
  /** key = `2026-07`（北京时间自然月）；永久保留，一年只有 12 条 */
  by_month: Record<string, LedgerTotal>;
  /** key = `scope/purpose` */
  by_node: Record<string, TokenBucket>;
  by_model: Record<string, TokenBucket>;
}

/**
 * `by_day` 的保留天数（`TOKEN_LEDGER_DAYS` 可改，0 = 不裁剪）。
 *
 * 日粒度是给「最近在烧多少」用的，看的是趋势；再往前看月粒度就够了。
 * 有了这个窗口，文件大小才有硬上界——也正因如此不需要「每天一个文件」（见类注释）。
 */
export const DAY_RETENTION = 90;

export function emptyLedgerTotal(): LedgerTotal {
  return { ...emptyTokenBucket(), flows: 0, errors: 0, llm_ms: 0 };
}

export function emptyLedger(at = beijingTimestamp()): TokenLedger {
  return {
    schema: LEDGER_SCHEMA,
    updated_at: at,
    first_at: at,
    all_time: emptyLedgerTotal(),
    by_day: {},
    by_month: {},
    by_node: {},
    by_model: {},
  };
}

@Injectable()
export class TokenLedgerService {
  private readonly dir: string;
  private readonly enabled: boolean;
  /** by_day 保留天数（TOKEN_LEDGER_DAYS，0 = 全留） */
  private readonly dayRetentionDays: number;
  private warned = false;

  constructor(private readonly config: ConfigService) {
    this.dir = path.resolve(
      process.cwd(),
      (this.config.get<string>('LOG_DIR') ?? 'logs').trim() || 'logs',
    );
    // 注意用 trim 后判空再 Number：Number('') === 0 会被当成「不裁剪」
    const rawDays = (
      this.config.get<string>('TOKEN_LEDGER_DAYS') ?? ''
    ).trim();
    const days = rawDays ? Number(rawDays) : NaN;
    this.dayRetentionDays =
      Number.isInteger(days) && days >= 0 ? days : DAY_RETENTION;
    // 与文件日志同一个开关：LOG_FILE=off 时不写任何文件
    const raw = (
      this.config.get<string>('TOKEN_LEDGER') ??
      this.config.get<string>('LOG_FILE') ??
      this.config.get<string>('LOG_ENABLED') ??
      'on'
    )
      .trim()
      .toLowerCase();
    this.enabled = raw !== 'off' && raw !== '0' && raw !== 'false';
  }

  /** 台账文件绝对路径（供 CLI 提示与重算脚本用）。 */
  filePath(): string {
    return path.join(this.dir, LEDGER_FILE);
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  /**
   * 累加一次 flow 的用量并落盘（同步：返回时已落盘，随后 `read()` 必看到新值）。
   * 返回累加后的台账，供调用方把「当天/累计总量」写进 token.log；
   * 关闭或 IO 失败时返回 null（失败只在 stderr 留一行，不抛）。
   */
  add(entry: LedgerEntry, at: number = Date.now()): TokenLedger | null {
    if (!this.enabled) return null;
    return this.applyAndWrite(entry, at);
  }

  /** 某天的累计（默认今天）；该天无记录或已过保留窗口时返回空桶。 */
  dayTotal(at: number = Date.now(), ledger?: TokenLedger): LedgerTotal {
    const src = ledger ?? this.read();
    return src.by_day[beijingDate(at)] ?? emptyLedgerTotal();
  }

  /** by_day 保留天数（重算脚本要用同一个窗口，否则重建结果与运行时不一致）。 */
  dayRetention(): number {
    return this.dayRetentionDays;
  }

  /** 读当前台账（不存在或损坏时返回空账）。 */
  read(): TokenLedger {
    try {
      const raw = fs.readFileSync(this.filePath(), 'utf8');
      return normalize(JSON.parse(raw) as Partial<TokenLedger>);
    } catch {
      return emptyLedger();
    }
  }

  /** 人眼可读的一行（CLI 收尾打印）。 */
  formatOneLine(ledger: TokenLedger = this.read()): string {
    const a = ledger.all_time;
    const today = ledger.by_day[beijingDate()];
    const todayPart = today
      ? ` 今日 ${today.total_tokens}（${today.flows} 次流程）`
      : '';
    return (
      `📚 [token 累计] total=${a.total_tokens} ` +
      `(prompt=${a.prompt_tokens} completion=${a.completion_tokens}) ` +
      `calls=${a.calls} flows=${a.flows}${todayPart}`
    );
  }

  /** 覆盖写入（重算脚本用；业务代码不应调用）。 */
  write(ledger: TokenLedger): void {
    fs.mkdirSync(this.dir, { recursive: true });
    const file = this.filePath();
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, `${JSON.stringify(ledger, null, 2)}\n`, 'utf8');
    fs.renameSync(tmp, file);
  }

  private applyAndWrite(entry: LedgerEntry, at: number): TokenLedger | null {
    try {
      const ledger = this.read();
      applyEntry(ledger, entry, at, this.dayRetentionDays);
      this.write(ledger);
      return ledger;
    } catch (err) {
      if (!this.warned) {
        this.warned = true;
        const msg = err instanceof Error ? err.message : String(err);
        process.stderr.write(`[token-ledger] disabled: ${msg}\n`);
      }
      return null;
    }
  }
}

/** 把一次 flow 的用量并入台账（重算脚本复用同一函数，保证口径一致）。 */
export function applyEntry(
  ledger: TokenLedger,
  entry: LedgerEntry,
  at: number = Date.now(),
  days = DAY_RETENTION,
): TokenLedger {
  const stamp = beijingTimestamp(at);
  if (!ledger.first_at) ledger.first_at = stamp;
  ledger.updated_at = stamp;

  addTotal(ledger.all_time, entry);
  const day = beijingDate(at);
  ledger.by_day[day] ??= emptyLedgerTotal();
  addTotal(ledger.by_day[day], entry);
  // 月桶独立累加，不是由日桶汇总来的——日桶会被裁剪，月桶要永久成立
  const month = beijingMonth(at);
  ledger.by_month[month] ??= emptyLedgerTotal();
  addTotal(ledger.by_month[month], entry);

  for (const [k, v] of Object.entries(entry.by_node ?? {})) {
    ledger.by_node[k] = addBucket(ledger.by_node[k], v);
  }
  for (const [k, v] of Object.entries(entry.by_model ?? {})) {
    ledger.by_model[k] = addBucket(ledger.by_model[k], v);
  }
  pruneDays(ledger, days);
  return ledger;
}

/**
 * 只保留最近 `keep` 天的日明细（按 key 字典序 ＝ 时间序）。
 *
 * 丢掉的量已经进了 `all_time` 与 `by_month`，所以裁剪不丢总账，只丢日粒度。
 * `keep <= 0` 表示不裁剪（`TOKEN_LEDGER_DAYS=0`，想留全量日账时用）。
 */
export function pruneDays(ledger: TokenLedger, keep = DAY_RETENTION): void {
  if (keep <= 0) return;
  const days = Object.keys(ledger.by_day).sort();
  if (days.length <= keep) return;
  for (const day of days.slice(0, days.length - keep)) {
    delete ledger.by_day[day];
  }
}

function addTotal(acc: LedgerTotal, entry: LedgerEntry): void {
  acc.flows += 1;
  acc.calls += num(entry.calls);
  acc.prompt_tokens += num(entry.prompt_tokens);
  acc.completion_tokens += num(entry.completion_tokens);
  acc.total_tokens += num(entry.total_tokens);
  acc.errors += num(entry.errors);
  acc.llm_ms += num(entry.llm_ms);
}

function addBucket(
  acc: TokenBucket | undefined,
  v: TokenBucket,
): TokenBucket {
  const base = acc ?? emptyTokenBucket();
  return {
    calls: base.calls + num(v.calls),
    prompt_tokens: base.prompt_tokens + num(v.prompt_tokens),
    completion_tokens: base.completion_tokens + num(v.completion_tokens),
    total_tokens: base.total_tokens + num(v.total_tokens),
  };
}

/** 补齐缺失字段：手工改过、旧版本写的、或半截文件都能安全接着用。 */
function normalize(raw: Partial<TokenLedger>): TokenLedger {
  const base = emptyLedger(raw.updated_at || beijingTimestamp());
  return {
    schema: LEDGER_SCHEMA,
    first_at: raw.first_at || base.first_at,
    updated_at: raw.updated_at || base.updated_at,
    all_time: { ...base.all_time, ...(raw.all_time ?? {}) },
    by_day: raw.by_day ?? {},
    by_month: raw.by_month ?? {},
    by_node: raw.by_node ?? {},
    by_model: raw.by_model ?? {},
  };
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}
