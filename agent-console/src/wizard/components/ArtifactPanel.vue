<script setup>
/**
 * ArtifactPanel —— 向导右侧「产物」tab（ARTIFACT_INSPECTOR=on 才挂载）
 * L1 阶段条 / L2 人读卡 / L3 JSON / 可选 runtime 投影对照。
 */
import { computed, onUnmounted, ref, watch } from 'vue';
import { pipelineApi, runtimeInspect } from '../../shared/api';
import { auth } from '../../shared/auth';
import { runtimeSafeId } from '../../shared/runtime-id';
import {
  STAGES,
  expertRole,
  formatRules,
  latestArtifact,
  previewText,
  stageStatus,
} from '../../shared/artifact-preview';

const props = defineProps({
  runId: { type: String, default: '' },
  snapshot: { type: Object, default: null },
  active: { type: Boolean, default: false },
});

const artifacts = ref(props.snapshot?.artifacts ?? []);
const selectedKind = ref('');
const selectedExpertKey = ref('');
const showJson = ref(false);
const inspect = ref(null);
const inspectError = ref('');
const inspectLoading = ref(false);
let pollTimer = 0;

function expertKey(item) {
  return item?.artifact_id || `${expertRole(item)}:${item?.version ?? ''}`;
}

const experts = computed(() =>
  (artifacts.value ?? []).filter((item) => item.kind === 'expert_result'),
);
const selected = computed(() => {
  if (selectedExpertKey.value) {
    return experts.value.find((item) => expertKey(item) === selectedExpertKey.value) ?? null;
  }
  return latestArtifact(artifacts.value, selectedKind.value);
});
const blueprint = computed(() => latestArtifact(artifacts.value, 'blueprint')?.payload ?? null);
const check = computed(
  () =>
    latestArtifact(artifacts.value, 'blueprint_check')?.payload ??
    latestArtifact(artifacts.value, 'flow_check')?.payload ??
    null,
);

function resetView() {
  artifacts.value = [];
  selectedKind.value = '';
  selectedExpertKey.value = '';
  showJson.value = false;
  inspect.value = null;
  inspectError.value = '';
}

watch(
  () => props.snapshot?.artifacts,
  (next) => {
    if (Array.isArray(next)) artifacts.value = next;
    else if (!next) resetView();
  },
);

watch(
  () => [props.runId, props.active],
  ([runId, active], prev) => {
    window.clearInterval(pollTimer);
    if (!prev || prev[0] !== runId) resetView();
    if (!runId || !active) return;
    refresh();
    pollTimer = window.setInterval(refresh, 2000);
  },
  { immediate: true },
);

onUnmounted(() => window.clearInterval(pollTimer));

async function refresh() {
  if (!props.runId) return;
  try {
    const data = await pipelineApi.get(props.runId);
    artifacts.value = data.artifacts ?? [];
  } catch {
    /* 轮询失败保持上一帧 */
  }
}

function selectKind(kind) {
  selectedKind.value = selectedKind.value === kind ? '' : kind;
  selectedExpertKey.value = '';
  showJson.value = false;
}

function selectExpert(item) {
  const key = expertKey(item);
  if (selectedExpertKey.value === key) {
    selectedExpertKey.value = '';
    selectedKind.value = '';
  } else {
    selectedExpertKey.value = key;
    selectedKind.value = 'expert_result';
  }
  showJson.value = false;
}

function stageTone(status) {
  if (status === 'ok') return 'success';
  if (status === 'fail') return 'danger';
  return 'info';
}

async function loadInspect() {
  const bp = blueprint.value;
  if (!bp?.clientCode || !bp?.runtimeAgentId) {
    inspectError.value = '还没有 Blueprint，无法对照投影';
    return;
  }
  inspectLoading.value = true;
  inspectError.value = '';
  try {
    inspect.value = await runtimeInspect({
      clientCode: bp.clientCode,
      userId: runtimeSafeId(auth.actor),
      runtimeAgentId: bp.runtimeAgentId,
    });
  } catch (error) {
    inspectError.value = error.message || String(error);
    inspect.value = null;
  } finally {
    inspectLoading.value = false;
  }
}

function matchTone(ok) {
  return ok ? 'success' : 'warning';
}
</script>

