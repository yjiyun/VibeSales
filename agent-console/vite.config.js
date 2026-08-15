import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';
const NEST=process.env.NEST_API ?? 'http://127.0.0.1:3100';
const MANAGER=process.env.MANAGER_API ?? 'http://127.0.0.1:8090';
const RUNTIME=process.env.RUNTIME_API ?? 'http://127.0.0.1:8088';
function tokenDefine(names, fallback = '') {
  for (const name of names) {
    const value = process.env[name];
    if (typeof value === 'string' && value.trim()) return JSON.stringify(value.trim());
  }
  return JSON.stringify(fallback);
}

/** AGENTTEAMS_HUMAN_IDS 是逗号分隔的白名单，取第一个当向导默认 actor。 */
function humanActorDefine() {
  const explicit = String(process.env.VITE_HUMAN_ACTOR ?? '').trim();
  if (explicit) return JSON.stringify(explicit);
  const first = String(process.env.AGENTTEAMS_HUMAN_IDS ?? '')
    .split(',')
    .map((id) => id.trim())
    .find(Boolean);
  return JSON.stringify(first ?? '');
}

function artifactInspectorDefine() {
  const explicit = String(process.env.ARTIFACT_INSPECTOR ?? '').trim().toLowerCase();
  if (explicit === 'on' || explicit === 'off') return JSON.stringify(explicit);
  return JSON.stringify(process.env.NODE_ENV === 'production' ? 'off' : 'on');
}

/** 混合栈多租户：把 WEB_AUTH_CREDENTIALS 编进欢迎屏下拉（本机开发才有）。 */
function wizardTenantsDefine() {
  const raw = String(process.env.WEB_AUTH_CREDENTIALS ?? '').trim();
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return JSON.stringify(
          parsed
            .filter((item) => item && item.token && item.client_code)
            .map((item) => ({ client_code: String(item.client_code), token: String(item.token) })),
        );
      }
    } catch {
      throw new Error('WEB_AUTH_CREDENTIALS must be valid JSON');
    }
  }
  const token = String(process.env.WEB_AUTH_TOKEN ?? process.env.VITE_WIZARD_TOKEN ?? '').trim();
  const clientCode = String(process.env.WEB_AUTH_CLIENT_CODE ?? '').trim();
  if (token && clientCode) return JSON.stringify([{ client_code: clientCode, token }]);
  return JSON.stringify([]);
}

export default defineConfig({plugins:[vue()],define:{
  'import.meta.env.VITE_ORCHESTRATION_MODE':JSON.stringify(process.env.ORCHESTRATION_MODE??'local'),
  'import.meta.env.VITE_ARTIFACT_INSPECTOR':artifactInspectorDefine(),
  'import.meta.env.VITE_WIZARD_TOKEN':tokenDefine(['WEB_AUTH_TOKEN','VITE_WIZARD_TOKEN'],'manual-dev-wizard-token-0123456789'),
  'import.meta.env.VITE_WIZARD_TENANTS':wizardTenantsDefine(),
  'import.meta.env.VITE_PIPELINE_TOKEN':tokenDefine(['PIPELINE_CONTROL_TOKEN','VITE_PIPELINE_TOKEN'],'manual-dev-pipeline-token-0123456789'),
  'import.meta.env.VITE_RUNTIME_TOKEN':tokenDefine(['RUNTIME_AUTH_TOKEN','AGENT_RUNTIME_TOKEN','VITE_RUNTIME_TOKEN'],'manual-dev-runtime-token-0123456789'),
  'import.meta.env.VITE_RUNTIME_ADMIN_TOKEN':tokenDefine(['RUNTIME_ADMIN_TOKEN','AGENT_RUNTIME_ADMIN_TOKEN','VITE_RUNTIME_ADMIN_TOKEN'],'manual-dev-runtime-admin-0123456789'),
  'import.meta.env.VITE_MANAGER_TOKEN':tokenDefine(['MANAGER_AUTH_TOKEN','VITE_MANAGER_TOKEN']),
  'import.meta.env.VITE_MANAGER_ADMIN_TOKEN':tokenDefine(['MANAGER_ADMIN_TOKEN','VITE_MANAGER_ADMIN_TOKEN']),
  // manager 审批要求 X-Actor 命中 AGENTTEAMS_HUMAN_IDS，否则 403 actor is not an
  // authorized Human。取白名单第一个作为向导默认 actor，免得用户手改 localStorage。
  'import.meta.env.VITE_HUMAN_ACTOR':humanActorDefine(),
},server:{
  host:process.env.CONSOLE_HOST??'127.0.0.1',
  port:Number(process.env.CONSOLE_PORT??5173),
  // vite ≥6 默认拒绝非 localhost/局域网 Host 头（DNS rebinding 防护），走真实域名
  // 反代进来的请求会被 403 拦掉：Blocked request. This host ("...") is not allowed。
  // 只有显式配了 CONSOLE_ALLOWED_HOSTS（逗号分隔）才放开，不内置任何域名。
  allowedHosts:String(process.env.CONSOLE_ALLOWED_HOSTS??'').split(',').map(host=>host.trim()).filter(Boolean),
  proxy:{'/api':{target:NEST,changeOrigin:true},'/orchestration':{target:MANAGER,changeOrigin:true,rewrite:path=>path.replace(/^\/orchestration/,'')},'/runtime':{target:RUNTIME,changeOrigin:true,rewrite:path=>path.replace(/^\/runtime/,'')}}
},build:{outDir:'dist',emptyOutDir:true}});
