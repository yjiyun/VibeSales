<script setup>
/**
 * 向导内「生成进度 + 确认发布」。Human Gate 仍走 pipeline approval_id，
 * 只是交互不再把用户赶到编排看板。
 */
import { computed, ref } from 'vue';
import MatchPreviewEmbed from './MatchPreviewEmbed.vue';

const props = defineProps({
  phase1: { type: Object, default: null },
  snapshot: { type: Object, default: null },
  published: { type: Boolean, default: false },
  publishing: { type: Boolean, default: false },
  error: { type: String, default: '' },
  history: { type: Boolean, default: false },
  rechecking: { type: Boolean, default: false },
  nudging: { type: Boolean, default: false },
  /** P2 匹配结果，已发布时用同一套「这版会怎么工作」折叠 */
  preview: { type: Object, default: null },
  /** 已确认与 Nest 权威状态不符、被 409 拒绝过的 approval_id，不能再靠它点亮按钮 */
  rejectedApprovalIds: { type: Array, default: () => [] },
});

const emit = defineEmits(['publish', 'revise', 'chat', 'recheck', 'nudge']);

const showTech = ref(false);

const summary = computed(() => props.phase1?.summary ?? {});
const triage = computed(() => props.phase1?.triage ?? {});
const brief = computed(
  () => summary.value.business_brief || triage.value.business_brief || '（无简述）',
);
const industry = computed(
  () => summary.value.industry?.name || summary.value.industry?.id || triage.value.industry || '—',
);
const goals = computed(() =>
  (summary.value.business_goals ?? [])
    .map((goal) => goal.name || goal.id)
    .filter(Boolean)
    .join('、') || '—',
);
const sceneId = computed(() => props.snapshot?.sceneId || triage.value.scene_id || '—');
const buildPath = computed(() => props.snapshot?.buildPath || '—');
const memory = computed(
  () => props.snapshot?.memory === true || triage.value.needs_long_term_memory === true,
);
const ready = computed(
  () =>
    !props.error &&
    props.snapshot?.status === 'WAITING_HUMAN' &&
    props.snapshot?.selfcheckOk !== false &&
    Boolean(props.snapshot?.approvalId) &&
    !props.rejectedApprovalIds.includes(props.snapshot?.approvalId),
);
const failed = computed(() => ['FAILED', 'ABORTED'].includes(props.snapshot?.status));
const kinds = computed(() => (props.snapshot?.artifacts ?? []).map((item) => item.kind).filter(Boolean));
</script>

<template>
  <div v-if="snapshot" class="wz-publish" :class="{ 'is-history': history }">
    <el-card shadow="never">
      <template #header>
        <div class="wz-publish__head">
          <strong>{{ published ? '已发布' : '确认发布' }}</strong>
          <el-tag v-if="history" size="small" type="info" effect="plain">历史版本</el-tag>
          <el-tag size="small" :type="published ? 'success' : failed ? 'danger' : 'warning'">
            {{ snapshot.status || '生成中' }}
          </el-tag>
          <el-tag size="small" type="info" effect="plain">{{ buildPath }}</el-tag>
        </div>
      </template>

      <MatchPreviewEmbed
        v-if="published && preview"
        hide-rerun
        :preview="preview"
        history
      />

      <template v-if="!published">
      <section class="wz-match__sec">
        <h4>需求摘要</h4>
        <dl class="wz-kv">
          <dt>行业 / 渠道</dt>
          <dd>{{ industry }} / {{ triage.channel || '—' }}</dd>
          <dt>业务目标</dt>
          <dd>{{ goals }}</dd>
          <dt>简述</dt>
          <dd>{{ brief }}</dd>
        </dl>
      </section>

      <section class="wz-match__sec">
        <h4>将发布的智能体</h4>
        <dl class="wz-kv">
          <dt>场景</dt>
          <dd>{{ sceneId }}</dd>
          <dt>名称</dt>
          <dd>{{ snapshot.displayName || sceneId }}</dd>
          <dt>长期记忆</dt>
          <dd>{{ memory ? '需要跨会话记住' : '本次不启用' }}</dd>
          <dt v-if="snapshot.skills?.length">Skill</dt>
          <dd v-if="snapshot.skills?.length">{{ snapshot.skills.join('、') }}</dd>
        </dl>
      </section>
      </template>

      <el-alert
        v-if="ready && !published"
        type="info"
        :closable="false"
        show-icon
        title="产物已就绪，请确认发布。发布完成后可进行沙盒试聊。"
      />
      <el-alert
        v-else-if="published"
        type="success"
        :closable="false"
        show-icon
        title="已发布。可以开始沙盒试聊，或继续补充细节。"
      />
      <el-alert
        v-else-if="failed || error"
        type="error"
        :closable="false"
        show-icon
        :title="error || '发布未完成，请返回修改需求后重新生成'"
      >
        <!-- failed 是终态（FAILED/ABORTED），重试没用；error 多是 Team Leader
             卡在某个阶段（比如 MCP 连接类抖动，几秒后自愈），值得提醒它重试。 -->
        <template v-if="error && !failed" #default>
          <div style="margin-top: 8px">
            <el-button size="small" :loading="nudging" :disabled="nudging" @click="emit('nudge')">
              {{ nudging ? '重试中…' : '重试' }}
            </el-button>
          </div>
        </template>
      </el-alert>
      <el-alert
        v-else
        type="warning"
        :closable="false"
        show-icon
        title="仍在生成，确认发布会在自检通过后可点。平台编排跑真模型通常 15–25 分钟；等久了点「检查发布闸门」复查即可，不用重新生成。"
      />

      <div v-if="!history" class="wz-actions" style="margin-top: 12px">
        <el-button
          v-if="!published"
          type="success"
          :loading="publishing"
          :disabled="!ready || publishing"
          @click="emit('publish')"
        >
          {{ publishing ? '发布中，请稍候…' : '确认发布' }}
        </el-button>
        <el-button v-if="published" type="primary" @click="emit('chat')">沙盒试聊</el-button>
        <el-button v-if="published" :disabled="publishing" @click="emit('revise')">
          继续补充细节
        </el-button>
        <!-- FAILED/ABORTED 是终态，再复查也不会变，按钮留着只会让人干等 -->
        <el-button
          v-if="!published && !ready && !failed"
          :loading="rechecking"
          :disabled="rechecking || publishing"
          @click="emit('recheck')"
        >
          检查发布闸门
        </el-button>
        <el-button v-if="!published" :disabled="publishing" @click="emit('revise')">返回修改需求</el-button>
        <el-button text bg @click="showTech = !showTech">
          {{ showTech ? '收起' : '查看' }}技术详情
        </el-button>
      </div>

      <pre v-if="showTech" class="wz-pre" style="margin-top: 10px">{{
        JSON.stringify(
          {
            run_id: snapshot.runId,
            approval_id: snapshot.approvalId,
            status: snapshot.status,
            artifacts: kinds,
            runtimeAgentId: snapshot.runtimeAgentId,
          },
          null,
          2,
        )
      }}</pre>
    </el-card>
  </div>
</template>
