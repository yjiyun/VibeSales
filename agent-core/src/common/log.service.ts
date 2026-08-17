/**
 * LogService — 打点的文件去处（log4js）＋ token 专用日志
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 * - `agent-core/README.md` 日志一节
 *
 * ## 在合并后的架构里是什么
 * 一个 {@link TraceSink} 实现，与 {@link StderrSink} 平级：
 * ```text
 * trace.step(scope, event, data)          ← 业务侧唯一打点 API
 *   └─ TraceService 算 TraceRecord → 广播给各 sink
 *        ├─ StderrSink   LOG_STDERR = off | on | verbose   （旧名 DEMO_TRACE / --trace-*）
 *        ├─ LogService   LOG_FILE   = off | on | verbose   （旧名 LOG_ENABLED）
 *        └─ FlowState.events                               （仅 Web 请求，随回包给前端）
 * ```
 * 两侧档位**独立**：向导默认静默 stderr，文件照写——排障不依赖当时开没开 trace。
 *
 * ## 日志文件（默认 `agent-core/logs/`）
 * - `app.log`   ：全链路流水（模块 / 流程 / 位置 / 耗时）
 * - `token.log` ：大模型 token 用量专用（JSON Lines，便于 jq 统计）
 *
 * ## 一行 app.log 怎么读
 * ```text
 * [2026-07-28 10:12:03.451] INFO  P1.WizardLlm     | flow=p1-wizard req=r-8a2f #17 +812ms Σ4210ms took=809ms | echoIndustry.done {"chars":42}
 *                                 ↑模块(scope)         ↑流程        ↑请求  ↑序号 ↑距上条 ↑本段累计 ↑显式耗时     ↑位置(event)  ↑数据
 * ```
 *
 * ## flow / req / 序号从哪来
 * 全在 {@link TraceRecord} 里，由 TraceService 从 {@link FlowContextService} 统一取一次。
 * 本类不自己维护流程状态，因此 stderr 的 `#17` 与 app.log 的 `#17` 必然同指一条。
 *
 * ## log4js 只当传输层
 * 不拿 level 兼任详略（详略是 sink 的 threshold）、不让 stderr 走 appender。
 * 这样将来换掉 log4js，改动只落在本文件。
 *
 * 环境变量：LOG_FILE（旧名 LOG_ENABLED）/ LOG_LEVEL / LOG_DIR / LOG_MAX_SIZE / LOG_BACKUPS
 */

import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as fs from 'fs';
import * as path from 'path';
import * as log4js from 'log4js';
import { FlowContextService, FlowLevel } from './flow-context';
import { FILE_TERSE, FILE_VERBOSE, stringifyRedacted } from './redact';
import { beijingTimestamp } from './time';
import {
  parseThreshold,
  passes,
  SinkThreshold,
  TraceRecord,
  TraceSink,
} from './trace-sink';

/** token.log 的行类型。 */
export type TokenLogKind = 'llm_call' | 'llm_error' | 'summary';

const APP_CATEGORY_FALLBACK = 'app';
const TOKEN_CATEGORY = '__token__';
/** LOG_LEVEL 只做严重度门槛（不再兼任正文开关）。 */
const LEVEL_ORDER: Record<FlowLevel, number> = {
  debug: -1,
  info: 0,
  warn: 1,
  error: 2,
};

@Injectable()
export class LogService implements TraceSink {
  readonly name = 'file';

  /** off | on | verbose；LOG_FILE 决定，旧 LOG_ENABLED=0 仍兼容为 off */
  threshold: SinkThreshold;

  private readonly minLevel: FlowLevel;
  private readonly dir: string;
  private readonly maxLogSize: number;
  private readonly backups: number;

  private configured = false;
  private failed = false;
  private readonly loggers = new Map<string, log4js.Logger>();

  constructor(
    private readonly config: ConfigService,
    private readonly flows: FlowContextService,
  ) {
    this.threshold = parseThreshold(
      this.config.get<string>('LOG_FILE') ??
        this.config.get<string>('LOG_ENABLED'),
      'on',
    );
    const rawLevel = (this.config.get<string>('LOG_LEVEL') ?? 'info')
      .trim()
      .toLowerCase();
    this.minLevel = rawLevel in LEVEL_ORDER ? (rawLevel as FlowLevel) : 'info';
    // 旧写法 LOG_LEVEL=debug 曾用来开正文，现映射为 verbose 档位
    if ((rawLevel === 'debug' || rawLevel === 'trace') && this.threshold === 'on') {
      this.threshold = 'verbose';
    }
    this.dir = path.resolve(
      process.cwd(),
      (this.config.get<string>('LOG_DIR') ?? 'logs').trim() || 'logs',
    );
    this.maxLogSize =
      Number(this.config.get<string>('LOG_MAX_SIZE') ?? 0) || 5 * 1024 * 1024;
    this.backups = Number(this.config.get<string>('LOG_BACKUPS') ?? 0) || 5;
  }

