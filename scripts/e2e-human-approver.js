#!/usr/bin/env node
'use strict';

function escapeRegex(value){return value.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');}
function requestedApproval(timeline,runId,leaders){
  const pattern=new RegExp('^APPROVAL_REQUIRED\\s+run_id='+escapeRegex(runId)+'\\s+approval_id=([A-Za-z0-9_-]+)$');
  for(const event of timeline.chunk??[]){
    if(event.type!=='m.room.message'||event.content?.msgtype!=='m.text'||!leaders.has(event.sender))continue;
    const match=pattern.exec(String(event.content.body??'').trim());
    if(match)return match[1];
  }
  return undefined;
}
function selfTest(){
  const leaders=new Set(['@leader:local']),run='run-1';
  const timeline={chunk:[
    {type:'m.room.message',sender:'@intruder:local',content:{msgtype:'m.text',body:'APPROVAL_REQUIRED run_id=run-1 approval_id=evil'}},
    {type:'m.room.message',sender:'@leader:local',content:{msgtype:'m.text',body:'please APPROVAL_REQUIRED run_id=run-1 approval_id=prose'}},
    {type:'m.room.message',sender:'@leader:local',content:{msgtype:'m.text',body:'APPROVAL_REQUIRED run_id=other approval_id=wrong'}},
    {type:'m.room.message',sender:'@leader:local',content:{msgtype:'m.text',body:'APPROVAL_REQUIRED run_id=run-1 approval_id=approval-1'}},
  ]};
  if(requestedApproval(timeline,run,leaders)!=='approval-1')throw new Error('strict approval request selection failed');
  if(requestedApproval({chunk:timeline.chunk.slice(0,3)},run,leaders)!==undefined)throw new Error('unauthorized or ambiguous request accepted');
  process.stdout.write('[PASS] E2E Human only approves exact current-run requests from an allowed Leader\n');
}
if(process.argv.includes('--self-test')){selfTest();process.exit(0);}

const required=name=>{const value=process.env[name]?.trim();if(!value)throw new Error(name+' required');return value;};
const matrix=required('AGENTTEAMS_MATRIX_URL').replace(/\/$/,''),runId=required('AGENTTEAMS_RUN_ID');
const manager=required('AGENT_MANAGER_URL').replace(/\/$/,''),managerToken=required('MANAGER_AUTH_TOKEN');
const user=required('AGENTTEAMS_E2E_HUMAN_USER_ID'),leaders=new Set(required('AGENTTEAMS_LEADER_IDS').split(',').map(x=>x.trim()).filter(Boolean));
const timeoutMs=Number(process.env.AGENTTEAMS_E2E_APPROVAL_TIMEOUT_MS??900000),enc=encodeURIComponent;
async function request(path,init={},expected){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);try{const response=await fetch(matrix+path,{...init,signal:controller.signal});const body=await response.text();if(expected!==undefined?response.status!==expected:!response.ok)throw new Error('Matrix HTTP '+response.status+' '+body.slice(0,200));return body?JSON.parse(body):{};}finally{clearTimeout(timer);}}
async function token(){const supplied=process.env.AGENTTEAMS_E2E_HUMAN_ACCESS_TOKEN?.trim();if(supplied)return supplied;const password=required('AGENTTEAMS_E2E_HUMAN_PASSWORD');const login=await request('/_matrix/client/v3/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'m.login.password',identifier:{type:'m.id.user',user},password})});if(login.user_id!==user||!login.access_token)throw new Error('E2E Human Matrix identity mismatch');return login.access_token;}
async function main(){
  if(!Number.isFinite(timeoutMs)||timeoutMs<1000)throw new Error('invalid approval timeout');
  const access=await token(),headers={authorization:'Bearer '+access};
  const who=await request('/_matrix/client/v3/account/whoami',{headers});if(who.user_id!==user)throw new Error('E2E Human token identity mismatch');
  const humans=new Set(required('AGENTTEAMS_HUMAN_IDS').split(',').map(x=>x.trim()).filter(Boolean));if(!humans.has(user)||leaders.has(user))throw new Error('E2E Human must be in Human allowlist only');
  const deadline=Date.now()+timeoutMs;
  while(Date.now()<deadline){
    const statusResponse=await fetch(manager+'/api/v1/orchestrations/'+enc(runId),{headers:{authorization:'Bearer '+managerToken,'x-role':'human','accept':'application/json'}});const statusText=await statusResponse.text();if(statusResponse.status===404){await new Promise(resolve=>setTimeout(resolve,500));continue;}if(!statusResponse.ok)throw new Error('Manager run status HTTP '+statusResponse.status+' '+statusText.slice(0,200));let status;try{status=JSON.parse(statusText);}catch{throw new Error('Manager run status returned non-JSON');}const approvalId=Array.isArray(status.pending_approvals)?status.pending_approvals[0]:undefined;
    if(approvalId){const response=await fetch(manager+'/api/v1/orchestrations/'+enc(runId)+'/approval',{method:'POST',headers:{authorization:'Bearer '+managerToken,'x-role':'human','x-actor':user,'content-type':'application/json','accept':'application/json'},body:JSON.stringify({approval_id:approvalId,approved:true})});const text=await response.text();if(!response.ok)throw new Error('Manager approval HTTP '+response.status+' '+text.slice(0,200));let approval;try{approval=JSON.parse(text).approval;}catch{throw new Error('Manager approval returned non-JSON');}if(!approval?.proof||approval?.decision!=='APPROVE')throw new Error('Manager approval response missing APPROVE proof');process.stdout.write('[PASS] isolated E2E Human approved through Manager/Nest signed proof run_id='+runId+' approval_id='+approvalId+'\n');return;}
    if(['SUCCEEDED','ABORTED','FAILED'].includes(status.status))throw new Error('run became '+status.status+' before Human approval');
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  throw new Error('E2E Human approval timed out for run_id='+runId);
}
main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
