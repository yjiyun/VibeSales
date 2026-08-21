<script setup>
/**
 * App —— 智能体助手（Web）
 *
 * 两栏布局：左对话流 / 右 Tabs（信息收集 · 沙盒试聊 · 产物 · 专家团 · 运行情况）。
 * 「产物」「专家团」受 ARTIFACT_INSPECTOR 双闸控制，生产默认关闭。
 * 向导首页不显示右侧 Tabs；开始会话后才出现。未发布可对话产物时「沙盒试聊」禁用。
 * 对话流是单一时间线（bubble / thinking / question / result / build / publish），
 * 底部常驻 XSender；交互卡片与思考态都在流内，只有一个滚动容器。
 *
 * 前端只做：发请求、按 WizardTurn 渲染、把用户选择回传。
 * 契约见 `agent-core/src/web/web.types.ts`。
 */

import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { DocumentCopy, Download, ArrowDown } from '@element-plus/icons-vue';
import { wizardApi as api, managerApi, pipelineApi } from '../shared/api';
import { createBuildRun } from './build-run';
import { renderMarkdown } from './markdown';
import { savePublication, loadPublication, isChatReady } from '../shared/publication';
import { applyApprovalGate, extractApprovalId, fromPipelineGet, mergePlatformSnapshot, publicationFromApprove, publicationFromSnapshot } from '../shared/run-snapshot';
import { deriveBuildSteps, emptyBuildProgress, isPublishGateTerminal, leaderBlockedMessage } from '../shared/build-progress';
import QuestionCard from './components/QuestionCard.vue';
import ResultCard from './components/ResultCard.vue';
import BuildProgressCard from './components/BuildProgressCard.vue';
import PublishCard from './components/PublishCard.vue';
import RuntimePanel from './components/RuntimePanel.vue';
import CollectPanel from './components/CollectPanel.vue';
import ArtifactPanel from './components/ArtifactPanel.vue';
import ExpertRoomPanel from './components/ExpertRoomPanel.vue';
import ChatView from '../chat/ChatView.vue';
import {
  DEFAULT_WIZARD_LLM_MODEL,
  resolveWizardLlmModel,
  WIZARD_LLM_MODELS,
} from '../shared/wizard-models';
import { inspectorVisible } from '../shared/inspector';
import { applyWizardTenant, wizardTenants } from '../shared/auth';
import { clearSession, loadSession, saveSession } from './session-store';

/** 后端健康状态（决定 LLM 开关是否可用） */
const health = ref(null);
/** 会话建立前的表单 */
const form = ref({ llm: true });
const selectedModel = ref(DEFAULT_WIZARD_LLM_MODEL);
const wizardModels = computed(() =>
  health.value?.models?.length ? health.value.models : WIZARD_LLM_MODELS,
);

const sessionId = ref('');
const stage = ref('');
const status = ref('');
/**
 * 统一时间线：bubble | thinking | question | result | build | publish | divider
 * `result` 内嵌 P2 匹配（§8.6 灰底折叠），不再单独占一格。
 * `build` 是「构建智能体」过程卡（P3C/P4 步骤）。
 * `publish` 是确认发布（v5：Human Gate 留在向导内）。
 * `divider` 标出「生成总结」这一轮向导发言的起点。
 */
const timeline = ref([]);
const question = ref(null);
const result = ref(null);
const collect = ref(null);

const starting = ref(false);
const loading = ref(false);
const previewLoading = ref(false);
const buildLoading = ref(false);
const orchestrationRun = ref(null);
const lastRunId = ref(localStorage.getItem('agent-console.last-run-id') || '');
const publishState = ref(null);
const buildState = ref(null);
const gateRechecking = ref(false);
const nudging = ref(false);
/** 确认发布会打 dry-run，platform 真模型可能要等很久；独立 ref，避免复查闸门把 loading 冲掉。 */
const publishing = ref(false);
const orchestrationMode = import.meta.env.VITE_ORCHESTRATION_MODE === 'platform' ? 'platform' : 'local';
/** 「使用模板」按需生成中 */
const templateLoading = ref(false);
const draft = ref('');

/** 每回合的 runtime，供运行面板做「本回合 / 累计」切换 */
const runtime = ref(null);
const runtimeHistory = ref([]);
/**
 * 右侧「运行情况」只服务开发联调，正式上线要隐藏。
 * 开关记在 localStorage，刷新后保持；默认开（当前仍在开发阶段）。
 */
const showRuntime = ref(localStorage.getItem('wz.runtime') !== 'off');
watch(showRuntime, (on) =>
  localStorage.setItem('wz.runtime', on ? 'on' : 'off'),
);
const rightTab = ref('collect');
const sandboxPublication = ref(loadPublication());
const sandboxReady = computed(() => isChatReady(sandboxPublication.value));
const showInspector = computed(() => inspectorVisible(health.value));
// 「AI」角标只在 Vite 开发模式出现；当前产品态隐藏，保留开关便于再打开。
// const isDev = import.meta.env.DEV;
watch(sandboxReady, (ok) => {
  if (!ok && rightTab.value === 'sandbox') rightTab.value = 'collect';
});
watch(showInspector, (on) => {
  if (!on && (rightTab.value === 'artifacts' || rightTab.value === 'expert-room')) {
    rightTab.value = 'collect';
  }
});

const streamRef = ref(null);
const senderRef = ref(null);
/** 聊天区滚离底部时，在输入框上方显示「回到底部」 */
const showJumpBottom = ref(false);
const STREAM_NEAR_BOTTOM = 80;
/** 思考态秒表 */
const thinkingStartedAt = ref(0);
const thinkingSecs = ref(0);
let thinkingTimer = null;
let buildTimer = null;
/** 刷新恢复与点「生成」会各起一轮闸门等待；新一轮必须取消上一轮，避免双轮询互相覆盖。 */
let gateWatchId = 0;

const started = computed(() => !!sessionId.value);
const tenants = wizardTenants();
const selectedTenant = ref(
  tenants.find((item) => item.token === (typeof localStorage !== 'undefined'
    ? localStorage.getItem('agent-console.wizard-token')
    : ''))?.client_code
    || tenants[0]?.client_code
    || '',
);

function tenantLabel(code) {
  if (code === 'acme_beauty') return '谷雨 · acme_beauty';
  if (code === 'acme_agri') return '极飞 · acme_agri';
  if (code === 'acme_edu') return '教育 · acme_edu';
  return code;
}

function authFailHint() {
  const port = typeof location !== 'undefined' ? location.port : '';
  if (port === '15173' || (!import.meta.env.DEV && port !== '5173' && port !== '3100')) {
    return '鉴权失败：15173 静态包里的 Wizard Bearer 与 BFF 对不上。在仓库根目录执行 ./scripts/refresh-agentteams-console.sh 后强制刷新（Cmd+Shift+R）。不要直接打开 Nest :3100。';
  }
  if (port === '3100') {
    return '鉴权失败：请用 platform Console http://127.0.0.1:15173/ 或 Vite http://127.0.0.1:5173 ，不要直接打开 Nest :3100。:3100 是 API，静态页默认凭证对不上当前后端。';
  }
  return '鉴权失败：请用 Vite 控制台 http://127.0.0.1:5173 ，或 platform Console http://127.0.0.1:15173/（须 refresh-agentteams-console.sh 打包）。不要直接打开 Nest :3100。';
}

