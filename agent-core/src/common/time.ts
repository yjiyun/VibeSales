/**
 * time — 日志时间的唯一格式化口径（北京时间）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么单独一个文件
 * 合并前四处各写各的：`app.log` 走 log4js `%d`（机器本地时间）、`token.log` 的 `ts`、
 * StderrSink 的时间片段、`FlowEvent.ts` 都是 `toISOString()`（UTC）。
 * 同一条打点在 app.log 里是 13:43:16、在 token.log 里是 05:43:16Z，对着排查极易看错行。
 * 现在四处共用本文件，**输出必然同一时刻同一写法**。
 *
 * ## 为什么不直接用本地时间
 * `toLocaleString` / log4js `%d` 都跟随机器 TZ：本机是 Asia/Shanghai 时看着没问题，
 * 换台 UTC 的机器（容器默认）跑就静默变成伦敦时间，且日志里没有任何标记能看出来。
 * 这里固定按 UTC+8 换算，与 `process.env.TZ` 无关。
 *
 * ## 写法
 * 不带时区后缀（`2026-07-29 13:43:16.006`）：日志给人看，多一个 `+08:00` 每行都在重复
 * 同一个常量。口径写在本文件与文档里，不写进每一行。
 */

/** 北京时间固定偏移（中国自 1991 年起不再实行夏令时，无需查表）。 */
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000;

/** 把时刻平移到北京时间，之后用 `getUTC*` 读出的就是北京时间的年月日时分秒。 */
function shifted(at: number | Date): Date {
  const ms = at instanceof Date ? at.getTime() : at;
  return new Date(ms + BEIJING_OFFSET_MS);
}

function pad(n: number, width = 2): string {
  return String(n).padStart(width, '0');
}

/** `2026-07-29`（北京时间的自然日；token 台账按日分桶用）。 */
export function beijingDate(at: number | Date = Date.now()): string {
  const d = shifted(at);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** `2026-07`（北京时间的自然月；token 台账把过期的日明细折叠到月）。 */
export function beijingMonth(at: number | Date = Date.now()): string {
  const d = shifted(at);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

/** `13:43:16.006`（stderr 一行只需时分秒，日期由文件/会话上下文给出）。 */
export function beijingClock(at: number | Date = Date.now()): string {
  const d = shifted(at);
  return (
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:` +
    `${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}`
  );
}

/** `2026-07-29 13:43:16.006`（app.log 前缀、token.log 的 ts、Web 事件流的 ts）。 */
export function beijingTimestamp(at: number | Date = Date.now()): string {
  return `${beijingDate(at)} ${beijingClock(at)}`;
}

/**
 * 把日志里的时间串读回时刻（重算脚本用）。
 *
 * 既认本文件产出的 `2026-07-29 13:43:16.006`（无后缀，按 UTC+8 解释），
 * 也认改口径之前写下的 ISO（`2026-07-29T05:43:16.006Z`，自带时区信息）；
 * 都认不出来时返回 undefined，由调用方决定跳过还是回退到当前时间。
 */
export function parseLogTimestamp(ts: unknown): number | undefined {
  if (typeof ts !== 'string' || !ts.trim()) return undefined;
  const s = ts.trim();
  const bare = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{1,3})?$/.test(s);
  const ms = Date.parse(bare ? `${s.replace(' ', 'T')}+08:00` : s);
  return Number.isFinite(ms) ? ms : undefined;
}
