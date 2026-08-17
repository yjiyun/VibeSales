/**
 * input-digest — 用户输入的日志摘要（CLI 与 Web 共用）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么需要
 * 原先 `answer.received` 只打 `text_chars: 42`：能看出用户说了话，却看不出说了什么，
 * 排查「为什么这句话没被解析成行业」时必须回头看前端录屏。摘要让 app.log 自己能读：
 *
 * ```text
 * answer.received {"stage":"S3_BRIEF","text_chars":63,
 *                  "text_digest":"我们主要卖进口护肤套装，面向 25-35 岁女性…(+18字)"}
 * ```
 *
 * ## 与 redact 的分工
 * `redact.ts` 的 {@link BODY_KEYS} 管的是**提示词与模型原文**——那些是给模型看的长正文，
 * terse 档一律省掉。用户输入是排查的起点，短、且每条都要有，因此这里主动截到一行，
 * 让它在 terse 档也能落盘，不依赖 verbose。全文仍可由 `LOG_FILE=verbose` 拿到。
 */

/** 一行摘要的默认上限（按字符计，中英同权）。 */
const DEFAULT_MAX = 80;

/**
 * 把一段用户输入压成一行摘要：折叠空白与换行，超长截断并标注省略字数。
 * @returns 摘要串；输入为空/全空白时返回 undefined（打点里该字段直接消失）
 */
export function digestText(
  text: string | undefined | null,
  max = DEFAULT_MAX,
): string | undefined {
  const flat = (text ?? '').replace(/\s+/g, ' ').trim();
  if (!flat) return undefined;
  // 按码点切，避免把 emoji / 生僻字的代理对截半
  const cps = [...flat];
  if (cps.length <= max) return flat;
  return `${cps.slice(0, max).join('')}…(+${cps.length - max}字)`;
}

/** 字符数（按码点，与 `digestText` 的截断口径一致）。 */
export function charCount(text: string | undefined | null): number {
  return [...((text ?? '').trim())].length;
}
