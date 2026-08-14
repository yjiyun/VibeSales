#!/usr/bin/env node
'use strict';

const required=name=>{const value=process.env[name]?.trim();if(!value)throw new Error(name+' required');return value;};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const enc=encodeURIComponent;

function parseIds(raw,name){
  const ids=new Set(raw.split(',').map(value=>value.trim()).filter(Boolean));
  if(!ids.size)throw new Error(name+' must contain at least one Matrix ID');
  for(const id of ids)if(!/^@[A-Za-z0-9._=-]+:[A-Za-z0-9.-]+(?::[0-9]{1,5})?$/.test(id))throw new Error(name+' contains an invalid Matrix ID');
  return ids;
}

function resetAcknowledged(timeline,workerId,afterTimestamp){
  for(const event of timeline.chunk??[]){
    if(event.type!=='m.room.message'||event.content?.msgtype!=='m.text'||event.sender!==workerId)continue;
    if(Number(event.origin_server_ts??0)<afterTimestamp)continue;
    const body=String(event.content.body??'');
    if(body.includes('New Conversation Started')||body.includes('No messages to summarize'))return event;
  }
  return undefined;
}

function selectTeamWorkers(team,workers){
  if(team?.name!=='chatflows-build-team'||!String(team.teamRoomID??'').startsWith('!'))throw new Error('fixed chatflows Team Room missing');
  const names=Array.isArray(team.workerNames)?team.workerNames:[];
  if(names.length!==10||new Set(names).size!==10)throw new Error('chatflows Team must contain exactly 10 execution Workers');
  const byName=new Map((workers?.workers??[]).map(worker=>[worker.name,worker]));
  return names.map(name=>{
    const worker=byName.get(name),matrixUserId=String(worker?.matrixUserID??'');
    if(worker?.team!=='chatflows-build-team'||worker?.role!=='worker'||!matrixUserId.startsWith('@'))throw new Error('invalid execution Worker identity: '+name);
    return {name,matrixUserId};
  });
}

