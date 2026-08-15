/**
 * 向导会话的本地持久化。
 *
 * 刷新页面本来会丢掉整条对话（timeline 只在内存里），用户不得不从头再走一遍向导 ——
 * platform 编排一次要跑十几分钟，重走一遍代价太大。这里把「重建界面所必需」的状态存进
 * localStorage：会话标识、时间线、当前问题/结果、收集态、run 与发布闸门。
 *
 * 只存数据，不存函数与 DOM；thinking 这类瞬时态不落盘（刷新后本就该重新开始等待）。
 */

const KEY = 'agent-console.wizard-session';
const VERSION = 1;
/** 时间线过长时只留最近这些条，避免超出 localStorage 配额（通常 5MB）。 */
const MAX_TIMELINE = 200;

function canUseStorage() {
  try {
    return typeof localStorage !== 'undefined';
  } catch {
    return false;
  }
}

/** thinking 是瞬时态：刷新后重新计时，落盘只会留下一个永远转圈的假气泡。 */
function persistableTimeline(timeline) {
  const rows = (timeline ?? []).filter((item) => item && item.type !== 'thinking');
  return rows.length > MAX_TIMELINE ? rows.slice(-MAX_TIMELINE) : rows;
}

export function saveSession(state) {
  if (!canUseStorage() || !state?.sessionId) return;
  try {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        version: VERSION,
        savedAt: Date.now(),
        sessionId: state.sessionId,
        stage: state.stage ?? '',
        status: state.status ?? '',
        timeline: persistableTimeline(state.timeline),
        question: state.question ?? null,
        result: state.result ?? null,
        collect: state.collect ?? null,
        draft: state.draft ?? '',
        selectedModel: state.selectedModel ?? '',
        llm: state.llm !== false,
        rightTab: state.rightTab ?? 'collect',
        runId: state.runId ?? '',
        publishSnapshot: state.publishSnapshot ?? null,
        published: state.published === true,
      }),
    );
  } catch {
    /* 配额满或隐私模式：持久化是增强项，失败不该影响会话本身 */
  }
}

export function loadSession() {
  if (!canUseStorage()) return null;
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (data?.version !== VERSION || !data?.sessionId) return null;
    return { ...data, timeline: Array.isArray(data.timeline) ? data.timeline : [] };
  } catch {
    return null;
  }
}

export function clearSession() {
  if (!canUseStorage()) return;
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* 同上：清理失败不影响主流程 */
  }
}
