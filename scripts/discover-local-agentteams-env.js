#!/usr/bin/env node
'use strict';
const fs=require('fs'),{execFileSync}=require('child_process');
const target=process.argv[2];if(!target)throw new Error('usage: discover-local-agentteams-env.js <source.env>');
const parse=file=>Object.fromEntries(fs.readFileSync(file,'utf8').split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!line.startsWith('#')&&line.includes('=')).map(line=>{const at=line.indexOf('=');return[line.slice(0,at).trim(),line.slice(at+1).trim().replace(/^(['"])(.*)\1$/,'$2')];}));
const existing=fs.existsSync(target)?parse(target):{};
const run=(file,args)=>execFileSync(file,args,{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
const envOf=name=>{const raw=run('docker',['inspect','--format','{{json .Config.Env}}',name]),entries=JSON.parse(raw);return Object.fromEntries(entries.map(line=>{const at=line.indexOf('=');return[line.slice(0,at),line.slice(at+1)];}));};
const controller=envOf('agentteams-controller'),manager=envOf('agentteams-manager');
const need=(object,key)=>{const value=object[key]?.trim();if(!value)throw new Error(key+' is not configured');return value;};
const controllerToken=run('docker',['exec','agentteams-controller','sh','-lc','test -s /var/run/agentteams/cli-token && cat /var/run/agentteams/cli-token']);
const who=JSON.parse(run('docker',['exec','agentteams-manager','sh','-lc','curl -fsS -H "Authorization: Bearer $AGENTTEAMS_MANAGER_MATRIX_TOKEN" "$AGENTTEAMS_MATRIX_URL/_matrix/client/v3/account/whoami"']));
if(typeof who.user_id!=='string'||!who.user_id.startsWith('@'))throw new Error('manager Matrix whoami invalid');
const domain=need(manager,'AGENTTEAMS_MATRIX_DOMAIN'),adminName=need(controller,'AGENTTEAMS_ADMIN_USER').replace(/^@/,'').split(':')[0];
const humanId='@'+adminName+':'+domain,leaderId='@chatflows-leader:'+domain;
if(new Set([humanId,leaderId,who.user_id]).size!==3)throw new Error('Manager/Human/Leader identities must be disjoint');
const fsEndpoint='http://agentteams-controller:9000',gateway='http://agentteams-controller:8080',gatewayKey=need(manager,'AGENTTEAMS_MANAGER_GATEWAY_KEY');
const values={...existing,
 AGENTTEAMS_DOCKER_NETWORK:'agentteams-net',
 MINIO_ENDPOINT:fsEndpoint,MINIO_ACCESS_KEY:need(controller,'AGENTTEAMS_MINIO_USER'),MINIO_SECRET_KEY:need(controller,'AGENTTEAMS_MINIO_PASSWORD'),MINIO_BUCKET:need(controller,'AGENTTEAMS_MINIO_BUCKET'),
 RUNTIME_MODEL:'dashscope:deepseek-v4-flash-0731',NEST_LLM_BASE_URL:gateway+'/v1',NEST_LLM_TOKEN:gatewayKey,RUNTIME_LLM_BASE_URL:gateway+'/v1',RUNTIME_LLM_TOKEN:gatewayKey,
 RUNTIME_MCP_URL:gateway+'/mcp-servers/chatflows-p3c',RUNTIME_MCP_TOKEN:gatewayKey,ORCHESTRATOR_LLM:'off',ORCHESTRATOR_LLM_BASE_URL:gateway+'/v1',
 FLOW_PLATFORM_MODE:'local',ORCHESTRATION_MODE:'platform',AGENT_MANAGER_HOST_PORT:'18090',
 AGENTTEAMS_CONTROLLER_URL:'http://agentteams-controller:8090',AGENTTEAMS_AUTH_TOKEN:controllerToken,
 AGENTTEAMS_MATRIX_URL:'http://agentteams-controller:6167',AGENTTEAMS_MATRIX_USER_ID:who.user_id,AGENTTEAMS_MATRIX_ACCESS_TOKEN:need(manager,'AGENTTEAMS_MANAGER_MATRIX_TOKEN'),
 CHATFLOWS_TASK_FS_ENDPOINT:fsEndpoint,CHATFLOWS_TASK_FS_ACCESS_KEY:'chatflows-task-manager',CHATFLOWS_TASK_FS_SECRET_KEY:existing.CHATFLOWS_TASK_FS_SECRET_KEY||'',CHATFLOWS_TASK_FS_BUCKET:need(manager,'AGENTTEAMS_FS_BUCKET'),CHATFLOWS_TASK_FS_PREFIX:'teams/chatflows-build-team/shared/tasks',
 AGENTTEAMS_HUMAN_IDS:humanId,AGENTTEAMS_E2E_HUMAN_USER_ID:humanId,AGENTTEAMS_E2E_HUMAN_PASSWORD:need(controller,'AGENTTEAMS_ADMIN_PASSWORD'),
 AGENTTEAMS_LEADER_IDS:leaderId,AGENTTEAMS_MANAGER_IDS:who.user_id,AGENTTEAMS_RUN_CLIENT_CODE:'acme_beauty_missing_kb',
 CHATFLOWS_MCP_BASE_URL:gateway,HIGRESS_CONSUMER_TOKEN:gatewayKey,
};
fs.writeFileSync(target,Object.entries(values).map(([key,value])=>key+'='+value).join('\n')+'\n',{mode:0o600});fs.chmodSync(target,0o600);
process.stdout.write('[PASS] discovered local AgentTeams endpoints and identities into mode-0600 source env; values not printed\n');
