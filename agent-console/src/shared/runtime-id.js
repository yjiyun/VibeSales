/**
 * 与 Nest `src/common/runtime-id.ts` 同一套 SAFE_ID。
 * 绑定表 user_id 和试聊 query userId 必须一致。
 */
export function runtimeSafeId(value, fallback = 'sandbox') {
  const cleaned = String(value ?? '')
    .replace(/[^A-Za-z0-9_-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 128);
  return cleaned || fallback;
}
