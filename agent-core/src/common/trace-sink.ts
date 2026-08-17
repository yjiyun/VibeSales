/**
 * TraceSink — 一条打点记录的去处（stderr / 文件 / Web 事件流）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 设计要点
 * 打点只有一个入口 {@link TraceService.step}：它算出 {@link TraceRecord} 后广播给所有 sink。
 * **详略由 sink 自己判**（各自的 {@link SinkThreshold}），业务代码不需要知道当前开没开 trace ——
 * 合并前 `trace.isVerbose() ? user : undefined` 那类写法正是要消除的对象。
 *
 * 时序字段（seq / deltaMs / totalMs）由 TraceService 从 FlowState 统一取一次，
 * 所有 sink 共用同一组，因此 stderr 的 `#17` 与 app.log 的 `#17` 必然是同一条。
 */

import { FlowLevel } from './flow-context';

/**
 * 单个 sink 的输出档位。
 * - `off`     不输出
 * - `on`      输出常规打点，提示词/模型原文等正文只留长度
 * - `verbose` 全部输出，含正文与 `verboseOnly` 记录
 */
export type SinkThreshold = 'off' | 'on' | 'verbose';

/** 严重度；只做过滤，不兼任详略开关（详略是 threshold 的事）。 */
export type TraceLevel = FlowLevel;

/** 记录种类：普通打点 / 段落分隔 / 流程开始。 */
export type TraceKind = 'step' | 'banner' | 'flow';

/** 一次打点的完整事实。 */
export interface TraceRecord {
  kind: TraceKind;
  /** 毫秒时间戳 */
  ts: number;
  /** 流程内自增序号（setFlow 归零） */
  seq: number;
  /** 流程名：p1 / p1-wizard / p2-match / web */
  flow: string;
  /** 请求标识；未设置为 `-` */
  requestId: string;
  /** 模块名，如 `P1.WizardLlm`；kind≠step 时为 `Flow` */
  scope: string;
  /** 位置/事件名，如 `echoIndustry.done` */
  event: string;
  data?: unknown;
  /** 距上一条记录 */
  deltaMs: number;
  /** 距最近一次 banner */
  totalMs: number;
  /** 显式耗时（data.ms，适合 LLM / IO） */
  ms?: number;
  level: TraceLevel;
  /** true 表示只有 verbose 档位的 sink 才输出（提示词全文等） */
  verboseOnly: boolean;
}

export interface TraceSink {
  /** 用于自述与去重，如 `stderr` / `file` */
  readonly name: string;
  /** 当前档位；各 sink 自读配置，运行期可改（如向导静默 stderr） */
  threshold: SinkThreshold;
  emit(rec: TraceRecord): void;
}

const OFF = new Set(['0', 'false', 'off', 'no', 'none']);
const VERBOSE = new Set(['2', 'full', 'verbose', 'debug', 'all']);
const ON = new Set(['1', 'true', 'on', 'info']);

/**
 * 解析档位字符串。兼容旧写法：`DEMO_TRACE=full` → verbose、`LOG_ENABLED=0` → off。
 * @param raw 配置值；空或无法识别时返回 fallback
 */
export function parseThreshold(
  raw: string | undefined | null,
  fallback: SinkThreshold = 'on',
): SinkThreshold {
  const v = (raw ?? '').trim().toLowerCase();
  if (!v) return fallback;
  if (OFF.has(v)) return 'off';
  if (VERBOSE.has(v)) return 'verbose';
  if (ON.has(v)) return 'on';
  return fallback;
}

/** 该记录是否应被这个档位输出。 */
export function passes(threshold: SinkThreshold, rec: TraceRecord): boolean {
  if (threshold === 'off') return false;
  if (rec.verboseOnly && threshold !== 'verbose') return false;
  return true;
}
