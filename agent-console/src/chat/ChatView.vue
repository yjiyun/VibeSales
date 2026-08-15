<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { runtimeChat } from '../shared/api';
import { auth } from '../shared/auth';
import { isChatReady, loadPublication } from '../shared/publication';
import { runtimeSafeId } from '../shared/runtime-id';

const TYPING_MIN_MS = 1200;
const typingDots = ref('.');
let typingTimer = 0;

const props = defineProps({
  embedded: { type: Boolean, default: false },
  publication: { type: Object, default: null },
});

const localPublication = ref(props.publication ?? loadPublication());
watch(
  () => props.publication,
  (next) => {
    if (next) localPublication.value = next;
  },
);

const publication = computed(() => props.publication ?? localPublication.value);
const ready = computed(() => isChatReady(publication.value));
const message = ref('');
const messages = ref([]);
const busy = ref(false);
const sessionId = ref('');
const chatRef = ref(null);

watch(
  () => publication.value?.runtimeAgentId,
  () => {
    sessionId.value = '';
    messages.value = [];
  },
);

function newSessionId() {
  // crypto.randomUUID 只在安全上下文（HTTPS / localhost）可用；测试环境常年走
  // http://<内网IP>:5173，浏览器里这个 API 直接不存在，取值即报
  // TypeError: crypto.randomUUID is not a function。回退到 getRandomValues
  // （不安全上下文里仍可用）拼接同形状字符串；runtime 只校验 [A-Za-z0-9_-]+，
  // 不强制标准 UUID，退化格式不影响后端。
  if (typeof crypto?.randomUUID === 'function') return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof crypto?.getRandomValues === 'function') crypto.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function newSession() {
  if (!sessionId.value) sessionId.value = newSessionId();
  return sessionId.value;
}

function onComposerKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey) return;
  if (event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  send();
}

function startTypingDots() {
  typingDots.value = '.';
  window.clearInterval(typingTimer);
  let n = 1;
  typingTimer = window.setInterval(() => {
    n = n === 3 ? 1 : n + 1;
    typingDots.value = '.'.repeat(n);
  }, 400);
}

function stopTypingDots() {
  window.clearInterval(typingTimer);
  typingTimer = 0;
  typingDots.value = '.';
}

function isTyping(item) {
  return item.role === 'assistant' && item.typing && !item.text;
}

async function scrollChat() {
  await nextTick();
  const el = chatRef.value;
  if (el) el.scrollTop = el.scrollHeight;
}

async function send() {
  const text = message.value.trim();
  if (!text || busy.value) return;
  if (!ready.value) {
    ElMessage.warning('还没有已发布的智能体，请先在向导里完成发布');
    return;
  }
  message.value = '';
  messages.value.push({ role: 'user', text });
  busy.value = true;
  const typedAt = Date.now();
  const assistant = {
    role: 'assistant',
    text: '',
    done: false,
    probe: false,
    blueprintProbe: false,
    typing: true,
  };
  messages.value.push(assistant);
  await scrollChat();
  startTypingDots();
  let buffer = '';
  let released = false;
  const reveal = () => {
    released = true;
    assistant.text = buffer;
    assistant.typing = !buffer && !assistant.done;
    scrollChat();
  };
  const hold = setTimeout(reveal, TYPING_MIN_MS);
  try {
    await runtimeChat(
      {
        clientCode: publication.value.clientCode,
        userId: runtimeSafeId(auth.actor),
        sessionId: newSession(),
        runtimeAgentId: publication.value.runtimeAgentId,
      },
      text,
      (event, data) => {
        if (event === 'message') buffer += data.delta ?? '';
        if (event === 'approval_required') buffer += '\n[需要确认]';
        if (event === 'done') assistant.done = true;
        if (event === 'error') throw new Error(data.message);
        if (released) reveal();
      },
    );
    if (!released) {
      const wait = TYPING_MIN_MS - (Date.now() - typedAt);
      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      reveal();
    }
    if (!assistant.done) throw new Error('SSE 未收到 done 事件');
    assistant.probe = /DRY_RUN_OK\s*:/.test(assistant.text);
    assistant.blueprintProbe = /BLUEPRINT_OK\b/.test(assistant.text);
  } catch (e) {
    ElMessage.error(e.message);
    buffer += '\n[错误] ' + e.message;
    reveal();
  } finally {
    clearTimeout(hold);
    stopTypingDots();
    assistant.typing = false;
    busy.value = false;
    await scrollChat();
  }
}
</script>

<template>
  <section
    class="sandbox"
    :data-runtime-agent-id="publication?.runtimeAgentId || ''"
    :data-client-code="publication?.clientCode || ''"
  >
    <div ref="chatRef" class="chat">
      <div v-if="!messages.length" class="sandbox-hint">直接发一句，看看刚发布的智能体怎么回。</div>
      <div v-for="(item, index) in messages" :key="index" :class="['chat-item', item.role]">
        <div v-if="isTyping(item)" class="sandbox-typing" aria-live="polite" aria-label="回复中">
          <span>回复中</span>
          <span class="sandbox-typing__dots">{{ typingDots }}</span>
        </div>
        <template v-else>
          {{ item.text }}
          <el-tag v-if="item.role === 'assistant' && item.done" size="small" type="success">done</el-tag>
          <el-tag v-if="item.probe" size="small" type="warning">链路探针，非智能体</el-tag>
          <el-tag v-if="item.blueprintProbe" size="small" type="warning">产物探针，非真模型</el-tag>
        </template>
      </div>
    </div>
    <div class="composer">
      <el-input
        v-model="message"
        type="textarea"
        :rows="3"
        :disabled="!ready || busy"
        placeholder="回车发送，Shift+回车换行"
        @keydown="onComposerKeydown"
      />
    </div>
  </section>
</template>

<style scoped>
.sandbox {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px 10px 10px;
  gap: 8px;
}

.chat {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 4px 0;
}

.sandbox-hint {
  color: var(--wz-text-weak, #909399);
  font-size: 13px;
  padding: 8px 2px;
}

.chat-item {
  max-width: 92%;
  padding: 8px 10px;
  border-radius: 10px;
  margin: 6px 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}

.chat-item.user {
  margin-left: auto;
  background: #ecf5ff;
  color: #303133;
}

.chat-item.assistant {
  margin-right: auto;
  background: #f4f4f5;
  color: #303133;
}

.sandbox-typing {
  color: var(--wz-text-weak, #909399);
  min-height: 1.4em;
}

.sandbox-typing__dots {
  display: inline-block;
  min-width: 1.6em;
  letter-spacing: 0.05em;
}

.composer :deep(.el-textarea) {
  width: 100%;
}
</style>