async function onTenantChange(code) {
  try {
    applyWizardTenant(code);
  } catch (err) {
    ElMessage.error(err.message || String(err));
    return;
  }
  if (started.value) await restart();
  try {
    health.value = await api.health();
  } catch (err) {
    ElMessage.error(err.message || String(err));
  }
}

const senderPlaceholder = computed(() => {
  const q = question.value;
  if (!q) return '直接输入要说的话，或点对话里的选项…';
  if (q.input === 'text') return q.hint || q.title || '直接输入…';
  return '也可直接输入文字作答；点选项更快';
});

onMounted(async () => {
  try {
    health.value = await api.health();
    if (!health.value.llm_available) form.value.llm = false;
    if (health.value?.default_model || health.value?.model) {
      selectedModel.value = resolveWizardLlmModel(
        health.value.default_model || DEFAULT_WIZARD_LLM_MODEL,
      );
    }
  } catch (err) {
    const msg = err.message || String(err);
    ElMessage.error(/unauthorized|401/i.test(msg) ? authFailHint() : `后端未就绪：${msg}`);
  }
  restoreSession();
});

/**
 * 刷新/重开页面后恢复上一次的向导会话。
 * platform 编排一次十几分钟，丢了整条对话就得从头再走一遍，代价太大。
 */
function restoreSession() {
  const saved = loadSession();
  if (!saved) return;
  sessionId.value = saved.sessionId;
  stage.value = saved.stage || '';
  status.value = saved.status || '';
  timeline.value = attachLegacyMatchCards(saved.timeline || []);
  question.value = saved.question || null;
  result.value = saved.result || null;
  collect.value = saved.collect || null;
  draft.value = saved.draft || '';
  if (saved.selectedModel) selectedModel.value = saved.selectedModel;
  form.value.llm = saved.llm !== false;
  if (saved.rightTab) rightTab.value = saved.rightTab;
  if (saved.runId) lastRunId.value = saved.runId;
  if (saved.publishSnapshot) {
    publishState.value = {
      snapshot: saved.publishSnapshot,
      published: saved.published === true,
      publishing: false,
      error: '',
    };
  }
  // 刷新后过程时间线（Room 旁注）通常已经没了；构建已结束就收成「已构建 + 用时」。
  const alreadyDone =
    saved.published === true ||
    ['WAITING_HUMAN', 'SUCCEEDED', 'FAILED', 'ABORTED'].includes(
      String(saved.publishSnapshot?.status ?? ''),
    );
  for (const item of timeline.value) {
    if (item.type !== 'build') continue;
    const itemDone = ['WAITING_HUMAN', 'SUCCEEDED', 'FAILED', 'ABORTED'].includes(
      String(item.progress?.status ?? ''),
    );
    if (alreadyDone || itemDone) item.compact = true;
  }
  nextTick(() => scrollToBottom(true));
  ElMessage.info('已恢复上次的对话');
  // 刷新会拆掉 startBuild 里那轮 waitForPublishGate。只复查一次的话，若当时 Nest
  // 还没到 WAITING_HUMAN（Leader 已在 Room 喊了 APPROVAL_REQUIRED、审批还没写进
  // artifact），按钮不会亮；之后闸门到了也不会再轮询——必须再刷一次才出现「确认发布」。
  if (saved.runId && !saved.published) {
    ensureBuildCard();
    const live = liveBuildItem();
    if (!alreadyDone) startBuildTimer(live?.startedAt);
    resumePublishGateWatch(saved.runId);
  }
}

/** 会话态一变就落盘：只存重建界面必需的数据，thinking 等瞬时态不落盘。 */
watch(
  [sessionId, stage, status, timeline, question, result, collect, draft, selectedModel, rightTab, lastRunId, publishState],
  () => {
    if (!sessionId.value) return;
    saveSession({
      sessionId: sessionId.value,
      stage: stage.value,
      status: status.value,
      timeline: timeline.value,
      question: question.value,
      result: result.value,
      collect: collect.value,
      draft: draft.value,
      selectedModel: selectedModel.value,
      llm: form.value.llm,
      rightTab: rightTab.value,
      runId: lastRunId.value,
      publishSnapshot: publishState.value?.snapshot ?? null,
      published: publishState.value?.published === true,
    });
  },
  { deep: true },
);

onUnmounted(() => {
  gateWatchId += 1;
  stopThinkingTimer();
  stopBuildTimer();
});

function stopThinkingTimer() {
  if (thinkingTimer) {
    clearInterval(thinkingTimer);
    thinkingTimer = null;
  }
}

function startThinkingTimer() {
  stopThinkingTimer();
  thinkingStartedAt.value = Date.now();
  thinkingSecs.value = 0;
  thinkingTimer = setInterval(() => {
    thinkingSecs.value = Math.floor(
      (Date.now() - thinkingStartedAt.value) / 1000,
    );
  }, 250);
}

/** 服务端返回的一回合 → 追加气泡 + 更新问题/结果/运行面板 */
function applyTurn(turn) {
  sessionId.value = turn.session_id;
  stage.value = turn.stage;
  status.value = turn.status;
  question.value = turn.question ?? null;
  result.value = turn.result ?? null;
  collect.value = turn.collect ?? null;

  // 拿掉上一轮 thinking
  timeline.value = timeline.value.filter((t) => t.type !== 'thinking');

  const msgs = turn.messages ?? [];
  const isSummaryTurn = msgs.some((m) => m.kind === 'summary');
  let summaryDividerPlaced = false;
  const thinkSecs = Math.max(
    thinkingSecs.value,
    Math.round((turn.runtime?.took_ms ?? 0) / 1000),
  );

  for (const m of msgs) {
    if (isSummaryTurn && !summaryDividerPlaced && m.kind !== 'notice') {
      timeline.value.push({
        id: `div-summary-${turn.session_id}-${Date.now()}`,
        type: 'divider',
      });
      summaryDividerPlaced = true;
    }
    const handoffSpeech = looksLikePreviewHandoff(m.content);
    if (m.kind === 'notice' && !handoffSpeech) {
      timeline.value.push({
        id: m.id,
        type: 'notice',
        html: renderMarkdown(m.content),
        kind: 'notice',
      });
      continue;
    }
    timeline.value.push({
      id: m.id,
      type: 'bubble',
      role: 'assistant',
      placement: 'start',
      variant: 'filled',
      shape: 'corner',
      html: renderMarkdown(m.content),
      kind: handoffSpeech ? 'speech' : m.kind,
      byLlm: !!m.by_llm,
      markdown: m.kind === 'summary' ? m.content : undefined,
      thinkSecs: m.kind === 'summary' ? thinkSecs : undefined,
    });
  }

  // 把上一张可交互问题卡改成只读（历史）
  for (const item of timeline.value) {
    if (item.type === 'question') item.active = false;
  }

  if (turn.question) {
    timeline.value.push({
      id: `q-${turn.question.stage}-${turn.question.key}-${Date.now()}`,
      type: 'question',
      question: turn.question,
      active: true,
    });
  }

  // 结果卡：向导每次收口都要在流末尾重新出现一张「向导已完成」。
  // 注意 DONE 之后的每一回合都仍带着上一版 session.result（改写中途也有值），
  // 因此只有 status=done 这一回合才算「又收口了一次」，否则只原地更新。
  const resultCards = timeline.value.filter((t) => t.type === 'result');
  let attachedPreview = false;
  if (turn.result) {
    const tail = timeline.value[timeline.value.length - 1]?.type;
    const tailIsResult = tail === 'result';
    if (turn.status === 'done' && !tailIsResult) {
      // 重新收口：旧的 P1 结果卡（含内嵌 P2）降级为历史，新卡进流末尾
      for (const item of resultCards) item.history = true;
      timeline.value.push({
        id: `result-${turn.session_id}-${Date.now()}`,
        type: 'result',
        result: turn.result,
        preview: turn.preview ?? null,
        history: false,
      });
      attachedPreview = !!turn.preview;
    } else if (resultCards.length) {
      const host = resultCards[resultCards.length - 1];
      host.result = turn.result;
      if (turn.preview) {
        host.preview = turn.preview;
        attachedPreview = true;
      }
    } else {
      timeline.value.push({
        id: `result-${turn.session_id}-${Date.now()}`,
        type: 'result',
        result: turn.result,
        preview: turn.preview ?? null,
        history: false,
      });
      attachedPreview = !!turn.preview;
    }
  }

  // P2 产物：CTA 直串时随回合一起到（§8.7），写到当前结果卡里
  if (turn.preview && !attachedPreview) attachPreview(turn.preview);

  runtime.value = turn.runtime;
  runtimeHistory.value.push(turn.runtime);
  scrollToBottom(true);
}

