<script setup>
/**
 * MatchCard —— P2 匹配结果正文
 *
 * 回答三个问题：**选中了什么 → 它会怎么工作 → 我还要准备什么**。
 * 设计见 `docs/P2-拆分.md` §8.3 / §8.6：嵌在 ResultCard 灰底折叠里，不再独占时间线一格。
 *
 * 口径：
 * - 用业务语言，`template_id` 只作副标题的小字，不当标题
 * - 理由用服务端的中文 `why_user`，不用英文 `why`；`reject_reasons` 留给右栏
 * - `custom` 不空手：归因 / 定制轮廓 / 建议三段（`custom_outline`）
 * - 前端不做任何判定，只渲染服务端结论
 */

import { computed, ref } from 'vue';

const props = defineProps({
  /** EndToEndResult（含 match / v0_preview / custom_outline） */
  preview: { type: Object, default: null },
  /** 重跑中 */
  loading: { type: Boolean, default: false },
  /** 历史版本：向导二次收口后降级留痕，不再挂重跑按钮 */
  history: { type: Boolean, default: false },
  /** 嵌在 ResultCard 折叠区：去掉外层卡片标题，避免和摘要句重复 */
  embedded: { type: Boolean, default: false },
});

const emit = defineEmits(['rerun']);

const showJson = ref(false);

const match = computed(() => props.preview?.match ?? null);
const v0 = computed(() => props.preview?.v0_preview ?? null);
const outline = computed(() => props.preview?.custom_outline ?? null);
const isHit = computed(() => match.value?.action === 'hit');

/** 标题按分支给结论，不用「向导已完成」那种阶段名 */
const title = computed(() =>
  isHit.value ? '已为你匹配到一版可用方案' : '暂无现成标品，给你一版定制轮廓',
);

/** 开通前必填用 v0 的中文 label；没有 v0 时退回 match 的英文 key */
const missing = computed(
  () => v0.value?.missing_required ?? match.value?.required_params_missing ?? [],
);

function json(v) {
  return JSON.stringify(v, null, 2);
}
</script>