function selfTest(){
  const workers={workers:Array.from({length:10},(_,index)=>({name:'w'+index,team:'chatflows-build-team',role:'worker',matrixUserID:'@w'+index+':local'}))};
  const team={name:'chatflows-build-team',teamRoomID:'!team:local',workerNames:Array.from({length:10},(_,index)=>'w'+index)};
  const selected=selectTeamWorkers(team,workers);
  if(selected.length!==10||selected[9].matrixUserId!=='@w9:local')throw new Error('execution Worker selection failed');
  const timeline={chunk:[
    {type:'m.room.message',sender:'@other:local',origin_server_ts:200,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
    {type:'m.room.message',sender:'@w0:local',origin_server_ts:99,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
    {type:'m.room.message',sender:'@w0:local',origin_server_ts:201,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
  ]};
  if(resetAcknowledged(timeline,'@w0:local',100)!==timeline.chunk[2])throw new Error('strict Worker reset acknowledgement selection failed');
  process.stdout.write('[PASS] Team session reset selects exactly 10 execution Workers and requires fresh per-Worker acknowledgements\n');
}

if(process.argv.includes('--self-test')){selfTest();process.exit(0);}

const controller=required('AGENTTEAMS_CONTROLLER_URL').replace(/\/$/,'');
const controllerToken=required('AGENTTEAMS_AUTH_TOKEN');
const matrix=required('AGENTTEAMS_MATRIX_URL').replace(/\/$/,'');
const humanId=required('AGENTTEAMS_E2E_HUMAN_USER_ID');
const humans=parseIds(required('AGENTTEAMS_HUMAN_IDS'),'AGENTTEAMS_HUMAN_IDS');
const managers=parseIds(required('AGENTTEAMS_MANAGER_IDS'),'AGENTTEAMS_MANAGER_IDS');
const leaders=parseIds(required('AGENTTEAMS_LEADER_IDS'),'AGENTTEAMS_LEADER_IDS');
const timeoutMs=Number(process.env.AGENTTEAMS_WORKER_RESET_TIMEOUT_MS??120000);

async function jsonRequest(base,path,init={}){
  const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),10000);
  try{
    const response=await fetch(base+path,{...init,signal:abort.signal});
    const text=await response.text();
    if(!response.ok)throw new Error('HTTP '+response.status+' '+text.slice(0,200));
    try{return text?JSON.parse(text):{};}catch{throw new Error('endpoint returned non-JSON');}
  }finally{clearTimeout(timer);}
}

async function accessToken(){
  const supplied=process.env.AGENTTEAMS_E2E_HUMAN_ACCESS_TOKEN?.trim();
  if(supplied)return supplied;
  const password=required('AGENTTEAMS_E2E_HUMAN_PASSWORD');
  const login=await jsonRequest(matrix,'/_matrix/client/v3/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'m.login.password',identifier:{type:'m.id.user',user:humanId},password})});
  if(login.user_id!==humanId||!login.access_token)throw new Error('E2E Human Matrix login identity mismatch');
  return login.access_token;
}

async function main(){
  if(!humans.has(humanId)||managers.has(humanId)||leaders.has(humanId))throw new Error('session reset identity must be in Human allowlist only');
  if(!Number.isFinite(timeoutMs)||timeoutMs<1000)throw new Error('invalid Worker reset timeout');
  const controllerHeaders={authorization:'Bearer '+controllerToken,accept:'application/json'};
  const [team,allWorkers]=await Promise.all([
    jsonRequest(controller,'/api/v1/teams/chatflows-build-team',{headers:controllerHeaders}),
    jsonRequest(controller,'/api/v1/workers',{headers:controllerHeaders}),
  ]);
  const workers=selectTeamWorkers(team,allWorkers),roomId=team.teamRoomID;
  const token=await accessToken(),matrixHeaders={authorization:'Bearer '+token};
  const who=await jsonRequest(matrix,'/_matrix/client/v3/account/whoami',{headers:matrixHeaders});
  if(who.user_id!==humanId)throw new Error('E2E Human Matrix token identity mismatch');
  for(const worker of workers){
    const startedAt=Date.now(),txn='worker-reset-'+worker.name+'-'+startedAt+'-'+Math.random().toString(16).slice(2);
    const content={msgtype:'m.text',body:worker.matrixUserId+' /new','m.mentions':{user_ids:[worker.matrixUserId]}};
    await jsonRequest(matrix,'/_matrix/client/v3/rooms/'+enc(roomId)+'/send/m.room.message/'+enc(txn),{method:'PUT',headers:{...matrixHeaders,'content-type':'application/json'},body:JSON.stringify(content)});
    const deadline=Date.now()+timeoutMs;
    let acknowledged=false;
    while(Date.now()<deadline){
      const timeline=await jsonRequest(matrix,'/_matrix/client/v3/rooms/'+enc(roomId)+'/messages?dir=b&limit=100',{headers:matrixHeaders});
      if(resetAcknowledged(timeline,worker.matrixUserId,startedAt)){acknowledged=true;break;}
      await sleep(1000);
    }
    if(!acknowledged)throw new Error('session reset acknowledgement timed out for '+worker.name);
    process.stdout.write('[PASS] reset execution Worker session: '+worker.name+'\n');
  }
  if(leaders.size!==1)throw new Error('exactly one Leader identity required');
  const leaderId=[...leaders][0],leaderStartedAt=Date.now(),leaderTxn='team-leader-reset-'+leaderStartedAt+'-'+Math.random().toString(16).slice(2);
  await jsonRequest(matrix,'/_matrix/client/v3/rooms/'+enc(roomId)+'/send/m.room.message/'+enc(leaderTxn),{method:'PUT',headers:{...matrixHeaders,'content-type':'application/json'},body:JSON.stringify({msgtype:'m.text',body:leaderId+' /new','m.mentions':{user_ids:[leaderId]}})});
  const leaderDeadline=Date.now()+timeoutMs;
  let leaderAcknowledged=false;
  while(Date.now()<leaderDeadline){const timeline=await jsonRequest(matrix,'/_matrix/client/v3/rooms/'+enc(roomId)+'/messages?dir=b&limit=100',{headers:matrixHeaders});if(resetAcknowledged(timeline,leaderId,leaderStartedAt)){leaderAcknowledged=true;break;}await sleep(1000);}
  if(!leaderAcknowledged)throw new Error('Team Room Leader session reset acknowledgement timed out');
  process.stdout.write('[PASS] all 10 execution Workers and the Leader acknowledged fresh Team Room sessions before E2E dispatch\n');
}

main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
