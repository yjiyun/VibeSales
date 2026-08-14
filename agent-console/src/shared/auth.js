import { reactive } from 'vue';

const KEYS = {
  wizardToken: 'wizard-token',
  managerToken: 'manager-token',
  managerAdminToken: 'manager-admin-token',
  pipelineToken: 'pipeline-token',
  runtimeToken: 'runtime-token',
  role: 'role',
  actor: 'actor',
};

const LOCAL_DEFAULTS = {
  wizardToken: 'manual-dev-wizard-token-0123456789',
  managerToken: '',
  managerAdminToken: '',
  pipelineToken: 'manual-dev-pipeline-token-0123456789',
  runtimeToken: 'manual-dev-runtime-token-0123456789',
  role: 'admin',
  actor: '@developer:local',
};

function viteToken(name, fallback) {
  try {
    const value = import.meta.env?.[name];
    if (typeof value === 'string' && value.trim()) return value.trim();
  } catch {
    /* Node 契约测试没有 Vite define */
  }
  return fallback;
}

function load(key, fallback = '') {
  try {
    const stored = localStorage.getItem(key);
    if (stored != null && stored !== '') return stored;
  } catch {
    /* Node / 无 storage */
  }
  return fallback;
}

export const auth = reactive({
  wizardToken: load('agent-console.wizard-token', viteToken('VITE_WIZARD_TOKEN', LOCAL_DEFAULTS.wizardToken)),
  managerToken: load('agent-console.manager-token', viteToken('VITE_MANAGER_TOKEN', LOCAL_DEFAULTS.managerToken)),
  managerAdminToken: load(
    'agent-console.manager-admin-token',
    viteToken('VITE_MANAGER_ADMIN_TOKEN', LOCAL_DEFAULTS.managerAdminToken),
  ),
  pipelineToken: load('agent-console.pipeline-token', viteToken('VITE_PIPELINE_TOKEN', LOCAL_DEFAULTS.pipelineToken)),
  runtimeToken: load('agent-console.runtime-token', viteToken('VITE_RUNTIME_TOKEN', LOCAL_DEFAULTS.runtimeToken)),
  role: load('agent-console.role', LOCAL_DEFAULTS.role),
  actor: load('agent-console.actor', LOCAL_DEFAULTS.actor),
});

export function saveAuth() {
  for (const [field, suffix] of Object.entries(KEYS)) {
    localStorage.setItem('agent-console.' + suffix, auth[field] ?? '');
  }
}

try {
  if (typeof localStorage !== 'undefined' && localStorage.getItem('agent-console.wizard-token') == null) {
    saveAuth();
  }
} catch {
  /* ignore */
}

// A23：admin 角色必须用独立的 manager 管理凭证，不复用普通 manager token。
export function managerToken() {
  return auth.role === 'admin' ? auth.managerAdminToken : auth.managerToken;
}

export function headers(token = auth.wizardToken, body = false) {
  return {
    ...(body ? { 'content-type': 'application/json' } : {}),
    authorization: 'Bearer ' + token,
    'x-role': auth.role,
    'x-actor': auth.actor,
  };
}
