<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { Setting } from '@element-plus/icons-vue';
import WizardView from './wizard/WizardView.vue';
import RunsView from './runs/RunsView.vue';
import { auth, restoreEnvAuth, saveAuth } from './shared/auth';
import { NAVIGATE_EVENT } from './shared/publication';

function normalizePage(value) {
  return value === 'chat' ? 'wizard' : (value || 'wizard');
}

const page = ref(normalizePage(localStorage.getItem('agent-console.page')));
const credentials = ref(false);

function navigate(value) {
  const next = normalizePage(value);
  page.value = next;
  localStorage.setItem('agent-console.page', next);
}

function onNavigate(event) {
  if (typeof event.detail === 'string') navigate(event.detail);
}

function save() {
  saveAuth();
  credentials.value = false;
}

function restoreFromEnv() {
  restoreEnvAuth();
  credentials.value = false;
}

onMounted(() => window.addEventListener(NAVIGATE_EVENT, onNavigate));
onUnmounted(() => window.removeEventListener(NAVIGATE_EVENT, onNavigate));
</script>
<template>
  <el-container class="console">
    <el-aside width="180px" class="side">
      <div class="brand">
        <span class="brand-mark">A</span>
        <div>
          <strong>VibeSales</strong>
          <small>销售运营智能体搭建</small>
        </div>
      </div>
      <el-menu :key="page" :default-active="page" @select="navigate">
        <el-menu-item index="wizard"><span>搭建向导</span></el-menu-item>
        <el-menu-item index="runs"><span>编排看板</span></el-menu-item>
      </el-menu>
      <div class="side-footer">
        <el-tooltip content="配置" placement="right">
          <el-button text circle aria-label="配置" @click="credentials = true">
            <el-icon :size="18"><Setting /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </el-aside>
    <el-main class="main">
      <KeepAlive>
        <WizardView v-if="page === 'wizard'" />
        <RunsView v-else />
      </KeepAlive>
    </el-main>
  </el-container>
  <el-drawer v-model="credentials" title="本机开发连接凭证" size="420px">
    <el-alert type="info" :closable="false" show-icon>
      随这次启动的 env 预填（local / platform 共用同一组键，不按模式另选密钥）。
      换栈或改 token 后会自动对齐；保存只覆盖本次启动。生产环境应由网关或 SSO 提供。
    </el-alert>
    <el-form label-position="top" class="credentials">
      <el-form-item label="Wizard Bearer">
        <el-input v-model="auth.wizardToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="Manager Bearer">
        <el-input v-model="auth.managerToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="Manager Admin Bearer">
        <el-input v-model="auth.managerAdminToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="Pipeline Control Bearer">
        <el-input v-model="auth.pipelineToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="Runtime Bearer">
        <el-input v-model="auth.runtimeToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="Runtime Admin Bearer">
        <el-input v-model="auth.runtimeAdminToken" type="password" show-password />
      </el-form-item>
      <el-form-item label="X-Role">
        <el-select v-model="auth.role">
          <el-option label="admin" value="admin" />
          <el-option label="orchestrator" value="orchestrator" />
          <el-option label="human" value="human" />
          <el-option label="user" value="user" />
        </el-select>
      </el-form-item>
      <el-form-item label="X-Actor">
        <el-input v-model="auth.actor" />
      </el-form-item>
      <div style="display: flex; gap: 8px">
        <el-button type="primary" @click="save">保存</el-button>
        <el-button @click="restoreFromEnv">恢复为本次启动 env</el-button>
      </div>
    </el-form>
  </el-drawer>
</template>