function formatThinkSecs(secs) {
  const n = Math.max(0, Math.round(Number(secs) || 0));
  if (n < 60) return `${n}秒`;
  const m = Math.floor(n / 60);
  const s = n % 60;
  return s ? `${m}分${s}秒` : `${m}分`;
}

function summaryText(item) {
  const md = String(item?.markdown ?? '').trim();
  if (md) return md;
  const html = String(item?.html ?? '');
  if (!html || typeof document === 'undefined') return '';
  const box = document.createElement('div');
  box.innerHTML = html;
  return (box.innerText || box.textContent || '').trim();
}

async function writeClipboard(text) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* 非安全上下文（http://内网 IP）会拒绝 Clipboard API */
    }
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.select();
  try {
    return document.execCommand('copy');
  } finally {
    document.body.removeChild(ta);
  }
}

async function copySummary(item) {
  const text = summaryText(item);
  if (!text) {
    ElMessage.warning('没有可复制的内容');
    return;
  }
  const ok = await writeClipboard(text);
  if (ok) ElMessage.success('已复制总结');
  else ElMessage.error('复制失败');
}

function exportSummary(item) {
  const text = summaryText(item);
  if (!text) {
    ElMessage.warning('没有可导出的内容');
    return;
  }
  const blob = new Blob([`${text}\n`], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `wizard-summary-${sessionId.value || 'draft'}.md`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  ElMessage.success('已导出 Markdown');
}

/** 当前有效的 P1 结果卡（内嵌 P2 写在这张上） */
function liveResultItem() {
  return [...timeline.value].reverse().find((row) => row.type === 'result' && !row.history) ?? null;
}

function liveBuildItem() {
  return [...timeline.value].reverse().find((row) => row.type === 'build' && !row.history) ?? null;
}

function stampBuildElapsed(secs, startedAt) {
  if (buildState.value) {
    buildState.value.elapsedSecs = secs;
    if (startedAt) buildState.value.startedAt = startedAt;
  }
  const live = liveBuildItem();
  if (live) {
    live.elapsedSecs = secs;
    if (startedAt) live.startedAt = startedAt;
  }
}

function stopBuildTimer() {
  if (buildTimer) {
    clearInterval(buildTimer);
    buildTimer = null;
  }
}

function startBuildTimer(fromMs) {
  stopBuildTimer();
  const startedAt = Number(fromMs) || Date.now();
  const tick = () => {
    stampBuildElapsed(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)), startedAt);
  };
  tick();
  buildTimer = setInterval(tick, 1000);
}

function maybeStopBuildTimer(snapshot) {
  if (isPublishGateTerminal(snapshot?.status)) stopBuildTimer();
}

/** 把 P2 结果写进当前结果卡，不再独占时间线一格 */
function attachPreview(preview) {
  const host = liveResultItem();
  if (host) host.preview = preview;
}

/** 时间线末尾那张结果卡是否已经有 P2（决定「先看看效果」是否还出现） */
const hasLiveMatch = computed(() => !!liveResultItem()?.preview);

/** 把用户的选择也显示成一条气泡（服务端只回助手侧消息） */
function pushUserBubble(label) {
  timeline.value.push({
    id: `u-${timeline.value.length}-${Date.now()}`,
    type: 'bubble',
    role: 'user',
    placement: 'end',
    variant: 'filled',
    shape: 'corner',
    html: renderMarkdown(label),
    kind: 'user',
  });
}

/** @param label 过渡态文案；P2 用偏成果的说法（§8.2②） */
function pushThinking(label = '正在整理') {
  timeline.value = timeline.value.filter((t) => t.type !== 'thinking');
  timeline.value.push({
    id: `thinking-${Date.now()}`,
    type: 'thinking',
    label,
  });
  startThinkingTimer();
  scrollToBottom(true);
}

function clearThinking() {
  stopThinkingTimer();
  timeline.value = timeline.value.filter((t) => t.type !== 'thinking');
}

function streamAwayFromBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

function syncJumpBottom() {
  const el = streamRef.value;
  if (!el) {
    showJumpBottom.value = false;
    return;
  }
  showJumpBottom.value = streamAwayFromBottom(el) > STREAM_NEAR_BOTTOM;
}

function onStreamScroll() {
  syncJumpBottom();
}

function jumpToBottom() {
  scrollToBottom(true);
}

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = streamRef.value;
    if (!el) return;
    const nearBottom = streamAwayFromBottom(el) < STREAM_NEAR_BOTTOM;
    if (force || nearBottom) {
      el.scrollTop = el.scrollHeight;
      showJumpBottom.value = false;
    } else {
      showJumpBottom.value = true;
    }
  });
}

async function start() {
  starting.value = true;
  try {
    const turn = await api.createSession({
      llm: form.value.llm,
      model: selectedModel.value,
    });
    applyTurn(turn);
  } catch (err) {
    ElMessage.error(err.message);
  } finally {
    starting.value = false;
  }
}

/** 把回传值翻译成人看的文本，用于用户侧气泡 */
function labelOf(body) {
  if (body.text) return body.text;
  const q = question.value;
  const opts = q?.groups?.length
    ? q.groups.flatMap((g) => g.options ?? [])
    : (q?.options ?? []);
  return (body.values ?? [])
    .map((v) => {
      const hit = opts.find((o) => o.id === v);
      if (hit) return hit.name;
      const qr = (q?.quick_replies ?? []).find((r) => r.value === v);
      return qr?.label ?? v;
    })
    .join('、');
}

