<script setup>
/**
 * ResultCard —— P1 向导收口结果（Phase1Result）
 *
 * 只渲染服务端给的结论（gate / scene_id / can_generate_v0），前端不做任何判定。
 * JSON 原文保留一份，方便直接交给 P2 CLI 复现。
 *
 * P2 嵌在本卡底部灰底折叠区（§8.6）：折叠态只露「这版会怎么工作」摘要。
 * 尚未出 P2 时才保留「先看看效果」兜底（主路径仍是 CTA 直串，§8.7）。
 */

import { computed, ref } from 'vue';
import MatchPreviewEmbed from './MatchPreviewEmbed.vue';

const props = defineProps({
  /** Phase1Result */
  result: { type: Object, default: null },
  /** 本轮是否已经出过 P2 结果（决定按钮文案：生成 / 再跑一次） */
  hasPreview: { type: Boolean, default: false },
  /** EndToEndResult，有则在本卡底部折叠展示 */
  preview: { type: Object, default: null },
  previewLoading: { type: Boolean, default: false },
  buildLoading: { type: Boolean, default: false },
  orchestrationMode: { type: String, default: 'local' },
  orchestrationRun: { type: Object, default: null },
  buildStarted: { type: Boolean, default: false },
  /**
   * 历史版本：向导二次收口后，上一张结果卡降级为只读留痕，
   * 不再挂 P2 入口（预览只对流末尾那一版负责）。
   */
  history: { type: Boolean, default: false },
});

const emit = defineEmits(['preview', 'build']);

const showJson = ref(false);

const gateType = computed(() => {
  const g = props.result?.gate;
  return g === 'PASS' ? 'success' : g === 'ASK' ? 'warning' : 'info';
});

const triage = computed(() => props.result?.triage ?? {});

function json(v) {
  return JSON.stringify(v, null, 2);
}

/** PASS 收口时服务端会塞一句 CLI 交接，对向导用户无意义，不展示 */
const userAsk = computed(() => {
  const text = String(props.result?.ask_user ?? '').trim();
  if (!text) return '';
  if (/交给 P2|match --triage/i.test(text)) return '';
  return text;
});

function exportPhase1Result() {
  const blob = new Blob([json(props.result) + '\n'], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `phase1-result-${props.result?.client_code || 'wizard'}.json`;
  link.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <div v-if="result" class="wz-result" :class="{ 'is-history': history }">
    <el-card shadow="never">
      <template #header>
        <div style="display: flex; align-items: center; gap: 8px">
          <strong style="font-size: 13px">向导已完成</strong>
          <el-tag v-if="history" size="small" type="info" effect="plain">
            历史版本
          </el-tag>
          <el-tag size="small" :type="gateType">闸门 {{ result.gate }}</el-tag>
          <el-tag size="small" type="info" effect="plain">
            {{ result.stage }}
          </el-tag>
          <el-tag
            size="small"
            :type="triage.can_generate_v0 ? 'success' : 'warning'"
            effect="plain"
          >
            {{ triage.can_generate_v0 ? '可生成 v0' : '暂不可生成 v0' }}
          </el-tag>
        </div>
      </template>

      <dl class="wz-kv">
        <dt>场景 scene_id</dt>
        <dd>{{ triage.scene_id || '（无匹配场景，走定制）' }}</dd>
        <dt>行业 / 渠道</dt>
        <dd>{{ triage.industry }} / {{ triage.channel }}</dd>
        <dt>置信度</dt>
        <dd>{{ triage.confidence ?? '-' }}</dd>
        <dt v-if="triage.missing_slots?.length">缺失槽位</dt>
        <dd v-if="triage.missing_slots?.length">
          {{ triage.missing_slots.join('、') }}
        </dd>
      </dl>

      <div v-if="userAsk" class="wz-note" style="margin-bottom: 10px">
        {{ userAsk }}
      </div>

      <MatchPreviewEmbed
        v-if="preview"
        :preview="preview"
        :loading="history ? false : previewLoading"
        :history="history"
        @rerun="emit('preview')"
      />

      <div class="wz-actions" style="margin-top: 0">
        <el-button
          v-if="!history && result.gate === 'PASS' && !buildStarted"
          type="success"
          :loading="buildLoading"
          @click="emit('build')"
        >
          开始构建（{{ orchestrationMode }}）
        </el-button>
        <el-button
          v-if="!history && !hasPreview"
          type="primary"
          :loading="previewLoading"
          @click="emit('preview')"
        >
          🚀 先看看效果（P2 匹配）
        </el-button>
        <el-button class="wz-json-link" link type="primary" @click="showJson = !showJson">
          {{ showJson ? '收起' : '查看' }} Phase1Result JSON
        </el-button>
        <el-button class="wz-json-link" link type="primary" @click="exportPhase1Result">
          导出平台验收 JSON
        </el-button>
      </div>

      <pre v-if="showJson" class="wz-pre" style="margin-top: 10px">{{
        json(result)
      }}</pre>
    </el-card>
  </div>
</template>
