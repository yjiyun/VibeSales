<script setup>
/**
 * ResultCard —— P1 向导收口结果（Phase1Result）
 *
 * 只渲染服务端给的结论（gate / scene_id / can_generate_v0），前端不做任何判定。
 * JSON 原文保留一份，方便直接交给 P2 CLI 复现。
 *
 * P2 结果不在这里：它是时间线里独立的一格 `MatchCard.vue`（见 §8.6）。
 * 本卡的按钮只是「再跑一次 P2」的兜底入口——主路径是 CTA 直接串 P2（§8.7）。
 */

import { computed, ref } from 'vue';

const props = defineProps({
  /** Phase1Result */
  result: { type: Object, default: null },
  /** 本轮是否已经出过 P2 结果（决定按钮文案：生成 / 再跑一次） */
  hasPreview: { type: Boolean, default: false },
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

      <div v-if="result.ask_user" class="wz-note" style="margin-bottom: 10px">
        {{ result.ask_user }}
      </div>

      <div class="wz-actions" style="margin-top: 0">
        <el-button
          v-if="!history && result.gate === 'PASS' && !buildStarted"
          type="success"
          :loading="buildLoading"
          @click="emit('build')"
        >
          开始生成（{{ orchestrationMode }}）
        </el-button>
        <el-button
          v-if="!history"
          :type="hasPreview ? 'default' : 'primary'"
          :loading="previewLoading"
          @click="emit('preview')"
        >
          {{ hasPreview ? '重跑匹配（P2）' : '🚀 先看看效果（P2 匹配）' }}
        </el-button>
        <el-button text bg @click="showJson = !showJson">
          {{ showJson ? '收起' : '查看' }} Phase1Result JSON
        </el-button>
        <el-button text bg @click="exportPhase1Result">
          导出平台验收 JSON
        </el-button>
      </div>

      <pre v-if="showJson" class="wz-pre" style="margin-top: 10px">{{
        json(result)
      }}</pre>
    </el-card>
  </div>
</template>
