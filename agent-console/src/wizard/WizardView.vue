<script setup>
/**
 * App —— 智能体助手（Web）
 *
 * 两栏布局：左对话流 / 右 Tabs（信息收集 · 沙盒试聊 · 运行情况）。
 * 向导首页不显示右侧 Tabs；开始会话后才出现。未发布可对话产物时「沙盒试聊」禁用。
 * 对话流是单一时间线（bubble / thinking / question / result），
 * 底部常驻 XSender；交互卡片与思考态都在流内，只有一个滚动容器。
 *
 * 前端只做：发请求、按 WizardTurn 渲染、把用户选择回传。
 * 契约见 `agent-core/src/web/web.types.ts`。
 */

import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { wizardApi as api, managerApi, pipelineApi } from '../shared/api';
import { createBuildRun } from './build-run';
import { renderMarkdown } from './markdown';
import { savePublication, loadPublication, isChatReady } from '../shared/publication';
import { fromManagerGet, fromPipelineGet, publicationFromApprove } from '../shared/run-snapshot';
import QuestionCard from './components/QuestionCard.vue';
import ResultCard from './components/ResultCard.vue';
import MatchCard from './components/MatchCard.vue';
import PublishCard from './components/PublishCard.vue';
import RuntimePanel from './components/RuntimePanel.vue';
import CollectPanel from './components/CollectPanel.vue';
import ChatView from '../chat/ChatView.vue';
import {
  DEFAULT_WIZARD_LLM_MODEL,
  resolveWizardLlmModel,
  WIZARD_LLM_MODELS,
} from '../shared/wizard-models';

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
 * 统一时间线：bubble | thinking | question | result | match | publish
 * `match` 是 P2 结果（§8.6）：进时间线后就自动复用 history 降级机制，
 * 不再需要在收口处手工清空全局预览态。
 * `publish` 是生成进度 + 确认发布（v5：Human Gate 留在向导内）。
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
const publishState = ref(null);
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
watch(sandboxReady, (ok) => {
  if (!ok && rightTab.value === 'sandbox') rightTab.value = 'collect';
});

const streamRef = ref(null);
const senderRef = ref(null);
/** 思考态秒表 */
const thinkingStartedAt = ref(0);
const thinkingSecs = ref(0);
let thinkingTimer = null;

const started = computed(() => !!sessionId.value);

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
    ElMessage.error(
      /unauthorized|401/i.test(msg)
        ? '鉴权失败：请用 Vite 控制台 http://127.0.0.1:5173 ，不要直接打开 Nest :3100。:3100 是 API，静态页里的默认凭证对不上当前后端。'
        : `后端未就绪：${msg}`,
    );
  }
});