<template>
  <div v-if="preview" class="wz-match" :class="{ 'is-history': history && !embedded, 'is-embedded': embedded }">
    <el-card shadow="never">
      <template v-if="!embedded" #header>
        <div class="wz-match__head">
          <strong style="font-size: 13px">{{ title }}</strong>
          <el-tag v-if="history" size="small" type="info" effect="plain">
            历史版本
          </el-tag>
          <el-tag size="small" :type="isHit ? 'success' : 'warning'">
            {{ isHit ? '命中标品' : '走定制' }}
          </el-tag>
          <el-tag size="small" type="info" effect="plain">
            P2 · via {{ match?.via || 'rule' }}
          </el-tag>
        </div>
      </template>
      <div v-if="embedded" class="wz-match__head">
        <el-tag size="small" :type="isHit ? 'success' : 'warning'">
          {{ isHit ? '命中标品' : '走定制' }}
        </el-tag>
        <el-tag size="small" type="info" effect="plain">
          P2 · via {{ match?.via || 'rule' }}
        </el-tag>
      </div>

      <!-- ===== hit：选中了什么 / 会怎么工作 / 还要准备什么 ===== -->
      <template v-if="isHit">
        <section class="wz-match__sec">
          <h4>选中了什么</h4>
          <div class="wz-match__pick">{{ match.display_name }}</div>
          <div class="wz-match__tid">{{ match.template_id }}</div>
          <p v-if="match.why_user" class="wz-note">{{ match.why_user }}</p>
          <template v-if="match.alternatives?.length">
            <div class="wz-note wz-match__sub">落选候选</div>
            <ul class="wz-match__list">
              <li v-for="a in match.alternatives" :key="a.template_id">
                {{ a.display_name }}
                <span v-if="a.why_not" class="wz-match__weak">
                  —— {{ a.why_not }}
                </span>
              </li>
            </ul>
          </template>
        </section>

        <section v-if="v0" class="wz-match__sec">
          <h4>这版会怎么工作</h4>
          <div class="wz-note wz-match__sub">角色定位</div>
          <p class="wz-note">{{ v0.role }}</p>

          <template v-if="v0.capabilities?.length">
            <div class="wz-note wz-match__sub">核心能力</div>
            <div class="wz-options">
              <el-tag
                v-for="c in v0.capabilities"
                :key="c"
                size="small"
                effect="plain"
              >
                {{ c }}
              </el-tag>
            </div>
          </template>

          <template v-if="v0.main_flow?.length">
            <div class="wz-note wz-match__sub">主流程</div>
            <ol class="wz-match__list">
              <li v-for="(s, i) in v0.main_flow" :key="i">{{ s }}</li>
            </ol>
          </template>

          <template v-if="v0.success_criteria?.length">
            <div class="wz-note wz-match__sub">成功判据</div>
            <ul class="wz-match__list">
              <li v-for="(s, i) in v0.success_criteria" :key="i">{{ s }}</li>
            </ul>
          </template>
        </section>

        <section class="wz-match__sec">
          <h4>开通前还要准备什么</h4>
          <template v-if="missing.length">
            <div class="wz-note wz-match__sub">必填参数</div>
            <div class="wz-options">
              <el-tag
                v-for="m in missing"
                :key="m"
                size="small"
                type="warning"
                effect="plain"
              >
                {{ m }}
              </el-tag>
            </div>
          </template>
          <p v-else class="wz-note">
            必填参数都齐了，这版几乎可以直接用。
          </p>

          <template v-if="match.diffs?.length">
            <div class="wz-note wz-match__sub">与你需求的差异</div>
            <ul class="wz-match__list">
              <li v-for="(d, i) in match.diffs" :key="i">{{ d }}</li>
            </ul>
          </template>
        </section>
      </template>

      <!-- ===== custom：为何没命中 / 若定制会怎样 / 建议 ===== -->
      <template v-else>
        <section class="wz-match__sec">
          <h4>为什么没有现成方案</h4>
          <p v-if="match?.why_user" class="wz-note">{{ match.why_user }}</p>
          <ul v-if="match?.reject_summary?.length" class="wz-match__list">
            <li v-for="(r, i) in match.reject_summary" :key="i">
              {{ r.display_name }}：{{ r.detail }}
            </li>
          </ul>
        </section>

        <section v-if="outline" class="wz-match__sec">
          <h4>如果定制，会是这样</h4>
          <div class="wz-note wz-match__sub">角色定位</div>
          <p class="wz-note">{{ outline.role }}</p>

          <template v-if="outline.capabilities?.length">
            <div class="wz-note wz-match__sub">能力诉求</div>
            <div class="wz-options">
              <el-tag
                v-for="c in outline.capabilities"
                :key="c"
                size="small"
                effect="plain"
              >
                {{ c }}
              </el-tag>
            </div>
          </template>

          <template v-if="outline.business_brief">
            <div class="wz-note wz-match__sub">业务简述</div>
            <p class="wz-note">{{ outline.business_brief }}</p>
          </template>
        </section>

        <section v-if="outline?.suggestions?.length" class="wz-match__sec">
          <h4>建议</h4>
          <ol class="wz-match__list">
            <li v-for="(s, i) in outline.suggestions" :key="i">{{ s }}</li>
          </ol>
        </section>
      </template>

      <div class="wz-actions">
        <el-button
          v-if="!history"
          size="small"
          :loading="loading"
          @click="emit('rerun')"
        >
          重跑匹配
        </el-button>
        <el-button class="wz-json-link" link type="primary" @click="showJson = !showJson">
          {{ showJson ? '收起' : '查看' }} EndToEndResult JSON
        </el-button>
      </div>
      <pre v-if="showJson" class="wz-pre" style="margin-top: 10px">{{
        json(preview)
      }}</pre>
    </el-card>
  </div>
</template>

