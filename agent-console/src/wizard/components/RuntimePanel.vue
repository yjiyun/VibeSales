<script setup>
/**
 * RuntimePanel —— 运行情况面板（开发期可见，上线可隐藏）
 *
 * 展示「一次 Web 请求 → 向导模块内部」的完整链路：
 * 事件流与 `logs/app.log` 同源（同一次 `trace.step` 分发到 stderr / 文件 / 本面板），
 * 所以这里的 `#序号`、`+Δms`、`Σms` 与日志文件里的完全一致。
 *
 * 事件流按**回合**折叠保存：每一轮对话一个折叠项，默认只展开最新一轮，
 * 单轮事件多时列表内部出滚动条；每条事件悬浮出 tips，给出换行排版后的完整信息
 * （时间 / 模块 / 位置 / 耗时 / data 的格式化 JSON）。
 */

import { computed, ref, watch } from 'vue';

const props = defineProps({
  /** 最近一回合的 runtime（WizardRuntime） */
  runtime: { type: Object, default: null },
  /** 历史回合的 runtime 列表，按回合折叠展示 */
  history: { type: Array, default: () => [] },
});

/** token 视图：本回合 / 会话累计 */
const showAll = ref(false);

/** 累计视图看会话总量，单回合视图看本回合 */
const token = computed(() =>
  showAll.value ? props.runtime?.token_session : props.runtime?.token,
);

const byNode = computed(() => Object.entries(token.value?.by_node ?? {}));

/** 每一轮对话一个折叠项 */
const turns = computed(() =>
  props.history.map((r, i) => {
    const events = r?.events ?? [];
    return {
      name: `t${i + 1}`,
      index: i + 1,
      events,
      dropped: r?.events_dropped ?? 0,
      tookMs: r?.took_ms ?? 0,
      tokens: r?.token?.total_tokens ?? 0,
      requestId: r?.request_id ?? '',
      hasWarn: events.some((e) => e.level === 'warn'),
      hasError: events.some((e) => e.level === 'error'),
    };
  }),
);

const totalEvents = computed(() =>
  props.history.reduce((n, r) => n + (r?.events?.length ?? 0), 0),
);

/**
 * 展开策略：自动只跟着最新一轮走。
 * 用户手动展开的历史轮次要保住，所以只把「上一次自动展开的那一项」收起。
 */
const activeTurns = ref([]);
let autoOpened = '';
watch(
  () => props.history.length,
  (n) => {
    if (!n) {
      activeTurns.value = [];
      autoOpened = '';
      return;
    }
    const name = `t${n}`;
    activeTurns.value = activeTurns.value
      .filter((x) => x !== autoOpened && x !== name)
      .concat(name);
    autoOpened = name;
  },
  { immediate: true },
);

