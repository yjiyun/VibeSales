<script setup>
/**
 * QuestionCard —— 当前这一问的输入区（对话流内卡片）
 *
 * 选项一律来自服务端 `WizardQuestion`（行业/业务目标取自 catalogs 词表），
 * 前端不内置任何业务枚举。三种输入形态：
 * - single：单选（可分组展示，如行业按 group 分簇）
 * - multi ：多选（业务目标）
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

/** 有分组就按分组渲染，否则平铺 */
const groups = computed(() => {
  const q = props.question;
  if (q.groups?.length) return q.groups;
  return [{ group: '', options: q.options ?? [] }];
});

const locked = computed(() => props.loading || props.disabled);

const canSubmit = computed(() => {
  if (locked.value) return false;
  const q = props.question;
  if (q.input === 'single') return !!picked.value;
  if (q.input === 'multi') return checked.value.length > 0;
  return false;
});

function toggleMulti(id) {
  if (locked.value) return;
  const i = checked.value.indexOf(id);
  if (i >= 0) checked.value.splice(i, 1);
  else checked.value.push(id);
}

/** 单选点击即提交：少一次点击，向导更顺 */
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
  <div class="wz-question" :class="{ 'is-disabled': disabled }">
    <div class="wz-question__title">{{ question.title }}</div>
    <div v-if="question.hint" class="wz-question__hint">{{ question.hint }}</div>

    <!-- 单选 / 多选 -->
    <template v-if="question.input !== 'text'">
      <div v-for="g in groups" :key="g.group || 'all'" class="wz-optgroup">
        <div v-if="g.group" class="wz-optgroup__label">{{ g.group }}</div>
        <div class="wz-options">
          <button
            v-for="o in g.options"
            :key="o.id"
            type="button"
            class="wz-opt"
            :class="{
              'is-active':
                question.input === 'single'
                  ? picked === o.id
                  : checked.includes(o.id),
            }"
            :disabled="locked"
            @click="
              question.input === 'single' ? pickSingle(o.id) : toggleMulti(o.id)
            "
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

    <div class="wz-actions">
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
      <el-button
        v-if="question.input === 'multi'"
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="submit"
      >
        提交
      </el-button>
      <el-button
        v-for="qr in question.quick_replies ?? []"
        :key="qr.value"
        :disabled="locked"
        text
        bg
        @click="quick(qr.value)"
      >
        {{ qr.label }}
      </el-button>
      <span
        v-if="question.input === 'single' && !disabled"
        class="wz-question__hint"
        style="margin: 0"
      >
        点击选项即可继续
      </span>
      <span v-if="disabled" class="wz-question__hint" style="margin: 0">
        已回答
      </span>
    </div>
  </div>
</template>

