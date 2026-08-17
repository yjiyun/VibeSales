/**
 * token-total — 累计台账的查看 / 校对 / 重建
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测
 *
 * ## 为什么有这个脚本
 * `logs/token-total.json` 是运行时一路累加出来的快照（{@link TokenLedgerService}）。
 * 快照没有自证能力：写坏了、手改过、某次进程被 kill 在半路，光看它看不出来。
 * 这个脚本从 `logs/token.log` 的 `kind=summary` 明细行重新算一遍，
 * 用同一个 {@link applyEntry} 口径——所以差异只可能来自数据，不会来自算法。
 *
 * ## 用法
 * ```bash
 * npm run token:total              # 看当前累计 + 与 token.log 的差异
 * npm run token:total -- --rebuild # 用 token.log 重建快照（会写文件）
 * npm run token:total -- --json    # 输出完整 JSON（喂给 jq）
 * ```
 *
 * ## 重建会丢东西，所以默认不让做
 * token.log 按大小滚动（`LOG_MAX_SIZE` / `LOG_BACKUPS`），滚掉的明细永久消失，
 * 而台账不滚动。因此「快照 ≥ 重算值」是**正常**的，不是错误。
 * 当重算值小于现有快照时，`--rebuild` 会拒绝执行并要求 `--force`：
 * 否则一次手抖就把滚动之前的历史抹平了。
 */

import * as fs from 'fs';
import * as path from 'path';
import {
  LedgerEntry,
  LedgerTotal,
  TokenLedger,
  TokenLedgerService,
  applyEntry,
  emptyLedger,
} from '../src/common/token-ledger.service';
import { beijingTimestamp, parseLogTimestamp } from '../src/common/time';

interface Options {
  rebuild: boolean;
  force: boolean;
  json: boolean;
}

function parseArgs(argv: string[]): Options {
  const flags = new Set(argv.slice(2));
  return {
    rebuild: flags.has('--rebuild'),
    force: flags.has('--force'),
    json: flags.has('--json'),
  };
}

/**
 * 借 TokenLedgerService 的 filePath / read / write / formatOneLine，
 * 以免这里再写一份路径规则与原子写。它只依赖 ConfigService.get，喂一个读 env 的即可。
 */
function ledgerService(): TokenLedgerService {
  const config = { get: (key: string) => process.env[key] };
  return new TokenLedgerService(config as never);
}

function logDir(): string {
  return path.resolve(
    process.cwd(),
    (process.env.LOG_DIR ?? 'logs').trim() || 'logs',
  );
}

/**
 * token.log 及其滚动备份，按时间从旧到新。
 * log4js 的 dateFile/file appender 滚动后 `.1` 最新、`.N` 最旧，故倒序拼接。
 */
function tokenLogFiles(dir: string): string[] {
  const base = path.join(dir, 'token.log');
  if (!fs.existsSync(dir)) return [];
  const backups = fs
    .readdirSync(dir)
    .filter((n) => /^token\.log\.\d+$/.test(n))
    .sort((a, b) => Number(b.split('.').pop()) - Number(a.split('.').pop()))
    .map((n) => path.join(dir, n));
  return [...backups, ...(fs.existsSync(base) ? [base] : [])];
}

interface ScanResult {
  ledger: TokenLedger;
  lines: number;
  summaries: number;
  broken: number;
  files: string[];
}