async function answer(body) {
  if (!sessionId.value || loading.value) return;
  // ABORTED 后不再收答
  if (status.value === 'aborted') return;
  const label = labelOf(body);
  if (label) pushUserBubble(label);
  loading.value = true;
  // 当前问题卡立刻变只读，避免重复点
  for (const item of timeline.value) {
    if (item.type === 'question' && item.active) item.active = false;
  }
  question.value = null;
  pushThinking();
  try {
    const turn = await api.answer(sessionId.value, body);
    clearThinking();
    applyTurn(turn);
  } catch (err) {
    clearThinking();
    ElMessage.error(err.message);
    // 失败时把问题放回去，避免卡死
    try {
      applyTurn(await api.snapshot(sessionId.value));
    } catch {
      /* 快照也失败就保持现状 */
    }
  } finally {
    loading.value = false;
  }
}

async function onSenderSubmit() {
  if (loading.value || !sessionId.value) return;
  let text = '';
  try {
    text = (senderRef.value?.getModelValue?.()?.text ?? '').trim();
  } catch {
    text = draft.value.trim();
  }
  if (!text) text = draft.value.trim();
  if (!text) return;
  draft.value = '';
  try {
    senderRef.value?.clear?.();
  } catch {
    /* ignore */
  }
  await answer({ text });
}

/**
 * 「使用模板」：把模板文本写进底部输入框，光标落到末尾供用户改写。
 * `onDemand` 时才请求服务端现场生成（结合行业/目标），失败退回服务端给的底稿。
 */
async function useTemplate(payload) {
  if (loading.value || templateLoading.value) return;
  let text = payload?.fallback ?? '';
  if (payload?.onDemand && sessionId.value) {
    templateLoading.value = true;
    try {
      const turn = await api.briefTemplate(sessionId.value);
      if (turn?.template_text) text = turn.template_text;
      if (turn?.runtime) {
        runtime.value = turn.runtime;
        runtimeHistory.value.push(turn.runtime);
      }
    } catch (err) {
      ElMessage.warning(`模板生成失败，已填入通用模板：${err.message}`);
    } finally {
      templateLoading.value = false;
    }
  }
  if (!text) return;
  draft.value = text;
  try {
    senderRef.value?.setText?.(text);
    senderRef.value?.focus?.('end');
  } catch {
    /* setText 不可用时仍保留 draft 兜底 */
  }
}

/**
 * 显式跑一次 P2（结果卡按钮 / 匹配卡「重跑匹配」）。
 * 主路径是 CTA 直串（§8.7），这里只是重跑入口。
 */
async function runPreview() {
  if (!sessionId.value || previewLoading.value) return;
  previewLoading.value = true;
  pushThinking('正在匹配方案并调动专家团…');
  try {
    const turn = await api.preview(sessionId.value);
    clearThinking();
    attachPreview(turn.result);
    runtime.value = turn.runtime;
    runtimeHistory.value.push(turn.runtime);
    scrollToBottom(true);
  } catch (err) {
    clearThinking();
    ElMessage.error(err.message);
  } finally {
    previewLoading.value = false;
  }
}

async function loadRunSnapshot(runId) {
  if (orchestrationMode !== 'platform') return fromPipelineGet(await pipelineApi.get(runId));
  // platform 下 manager 只报自己的编排状态（DISPATCHED…），阶段与 approval 要看 Nest，
  // 否则 Nest 已 WAITING_HUMAN 而向导还在转圈。Nest 读失败时退回 manager，不让轮询中断。
  const managerData = await managerApi.get(runId);
  let pipelineData = null;
  let nestUnreachable = '';
  try {
    pipelineData = await pipelineApi.get(runId);
  } catch (err) {
    nestUnreachable = err?.message || String(err);
  }
  let snapshot = mergePlatformSnapshot(managerData, pipelineData);
  if (nestUnreachable) snapshot = { ...snapshot, nestUnreachable };
  if (snapshot.approvalId && snapshot.status === 'WAITING_HUMAN') return snapshot;
  // Team Room 聊天文本（Leader 的口头汇报）只当作「可能到闸门了」的线索去加速下一轮轮询，
  // 不直接采信当真——Leader 曾在 Nest 真正进入 WAITING_HUMAN 之前就编出这句话（run
  // aef1e08b，approval_id 本身也是假的）。真正的 approvalId/status 只认 Nest/manager
  // 下一次真实返回的值，applyApprovalGate 只标注 approvalGuess，不改 snapshot.status。
  try {
    snapshot = applyApprovalGate(snapshot, extractApprovalId((await managerApi.room(runId)).messages, runId));
  } catch {
    /* Room 读失败不阻断闸门轮询 */
  }
  return snapshot;
}

function updateThinkingLabel(label) {
  const item = [...timeline.value].reverse().find((row) => row.type === 'thinking');
  if (item) item.label = label;
}

/** 旧会话把 P2 做成独立 match 格：折进最近一张结果卡，避免刷新后丢匹配。 */
function attachLegacyMatchCards(items) {
  const next = [];
  for (const item of items ?? []) {
    if (item?.type !== 'match') {
      next.push(item);
      continue;
    }
    const host =
      [...next].reverse().find((row) => row.type === 'result' && !!row.history === !!item.history) ||
      [...next].reverse().find((row) => row.type === 'result');
    if (host && !host.preview) host.preview = item.preview;
  }
  for (const item of next) {
    if (item.type !== 'publish' || item.preview) continue;
    const host =
      [...next].reverse().find((row) => row.type === 'result' && row.preview) ||
      next.find((row) => row.type === 'result' && row.preview);
    if (host) item.preview = host.preview;
  }
  return next.map(promotePreviewHandoffBubble);
}

/** 匹配过渡话术是向导回复，旧会话误存成 notice。 */
function looksLikePreviewHandoff(text) {
  return /匹配(可用)?方案/.test(String(text ?? ''));
}

function promotePreviewHandoffBubble(item) {
  if (!item || (item.type !== 'notice' && item.kind !== 'notice')) return item;
  const text = `${item.markdown ?? ''} ${item.html ?? ''}`;
  if (!looksLikePreviewHandoff(text)) return item;
  return {
    ...item,
    type: 'bubble',
    role: 'assistant',
    placement: 'start',
    variant: 'filled',
    shape: 'corner',
    kind: 'speech',
  };
}

function ensureBuildCard() {
  if (timeline.value.some((row) => row.type === 'build' && !row.history)) return;
  const item = { id: `build-restore-${Date.now()}`, type: 'build', history: false };
  const idx = timeline.value.findIndex((row) => row.type === 'publish' && !row.history);
  if (idx >= 0) timeline.value.splice(idx, 0, item);
  else timeline.value.push(item);
}

function pushBuildCard() {
  for (const item of timeline.value) {
    if (item.type === 'build' && !item.history) {
      item.history = true;
      item.progress = buildState.value?.progress ?? item.progress;
      item.error = buildState.value?.error ?? item.error;
      item.elapsedSecs = buildState.value?.elapsedSecs ?? item.elapsedSecs;
      item.startedAt = buildState.value?.startedAt ?? item.startedAt;
    }
    if (item.type === 'publish') item.history = true;
  }
  const startedAt = Date.now();
  timeline.value.push({
    id: `build-${Date.now()}`,
    type: 'build',
    history: false,
    startedAt,
    elapsedSecs: 0,
  });
}

function openExpertRoom() {
  showRuntime.value = true;
  rightTab.value = 'expert-room';
}

