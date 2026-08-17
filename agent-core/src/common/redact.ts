/**
 * redact — 日志值的截断与脱敏（stderr 与文件共用同一份实现）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么单独一个文件
 * 合并前 TraceService 与 LogService 各有一份 `prepare()`：字符上限 500/4000 对 8000、
 * 数组 20/50 对 100、深度 6 对 8、是否省略提示词正文也不同——同一套逻辑两份实现必然漂移。
 * 现在差异收敛成四个 policy 常量，逻辑只有这一份。
 *
 * ## 谁用哪个 policy
 * ```text
 * StderrSink  threshold=on      → STDERR_TERSE    （省略正文，短截断：人眼扫流水）
 *             threshold=verbose → STDERR_VERBOSE
 * FileSink    threshold=on      → FILE_TERSE      （省略正文，长截断：事后分析）
 *             threshold=verbose → FILE_VERBOSE    （含提示词与模型原文全文）
 * ```
 * 业务代码因此**不再需要判断当前详略**（原先 Intent/Decide 里的
 * `user: trace.isVerbose() ? user : undefined`）——照常把字段传进来，由 policy 决定落多少。
 */

/** 提示词/正文类字段：非 verbose 时只留长度，避免刷屏与泄漏无关正文。 */
export const BODY_KEYS = ['brief', 'system', 'user', 'prompt'] as const;

export interface RedactPolicy {
  /** 单个字符串上限，超出截断并标注 `+N chars` */
  maxChars: number;
  /** 数组保留项数，超出标注 `+N items` */
  maxItems: number;
  /** 递归深度上限，超出记 `[MaxDepth]` */
  maxDepth: number;
  /** 这些 key 的值替换为占位（只留长度） */
  omitKeys: readonly string[];
}

export const STDERR_TERSE: RedactPolicy = {
  maxChars: 500,
  maxItems: 20,
  maxDepth: 6,
  omitKeys: BODY_KEYS,
};

export const STDERR_VERBOSE: RedactPolicy = {
  maxChars: 4000,
  maxItems: 50,
  maxDepth: 6,
  omitKeys: [],
};

export const FILE_TERSE: RedactPolicy = {
  maxChars: 2000,
  maxItems: 50,
  maxDepth: 8,
  omitKeys: BODY_KEYS,
};

export const FILE_VERBOSE: RedactPolicy = {
  maxChars: 8000,
  maxItems: 100,
  maxDepth: 8,
  omitKeys: [],
};

/** 按 policy 递归裁剪值；不修改入参。 */
export function redact(value: unknown, policy: RedactPolicy): unknown {
  return walk(value, policy, 0);
}

/** 裁剪后序列化；序列化失败（循环引用等）退化为 String()。 */
export function stringifyRedacted(
  value: unknown,
  policy: RedactPolicy,
): string {
  try {
    return JSON.stringify(redact(value, policy)) ?? String(value);
  } catch {
    return String(value);
  }
}

function walk(value: unknown, policy: RedactPolicy, depth: number): unknown {
  if (value == null) return value;
  if (typeof value === 'string') return clampString(value, policy.maxChars);
  if (typeof value !== 'object') return value;
  if (depth >= policy.maxDepth) return '[MaxDepth]';

  if (Array.isArray(value)) {
    const items: unknown[] = value
      .slice(0, policy.maxItems)
      .map((v) => walk(v, policy, depth + 1));
    if (value.length > policy.maxItems) {
      items.push(`…(+${value.length - policy.maxItems} items)`);
    }
    return items;
  }

  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    if (policy.omitKeys.includes(k)) {
      out[k] = omitted(v);
      continue;
    }
    out[k] = walk(v, policy, depth + 1);
  }
  return out;
}

function clampString(s: string, max: number): string {
  if (s.length <= max) return s;
  return `${s.slice(0, max)}…(+${s.length - max} chars)`;
}

function omitted(v: unknown): string {
  let len = 0;
  try {
    len = typeof v === 'string' ? v.length : (JSON.stringify(v) ?? '').length;
  } catch {
    len = -1;
  }
  return `[omitted len=${len}; 设 LOG_STDERR=verbose 或 LOG_FILE=verbose]`;
}
