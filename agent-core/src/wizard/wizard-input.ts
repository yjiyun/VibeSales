/**
 * [P1] 向导答案解析 — CLI 与 Web 共用
 *
 * 把「用户输了什么 → 词表 id」这段纯函数从 `p1-wizard.command.ts` 抽出来，
 * 让 Web 层（`web/wizard-session.service.ts`）走同一套判定：
 * 序号 / id / 名称包含 / 多选分隔 / 跳过词 / 退出词 / CTA 词，一处改两处生效。
 *
 * 不含任何 IO 与状态；LLM 归一化仍由 `WizardLlmReceptionist` 负责。
 */

import { CatalogOption, WizardNextAction } from '../common/types';

export const QUIT_WORDS = new Set(['q', 'quit', '退出', 'exit']);
export const SKIP_WORDS = new Set(['跳过', 'skip', '']);
export const GENERATE_WORDS = new Set(['生成', '直接生成', '先看效果', 'g', 'go']);
export const PREVIEW_WORDS = new Set([
  '1',
  '先看看效果',
  '先看效果',
  '看看效果',
  'preview',
]);
export const DETAIL_WORDS = new Set([
  '2',
  '继续补充细节',
  '继续补充',
  '补充细节',
  'detail',
  // Web 端 CTA 按钮直接回传 WizardNextAction 字面量
  'continue_detail',
]);

/** 归一化用户输入（去空白 + 小写）。 */
export function norm(s: string): string {
  return (s ?? '').trim().toLowerCase();
}

export function isQuit(raw: string): boolean {
  return QUIT_WORDS.has(norm(raw));
}

export function isSkip(raw: string): boolean {
  return SKIP_WORDS.has(norm(raw));
}

export function isGenerate(raw: string): boolean {
  return GENERATE_WORDS.has(norm(raw));
}

/**
 * 单选匹配：id 全等 → 名称全等 → 名称互相包含。
 * @returns 命中的 option id，未命中 null
 */
export function matchOption(
  opts: CatalogOption[],
  raw: string,
): string | null {
  const t = (raw ?? '').trim().toLowerCase();
  if (!t) return null;
  const hit = opts.find(
    (o) =>
      o.id.toLowerCase() === t ||
      o.name.toLowerCase() === t ||
      o.name.toLowerCase().includes(t) ||
      t.includes(o.name.toLowerCase()),
  );
  return hit ? hit.id : null;
}

/**
 * 单选：先认 1-based 序号，再退回名称/id 匹配。
 */
export function pickOne(
  opts: CatalogOption[],
  raw: string,
): string | null {
  const t = norm(raw);
  const num = Number(t);
  if (Number.isInteger(num) && num >= 1 && num <= opts.length) {
    return opts[num - 1].id;
  }
  return matchOption(opts, raw);
}

/**
 * 多选：按逗号/空格切分，每段各自认序号或名称；结果去重且保持输入顺序。
 */
export function parseMulti(opts: CatalogOption[], raw: string): string[] {
  const tokens = (raw ?? '')
    .split(/[,，\s]+/)
    .map((t) => t.trim())
    .filter(Boolean);
  const ids = new Set<string>();
  for (const tk of tokens) {
    const num = Number(tk);
    if (Number.isInteger(num) && num >= 1 && num <= opts.length) {
      ids.add(opts[num - 1].id);
      continue;
    }
    const m = matchOption(opts, tk);
    if (m) ids.add(m);
  }
  return [...ids];
}

/**
 * 「短指令」阈值：超过此长度或含换行的输入视为正文，
 * 不再按关键词模糊判成 CTA（否则粘贴「补充信息：\n主要客户：…」会被当成点了按钮，正文被丢弃）。
 */
const SHORT_COMMAND_MAX_CHARS = 24;

function isShortCommand(raw: string): boolean {
  const t = (raw ?? '').trim();
  return !t.includes('\n') && t.length <= SHORT_COMMAND_MAX_CHARS;
}

/**
 * 总结页 CTA：1/先看看效果 → preview，2/继续补充 → continue_detail。
 *
 * 精确词表（含 Web 按钮回传的字面量）始终生效；
 * 「效果」「补充」这类模糊命中只在短指令上生效，避免吞掉用户粘贴的长正文。
 *
 * @returns null 表示没听懂，调用方应重问 / 当作正文处理
 */
export function parseNextAction(raw: string): WizardNextAction | null {
  const n = norm(raw);
  if (PREVIEW_WORDS.has(n)) return 'preview';
  if (DETAIL_WORDS.has(n)) return 'continue_detail';
  if (!isShortCommand(raw)) return null;
  if (n.includes('效果')) return 'preview';
  if (n.includes('补充')) return 'continue_detail';
  return null;
}

/** 「补充信息」这类意图前缀：剥掉后剩下的才是真正的正文。 */
const DETAIL_INTENT_PREFIX =
  /^\s*(以下是补充信息|继续补充细节|继续补充|补充信息|补充细节|补充如下|补充)\s*[:：,，。]?\s*/;

/**
 * 识别「补充信息」意图，并剥出随手附带的正文。
 *
 * 两种输入要分开处理：
 * - 只表达意图（点按钮 / 只打了「补充信息」）→ `body` 为空，调用方给引导清单；
 * - 意图 + 内容（「补充信息：\n主要客户：…」）→ `body` 是正文，调用方交给 LLM 提取字段。
 *
 * @returns null 表示不是补充意图
 */
export function parseDetailIntent(raw: string): { body: string } | null {
  const t = (raw ?? '').trim();
  if (!t) return null;
  if (DETAIL_WORDS.has(norm(t))) return { body: '' };
  if (!DETAIL_INTENT_PREFIX.test(t)) return null;
  return { body: t.replace(DETAIL_INTENT_PREFIX, '').trim() };
}

/**
 * 是否像「一次说了多个字段」的整段描述：
 * 含换行，或出现两个及以上字段线索词（客户/客群/产品/目标/禁止/转人工…）。
 * 命中则值得先走 LLM 提取，而不是原样塞进当前追问的字段。
 */
export function looksLikeMultiField(raw: string): boolean {
  const t = (raw ?? '').trim();
  if (!t) return false;
  if (/\r?\n/.test(t)) return true;
  const cues = [
    /客户|客群|人群/,
    /产品|服务|货盘|卖/,
    /目标|希望|成功/,
    /禁止|不要|不能|避免/,
    /转人工|人工/,
  ];
  const hits = cues.filter((re) => re.test(t)).length;
  return hits >= 2 && /[:：]/.test(t);
}
