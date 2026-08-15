/** 产物观察开关。生产构建默认 off；dev 默认 on。显式 ARTIFACT_INSPECTOR=off 永远关。 */

export function inspectorBuildEnabled() {
  try {
    return import.meta.env?.VITE_ARTIFACT_INSPECTOR === 'on';
  } catch {
    return false;
  }
}

/** 双闸：构建期 off 或健康检查 artifact_inspector!==true 都不展示。 */
export function inspectorVisible(health) {
  return inspectorBuildEnabled() && health?.artifact_inspector === true;
}