async function refreshBuildProgress(runId) {
  let room = { messages: [] };
  if (orchestrationMode === 'platform') {
    try {
      room = await managerApi.room(runId);
    } catch {
      /* Room 读失败不阻断过程卡 */
    }
  }
  const snapshot = await loadRunSnapshot(runId);
  const progress = deriveBuildSteps({
    artifacts: snapshot.artifacts,
    status: snapshot.status,
    phase: snapshot.phase,
    mode: orchestrationMode,
    roomMessages: room.messages ?? [],
  });
  const blocked = leaderBlockedMessage(room);
  const error = blocked ? `Team Leader 没有继续编排：${blocked.slice(0, 120)}` : '';
  buildState.value = {
    runId,
    snapshot,
    progress,
    error,
    elapsedSecs: buildState.value?.elapsedSecs ?? liveBuildItem()?.elapsedSecs ?? 0,
    startedAt: buildState.value?.startedAt ?? liveBuildItem()?.startedAt,
  };
  const live = liveBuildItem();
  if (live) {
    live.progress = progress;
    live.error = error;
  }
  maybeStopBuildTimer(snapshot);
  return { snapshot, blocked, progress };
}

// platform 编排要等 Leader 逐棒派活 + 真模型跑完 P3C 五步（Guidance、四专家、compose、
// selfcheck、persist）再到 P4 pending_approval，实测 15–25 分钟；原来 240×2s=8 分钟远远
// 不够，超时后又直接抛错、publishState 不落地，于是 run 明明已到 WAITING_HUMAN 却再也点
// 不到「确认发布」。这里给足 30 分钟，并把超时改成可恢复（见下方 timedOut 分支）。
function syncPublicationFromSnapshot(snapshot) {
  if (snapshot?.status !== 'SUCCEEDED') return;
  const publication = publicationFromSnapshot(snapshot);
  if (!publication.clientCode || !publication.runtimeAgentId) return;
  savePublication(publication);
  sandboxPublication.value = loadPublication();
}

function rememberPublishSnapshot(snapshot, extra = {}) {
  const published = snapshot.status === 'SUCCEEDED';
  if (published) syncPublicationFromSnapshot(snapshot);
  const error = Object.prototype.hasOwnProperty.call(extra, 'error') ? extra.error : publishState.value?.error || '';
  if (!publishState.value) {
    publishState.value = { snapshot, published, publishing: false, error };
    const live = timeline.value.some((row) => row.type === 'publish' && !row.history);
    if (!live) pushPublishCard();
    return;
  }
  publishState.value.snapshot = snapshot;
  publishState.value.published = published;
  if (Object.prototype.hasOwnProperty.call(extra, 'error')) publishState.value.error = extra.error;
}

function resumePublishGateWatch(runId) {
  const watchId = ++gateWatchId;
  waitForPublishGate(runId, watchId).catch(() => {});
}

async function waitForPublishGate(runId, watchId = ++gateWatchId) {
  const attempts = orchestrationMode === 'platform' ? 900 : 60;
  const delayMs = orchestrationMode === 'platform' ? 2000 : 1500;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (watchId !== gateWatchId) {
      return publishState.value?.snapshot || { runId, status: 'RUNNING' };
    }
    const { snapshot, blocked } = await refreshBuildProgress(runId);
    if (isPublishGateTerminal(snapshot.status)) {
      rememberPublishSnapshot(snapshot, { error: '' });
      return snapshot;
    }
    // Room 卡住只提示、不结束轮询。RUN_BLOCKED 常几秒自愈；Leader 从 BLOCKED_HUMAN
    // 走到 APPROVAL_REQUIRED 也一样。这里 return 的话，按钮只能靠刷新出现。
    if (blocked) {
      rememberPublishSnapshot(snapshot, {
        error: `Team Leader 没有继续编排：${blocked.slice(0, 120)}`,
      });
    } else if (publishState.value) {
      publishState.value.snapshot = snapshot;
      publishState.value.error = '';
    }
    // approvalGuess 是 Leader 口头汇报的线索，不采信为真，但值得更快去问一次权威源，
    // 免得用户在「Leader 已经喊了 APPROVAL_REQUIRED」和「按钮还没点亮」之间多等一轮。
    await new Promise((resolve) => setTimeout(resolve, snapshot.approvalGuess ? Math.min(delayMs, 500) : delayMs));
  }
  // 不抛错：把最后一次快照带 timedOut 交回去，startBuild 会照常落 publishState。
  // 这样即使编排比预期更慢，用户仍能用「检查发布闸门」按钮复查，run 一旦到
  // WAITING_HUMAN 就能立刻点「确认发布」，不必重跑整条流水线。
  const last = await refreshBuildProgress(runId);
  const snapshot = { ...last.snapshot, timedOut: true };
  rememberPublishSnapshot(snapshot);
  return snapshot;
}

/** 超时/刷新后手动复查闸门：run 到 WAITING_HUMAN 就补出「确认发布」卡片。 */
async function recheckPublishGate() {
  const runId = publishState.value?.snapshot?.runId || lastRunId.value;
  if (!runId || gateRechecking.value || publishing.value) return;
  gateRechecking.value = true;
  try {
    ensureBuildCard();
    const { snapshot } = await refreshBuildProgress(runId);
    publishState.value = {
      snapshot,
      published: snapshot.status === 'SUCCEEDED',
      publishing: false,
      error: buildState.value?.error || '',
    };
    if (snapshot.status === 'SUCCEEDED') syncPublicationFromSnapshot(snapshot);
    if (['WAITING_HUMAN', 'SUCCEEDED'].includes(snapshot.status)) {
      // 恢复会话时 timeline 里可能已带着上次那张 publish 卡，别再追加一张
      const live = timeline.value.some((row) => row.type === 'publish' && !row.history);
      if (!live) pushPublishCard();
      scrollToBottom(true);
      ElMessage.success(snapshot.status === 'WAITING_HUMAN' ? '闸门已就绪，请确认后发布' : '已发布，可以开始沙盒试聊');
    } else if (snapshot.nestUnreachable) {
      ElMessage.warning(
        `Nest 快照读失败（${snapshot.nestUnreachable}），manager 仍是 ${snapshot.status || '进行中'}。闸门以 Nest 为准，修好后再查。`,
      );
    } else if (['FAILED', 'ABORTED'].includes(snapshot.status)) {
      // 终态：复查再多次也不会变，别用「稍后再查」误导用户干等
      ElMessage.error(
        `run ${snapshot.runId?.slice(0, 8) || ''} 已 ${snapshot.status}（${snapshot.phase || '—'}），` +
          '不会再推进。请点「返回修改需求」重新生成。',
      );
    } else {
      ElMessage.info(`run 仍在 ${snapshot.status || '进行中'}（${snapshot.phase || '—'}），稍后再查`);
    }
  } catch (err) {
    ElMessage.error(err.message || String(err));
  } finally {
    gateRechecking.value = false;
  }
}

/**
 * 「重试」：不重跑整条流水线，只是补一条 @Leader 的提醒让它重新派活/重试当前阶段。
 * 对应 manager 的 nudge 端点——本质等价于人工在 Team Room 手敲一条消息，run 本身
 * 没有被 abort，Leader 已完成的步骤（如 composeBlueprint 拿到的 blueprintId）不丢。
 */
