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

function resetAcknowledged(timeline,leaders,afterTimestamp){
  for(const event of timeline.chunk??[]){
    if(event.type!=='m.room.message'||event.content?.msgtype!=='m.text'||!leaders.has(event.sender))continue;
    if(Number(event.origin_server_ts??0)<afterTimestamp)continue;
    const body=String(event.content.body??'');
    if(body.includes('New Conversation Started')||body.includes('No messages to summarize'))return event;
  }
  return undefined;
}

function selfTest(){
  const leaders=new Set(['@leader:local']);
  const timeline={chunk:[
    {type:'m.room.message',sender:'@intruder:local',origin_server_ts:200,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
    {type:'m.room.message',sender:'@leader:local',origin_server_ts:99,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
    {type:'m.room.message',sender:'@leader:local',origin_server_ts:201,content:{msgtype:'m.text',body:'**New Conversation Started!**'}},
  ]};
  if(resetAcknowledged(timeline,leaders,100)!==timeline.chunk[2])throw new Error('strict Leader reset acknowledgement selection failed');
  if(resetAcknowledged({chunk:timeline.chunk.slice(0,2)},leaders,100)!==undefined)throw new Error('stale or unauthorized reset acknowledgement accepted');
  process.stdout.write('[PASS] Leader session reset accepts only a fresh acknowledgement from an allowed Leader\n');
}

if(process.argv.includes('--self-test')){selfTest();process.exit(0);}

const matrix=required('AGENTTEAMS_MATRIX_URL').replace(/\/$/,'');
const roomId=required('AGENTTEAMS_LEADER_ROOM_ID');
const managerId=required('AGENTTEAMS_MATRIX_USER_ID');
const managers=parseIds(required('AGENTTEAMS_MANAGER_IDS'),'AGENTTEAMS_MANAGER_IDS');
const leaders=parseIds(required('AGENTTEAMS_LEADER_IDS'),'AGENTTEAMS_LEADER_IDS');
const timeoutMs=Number(process.env.AGENTTEAMS_LEADER_RESET_TIMEOUT_MS??120000);

async function matrixRequest(path,init={}){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);
  try{
    const response=await fetch(matrix+path,{...init,signal:controller.signal});
    const text=await response.text();
    if(!response.ok)throw new Error('Matrix HTTP '+response.status+' '+text.slice(0,200));
    try{return text?JSON.parse(text):{};}catch{throw new Error('Matrix returned non-JSON');}
  }finally{clearTimeout(timer);}
}

async function accessToken(){
  const supplied=process.env.AGENTTEAMS_MATRIX_ACCESS_TOKEN?.trim();
  if(supplied)return supplied;
  const password=required('AGENTTEAMS_MATRIX_PASSWORD');
  const login=await matrixRequest('/_matrix/client/v3/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'m.login.password',identifier:{type:'m.id.user',user:managerId},password})});
  if(login.user_id!==managerId||!login.access_token)throw new Error('Manager Matrix login identity mismatch');
  return login.access_token;
}

async function main(){
  if(!managers.has(managerId)||leaders.has(managerId))throw new Error('session reset identity must be in Manager allowlist only');
  if(!roomId.startsWith('!'))throw new Error('AGENTTEAMS_LEADER_ROOM_ID must be a Matrix room ID');
  if(!Number.isFinite(timeoutMs)||timeoutMs<1000)throw new Error('invalid Leader reset timeout');
  const token=await accessToken(),headers={authorization:'Bearer '+token};
  const who=await matrixRequest('/_matrix/client/v3/account/whoami',{headers});
  if(who.user_id!==managerId)throw new Error('Manager Matrix token identity mismatch');
  const leader=[...leaders][0],startedAt=Date.now();
  const txn='leader-reset-'+startedAt+'-'+Math.random().toString(16).slice(2);
  const content={msgtype:'m.text',body:leader+' /new','m.mentions':{user_ids:[leader]}};
  await matrixRequest('/_matrix/client/v3/rooms/'+enc(roomId)+'/send/m.room.message/'+enc(txn),{method:'PUT',headers:{...headers,'content-type':'application/json'},body:JSON.stringify(content)});
  const deadline=Date.now()+timeoutMs;
  while(Date.now()<deadline){
    const timeline=await matrixRequest('/_matrix/client/v3/rooms/'+enc(roomId)+'/messages?dir=b&limit=100',{headers});
    if(resetAcknowledged(timeline,leaders,startedAt)){
      process.stdout.write('[PASS] Leader acknowledged a fresh Matrix session before E2E dispatch\n');
      return;
    }
    await sleep(1000);
  }
  throw new Error('Leader session reset acknowledgement timed out');
}

main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
