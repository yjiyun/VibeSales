import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runId = '123e4567-e89b-42d3-a456-426614174000';
const roomId = '!chatflows:local';
const approvalId = 'approval-platform-1';
let approved = false;
const calls = [];
const blueprint = { blueprintId:'bp-platform', version:1, clientCode:'acme', runtimeAgentId:'agent-platform', meta:{ runId }, prompt:{agentsMd:'a',soulMd:'s'}, skills:[], tools:{allow:[],deny:[],mcpServers:[]}, runtime:{model:'qwen',isolationScope:'USER',maxContextTokens:100} };
const artifacts = ['wizard_state','triage','match_result','guidance','expert_dispatch',...Array(4).fill('expert_result'),'blueprint','blueprint_check','approval','import_result','dry_run','evidence'].map((kind,index) => ({ artifact_id:`a-${index}`,run_id:runId,client_code:'acme',kind,version:kind==='expert_result'?index:1,payload:kind==='approval'?{approval_id:approvalId,status:'PENDING'}:kind==='blueprint'?blueprint:{ok:true} }));
const listen = server => new Promise((resolve,reject) => { server.once('error',reject);server.listen(0,'127.0.0.1',()=>resolve(server)); });
const server = await listen(http.createServer(async (request,response) => {
  let body='';for await(const chunk of request)body+=chunk;calls.push({method:request.method,url:request.url,headers:request.headers,body});
  const send=(status,value,type='application/json')=>{response.writeHead(status,{'content-type':type});response.end(type==='application/json'?JSON.stringify(value):value);};
  if(request.url==='/api/v1/health')return send(200,{ok:true});
  if(request.url==='/api/v1/orchestrations'&&request.method==='POST')return send(202,{run_id:runId,client_code:'acme',room_id:roomId,status:'DISPATCHED',pending_approvals:[approvalId]});
  if(request.url===`/api/v1/orchestrations/${runId}`&&request.method==='GET')return send(200,{run_id:runId,client_code:'acme',room_id:roomId,status:approved?'SUCCEEDED':'DISPATCHED',pending_approvals:approved?[]:[approvalId]});
  if(request.url===`/api/v1/orchestrations/${runId}/approval`&&request.method==='POST'){approved=true;return send(200,{run_id:runId,status:'SIGNED',approval:{approval_id:approvalId,actor:'@human:local',decision:'APPROVE',proof:'signed-proof'}});}
  if(request.url===`/api/v1/pipeline/${runId}`)return send(200,{run:{run_id:runId,client_code:'acme',status:approved?'SUCCEEDED':'WAITING_HUMAN',current_phase:'P4',build_path:'P3C'},artifacts});
  if(request.url?.startsWith('/api/v1/blueprints/publish'))return send(200,{blueprintId:'bp-platform',version:1,status:'PUBLISHED'});
  if(request.url?.startsWith('/api/v1/chat?'))return send(200,'event: message\ndata: {"delta":"ok"}\n\nevent: done\ndata: {}\n\n','text/event-stream');
  if(request.url===`/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/messages?dir=b&limit=100`)return send(200,{chunk:[{content:{body:`@chatflows-leader task-${runId} spec.md ready`}},{content:{body:`@chatflows-leader APPROVAL_PROOF run_id=${runId} approval_id=${approvalId}`}}]});
  return send(404,{error:'not found'});
}));