function fmtMs(ms) {
  if (typeof ms !== 'number') return '';
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

/** 行内紧凑显示：对象压成一行 */
function fmtData(data) {
  if (data === undefined || data === null) return '';
  if (typeof data === 'string') return data;
  try {
    return JSON.stringify(data);
  } catch {
    return String(data);
  }
}

/** tips 里用的换行排版：对象缩进两格，长文截断 */
function prettyData(data) {
  if (data === undefined || data === null) return '';
  let text;
  if (typeof data === 'string') {
    text = data;
  } else {
    try {
      text = JSON.stringify(data, null, 2);
    } catch {
      text = String(data);
    }
  }
  return text.length > 4000 ? `${text.slice(0, 4000)}\n…（已截断）` : text;
}

/** 只截时分秒毫秒，行内够看；tips 里给完整北京时间 */
function shortTs(ts) {
  return typeof ts === 'string' ? (ts.split(' ')[1] ?? ts) : '';
}

function levelClass(level) {
  return level === 'error' ? 'is-error' : level === 'warn' ? 'is-warn' : '';
}

const LEVEL_TEXT = {
  debug: 'DEBUG',
  info: 'INFO',
  warn: 'WARN',
  error: 'ERROR',
};

function levelText(level) {
  return LEVEL_TEXT[level] ?? String(level ?? '');
}
</script>

<template>
  <div class="wz-runtime-pane">
    <div class="wz-panel__head">
      <span>本回合</span>
      <el-tag v-if="runtime" size="small" type="info" effect="plain">
        flow={{ runtime.flow }}
      </el-tag>
      <span class="wz-spacer" style="flex: 1" />
      <el-switch
        v-model="showAll"
        size="small"
        inline-prompt
        active-text="累计"
        inactive-text="本回合"
      />
    </div>

    <div class="wz-panel__body">
      <template v-if="runtime">
        <dl class="wz-kv">
          <dt>request_id</dt>
          <dd>{{ runtime.request_id }}</dd>
          <dt>本回合耗时</dt>
          <dd>{{ runtime.took_ms }} ms</dd>
          <dt>LLM 接待员</dt>
          <dd>{{ runtime.llm ? '开（模型润色/出题）' : '关（模板话术）' }}</dd>
          <dt>回合数</dt>
          <dd>{{ history.length }}</dd>
        </dl>

        <template v-if="token">
          <div class="wz-panel__subhead">
            token 用量{{ showAll ? '（会话累计）' : '（本回合）' }}
          </div>
          <dl class="wz-kv">
            <dt>调用次数</dt>
            <dd>{{ token.calls }}<span v-if="token.errors"> · 失败 {{ token.errors }}</span></dd>
            <dt>prompt / completion</dt>
            <dd>{{ token.prompt_tokens }} / {{ token.completion_tokens }}</dd>
            <dt>合计 tokens</dt>
            <dd>{{ token.total_tokens }}</dd>
            <dt>模型耗时</dt>
            <dd>{{ token.llm_ms }} ms</dd>
          </dl>
          <div v-if="byNode.length" class="wz-events" style="margin-bottom: 12px">
            <div
              v-for="[node, b] in byNode"
              :key="node"
              class="wz-event"
              style="grid-template-columns: 1fr auto"
            >
              <span class="wz-event__name">{{ node }}</span>
              <span class="wz-event__ms">
                {{ b.calls }} 次 · {{ b.total_tokens }} tok
              </span>
            </div>
          </div>
        </template>
        <div v-else class="wz-empty">
          本回合未调用大模型（token 无消耗）。
        </div>

        <div class="wz-panel__subhead">
          事件流（与 logs/app.log 同源，{{ history.length }} 回合共
          {{ totalEvents }} 条）
        </div>

        <!-- 每一轮对话一个折叠项：默认只展开最新一轮，历史轮次可随时翻回 -->
        <el-collapse v-model="activeTurns" class="wz-turns">
          <el-collapse-item
            v-for="t in turns"
            :key="t.name"
            :name="t.name"
            class="wz-turn"
          >
            <template #title>
              <span class="wz-turn__title">
                <span class="wz-turn__idx">第 {{ t.index }} 回合</span>
                <el-tag
                  v-if="t.hasError"
                  size="small"
                  type="danger"
                  effect="plain"
                >
                  error
                </el-tag>
                <el-tag
                  v-else-if="t.hasWarn"
                  size="small"
                  type="warning"
                  effect="plain"
                >
                  warn
                </el-tag>
                <span class="wz-turn__meta">
                  {{ t.events.length }} 条 · {{ fmtMs(t.tookMs) }}
                  <template v-if="t.tokens"> · {{ t.tokens }} tok</template>
                  <template v-if="t.dropped"> · 丢弃 {{ t.dropped }}</template>
                </span>
              </span>
            </template>

            <div class="wz-events wz-events--scroll">
              <el-tooltip
                v-for="e in t.events"
                :key="`${t.name}-${e.seq}-${e.ts}`"
                placement="left"
                :show-after="180"
                :offset="10"
                popper-class="wz-event-tip"
              >
                <template #content>
                  <div class="wz-tip">
                    <div class="wz-tip__head">
                      #{{ e.seq }} · {{ levelText(e.level) }} · {{ e.ts }}
                    </div>
                    <dl class="wz-tip__kv">
                      <dt>模块</dt>
                      <dd>{{ e.scope }}</dd>
                      <dt>位置</dt>
                      <dd>{{ e.event }}</dd>
                      <dt>距上一条</dt>
                      <dd>+{{ e.delta_ms }}ms</dd>
                      <dt>段内累计</dt>
                      <dd>{{ e.total_ms }}ms</dd>
                      <template v-if="e.ms !== undefined">
                        <dt>本步耗时</dt>
                        <dd>{{ e.ms }}ms</dd>
                      </template>
                    </dl>
                    <template v-if="e.data !== undefined && e.data !== null">
                      <div class="wz-tip__label">data</div>
                      <pre class="wz-tip__pre">{{ prettyData(e.data) }}</pre>
                    </template>
                  </div>
                </template>

                <div class="wz-event" :class="levelClass(e.level)">
                  <span class="wz-event__seq">#{{ e.seq }}</span>
                  <span class="wz-event__name">
                    <span class="wz-event__scope">{{ e.scope }}</span>{{ e.event }}
                  </span>
                  <span class="wz-event__ms">
                    +{{ e.delta_ms }}ms<template v-if="e.ms !== undefined">
                      · 耗时 {{ e.ms }}ms</template
                    >
                  </span>
                  <span v-if="e.data !== undefined" class="wz-event__data">
                    {{ fmtData(e.data) }}
                  </span>
                </div>
              </el-tooltip>
              <div v-if="!t.events.length" class="wz-empty">本回合无事件。</div>
            </div>
          </el-collapse-item>
        </el-collapse>
        <div v-if="!turns.length" class="wz-empty">暂无事件。</div>
      </template>
      <div v-else class="wz-empty">开始会话后这里会显示每一回合的运行链路。</div>
    </div>
  </div>
</template>

<style scoped>
.wz-panel__subhead {
  font-size: 12px;
  font-weight: 600;
  color: var(--wz-text);
  margin: 4px 0 6px;
  padding-top: 6px;
  border-top: 1px solid var(--wz-border);
}
</style>

