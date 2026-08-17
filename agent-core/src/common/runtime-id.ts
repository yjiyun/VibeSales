/**
 * Runtime / sandbox userId 必须是 SAFE_ID（[A-Za-z0-9_-]）。
 * 向导 X-Actor 常是 Matrix 风格 `@developer:local`，绑定行和试聊 query 都走这里，否则 PG 查空。
 * 与 agent-console `src/shared/runtime-id.js` 保持同一套规则。
 */
export function runtimeSafeId(value: string, fallback = 'sandbox'): string {
  const cleaned = String(value ?? '')
    .replace(/[^A-Za-z0-9_-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 128);
  return cleaned || fallback;
}
