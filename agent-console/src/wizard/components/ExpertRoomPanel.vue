<script setup>
/**
 * ExpertRoomPanel —— 向导右侧「专家团」tab（ARTIFACT_INSPECTOR 双闸）
 * local：不打 manager，空房间是预期。platform：只读投影 Team Room。
 * 滚动：贴底则跟最新消息；往上翻时底部提示未读条数。
 */
import { nextTick, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { managerApi } from '../../shared/api';

const BOTTOM_PX = 80;

const props = defineProps({
  runId: { type: String, default: '' },
  orchestrationMode: { type: String, default: 'local' },
  active: { type: Boolean, default: false },
});

const error = ref('');
const roomId = ref('');
const messages = ref([]);
const textCount = ref(0);
const unreadCount = ref(0);
const scrollerRef = ref(null);
let pinnedToBottom = true;
let pollTimer = 0;

const localEmpty =
  '当前是 local 编排，Nest 直跑 P1→P4，不向 Matrix 派活。Element / Dashboard #chat 空房间是预期。';
const waitingRun = '点「开始生成（platform）」后，这里会投影 Team Room 的 @mention。';

watch(
  () => [props.runId, props.active, props.orchestrationMode],
  ([runId, active, mode], prev) => {
    window.clearInterval(pollTimer);
    const runChanged = !prev || prev[0] !== runId;
    if (runChanged) {
      messages.value = [];
      unreadCount.value = 0;
      pinnedToBottom = true;
      roomId.value = '';
      error.value = '';
    }
    if (mode !== 'platform' || !runId || !active) return;
    refresh();
    pollTimer = window.setInterval(refresh, 2000);
  },
  { immediate: true },
);

onUnmounted(() => window.clearInterval(pollTimer));

watch(
  () => props.active,
  async (on) => {
    if (!on || !pinnedToBottom || !messages.value.length) return;
    await nextTick();
    requestAnimationFrame(() => scrollToBottom());
  },
);

function messageKey(item) {
  return item?.event_id || `${item?.origin_server_ts ?? ''}:${item?.body ?? ''}`;
}

function isAtBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_PX;
}

function onScrollerScroll() {
  const el = scrollerRef.value;
  if (!el) return;
  pinnedToBottom = isAtBottom(el);
  if (pinnedToBottom) unreadCount.value = 0;
}

function scrollToBottom(smooth = false) {
  const el = scrollerRef.value;
  if (!el) return;
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
}

function jumpToLatest() {
  pinnedToBottom = true;
  unreadCount.value = 0;
  scrollToBottom(true);
}

async function applyMessages(incoming) {
  const firstPaint = messages.value.length === 0;
  const seen = new Set(messages.value.map(messageKey));
  const added = firstPaint ? 0 : incoming.filter((item) => !seen.has(messageKey(item))).length;
  messages.value = incoming;
  await nextTick();
  if (firstPaint || pinnedToBottom) {
    pinnedToBottom = true;
    unreadCount.value = 0;
    scrollToBottom();
    return;
  }
  if (added) unreadCount.value += added;
}

async function refresh() {
  if (props.orchestrationMode !== 'platform' || !props.runId) return;
  try {
    const data = await managerApi.room(props.runId);
    roomId.value = data.room_id || '';
    const incoming = Array.isArray(data.messages) ? data.messages : [];
    textCount.value = data.text_count ?? incoming.length;
    error.value = '';
    await applyMessages(incoming);
  } catch (err) {
    const msg = err.message || String(err);
    error.value = /endpoint not found/i.test(msg)
      ? 'manager 还没有 GET /orchestrations/{runId}/room。这是开始生成之后才加的观察接口，需要重新编译并重启 agent-manager（不必重启 Nest / Console）。'
      : msg;
  }
}

function senderLocalpart(sender) {
  if (typeof sender !== 'string' || !sender) return '—';
  if (sender.startsWith('@')) {
    const local = sender.slice(1).split(':')[0];
    return local || sender;
  }
  return sender;
}

function formatTs(ts) {
  const n = Number(ts);
  if (!n) return '';
  return new Date(n).toLocaleString('zh-CN', { hour12: false });
}

async function copyRoom() {
  if (!roomId.value) return;
  try {
    await navigator.clipboard.writeText(roomId.value);
    ElMessage.success('已复制房间 id');
  } catch {
    ElMessage.warning('复制失败');
  }
}
</script>

<template>
  <div class="wz-runtime-pane wz-expert-room" :data-mode="orchestrationMode">
    <div class="wz-panel__head">
      <span>专家团</span>
      <el-tag v-if="orchestrationMode === 'platform' && roomId" size="small" type="info" effect="plain">
        {{ roomId }}
      </el-tag>
      <span class="wz-spacer" style="flex: 1" />
      <el-button v-if="roomId" size="small" text bg @click="copyRoom">复制房间 id</el-button>
    </div>
    <div ref="scrollerRef" class="wz-panel__body" @scroll="onScrollerScroll">
      <div v-if="orchestrationMode !== 'platform'" class="wz-empty" data-empty="local">
        {{ localEmpty }}
      </div>
      <div v-else-if="!runId" class="wz-empty" data-empty="waiting">
        {{ waitingRun }}
      </div>
      <template v-else>
        <el-alert
          v-if="error"
          type="warning"
          :closable="false"
          show-icon
          :title="error"
        />
        <p v-if="!error" class="wz-note">
          只读投影本次 run 的 Team Room，不是聊天输入。含本 run_id 的消息会标出。共
          {{ textCount }} 条文本。
        </p>
        <div v-if="!error && !messages.length" class="wz-empty" data-empty="silent">
          Team Room 已连接，但还没有 m.room.message。若刚点开始生成，等几秒；一直空则对照 Element。
        </div>
        <ol v-if="messages.length" class="wz-expert-room-list">
          <li
            v-for="item in messages"
            :key="item.event_id || item.origin_server_ts + item.body"
            class="wz-expert-msg"
            :class="{ 'is-for-run': item.for_run }"
          >
            <div class="wz-expert-msg__meta">
              <span class="wz-expert-msg__sender">{{ senderLocalpart(item.sender) }}</span>
              <span v-if="item.for_run" class="wz-expert-msg__run">本 run</span>
              <span v-if="item.origin_server_ts" class="wz-expert-msg__ts">{{
                formatTs(item.origin_server_ts)
              }}</span>
            </div>
            <pre>{{ item.body }}</pre>
          </li>
        </ol>
      </template>
    </div>
    <button
      v-if="unreadCount"
      type="button"
      class="wz-expert-room-jump"
      @click="jumpToLatest"
    >
      {{ unreadCount }} 条新消息
    </button>
  </div>
</template>
