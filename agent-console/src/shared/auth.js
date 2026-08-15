import { reactive } from 'vue';

const KEYS = {
  wizardToken: 'wizard-token',
  managerToken: 'manager-token',
  managerAdminToken: 'manager-admin-token',
  pipelineToken: 'pipeline-token',
  runtimeToken: 'runtime-token',
  runtimeAdminToken: 'runtime-admin-token',
  role: 'role',
  actor: 'actor',
};

const LOCAL_DEFAULTS = {
  wizardToken: 'manual-dev-wizard-token-0123456789',
  managerToken: '',
  managerAdminToken: '',
  pipelineToken: 'manual-dev-pipeline-token-0123456789',
  runtimeToken: 'manual-dev-runtime-token-0123456789',
  runtimeAdminToken: 'manual-dev-runtime-admin-0123456789',
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
  runtimeAdminToken: load(
    'agent-console.runtime-admin-token',
    viteToken('VITE_RUNTIME_ADMIN_TOKEN', LOCAL_DEFAULTS.runtimeAdminToken),
  ),
  role: load('agent-console.role', LOCAL_DEFAULTS.role),
  actor: resolveActor(),
});

/** `@user:host` 形式才可能是 Matrix 账号；`@developer:local` 这类占位值不算。 */
function looksLikeMatrixId(value) {
  return /^@[^:\s]+:[^:\s]+(:\d+)?$/.test(value ?? '') && !String(value).endsWith(':local');
}

/**
 * 取 X-Actor。
 *
 * platform 下 manager 校验 `AGENTTEAMS_HUMAN_IDS.contains(actor)` 才允许审批，actor 不在
 * 白名单就 403 `actor is not an authorized Human`。白名单只存在于部署环境（env），前端
 * 不该也不能猜它 —— 由 vite.config 把 `AGENTTEAMS_HUMAN_IDS` 的第一项注入成
 * `VITE_HUMAN_ACTOR`，这里只消费，不内置任何 homeserver 域名或端口。
 *
 * 同时纠正历史脏值：早先版本把本机占位 actor 写进了 localStorage，platform 下必然被拒。
 */
function resolveActor() {
  const platform = viteToken('VITE_ORCHESTRATION_MODE', '') === 'platform';
  const injected = viteToken('VITE_HUMAN_ACTOR', '');
  const stored = load('agent-console.actor', '');

  if (!platform) return stored || LOCAL_DEFAULTS.actor;

  // platform：优先用注入的白名单账号；已存的值只有像 Matrix ID 才保留（用户可能手工改过）
  if (stored && looksLikeMatrixId(stored)) return stored;
  if (injected) {
    try {
      localStorage.setItem('agent-console.actor', injected);
    } catch {
      /* 无 storage 时仅本次生效 */
    }
    return injected;
  }
  // 注入缺失（起 console 时没带 AGENTTEAMS_HUMAN_IDS）：不猜，保留原值并在控制台点明
  console.warn(
    '[agent-console] platform 模式缺少 VITE_HUMAN_ACTOR：请在启动 console 时提供 ' +
      'AGENTTEAMS_HUMAN_IDS（或 VITE_HUMAN_ACTOR），否则「确认发布」会被 manager 拒为 ' +
      'actor is not an authorized Human。',
  );
  return stored || LOCAL_DEFAULTS.actor;
}

export function saveAuth() {
  for (const [field, suffix] of Object.entries(KEYS)) {
    localStorage.setItem('agent-console.' + suffix, auth[field] ?? '');
  }
}

/** 混合栈多租户：启动时注入的 token → client_code 列表。 */
export function wizardTenants() {
  try {
    const raw = import.meta.env?.VITE_WIZARD_TENANTS;
    if (Array.isArray(raw)) return raw.filter((item) => item?.client_code && item?.token);
    if (typeof raw === 'string' && raw.trim()) {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter((item) => item?.client_code && item?.token) : [];
    }
  } catch {
    /* 未注入或解析失败 */
  }
  return [];
}

export function applyWizardTenant(clientCode) {
  const hit = wizardTenants().find((item) => item.client_code === clientCode);
  if (!hit?.token) throw new Error('unknown wizard tenant: ' + clientCode);
  auth.wizardToken = hit.token;
  saveAuth();
  return hit;
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