  isEnabled(): boolean {
    return this.threshold !== 'off' && !this.failed;
  }

  /** 日志目录（供 CLI 提示"明细见 …"）。 */
  logDir(): string {
    return this.dir;
  }

  /** 当前流程名（p1 / p1-wizard / p2-match / web …）。 */
  currentFlow(): string {
    return this.flows.current().flow;
  }

  /** 当前 request_id（未设置为 `-`）。 */
  currentRequestId(): string {
    return this.flows.current().request_id;
  }

  /** TraceSink：把一条记录写进 app.log。 */
  emit(rec: TraceRecord): void {
    if (!this.isEnabled()) return;
    if (!passes(this.threshold, rec)) return;
    if (LEVEL_ORDER[rec.level] < LEVEL_ORDER[this.minLevel]) return;

    const logger = this.logger(rec.scope || APP_CATEGORY_FALLBACK);
    if (!logger) return;

    const head = [
      `flow=${rec.flow}`,
      `req=${rec.requestId}`,
      `#${rec.seq}`,
      `+${rec.deltaMs}ms`,
      `Σ${rec.totalMs}ms`,
    ];
    if (rec.ms !== undefined) head.push(`took=${rec.ms}ms`);

    const policy = this.threshold === 'verbose' ? FILE_VERBOSE : FILE_TERSE;
    const payload =
      rec.data === undefined ? '' : ` ${stringifyRedacted(rec.data, policy)}`;
    logger[rec.level](`${head.join(' ')} | ${rec.event}${payload}`);
  }

  /** 写 token 专用日志（JSON Lines，一行一条）。 */
  token(kind: TokenLogKind, payload: Record<string, unknown>): void {
    if (!this.isEnabled()) return;
    const logger = this.logger(TOKEN_CATEGORY);
    if (!logger) return;
    const state = this.flows.current();
    logger.info(
      JSON.stringify({
        ts: beijingTimestamp(),
        kind,
        flow: state.flow,
        request_id: state.request_id,
        ...payload,
      }),
    );
  }

  /** 刷盘并关闭 appender（进程退出前必须调用，否则可能丢尾部日志）。 */
  async shutdown(): Promise<void> {
    if (!this.configured) return;
    this.configured = false;
    this.loggers.clear();
    await new Promise<void>((resolve) => {
      log4js.shutdown(() => resolve());
    });
  }

  private logger(category: string): log4js.Logger | null {
    if (!this.configure()) return null;
    const cached = this.loggers.get(category);
    if (cached) return cached;
    const created = log4js.getLogger(category);
    this.loggers.set(category, created);
    return created;
  }

  /** 首次使用时才建目录、配 appender；日志本身出错不得影响主流程。 */
  private configure(): boolean {
    if (this.configured) return true;
    if (this.failed || this.threshold === 'off') return false;
    try {
      fs.mkdirSync(this.dir, { recursive: true });
      log4js.configure({
        appenders: {
          app: {
            type: 'file',
            filename: path.join(this.dir, 'app.log'),
            maxLogSize: this.maxLogSize,
            backups: this.backups,
            keepFileExt: true,
            layout: {
              type: 'pattern',
              // 用自定义 token 而非 %d：%d 跟随机器 TZ，容器里会静默变 UTC
              pattern: '[%x{bjt}] %-5p %-16c | %m',
              tokens: {
                bjt: (ev: log4js.LoggingEvent) =>
                  beijingTimestamp(ev.startTime ?? new Date()),
              },
            },
          },
          token: {
            type: 'file',
            filename: path.join(this.dir, 'token.log'),
            maxLogSize: this.maxLogSize,
            backups: this.backups,
            keepFileExt: true,
            // JSON Lines：不加前缀，直接落原始 message
            layout: { type: 'messagePassThrough' },
          },
        },
        categories: {
          // 严重度过滤已在 emit 里按 minLevel 做过，appender 放最低即可
          default: { appenders: ['app'], level: 'all' },
          [TOKEN_CATEGORY]: { appenders: ['token'], level: 'all' },
        },
      });
      this.configured = true;
      return true;
    } catch (err) {
      this.failed = true;
      const msg = err instanceof Error ? err.message : String(err);
      process.stderr.write(`[log] disabled: ${msg}\n`);
      return false;
    }
  }
}
