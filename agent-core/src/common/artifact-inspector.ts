/** 产物观察 debug 开关。未设置或非 `on` 一律视为关闭（生产默认关）。 */
export function artifactInspectorEnabled(): boolean {
  return String(process.env.ARTIFACT_INSPECTOR ?? '').trim().toLowerCase() === 'on';
}
