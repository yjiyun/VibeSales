<script setup>
/**
 * CollectPanel —— 右侧「信息收集」Tab
 *
 * 数据完全来自服务端 WizardTurn.collect（进度 + 清单），
 * 前端不写死业务枚举 / 顺序 / 标题。
 * 对照竞品：3chat截图/搭建助手2-2.jpg
 */

import { computed } from 'vue';

const props = defineProps({
  collect: { type: Object, default: null },
});

const step = computed(() => props.collect?.step ?? 0);
const total = computed(() => props.collect?.total ?? 0);
const percent = computed(() => {
  if (!total.value) return 0;
  return Math.min(100, Math.round((step.value / total.value) * 100));
});

const currentLabel = computed(() => {
  const key = props.collect?.current_key;
  if (!key) return '';
  const hit = (props.collect?.items ?? []).find((i) => i.key === key);
  return hit?.label ?? '';
});

const items = computed(() => props.collect?.items ?? []);

function statusIcon(status) {
  if (status === 'done') return '✓';
  if (status === 'current') return '●';
  if (status === 'skipped') return '–';
  return '○';
}
</script>

<template>
  <div class="wz-runtime-pane wz-collect">
    <div class="wz-panel__body">
      <div v-if="!collect" class="wz-empty">开始后这里会显示收集进度。</div>
      <template v-else>
        <!-- 子版块 1：信息收集进度 -->
        <div class="wz-collect__section">
          <div class="wz-panel__subhead">信息收集进度</div>
          <div class="wz-collect__progress">
            <span class="wz-collect__frac">
              <strong>{{ step }}</strong>
              <span> / {{ total }}</span>
            </span>
            <el-progress
              :percentage="percent"
              :stroke-width="8"
              :show-text="false"
              style="flex: 1"
            />
          </div>
          <div v-if="currentLabel" class="wz-note" style="margin-top: 6px">
            当前待补充：
            <strong>{{ currentLabel }}</strong>
          </div>
          <div v-else-if="step >= total" class="wz-note" style="margin-top: 6px">
            基本信息已齐，可继续细补或先看看效果。
          </div>
        </div>

        <!-- 子版块 2：收集清单 -->
        <div class="wz-collect__section">
          <div class="wz-panel__subhead">收集清单</div>
          <ul class="wz-collect__list">
            <li
              v-for="item in items"
              :key="item.key"
              class="wz-collect__item"
              :class="`is-${item.status}`"
            >
              <span class="wz-collect__dot" aria-hidden="true">{{
                statusIcon(item.status)
              }}</span>
              <div class="wz-collect__main">
                <div class="wz-collect__label">
                  {{ item.label }}
                  <el-tag
                    v-if="item.optional"
                    size="small"
                    type="info"
                    effect="plain"
                    style="margin-left: 4px"
                  >
                    可选
                  </el-tag>
                </div>
                <div v-if="item.why" class="wz-collect__why">{{ item.why }}</div>
                <div v-if="item.value" class="wz-collect__value">
                  {{ item.value }}
                </div>
              </div>
            </li>
          </ul>
        </div>

        <div class="wz-collect__tip">
          你可以按步骤回答对话里的问题，也可以在底部输入框一次说清业务信息。
        </div>
      </template>
    </div>
  </div>
</template>

