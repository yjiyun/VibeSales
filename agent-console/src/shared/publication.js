/** 向导发布成功后的产物绑定。沙盒试聊只读这里，不回落到启动脚本的 seed。 */
export const PUBLICATION_KEY = 'agent-console.publication';
export const NAVIGATE_EVENT = 'agent-console:navigate';

export function loadPublication() {
  try {
    const raw = localStorage.getItem(PUBLICATION_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (!data || typeof data !== 'object') return null;
    return data;
  } catch {
    return null;
  }
}

export function savePublication(input) {
  const publication = {
    clientCode: String(input.clientCode ?? '').trim(),
    runtimeAgentId: String(input.runtimeAgentId ?? '').trim(),
    runId: String(input.runId ?? '').trim(),
    sceneId: String(input.sceneId ?? '').trim(),
    displayName: String(input.displayName ?? '').trim(),
    buildPath: String(input.buildPath ?? '').trim(),
    publishedAt: input.publishedAt ?? new Date().toISOString(),
  };
  localStorage.setItem(PUBLICATION_KEY, JSON.stringify(publication));
  return publication;
}

export function clearPublication() {
  localStorage.removeItem(PUBLICATION_KEY);
}

export function isChatReady(publication = loadPublication()) {
  return Boolean(publication?.clientCode && publication?.runtimeAgentId);
}

export function navigateConsole(page) {
  localStorage.setItem('agent-console.page', page);
  window.dispatchEvent(new CustomEvent(NAVIGATE_EVENT, { detail: page }));
}