async function nudgeRun() {
  const runId = publishState.value?.snapshot?.runId;
  if (!runId || nudging.value) return;
  nudging.value = true;
  try {
    await managerApi.nudge(runId);
    if (publishState.value) publishState.value.error = '';
    if (buildState.value) buildState.value.error = '';
    ElMessage.success('已提醒 Team Leader 重试，稍后点「检查发布闸门」查看进展');
  } catch (err) {
    ElMessage.error(`重试提醒失败：${err.message || err}`);
  } finally {
    nudging.value = false;
  }
}

function pushPublishCard() {
  for (const item of timeline.value) {
    if (item.type === 'publish') item.history = true;
  }
  timeline.value.push({
    id: `publish-${publishState.value?.snapshot?.runId || Date.now()}`,
    type: 'publish',
    history: false,
    preview: liveResultItem()?.preview ?? null,
  });
}

/**
 * 开始生成前先处理「上一次还没跑完的 run」。
 *
 * 所有 Worker 共用同一个 Team Room：并行两个 run 会让 Leader 收到交叉的 run_id、
 * 互相抢 Worker，两条流水线都跑不干净（实测刷新页面后再点一次生成，Room 里出现
 * 59d10404 与 eb22b8e5 同时推进）。所以新 run 之前必须让旧 run 收口。
 *
 * 要不要终止交给用户决定：返回 true 表示可以继续开新 run。
 */
