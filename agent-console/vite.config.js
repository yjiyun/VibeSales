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

export default defineConfig({plugins:[vue()],define:{
  'import.meta.env.VITE_ORCHESTRATION_MODE':JSON.stringify(process.env.ORCHESTRATION_MODE??'local'),
  'import.meta.env.VITE_WIZARD_TOKEN':tokenDefine(['WEB_AUTH_TOKEN','VITE_WIZARD_TOKEN'],'manual-dev-wizard-token-0123456789'),
  'import.meta.env.VITE_PIPELINE_TOKEN':tokenDefine(['PIPELINE_CONTROL_TOKEN','VITE_PIPELINE_TOKEN'],'manual-dev-pipeline-token-0123456789'),
  'import.meta.env.VITE_RUNTIME_TOKEN':tokenDefine(['RUNTIME_AUTH_TOKEN','AGENT_RUNTIME_TOKEN','VITE_RUNTIME_TOKEN'],'manual-dev-runtime-token-0123456789'),
  'import.meta.env.VITE_MANAGER_TOKEN':tokenDefine(['MANAGER_AUTH_TOKEN','VITE_MANAGER_TOKEN']),
  'import.meta.env.VITE_MANAGER_ADMIN_TOKEN':tokenDefine(['MANAGER_ADMIN_TOKEN','VITE_MANAGER_ADMIN_TOKEN']),
},server:{host:process.env.CONSOLE_HOST??'127.0.0.1',port:Number(process.env.CONSOLE_PORT??5173),proxy:{'/api':{target:NEST,changeOrigin:true},'/orchestration':{target:MANAGER,changeOrigin:true,rewrite:path=>path.replace(/^\/orchestration/,'')},'/runtime':{target:RUNTIME,changeOrigin:true,rewrite:path=>path.replace(/^\/runtime/,'')}}},build:{outDir:'dist',emptyOutDir:true}});
