<script setup>
/**
 * 「这版会怎么工作」灰底折叠：摘要两行 + 下方折叠箭头，展开后是 MatchCard 原文。
 * ResultCard / PublishCard 共用，避免两套结构。
 */
import { computed, ref } from 'vue';
import { ArrowDown } from '@element-plus/icons-vue';
import MatchCard from './MatchCard.vue';

const props = defineProps({
  preview: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  history: { type: Boolean, default: false },
  /** 已发布卡里不挂重跑，只给人看简介 */
  hideRerun: { type: Boolean, default: false },
});

const emit = defineEmits(['rerun']);

const expanded = ref(false);

const body = computed(() => {
  const match = props.preview?.match;
  const v0 = props.preview?.v0_preview;
  if (match?.action === 'hit') {
    return v0?.role || match.display_name || '已匹配到一版可用方案';
  }
  return '暂无现成标品，按定制轮廓推进';
});

function toggle() {
  expanded.value = !expanded.value;
}
</script>

<template>
  <div v-if="preview" class="wz-match-embed">
    <button
      type="button"
      class="wz-match-embed__intro"
      :aria-expanded="expanded"
      @click="toggle"
    >
      <div class="wz-match-embed__title">这版会怎么工作</div>
      <p class="wz-match-embed__body">{{ body }}</p>
      <span class="wz-match-embed__toggle" :class="{ 'is-open': expanded }">
        <el-icon :size="14"><ArrowDown /></el-icon>
      </span>
    </button>
    <div v-show="expanded" class="wz-match-embed__detail">
      <MatchCard
        embedded
        :preview="preview"
        :loading="loading"
        :history="history || hideRerun"
        @rerun="emit('rerun')"
      />
    </div>
  </div>
</template>