async function settlePreviousRun() {
  const runId = lastRunId.value;
  if (!runId) return true;
  let snapshot;
  try {
    snapshot = await loadRunSnapshot(runId);
  } catch {
    return true; // 查不到（已清库等）就不拦着
  }
  if (!['RUNNING', 'WAITING_HUMAN'].includes(snapshot.status)) return true;

  const waiting = snapshot.status === 'WAITING_HUMAN';
  try {
    await ElMessageBox.confirm(
      waiting
        ? `上一个 run ${runId.slice(0, 8)} 已经生成完、正在等你确认发布（${snapshot.phase || 'P4'}）。` +
            '重新生成会终止它，之前十几分钟的编排结果就作废了。'
        : `上一个 run ${runId.slice(0, 8)} 还在生成中（${snapshot.status}／${snapshot.phase || '—'}）。` +
            '所有 Worker 共用一个 Team Room，两个 run 并行会互相抢占、都跑不完。',
      waiting ? '上一个结果还没发布' : '上一个生成还没结束',
      {
        confirmButtonText: waiting ? '终止它并重新生成' : '终止它并重新生成',
        cancelButtonText: waiting ? '先去确认发布' : '继续等待',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    );
  } catch {
    // 取消：不开新 run。等待中的就把闸门复查出来，让用户直接去发布。
    await recheckPublishGate();
    return false;
  }

  try {
    const body = { reason: '用户在向导里重新生成，终止上一个未完成的 run' };
    if (orchestrationMode === 'platform') await managerApi.abort(runId, body);
    else await pipelineApi.abort(runId, body);
    ElMessage.success(`已终止 ${runId.slice(0, 8)}`);
  } catch (err) {
    ElMessage.warning(`终止上一个 run 失败：${err.message || err}。继续新建，但请留意 Room 里是否有交叉 run。`);
  }
  publishState.value = null;
  buildState.value = null;
  stopBuildTimer();
  clearLastRun();
  return true;
}

/** 同一个 CTA 按 VITE_ORCHESTRATION_MODE 选择本地权威管线或 AgentTeams 平台编排。 */
async function startBuild() {
  if (!result.value || result.value.gate !== 'PASS' || buildLoading.value) return;
  if (!(await settlePreviousRun())) return;
  buildLoading.value = true;
  buildState.value = {
    runId: '',
    snapshot: null,
    progress: emptyBuildProgress(orchestrationMode),
    error: '',
    elapsedSecs: 0,
    startedAt: Date.now(),
  };
  pushBuildCard();
  startBuildTimer(liveBuildItem()?.startedAt);
  scrollToBottom(true);
  try {
    const phase1 = result.value;
    orchestrationRun.value = await createBuildRun(phase1, orchestrationMode, { managerApi, pipelineApi });
    lastRunId.value = orchestrationRun.value.run_id || '';
    localStorage.setItem('agent-console.last-run-id', orchestrationRun.value.run_id);
    localStorage.setItem('agent-console.last-run-mode', orchestrationMode);
    const watchId = ++gateWatchId;
    await refreshBuildProgress(orchestrationRun.value.run_id);
    const snapshot = await waitForPublishGate(orchestrationRun.value.run_id, watchId);
    if (watchId !== gateWatchId) return;
    scrollToBottom(true);
    if (snapshot.timedOut) {
      ElMessage.warning(
        `编排还没到发布闸门（当前 ${snapshot.status || '进行中'}／${snapshot.phase || '—'}）。` +
          '不用重跑，稍后点卡片上的「检查发布闸门」即可继续。',
      );
    } else if (publishState.value?.error) {
      ElMessage.warning('Team Leader 卡住了，可以在卡片上点「重试」（多数是几秒能自愈的连接抖动）。');
    } else {
      ElMessage.success(
        snapshot.status === 'WAITING_HUMAN' ? '已生成，请确认后发布' : '已创建 run：' + snapshot.runId,
      );
    }
  } catch (err) {
    stopBuildTimer();
    if (buildState.value) buildState.value.error = err.message || String(err);
    ElMessage.error(err.message);
  } finally {
    buildLoading.value = false;
  }
}

/** approval_id 与 Nest/manager 真实审批状态不符时的报错，是「Leader 汇报与权威状态不一致」
 *  的典型指纹（曾实测发生：run aef1e08b，approval_id 本身也是 Leader 编的）。命中这些报错时，
 *  这个 approval_id 应视为永久失效，本次会话内不再靠它点亮发布按钮，避免反复撞同一个 409。 */
function isApprovalMismatchError(message) {
  return /run is not waiting for P4 approval|approval request is missing or already decided|approval credential mismatch or already consumed|P4 approval cannot be reopened/i.test(
    String(message ?? ''),
  );
}

function pushFailureNotice(text) {
  timeline.value.push({
    id: `notice-publish-failed-${Date.now()}`,
    type: 'notice',
    kind: 'notice',
    html: renderMarkdown(text),
  });
}

async function confirmPublish() {
  const state = publishState.value;
  if (!state?.snapshot?.approvalId || publishing.value || state.published) return;
  const submittedApprovalId = state.snapshot.approvalId;
  if ((state.rejectedApprovalIds ?? []).includes(submittedApprovalId)) {
    ElMessage.warning('这个 approval_id 已经被拒绝过一次，请先点「检查发布闸门」核对最新状态');
    return;
  }
  publishing.value = true;
  state.publishing = true;
  state.error = '';
  await nextTick();
  try {
    const body = { approval_id: submittedApprovalId, approved: true };
    const decided =
      orchestrationMode === 'platform'
        ? await managerApi.approve(state.snapshot.runId, body)
        : await pipelineApi.approve(state.snapshot.runId, body);
    let snapshot = await loadRunSnapshot(state.snapshot.runId);
    state.snapshot = snapshot;
    const attempts = orchestrationMode === 'platform' ? 180 : 1;
    for (let attempt = 0; attempt < attempts && snapshot.status !== 'SUCCEEDED' && !['FAILED', 'ABORTED'].includes(snapshot.status); attempt += 1) {
      updateThinkingLabel('正在发布并导入智能体…');
      await new Promise((resolve) => setTimeout(resolve, 2000));
      snapshot = await loadRunSnapshot(state.snapshot.runId);
      state.snapshot = snapshot;
    }
    state.published = snapshot.status === 'SUCCEEDED';
    savePublication(publicationFromApprove(decided, snapshot));
    sandboxPublication.value = loadPublication();
    if (state.published) {
      ElMessage.success('已发布，可以开始沙盒试聊');
      if (isChatReady(sandboxPublication.value)) goChat();
    } else if (['FAILED', 'ABORTED'].includes(snapshot.status)) {
      state.error = `发布未完成：${snapshot.status}`;
      ElMessage.error(state.error);
      pushFailureNotice(`⚠️ 发布未完成：run \`${snapshot.runId?.slice(0, 8) || ''}\` 已 ${snapshot.status}，不会再推进。`);
      scrollToBottom(true);
    } else {
      ElMessage.success('已提交发布，导入还在进行，稍后点「检查发布闸门」');
    }
    scrollToBottom(true);
  } catch (err) {
    const message = err.message || String(err);
    const mismatch = isApprovalMismatchError(message);
    try {
      state.snapshot = await loadRunSnapshot(state.snapshot.runId);
    } catch {
      /* keep the gate snapshot so the card can still show FAILED after reload */
    }
    if (mismatch) {
      state.rejectedApprovalIds = [...(state.rejectedApprovalIds ?? []), submittedApprovalId];
      if (state.snapshot?.status === 'SUCCEEDED') {
        state.published = true;
        state.error = '';
        syncPublicationFromSnapshot(state.snapshot);
        ElMessage.success('该 run 已经发布完成，无需再点确认发布');
        if (isChatReady(sandboxPublication.value)) goChat();
        return;
      }
    }
    state.error = message;
    ElMessage.error(state.error);
    // 不能让 409 悄悄消失在一条几秒后就消失的 toast 里：留痕到时间线，刷新页面后仍可见。
    pushFailureNotice(
      mismatch
        ? `⚠️ 确认发布失败：${message}\n\nTeam Leader 汇报的审批状态与 Nest 实际状态不一致，请点「检查发布闸门」重新核对，不要重复点击确认发布。`
        : `⚠️ 确认发布失败：${message}`,
    );
    scrollToBottom(true);
  } finally {
    publishing.value = false;
    if (publishState.value) publishState.value.publishing = false;
  }
}

function goChat() {
  sandboxPublication.value = loadPublication();
  if (!isChatReady(sandboxPublication.value)) {
    ElMessage.warning('还没有可对话的智能体，请先确认发布 P3C 产物');
    return;
  }
  showRuntime.value = true;
  rightTab.value = 'sandbox';
}

function clearLastRun() {
  lastRunId.value = '';
  localStorage.removeItem('agent-console.last-run-id');
  localStorage.removeItem('agent-console.last-run-mode');
}

/**
 * 重新开始：清掉本地会话，并顺手收口还在跑的 run。
 * 不收口的话它会继续在 Team Room 里推进、和下一个 run 抢 Worker（§3.19）。
 */
async function restart() {
  if (publishing.value) {
    ElMessage.warning('正在发布，请等当前这次结束再重新开始');
    return;
  }
  if (lastRunId.value) {
    const runId = lastRunId.value;
    try {
      const snapshot = await loadRunSnapshot(runId);
      if (['RUNNING', 'WAITING_HUMAN'].includes(snapshot.status)) {
        const body = { reason: '用户在向导里重新开始，收口未完成的 run' };
        if (orchestrationMode === 'platform') await managerApi.abort(runId, body);
        else await pipelineApi.abort(runId, body);
        ElMessage.success(`已终止上一个 run ${runId.slice(0, 8)}`);
      }
    } catch {
      /* 查不到或终止失败都不该拦住「重新开始」 */
    }
  }
  clearThinking();
  sessionId.value = '';
  stage.value = '';
  status.value = '';
  timeline.value = [];
  question.value = null;
  result.value = null;
  collect.value = null;
  runtime.value = null;
  runtimeHistory.value = [];
  orchestrationRun.value = null;
  publishState.value = null;
  buildState.value = null;
  stopBuildTimer();
  clearLastRun();
  // 显式重开会话时也要丢掉缓存，否则下次刷新又被恢复成这条已废弃的对话
  clearSession();
  sandboxPublication.value = loadPublication();
  rightTab.value = 'collect';
  draft.value = '';
}
</script>

<template>
  <div class="wz-app">
    <header class="wz-header">
      <h1>智能体助手</h1>
      <el-tag v-if="stage" size="small" type="info" effect="plain">
        {{ stage }}
      </el-tag>
      <el-tag
        v-if="status === 'done'"
        size="small"
        type="success"
        effect="plain"
      >
        已完成
      </el-tag>
      <span class="wz-spacer" />
      <el-select
        v-model="selectedModel"
        class="wz-model-select"
        size="small"
        :disabled="started"
        placeholder="选择模型"
      >
        <el-option
          v-for="item in wizardModels"
          :key="item"
          :label="item"
          :value="item"
        />
      </el-select>
      <el-tag
        v-if="health && !health.llm_available"
        size="small"
        type="warning"
        effect="plain"
      >
        未配置 API Key
      </el-tag>
      <el-switch
        v-if="started"
        v-model="showRuntime"
        size="small"
        active-text="侧栏"
      />
      <el-button v-if="started" size="small" text bg :disabled="publishing" @click="restart">
        重新开始
      </el-button>
    </header>

    <div class="wz-body">
      <main class="wz-chat">
        <!-- 未开始：欢迎页 -->
        <div v-if="!started" class="wz-stream">
          <div class="wz-start">
            <Welcome
              variant="filled"
              icon="🚀"
              title="从一句话开始，搭出你的第一个销售运营 Agent"
              description="我会先问几个问题（行业、想让 AI 先干什么、业务简述），再整理成总结并匹配行业最佳实践。开始后可在右侧查看信息收集和生成进度；发布后可切到沙盒试聊。"
            />
            <div class="wz-start__form">
              <div v-if="tenants.length" class="wz-start__row">
                <label>账号</label>
                <el-select
                  v-model="selectedTenant"
                  size="small"
                  style="width: 220px"
                  @change="onTenantChange"
                >
                  <el-option
                    v-for="item in tenants"
                    :key="item.client_code"
                    :label="tenantLabel(item.client_code)"
                    :value="item.client_code"
                  />
                </el-select>
                <span class="wz-note">换账号只换 Wizard Bearer，不必重启 Nest。</span>
              </div>
              <div class="wz-start__row">
                <label>LLM</label>
                <el-switch
                  v-model="form.llm"
                  :disabled="!health?.llm_available"
                />
                <span class="wz-note">
                  {{
                    health?.llm_available
                      ? '开：模型润色话术与出题；关：走模板话术，不消耗 token'
                      : '未配置 DASHSCOPE_API_KEY，只能走模板话术'
                  }}
                </span>
              </div>
              <div>
                <el-button
                  type="primary"
                  :loading="starting"
                  @click="start"
                >
                  开始
                </el-button>
              </div>
              <div class="wz-note">
                租户由当前 Bearer 凭证在服务端绑定，页面不会提交 client_code。
                <template v-if="health?.client_code">当前 {{ health.client_code }}。</template>
              </div>
            </div>
          </div>
        </div>

        <!-- 会话进行中：单一时间线 + 常驻输入 -->
        <template v-else>
          <div ref="streamRef" class="wz-stream" @scroll="onStreamScroll">
            <div
              v-for="item in timeline"
              :key="item.id"
              class="wz-timeline-item"
              :class="[
                `is-${item.type}`,
                item.kind === 'notice' && 'is-notice',
                item.placement === 'end' && 'is-user',
                item.type === 'bubble' && item.placement === 'start' && 'is-assistant',
              ]"
            >
              <!-- 系统提示：微信系统消息弱化样式（无气泡） -->
              <div
                v-if="item.type === 'notice' || item.kind === 'notice'"
                class="wz-sys-notice"
              >
                <div class="wz-sys-notice__text wz-md" v-html="item.html" />
              </div>

              <!-- 生成总结这一轮：向导发言上方的分隔 -->
              <div
                v-else-if="item.type === 'divider'"
                class="wz-divider"
                role="separator"
              />

              <!-- 聊天气泡 -->
              <Bubble
                v-else-if="item.type === 'bubble'"
                :placement="item.placement"
                :variant="item.variant"
                :shape="item.shape"
                avatar=""
              >
                <template #content>
                  <div class="wz-md">
                    <div class="wz-md__body" v-html="item.html" />
                    <!-- 开发态 AI 角标，暂时隐藏
                    <span
                      v-if="item.byLlm && isDev"
                      class="wz-badge-llm"
                    >AI</span>
                    -->
                  </div>
                </template>
                <template v-if="item.kind === 'summary'" #footer>
                  <div class="wz-summary-bar">
                    <el-tooltip content="复制" placement="top">
                      <button
                        type="button"
                        class="wz-summary-bar__btn"
                        aria-label="复制"
                        @click.stop="copySummary(item)"
                      >
                        <el-icon><DocumentCopy /></el-icon>
                      </button>
                    </el-tooltip>
                    <el-tooltip content="导出 Markdown" placement="top">
                      <button
                        type="button"
                        class="wz-summary-bar__btn"
                        aria-label="导出 Markdown"
                        @click.stop="exportSummary(item)"
                      >
                        <el-icon><Download /></el-icon>
                      </button>
                    </el-tooltip>
                    <span
                      v-if="item.thinkSecs != null"
                      class="wz-summary-bar__time"
                    >
                      思考 {{ formatThinkSecs(item.thinkSecs) }}
                    </span>
                  </div>
                </template>
              </Bubble>

              <!-- 思考中：一行弱提示，对齐竞品「思考中... Ns」 -->
              <div
                v-else-if="item.type === 'thinking'"
                class="wz-thinking"
                aria-live="polite"
                aria-label="思考中"
              >
                <span class="wz-thinking__spin" aria-hidden="true" />
                <span class="wz-thinking__text">
                  思考中...{{ thinkingSecs ? ` ${thinkingSecs}秒` : '' }}
                </span>
              </div>

              <!-- 交互卡片（历史只读，最新一张可交互） -->
              <div
                v-else-if="item.type === 'question'"
                class="wz-qcard"
                :class="{ 'is-history': !item.active }"
              >
                <QuestionCard
                  :question="item.question"
                  :loading="loading"
                  :disabled="!item.active || loading"
                  :template-loading="item.active && templateLoading"
                  @answer="answer"
                  @use-template="useTemplate"
                />
              </div>

              <!-- P1 收口结果（历史版本只读，不挂 P2 入口） -->
              <ResultCard
                v-else-if="item.type === 'result'"
                :result="item.result || result"
                :has-preview="item.history ? !!item.preview : hasLiveMatch"
                :preview="item.preview"
                :preview-loading="item.history ? false : previewLoading"
                :build-loading="item.history ? false : buildLoading"
                :orchestration-mode="orchestrationMode"
                :orchestration-run="item.history ? null : orchestrationRun"
                :build-started="item.history ? true : !!buildState || !!publishState"
                :history="!!item.history"
                @preview="runPreview"
                @build="startBuild"
              />

              <BuildProgressCard
                v-else-if="item.type === 'build'"
                :progress="item.history ? item.progress : (buildState?.progress ?? item.progress)"
                :error="item.history ? item.error || '' : (buildState?.error || item.error || '')"
                :elapsed-secs="item.history ? item.elapsedSecs || 0 : (buildState?.elapsedSecs ?? item.elapsedSecs ?? 0)"
                :compact="!!item.compact"
                :mode="orchestrationMode"
                :show-inspector="showInspector"
                :history="!!item.history"
                @open-room="openExpertRoom"
              />

              <PublishCard
                v-else-if="item.type === 'publish'"
                :phase1="result"
                :snapshot="publishState?.snapshot"
                :published="!!publishState?.published"
                :publishing="publishing"
                :error="publishState?.error || ''"
                :history="!!item.history"
                :rechecking="gateRechecking"
                :nudging="nudging"
                :rejected-approval-ids="publishState?.rejectedApprovalIds ?? []"
                :preview="item.history ? item.preview : liveResultItem()?.preview"
                @publish="confirmPublish"
                @revise="restart"
                @chat="goChat"
                @recheck="recheckPublishGate"
                @nudge="nudgeRun"
              />
            </div>
          </div>

          <div class="wz-compose-dock">
            <el-tooltip content="回到底部" placement="top">
              <button
                v-show="showJumpBottom"
                type="button"
                class="wz-jump-bottom"
                aria-label="回到底部"
                @click="jumpToBottom"
              >
                <el-icon><ArrowDown /></el-icon>
              </button>
            </el-tooltip>
            <div class="wz-compose">
              <XSender
                ref="senderRef"
                :placeholder="senderPlaceholder"
                :loading="loading"
                :disabled="loading || status === 'aborted'"
                :tip-config="false"
                submit-type="enter"
                @submit="onSenderSubmit"
              />
            </div>
          </div>
        </template>
      </main>

      <aside v-if="started && showRuntime" class="wz-runtime">
        <el-tabs v-model="rightTab" class="wz-runtime-tabs">
          <el-tab-pane label="信息收集" name="collect">
            <CollectPanel :collect="collect" />
          </el-tab-pane>
          <el-tab-pane name="sandbox" :disabled="!sandboxReady">
            <template #label>
              <span :title="sandboxReady ? '' : '请先确认发布可对话的智能体'">沙盒试聊</span>
            </template>
            <ChatView embedded :publication="sandboxPublication" />
          </el-tab-pane>
          <el-tab-pane v-if="showInspector" label="产物" name="artifacts">
            <ArtifactPanel
              :run-id="publishState?.snapshot?.runId || orchestrationRun?.run_id || lastRunId || ''"
              :snapshot="publishState?.snapshot"
              :active="rightTab === 'artifacts'"
            />
          </el-tab-pane>
          <el-tab-pane v-if="showInspector" label="专家团" name="expert-room">
            <ExpertRoomPanel
              :run-id="publishState?.snapshot?.runId || orchestrationRun?.run_id || lastRunId || ''"
              :orchestration-mode="orchestrationMode"
              :active="rightTab === 'expert-room'"
            />
          </el-tab-pane>
          <el-tab-pane label="运行情况" name="runtime">
            <RuntimePanel :runtime="runtime" :history="runtimeHistory" />
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>
  </div>
</template>
