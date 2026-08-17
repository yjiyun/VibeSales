/**
 * prompts/render.ts — {{key}} 占位符替换
 *
 * 缺 key（或值为 undefined/null）直接抛错，避免静默产出烂 prompt。
 * 值一律 String()；空字符串允许（例如 brief 尚未填写）。
 */

const PLACEHOLDER = /\{\{(\w+)\}\}/g;

export function renderPrompt(
  template: string,
  vars: Record<string, string | number | undefined | null>,
  label: string,
): string {
  return template.replace(PLACEHOLDER, (_m, key: string) => {
    if (!(key in vars) || vars[key] === undefined || vars[key] === null) {
      throw new Error(`prompt render missing {{${key}}} in ${label}`);
    }
    return String(vars[key]);
  });
}
