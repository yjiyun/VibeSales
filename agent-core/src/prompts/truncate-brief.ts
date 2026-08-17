/**
 * prompts/truncate-brief.ts — BRIEF 截断（截断策略，非文案）
 *
 * 控制送模 token（约 1000–1500 字/候选）。不读、不拼接 workflow/*.yaml。
 */

export function truncateBrief(brief: string, maxChars = 1400): string {
  if (!brief) return '';
  const normalized = brief.replace(/\r\n/g, '\n').trim();
  if (normalized.length <= maxChars) return normalized;
  return `${normalized.slice(0, maxChars)}\n…(截断)`;
}
