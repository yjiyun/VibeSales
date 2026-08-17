/**
 * StderrSink — 打点的 stderr 去处（人眼扫流水）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么不走 log4js
 * log4js 在本项目里只承担**文件传输层**（滚动、flush）。stderr 要同步、无缓冲、
 * 进程崩溃前也已落屏，且**绝不写 stdout**（结果 JSON 要能 `| jq`），
 * 直接 `process.stderr.write` 比套一层 appender 更可控。
 *
 * 档位：`LOG_STDERR`（旧名 `DEMO_TRACE` / `--trace-*` 仍兼容）= off | on | verbose
 */

import {
  STDERR_TERSE,
  STDERR_VERBOSE,
  stringifyRedacted,
} from './redact';
import { beijingClock } from './time';
import { passes, SinkThreshold, TraceRecord, TraceSink } from './trace-sink';

export class StderrSink implements TraceSink {
  readonly name = 'stderr';

  constructor(public threshold: SinkThreshold = 'on') {}

  emit(rec: TraceRecord): void {
    if (!passes(this.threshold, rec)) return;

    if (rec.kind === 'banner') {
      process.stderr.write(`[trace] -------- ${rec.event} --------\n`);
      return;
    }

    const ts = beijingClock(rec.ts);
    const policy = this.threshold === 'verbose' ? STDERR_VERBOSE : STDERR_TERSE;
    const payload =
      rec.data === undefined ? '' : ` ${stringifyRedacted(rec.data, policy)}`;
    process.stderr.write(
      `[trace] ${ts} | ${this.timing(rec)} | ${rec.scope} | ${rec.event}${payload}\n`,
    );
  }

  /** LLM 调用的显式耗时标 `llm=`，其余标 `took=`，便于一眼扫出模型等待。 */
  private timing(rec: TraceRecord): string {
    const base = `+${rec.deltaMs}ms | Σ${rec.totalMs}ms`;
    if (rec.ms === undefined) return base;
    const isLlm =
      rec.scope === 'Qwen' ||
      rec.event.includes('qwen') ||
      rec.event.startsWith('chatJson');
    return `${base} | ${isLlm ? 'llm' : 'took'}=${rec.ms}ms`;
  }
}