const temp=fs.mkdtempSync(path.join(os.tmpdir(),'agentteams-platform-e2e-'));
const envFile=path.join(temp,'test.env');
const evidence=path.join(temp,'evidence.json');
const phase1File=path.join(temp,'phase1-result.json');
const base=`http://127.0.0.1:${server.address().port}`;
const phase1={gate:'PASS',client_code:'acme',triage:{scene_id:'beauty_wecom_cs',industry:'beauty',channel:'wecom'},summary:{business_brief:'same wizard result'}};
fs.writeFileSync(phase1File,JSON.stringify(phase1));
fs.writeFileSync(envFile,Object.entries({MANAGER_API:base,MANAGER_AUTH_TOKEN:'manager-token-0123456789',CHATFLOWS_NEST_URL:base,PIPELINE_CONTROL_TOKEN:'pipeline-token-0123456789',AGENT_RUNTIME_URL:base,AGENT_RUNTIME_TOKEN:'runtime-token-0123456789',AGENTTEAMS_MATRIX_URL:base,AGENTTEAMS_MATRIX_ACCESS_TOKEN:'matrix-token',AGENTTEAMS_MATRIX_USER_ID:'@manager:local',AGENTTEAMS_HUMAN_IDS:'@human:local',AGENTTEAMS_RUN_CLIENT_CODE:'acme',AGENTTEAMS_PHASE1_RESULT_FILE:phase1File,BLUEPRINT_ADMIN_TOKEN:'blueprint-token-0123456789',AGENTLOOP_ENDPOINT:base+'/agentloop',AGENTLOOP_ACCESS_KEY:'key',AGENTLOOP_ACCESS_SECRET:'secret',AGENTTEAMS_RUN_TIMEOUT_SECONDS:'3',AGENTTEAMS_E2E_POLL_MS:'10',AGENTTEAMS_E2E_EVIDENCE_FILE:evidence}).map(([key,value])=>`${key}=${value}`).join('\n')+'\n');
const result=await new Promise((resolve,reject)=>{const child=spawn(process.execPath,[path.join(root,'scripts/run-agentteams-platform-e2e.js'),envFile],{cwd:root,env:{...process.env}});let stdout='',stderr='';child.stdout.on('data',x=>stdout+=x);child.stderr.on('data',x=>stderr+=x);child.once('error',reject);child.once('close',status=>resolve({status,stdout,stderr}));});
try{
 assert.equal(result.status,0,result.stderr);
 assert.match(result.stdout,/Nest-issued run_id/);
 assert.match(result.stdout,/Runtime SSE message \+ done/);
 assert.match(result.stdout,/\[PENDING\] AgentLoop query-side proof/);
 const create=calls.find(call=>call.url==='/api/v1/orchestrations'&&call.method==='POST');
 assert.equal(create.headers['x-role'],'orchestrator');assert.equal(create.headers['x-actor'],'@manager:local');
 assert.deepEqual(JSON.parse(create.body),{client_code:'acme',spec:JSON.stringify({phase:'P1',phase1_result:phase1},null,2)});
 const approval=calls.find(call=>call.url?.endsWith('/approval'));assert.equal(approval.headers['x-role'],'human');assert.equal(approval.headers['x-actor'],'@human:local');
 const saved=JSON.parse(fs.readFileSync(evidence,'utf8'));assert.equal(saved.run_id,runId);assert.equal(saved.worker_usage_available,false);
 const shell=fs.readFileSync(path.join(root,'scripts/run-agentteams-e2e.sh'),'utf8');
 assert.doesNotMatch(shell,/randomUUID|run\.sh" (?:run|resume)|AGENTTEAMS_RESUME/);
 assert.match(shell,/AGENTTEAMS_CONFIRM_APPLY/);
 assert.match(shell,/confirm_apply="\$\{AGENTTEAMS_CONFIRM_APPLY:-\}"/);
 assert.match(shell,/ensure_manager/);
 assert.match(shell,/--validate-phase1-only/);
 const validate=async value=>{fs.writeFileSync(phase1File,JSON.stringify(value));return await new Promise((resolve,reject)=>{const child=spawn(process.execPath,[path.join(root,'scripts/run-agentteams-platform-e2e.js'),envFile,'--validate-phase1-only'],{cwd:root,env:{...process.env}});let stdout='',stderr='';child.stdout.on('data',x=>stdout+=x);child.stderr.on('data',x=>stderr+=x);child.once('error',reject);child.once('close',status=>resolve({status,stdout,stderr}));});};
 const ask=await validate({...phase1,gate:'ASK'});assert.notEqual(ask.status,0);assert.match(ask.stderr,/gate must be PASS/);
 const otherTenant=await validate({...phase1,client_code:'other'});assert.notEqual(otherTenant.status,0);assert.match(otherTenant.stderr,/client_code must match/);
 process.stdout.write('[PASS] platform e2e uses Manager HTTP + Nest-issued run_id, Human approval, artifacts, Matrix and Runtime SSE\n');
}finally{await new Promise(resolve=>server.close(resolve));fs.rmSync(temp,{recursive:true,force:true});}
