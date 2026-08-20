<script setup>
/**
 * BuildProgressCard —— 「构建智能体」过程时间线
 *
 * 骨架来自 Nest 产物阶段；旁注来自专家团 Room（已去掉思考过程）。
 * 确认发布仍是下一张 PublishCard，本卡只负责生成过程。
 */
import { computed } from 'vue';

const props = defineProps({
  progress: { type: Object, default: null },
  error: { type: String, default: '' },
  mode: { type: String, default: 'local' },
  showInspector: { type: Boolean, default: false },
  history: { type: Boolean, default: false },
  elapsedSecs: { type: Number, default: 0 },
});

defineEmits(['open-room']);

const steps = computed(() => props.progress?.steps ?? []);
const failed = computed(
  () =>
    Boolean(props.error) ||
    props.progress?.title === '构建失败' ||
    ['FAILED', 'ABORTED'].includes(props.progress?.status),
);
const ready = computed(() =>
  ['WAITING_HUMAN', 'SUCCEEDED'].includes(props.progress?.status),
);

function formatWait(secs) {
  const n = Math.max(0, Math.round(Number(secs) || 0));
  if (n < 60) return `${n}秒`;
  const m = Math.floor(n / 60);
  const s = n % 60;
  return s ? `${m}分${s}秒` : `${m}分`;
}

const waitText = computed(() => {
  const t = formatWait(props.elapsedSecs);
  if (ready.value || failed.value) return `用时 ${t}`;
  return `已等待 ${t}`;
});
</script>

<template>
  <div class="wz-build" :class="{ 'is-history': history }">
    <el-card shadow="never">
      <template #header>
        <div class="wz-build__head">
          <strong style="font-size: 13px">{{ progress?.title || '构建智能体' }}</strong>
          <el-tag v-if="history" size="small" type="info" effect="plain">历史版本</el-tag>
          <el-tag size="small" :type="failed ? 'danger' : ready ? 'success' : 'warning'">
            {{ progress?.status || '构建中' }}
          </el-tag>
        </div>
      </template>

      <p class="wz-build__wait" aria-live="polite">{{ waitText }}</p>

      <ol class="wz-build__steps">
        <li
          v-for="step in steps"
          :key="step.id"
          class="wz-build__step"
          :class="`is-${step.state}`"
        >
          <span class="wz-build__dot" aria-hidden="true" />
          <div>
            <div class="wz-build__label">{{ step.label }}</div>
            <p v-for="(note, i) in step.notes" :key="i" class="wz-build__note">{{ note }}</p>
          </div>
        </li>
      </ol>

      <p class="wz-build__wait" aria-live="polite">{{ waitText }}</p>

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        style="margin-top: 10px"
      />

      <p v-if="!failed && !error" class="wz-build__hint">
        {{
          ready
            ? '构建已完成，请在下方确认后发布。'
            : '构建过程可能持续几分钟，请耐心等待。'
        }}
      </p>
    </el-card>
  </div>
</template>
