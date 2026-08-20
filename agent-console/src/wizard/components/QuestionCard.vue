<script setup>
/**
 * QuestionCard —— 当前这一问的输入区（对话流内卡片）
 *
 * 选项一律来自服务端 `WizardQuestion`（行业/业务目标取自 catalogs 词表），
 * 前端不内置任何业务枚举。三种输入形态：
 * - single：单选（行业 S1 为竖排单选钮 + 确认；CTA 等仍点选即提交）
 * - multi ：多选（业务目标：同款竖排列表，方框多选 + 确认）
 * - text  ：自由文本题干 + 快捷回复（正文输入交给底部 XSender）
 *
 * 历史卡片传 disabled=true，只做只读回显。
 */

import { computed, ref, watch } from 'vue';

const props = defineProps({
  question: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  /** 「使用模板」正在向服务端按需生成 */
  templateLoading: { type: Boolean, default: false },
});

const emit = defineEmits(['answer', 'use-template']);

/** 单选选中项 */
const picked = ref('');
/** 多选选中项 */
const checked = ref([]);

// 切换到新一问时清空上一问的输入
watch(
  () => `${props.question.stage}:${props.question.key}`,
  () => {
    picked.value = '';
    checked.value = [];
  },
);

/** 有分组就按分组渲染；行业 S1 的「分类」标题要隐藏，只保留选项本身。 */
const groups = computed(() => {
  const q = props.question;
  if (q.key === 'industry') {
    const opts = q.groups?.length
      ? q.groups.flatMap((g) => g.options ?? [])
      : (q.options ?? []);
    return [{ group: '', options: opts }];
  }
  if (q.groups?.length) return q.groups;
  return [{ group: '', options: q.options ?? [] }];
});

const locked = computed(() => props.loading || props.disabled);

/** 行业 / 业务目标：竖排选择列表（单选圆钮 / 多选方框） */
const isChoiceList = computed(
  () => props.question.key === 'industry' || props.question.input === 'multi',
);
const isMulti = computed(() => props.question.input === 'multi');

const canSubmit = computed(() => {
  if (locked.value) return false;
  const q = props.question;
  if (q.input === 'single') return !!picked.value;
  if (q.input === 'multi') return checked.value.length > 0;
  return false;
});

const hasChoice = computed(() =>
  isMulti.value ? checked.value.length > 0 : !!picked.value,
);

const showActions = computed(() => {
  const q = props.question;
  if (isChoiceList.value) return true;
  if (q.input === 'text' && (q.template_text || q.template_on_demand)) return true;
  if ((q.quick_replies ?? []).length) return true;
  return !!props.disabled;
});

function isActive(id) {
  return isMulti.value ? checked.value.includes(id) : picked.value === id;
}

function toggleMulti(id) {
  if (locked.value) return;
  const i = checked.value.indexOf(id);
  if (i >= 0) checked.value.splice(i, 1);
  else checked.value.push(id);
}

function selectSingle(id) {
  if (locked.value) return;
  picked.value = id;
}

function pickChoice(id) {
  if (isMulti.value) toggleMulti(id);
  else selectSingle(id);
}

function clearChoices() {
  if (locked.value) return;
  picked.value = '';
  checked.value = [];
}

/** CTA 等：点击即提交 */
function pickSingle(id) {
  if (locked.value) return;
  picked.value = id;
  emit('answer', { values: [id] });
}

function submit() {
  if (!canSubmit.value) return;
  const q = props.question;
  if (q.input === 'single') emit('answer', { values: [picked.value] });
  else if (q.input === 'multi') emit('answer', { values: [...checked.value] });
}

/** 快捷回复（跳过 / 直接生成 / 都不匹配…）直接按 value 回传 */
function quick(value) {
  if (locked.value) return;
  emit('answer', { values: [value] });
}

/**
 * 使用模板：交给父组件决定文本来源。
 * `template_on_demand` 时父组件会先请求服务端现场生成（失败退回 template_text）。
 */
