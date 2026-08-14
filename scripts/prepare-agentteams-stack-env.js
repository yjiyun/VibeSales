#!/usr/bin/env node
'use strict';
const fs=require('fs'),crypto=require('crypto'),path=require('path');
const source=process.argv[2],target=process.argv[3];if(!source||!target)throw new Error('usage: prepare-agentteams-stack-env.js <source.env> <target.env>');
const parse=file=>Object.fromEntries(fs.readFileSync(file,'utf8').split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!line.startsWith('#')&&line.includes('=')).map(line=>{const at=line.indexOf('=');return[line.slice(0,at).trim(),line.slice(at+1).trim().replace(/^(['"])(.*)\1$/,'$2')];}));
const sourceEnv=parse(source),example=parse(path.resolve(__dirname,'../deploy/agentteams/integration.env.example')),secret=()=>crypto.randomBytes(24).toString('hex'),nonEmpty=Object.fromEntries(Object.entries(sourceEnv).filter(([,value])=>value));
const values={...example,...nonEmpty,
 AGENTTEAMS_DOCKER_NETWORK:sourceEnv.AGENTTEAMS_DOCKER_NETWORK||'agentteams-net',
 MINIO_ENDPOINT:sourceEnv.MINIO_ENDPOINT,
 MINIO_ACCESS_KEY:sourceEnv.MINIO_ACCESS_KEY,
 MINIO_SECRET_KEY:sourceEnv.MINIO_SECRET_KEY,
 NEST_LLM_TOKEN:sourceEnv.NEST_LLM_TOKEN||sourceEnv.HIGRESS_CONSUMER_TOKEN,
 RUNTIME_LLM_TOKEN:sourceEnv.RUNTIME_LLM_TOKEN||sourceEnv.HIGRESS_CONSUMER_TOKEN,
 RUNTIME_MCP_TOKEN:sourceEnv.RUNTIME_MCP_TOKEN||sourceEnv.HIGRESS_CONSUMER_TOKEN,
 AGENTTEAMS_E2E_HUMAN_USER_ID:sourceEnv.AGENTTEAMS_E2E_HUMAN_USER_ID||sourceEnv.AGENTTEAMS_ADMIN_USER,
 AGENTTEAMS_E2E_HUMAN_PASSWORD:sourceEnv.AGENTTEAMS_E2E_HUMAN_PASSWORD||sourceEnv.AGENTTEAMS_ADMIN_PASSWORD,
};
for(const key of ['POSTGRES_ADMIN_PASSWORD','CHATFLOWS_APP_DB_PASSWORD','AGENT_RUNTIME_DB_PASSWORD','RUNTIME_AUTH_TOKEN','RUNTIME_ADMIN_TOKEN','BLUEPRINT_ADMIN_TOKEN','PIPELINE_CONTROL_TOKEN','WEB_AUTH_TOKEN','MANAGER_AUTH_TOKEN','MANAGER_ADMIN_TOKEN','APPROVAL_SIGNING_SECRET','MCP_SERVER_TOKEN'])if(!values[key])values[key]=secret();
const flowMode=(values.FLOW_PLATFORM_MODE||'local').toLowerCase();if(!['local','production'].includes(flowMode))throw new Error('FLOW_PLATFORM_MODE must be local or production');values.FLOW_PLATFORM_MODE=flowMode;
const required=['AGENTTEAMS_DOCKER_NETWORK','MINIO_ENDPOINT','MINIO_ACCESS_KEY','MINIO_SECRET_KEY','NEST_LLM_TOKEN','RUNTIME_LLM_TOKEN','RUNTIME_MCP_TOKEN','AGENTTEAMS_CONTROLLER_URL','AGENTTEAMS_AUTH_TOKEN','AGENTTEAMS_MATRIX_URL','AGENTTEAMS_MATRIX_USER_ID','CHATFLOWS_TASK_FS_ENDPOINT','CHATFLOWS_TASK_FS_ACCESS_KEY','CHATFLOWS_TASK_FS_SECRET_KEY','CHATFLOWS_TASK_FS_BUCKET','CHATFLOWS_TASK_FS_PREFIX','AGENTTEAMS_HUMAN_IDS','AGENTTEAMS_LEADER_IDS','AGENTTEAMS_MANAGER_IDS','AGENTTEAMS_E2E_HUMAN_USER_ID','CHATFLOWS_MCP_BASE_URL','HIGRESS_CONSUMER_TOKEN'];if(flowMode==='production')required.push('YUNFLOW_BASE_URL','YUNFLOW_TOKEN');
const missing=required.filter(key=>!values[key]);if(missing.length)throw new Error('source env cannot supply: '+missing.join(', '));
for(const key of ['RUNTIME_AUTH_TOKEN','RUNTIME_ADMIN_TOKEN','MANAGER_AUTH_TOKEN','MANAGER_ADMIN_TOKEN'])if(values[key].length<16)throw new Error(key+' must be at least 16 characters');
if(values.RUNTIME_AUTH_TOKEN===values.RUNTIME_ADMIN_TOKEN||values.MANAGER_AUTH_TOKEN===values.MANAGER_ADMIN_TOKEN)throw new Error('regular/admin tokens must differ');
const ordered=Object.keys(example);for(const key of Object.keys(values))if(!ordered.includes(key))ordered.push(key);fs.writeFileSync(target,ordered.map(key=>key+'='+(values[key]??'')).join('\n')+'\n',{mode:0o600});fs.chmodSync(target,0o600);process.stdout.write('[PASS] wrote mode-0600 stack env with '+ordered.length+' keys; values not printed\n');
