#!/usr/bin/env node
'use strict';

const fs=require('fs');
const required=name=>{const value=process.env[name]?.trim();if(!value)throw new Error(name+' required');return value;};
const base=required('AGENT_MANAGER_URL').replace(/\/$/,''),token=required('MANAGER_AUTH_TOKEN');
const headers={authorization:'Bearer '+token,'x-role':'orchestrator','x-actor':'agentteams-e2e','content-type':'application/json','accept':'application/json'};
async function request(path,init={}){const response=await fetch(base+path,{headers,...init});const text=await response.text();if(!response.ok)throw new Error('Manager HTTP '+response.status+' '+text.slice(0,240));try{return text?JSON.parse(text):{};}catch{throw new Error('Manager returned non-JSON');}}
async function start(){const spec=fs.readFileSync(required('AGENTTEAMS_RUN_SPEC'),'utf8'),body={client_code:required('AGENTTEAMS_RUN_CLIENT_CODE'),room_id:required('AGENTTEAMS_LEADER_ROOM_ID'),spec},run=await request('/api/v1/orchestrations',{method:'POST',body:JSON.stringify(body)});if(typeof run.run_id!=='string'||!run.run_id)throw new Error('Manager create response missing run_id');process.stdout.write(run.run_id+'\n');}
async function wait(){const runId=required('AGENTTEAMS_RUN_ID'),deadline=Date.now()+Number(process.env.AGENTTEAMS_RUN_TIMEOUT_SECONDS??3600)*1000;while(Date.now()<deadline){const run=await request('/api/v1/orchestrations/'+encodeURIComponent(runId),{headers:{authorization:'Bearer '+token,'x-role':'orchestrator','accept':'application/json'}});if(['SUCCEEDED','ABORTED','FAILED'].includes(run.status)){if(run.status!=='SUCCEEDED')throw new Error('run '+runId+' ended '+run.status+': '+String(run.summary??''));process.stdout.write('[PASS] Manager HTTP orchestration reached SUCCEEDED run_id='+runId+'\n');return;}await new Promise(resolve=>setTimeout(resolve,2000));}throw new Error('Manager orchestration timed out run_id='+runId);}
const command=process.argv[2];(command==='start'?start():command==='wait'?wait():Promise.reject(new Error('usage: e2e-manager-run.js start|wait'))).catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