function scan(dir: string, keep: number): ScanResult {
  const files = tokenLogFiles(dir);
  const ledger = emptyLedger();
  let lines = 0;
  let summaries = 0;
  let broken = 0;
  // first_at 由第一条明细决定，而不是 emptyLedger 的「现在」
  let first: number | undefined;

  for (const file of files) {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const text = line.trim();
      if (!text) continue;
      lines += 1;
      let rec: Record<string, unknown>;
      try {
        rec = JSON.parse(text) as Record<string, unknown>;
      } catch {
        broken += 1;
        continue;
      }
      if (rec.kind !== 'summary') continue;
      // 明细行没有 ts（不该发生）时按 0 计，宁可日期分桶落到 1970 也不静默丢量
      const at = parseLogTimestamp(rec.ts) ?? 0;
      if (first === undefined || at < first) first = at;
      // 传同一个保留窗口：否则重建出来的 by_day 会比运行时多，两边永远对不上
      applyEntry(ledger, rec as unknown as LedgerEntry, at, keep);
      summaries += 1;
    }
  }

  if (first !== undefined) ledger.first_at = beijingTimestamp(first);
  return { ledger, lines, summaries, broken, files };
}

/**
 * 打印最近若干个时间桶（key 字典序 ＝ 时间序，取尾部 `limit` 个）。
 * 桶为空就整段不打，免得输出里出现一个「近 14 天」空标题。
 */
function writeBuckets(
  out: NodeJS.WriteStream,
  title: string,
  buckets: Record<string, LedgerTotal>,
  limit: number,
): void {
  const keys = Object.keys(buckets).sort().slice(-limit);
  if (keys.length === 0) return;
  const width = Math.max(...keys.map((k) => k.length));
  out.write(`\n${title}\n`);
  for (const key of keys) {
    const b = buckets[key];
    out.write(
      `  ${key.padEnd(width)}  ${String(b.total_tokens).padStart(9)} tokens  ` +
        `${String(b.calls).padStart(4)} calls  ${String(b.flows).padStart(4)} flows` +
        `${b.errors ? `  errors=${b.errors}` : ''}\n`,
    );
  }
}

function main(): void {
  const opts = parseArgs(process.argv);
  const svc = ledgerService();
  const dir = logDir();
  const current = svc.read();
  const scanned = scan(dir, svc.dayRetention());

  if (opts.json) {
    process.stdout.write(
      `${JSON.stringify({ current, recomputed: scanned.ledger }, null, 2)}\n`,
    );
    return;
  }

  const out = process.stdout;
  out.write(`台账文件  ${svc.filePath()}\n`);
  out.write(
    `明细来源  ${scanned.files.length ? scanned.files.map((f) => path.basename(f)).join(', ') : '(无 token.log)'}\n`,
  );
  out.write(
    `扫描结果  ${scanned.lines} 行，其中 summary ${scanned.summaries} 条${scanned.broken ? `，坏行 ${scanned.broken}` : ''}\n\n`,
  );
  out.write(`现有快照  ${svc.formatOneLine(current)}\n`);
  out.write(`日志重算  ${svc.formatOneLine(scanned.ledger)}\n`);

  writeBuckets(out, '近 14 天', current.by_day, 14);
  writeBuckets(out, '按月', current.by_month, 12);

  const diff = current.all_time.total_tokens - scanned.ledger.all_time.total_tokens;
  if (diff === 0) {
    out.write('\n✅ 一致。\n');
  } else if (diff > 0) {
    out.write(
      `\nℹ️  快照比日志多 ${diff} tokens —— 正常，多出来的是已被滚动清掉的明细。\n`,
    );
  } else {
    out.write(
      `\n⚠️  快照比日志少 ${-diff} tokens —— 快照可能被截断或手改过，考虑 --rebuild。\n`,
    );
  }

  if (!opts.rebuild) {
    if (diff !== 0) out.write('   重建：npm run token:total -- --rebuild\n');
    return;
  }

  if (diff > 0 && !opts.force) {
    out.write(
      '\n❌ 拒绝重建：会丢掉已滚动明细里的 ' +
        `${diff} tokens。确认要以日志为准请加 --force。\n`,
    );
    process.exitCode = 1;
    return;
  }

  svc.write(scanned.ledger);
  out.write(`\n✅ 已用 ${scanned.summaries} 条明细重建 ${svc.filePath()}\n`);
}

main();