onUnmounted(() => stopThinkingTimer());

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

  for (const m of turn.messages ?? []) {
    timeline.value.push({
      id: m.id,
      type: 'bubble',
      role: 'assistant',
      placement: 'start',
      variant: m.kind === 'notice' ? 'outlined' : 'filled',
      shape: 'corner',
      html: renderMarkdown(m.content),
      kind: m.kind,
      byLlm: !!m.by_llm,
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
  if (turn.result) {
    const tail = timeline.value[timeline.value.length - 1]?.type;
    const tailIsCard = tail === 'result' || tail === 'match';
    if (turn.status === 'done' && !tailIsCard) {
      // 重新收口：旧的 P1 结果卡与 P2 匹配卡一起降级为历史，新卡进流末尾
      for (const item of resultCards) item.history = true;
      markMatchHistory();
      timeline.value.push({
        id: `result-${turn.session_id}-${Date.now()}`,
        type: 'result',
        result: turn.result,
        history: false,
      });
    } else if (resultCards.length) {
      resultCards[resultCards.length - 1].result = turn.result;
    } else {
      timeline.value.push({
        id: `result-${turn.session_id}-${Date.now()}`,
        type: 'result',
        result: turn.result,
        history: false,
      });
    }
  }

  // P2 产物：CTA 直串时随回合一起到（§8.7），推成时间线独立一格
  if (turn.preview) pushMatchCard(turn.preview, turn.session_id);

  runtime.value = turn.runtime;
  runtimeHistory.value.push(turn.runtime);
  scrollToBottom(true);
}

/** 已有的 P2 匹配卡降级为历史留痕（跟 question / result 卡同一套机制） */
function markMatchHistory() {
  for (const item of timeline.value) {
    if (item.type === 'match') item.history = true;
  }
}

/** P2 结果推进时间线：旧卡留痕，新卡进流末尾 */
function pushMatchCard(preview, sessionId_) {
  markMatchHistory();
  timeline.value.push({
    id: `match-${sessionId_}-${Date.now()}`,
    type: 'match',
    preview,
    history: false,
  });
}

/** 时间线末尾是否已经有一张当前有效的 P2 匹配卡（决定按钮文案） */
const hasLiveMatch = computed(() =>
  timeline.value.some((t) => t.type === 'match' && !t.history),
);

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

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = streamRef.value;
    if (!el) return;
    const nearBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (force || nearBottom) {
      el.scrollTop = el.scrollHeight;
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
  pushThinking('正在按你的信息匹配可用方案…');
  try {
    const turn = await api.preview(sessionId.value);
    clearThinking();
    pushMatchCard(turn.result, turn.session_id);
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
  if (orchestrationMode === 'platform') return fromManagerGet(await managerApi.get(runId));
  return fromPipelineGet(await pipelineApi.get(runId));
}

async function waitForPublishGate(runId) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const snapshot = await loadRunSnapshot(runId);
    if (['WAITING_HUMAN', 'SUCCEEDED', 'FAILED', 'ABORTED'].includes(snapshot.status)) return snapshot;
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  throw new Error('生成超时，请到编排看板查看');
}

function pushPublishCard() {
  for (const item of timeline.value) {
    if (item.type === 'publish') item.history = true;
  }
  timeline.value.push({
    id: `publish-${publishState.value?.snapshot?.runId || Date.now()}`,
    type: 'publish',
    history: false,
  });
}

/** 同一个 CTA 按 VITE_ORCHESTRATION_MODE 选择本地权威管线或 AgentTeams 平台编排。 */
async function startBuild() {
  if (!result.value || result.value.gate !== 'PASS' || buildLoading.value) return;
  buildLoading.value = true;
  pushThinking('正在按你的需求生成智能体…');
  try {
    const phase1 = result.value;
    orchestrationRun.value = await createBuildRun(phase1, orchestrationMode, { managerApi, pipelineApi });
    localStorage.setItem('agent-console.last-run-id', orchestrationRun.value.run_id);
    localStorage.setItem('agent-console.last-run-mode', orchestrationMode);
    const snapshot = await waitForPublishGate(orchestrationRun.value.run_id);
    publishState.value = { snapshot, published: snapshot.status === 'SUCCEEDED', publishing: false, error: '' };
    clearThinking();
    pushPublishCard();
    scrollToBottom(true);
    ElMessage.success(
      snapshot.status === 'WAITING_HUMAN' ? '已生成，请确认后发布' : '已创建 run：' + snapshot.runId,
    );
  } catch (err) {
    clearThinking();
    ElMessage.error(err.message);
  } finally {
    buildLoading.value = false;
  }
}

async function confirmPublish() {
  const state = publishState.value;
  if (!state?.snapshot?.approvalId || state.publishing || state.published) return;
  state.publishing = true;
  state.error = '';
  try {
    const body = { approval_id: state.snapshot.approvalId, approved: true };
    const decided =
      orchestrationMode === 'platform'
        ? await managerApi.approve(state.snapshot.runId, body)
        : await pipelineApi.approve(state.snapshot.runId, body);
    const snapshot = await loadRunSnapshot(state.snapshot.runId);
    state.snapshot = snapshot;
    state.published = snapshot.status === 'SUCCEEDED';
    savePublication(publicationFromApprove(decided, snapshot));
    sandboxPublication.value = loadPublication();
    ElMessage.success(state.published ? '已发布' : '已提交发布');
    scrollToBottom(true);
  } catch (err) {
    try {
      state.snapshot = await loadRunSnapshot(state.snapshot.runId);
    } catch {
      /* keep the gate snapshot so the card can still show FAILED after reload */
    }
    state.error = err.message || String(err);
    ElMessage.error(state.error);
  } finally {
    state.publishing = false;
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

function restart() {
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
      <el-button v-if="started" size="small" text bg @click="restart">
        重新开始
      </el-button>
    </header>

    <div class="wz-body">
      <main class="wz-chat">
        <!-- 未开始：租户由已校验凭证绑定，不接受页面输入 -->
        <div v-if="!started" class="wz-stream">
          <div class="wz-start">
            <Welcome
              variant="filled"
              icon="🚀"
              title="从一句话开始，搭出你的第一个 Agent"
              description="我会先问几个问题（行业、想让 AI 先干什么、业务简述），再整理成总结并匹配装机模板。开始后可在右侧查看信息收集进度；发布后可切到沙盒试聊。"
            />
            <div class="wz-start__form">
              <div class="wz-start__row">
                <label>LLM 接待员</label>
                <el-switch
                  v-model="form.llm"
                  :disabled="!health?.llm_available"
                />
                <span class="wz-note">
                  {{
                    health?.llm_available
                      ? '开：模型润色话术与出题；关：走模板话术（不消耗 token）'
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
              <div class="wz-note">租户由当前 Bearer 凭证在服务端绑定，页面不会提交 client_code。</div>
            </div>
          </div>
        </div>

        <!-- 会话进行中：单一时间线 + 常驻输入 -->
        <template v-else>
          <div ref="streamRef" class="wz-stream">
            <div
              v-for="item in timeline"
              :key="item.id"
              class="wz-timeline-item"
              :class="`is-${item.type}`"
            >
              <!-- 聊天气泡 -->
              <Bubble
                v-if="item.type === 'bubble'"
                :placement="item.placement"
                :variant="item.variant"
                :shape="item.shape"
                avatar=""
              >
                <template #content>
                  <div class="wz-md" v-html="item.html" />
                </template>
                <template v-if="item.byLlm" #footer>
                  <el-tag
                    class="wz-badge-llm"
                    size="small"
                    type="success"
                    effect="plain"
                  >
                    模型生成
                  </el-tag>
                </template>
              </Bubble>

              <!-- 思考中（对话流内） -->
              <div v-else-if="item.type === 'thinking'" class="wz-thinking">
                <Thinking
                  :model-value="true"
                  status="thinking"
                  :content="`${item.label || '正在整理'}… ${thinkingSecs}s`"
                  :auto-collapse="false"
                  button-width="180px"
                />
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
                :has-preview="hasLiveMatch"
                :preview-loading="item.history ? false : previewLoading"
                :build-loading="item.history ? false : buildLoading"
                :orchestration-mode="orchestrationMode"
                :orchestration-run="item.history ? null : orchestrationRun"
                :build-started="!!publishState"
                :history="!!item.history"
                @preview="runPreview"
                @build="startBuild"
              />

              <!-- P2 匹配结果（§8.6：时间线一格，历史自动降级） -->
              <MatchCard
                v-else-if="item.type === 'match'"
                :preview="item.preview"
                :loading="item.history ? false : previewLoading"
                :history="!!item.history"
                @rerun="runPreview"
              />

              <PublishCard
                v-else-if="item.type === 'publish'"
                :phase1="result"
                :snapshot="publishState?.snapshot"
                :published="!!publishState?.published"
                :publishing="!!publishState?.publishing"
                :error="publishState?.error || ''"
                :history="!!item.history"
                @publish="confirmPublish"
                @revise="restart"
                @chat="goChat"
              />
            </div>
          </div>

          <div class="wz-compose">
            <ul
              v-if="question?.examples?.length"
              class="wz-examples wz-compose__examples"
            >
              <li v-for="(ex, i) in question.examples" :key="i">例：{{ ex }}</li>
            </ul>
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
          <el-tab-pane label="运行情况" name="runtime">
            <RuntimePanel :runtime="runtime" :history="runtimeHistory" />
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>
  </div>
</template>