function useTemplate() {
  if (locked.value || props.templateLoading) return;
  const q = props.question;
  if (!q.template_text && !q.template_on_demand) return;
  emit('use-template', {
    onDemand: !!q.template_on_demand,
    fallback: q.template_text ?? '',
  });
}
</script>

<template>
  <div
    class="wz-question"
    :class="{ 'is-disabled': disabled, 'is-choice-list': isChoiceList }"
  >
    <div class="wz-question__title">{{ question.title }}</div>
    <div
      v-if="question.hint"
      class="wz-question__hint"
      :class="{ 'is-quote': isChoiceList }"
    >
      {{ question.hint }}
    </div>

    <!-- 行业 / 业务目标：竖排选择列表 -->
    <template v-if="isChoiceList">
      <div v-for="g in groups" :key="g.group || 'all'" class="wz-optgroup">
        <div v-if="g.group" class="wz-optgroup__label">{{ g.group }}</div>
        <div
          class="wz-radio-list"
          :role="isMulti ? 'group' : 'radiogroup'"
        >
          <button
            v-for="o in g.options"
            :key="o.id"
            type="button"
            class="wz-opt wz-radio-opt"
            :class="{ 'is-active': isActive(o.id) }"
            :role="isMulti ? 'checkbox' : 'radio'"
            :aria-checked="isActive(o.id)"
            :disabled="locked"
            @click="pickChoice(o.id)"
          >
            <span
              class="wz-radio-opt__dot"
              :class="{ 'is-check': isMulti }"
              aria-hidden="true"
            />
            <span class="wz-radio-opt__name">{{ o.name }}</span>
          </button>
        </div>
      </div>
    </template>

    <!-- CTA 等：选项卡片，点击即提交 -->
    <template v-else-if="question.input !== 'text'">
      <div v-for="g in groups" :key="g.group || 'all'" class="wz-optgroup">
        <div v-if="g.group" class="wz-optgroup__label">{{ g.group }}</div>
        <div class="wz-options">
          <button
            v-for="o in g.options"
            :key="o.id"
            type="button"
            class="wz-opt"
            :class="{ 'is-active': picked === o.id }"
            :disabled="locked"
            @click="pickSingle(o.id)"
          >
            <div class="wz-opt__name">{{ o.name }}</div>
            <div v-if="o.description" class="wz-opt__desc">
              {{ o.description }}
            </div>
          </button>
        </div>
      </div>
    </template>

    <!-- 自由文本：题干在卡片，输入走底部 XSender -->
    <template v-else>
      <div class="wz-note">请在底部输入框回答本问题。</div>
      <ul v-if="question.examples?.length" class="wz-examples">
        <li v-for="(ex, i) in question.examples" :key="i">例：{{ ex }}</li>
      </ul>
    </template>

    <div v-if="showActions" class="wz-actions" :class="{ 'is-choice': isChoiceList }">
      <el-button
        v-if="
          question.input === 'text' &&
          (question.template_text || question.template_on_demand)
        "
        :disabled="locked"
        :loading="templateLoading"
        text
        bg
        @click="useTemplate"
      >
        {{ templateLoading ? '生成中…' : '使用模板' }}
      </el-button>
      <template v-if="!isChoiceList">
        <el-button
          v-for="qr in question.quick_replies ?? []"
          :key="qr.value"
          class="wz-actions__skip"
          :disabled="locked"
          text
          bg
          @click="quick(qr.value)"
        >
          {{ qr.label }}
        </el-button>
      </template>
      <template v-if="isChoiceList && !disabled">
        <el-button text :disabled="locked || !hasChoice" @click="clearChoices">
          清空
        </el-button>
        <el-button
          type="primary"
          round
          :loading="loading"
          :disabled="!canSubmit"
          @click="submit"
        >
          确认
        </el-button>
      </template>
      <span v-if="disabled" class="wz-question__hint" style="margin: 0">
        已回答
      </span>
    </div>
  </div>
</template>