<template>
  <div class="wz-runtime-pane wz-artifacts">
    <div class="wz-panel__head">
      <span>产物</span>
      <el-tag v-if="snapshot?.buildPath" size="small" type="info" effect="plain">
        {{ snapshot.buildPath }}
      </el-tag>
      <span class="wz-spacer" style="flex: 1" />
      <el-button
        size="small"
        text
        bg
        :disabled="!blueprint"
        :loading="inspectLoading"
        @click="loadInspect"
      >
        对照投影
      </el-button>
    </div>
    <div class="wz-panel__body">
      <div v-if="!runId" class="wz-empty">开始生成后，这里会按阶段列出专家输出与 Blueprint。</div>
      <template v-else>
        <div class="wz-panel__subhead">阶段</div>
        <div class="wz-artifact-stages">
          <el-tag
            v-for="stage in STAGES"
            :key="stage.id"
            size="small"
            :type="stageTone(stageStatus(artifacts, stage.kinds))"
            effect="plain"
            class="wz-artifact-stage"
            :data-stage="stage.id"
            @click="selectKind(stage.kinds[0])"
          >
            {{ stage.label }}
          </el-tag>
        </div>

        <div class="wz-panel__subhead">专家输出</div>
        <div v-if="!experts.length" class="wz-empty">尚无 expert_result。</div>
        <div v-else class="wz-expert-cards">
          <article
            v-for="item in experts"
            :key="expertKey(item)"
            class="wz-expert-card"
            :class="{ 'is-active': selectedExpertKey === expertKey(item) }"
            :data-expert-role="expertRole(item)"
          >
            <button
              type="button"
              class="wz-expert-card__title"
              @click="selectExpert(item)"
            >
              {{ expertRole(item) }}
            </button>
            <pre>{{ previewText(item).slice(0, 240) }}</pre>
          </article>
        </div>

        <div v-if="blueprint" class="wz-panel__subhead">Blueprint</div>
        <dl v-if="blueprint" class="wz-kv">
          <dt>runtimeAgentId</dt>
          <dd>{{ blueprint.runtimeAgentId }}</dd>
          <dt>skills</dt>
          <dd>{{ (blueprint.skills ?? []).map((s) => s.name).join('、') || '—' }}</dd>
          <dt>rules</dt>
          <dd>{{ formatRules(blueprint.rules) }}</dd>
          <dt>自检</dt>
          <dd>{{ check?.ok === false ? 'FAIL' : check?.ok === true ? 'OK' : '—' }}</dd>
        </dl>
        <pre v-if="blueprint?.prompt?.soulMd" class="wz-pre" data-preview="soul">{{
          blueprint.prompt.soulMd
        }}</pre>

        <div v-if="inspect" class="wz-panel__subhead">投影对照</div>
        <dl v-if="inspect" class="wz-kv">
          <dt>published</dt>
          <dd>{{ inspect.published ? '是' : '否' }}</dd>
          <dt>soulMd</dt>
          <dd>
            <el-tag size="small" :type="matchTone(inspect.match?.soulMd)" effect="plain">
              {{ inspect.match?.soulMd ? '一致' : '未投影或不一致' }}
            </el-tag>
          </dd>
          <dt>agentsMd</dt>
          <dd>
            <el-tag size="small" :type="matchTone(inspect.match?.agentsMd)" effect="plain">
              {{ inspect.match?.agentsMd ? '一致' : '未投影或不一致' }}
            </el-tag>
          </dd>
        </dl>
        <el-alert
          v-if="inspectError"
          type="warning"
          :closable="false"
          show-icon
          :title="inspectError"
        />

        <div v-if="selected" class="wz-panel__subhead">{{ selected.kind }} · v{{ selected.version }}</div>
        <pre v-if="selected && !showJson" class="wz-pre">{{ previewText(selected) }}</pre>
        <pre v-if="selected && showJson" class="wz-pre">{{
          JSON.stringify(selected.payload, null, 2)
        }}</pre>
        <el-button
          v-if="selected"
          size="small"
          text
          bg
          @click="showJson = !showJson"
        >
          {{ showJson ? '人读预览' : '查看 JSON' }}
        </el-button>
      </template>
    </div>
  </div>
</template>
