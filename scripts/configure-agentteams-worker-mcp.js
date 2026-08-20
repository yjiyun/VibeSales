#!/usr/bin/env node
'use strict';

const {execFileSync}=require('child_process');

const assignments=[
  ['wizard-intent','chatflows-p1'],
  ['template-match','chatflows-p2'],
  ['template-personalize','chatflows-p3'],
  ['flow-generate','chatflows-p3b'],
  ['blueprint-compose','chatflows-p3c'],
  ['flow-import-run','chatflows-p4'],
  ['persona-expert','chatflows-p3c'],
  ['business-expert','chatflows-p3c'],
  ['skill-expert','chatflows-p3c'],
  ['tool-expert','chatflows-p3c'],
];
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function dockerPython(container, script, extraEnv){
  const ssh=process.env.AGENTTEAMS_DOCKER_SSH?.trim();
  const envArgs=Object.entries(extraEnv||{}).flatMap(([key,value])=>{
    if(!/^[A-Z0-9_]+$/.test(key))throw new Error('invalid env name');
    return['-e',key+'='+value];
  });
  const opts={input:script,encoding:'utf8',stdio:['pipe','pipe','pipe']};
  const execArgs=['exec','-i',...envArgs,container,'python','-'];
  if(!ssh)return execFileSync('docker',execArgs,opts);
  const sshArgs=['-o','StrictHostKeyChecking=accept-new'];
  if(process.env.SSHPASS){
    sshArgs.push('-o','PreferredAuthentications=password','-o','PubkeyAuthentication=no');
    return execFileSync('sshpass',['-e','ssh',...sshArgs,ssh,'docker',...execArgs],opts);
  }
  return execFileSync('ssh',['-o','BatchMode=yes',...sshArgs,ssh,'docker',...execArgs],opts);
}
const python=client=>String.raw`
import json,os,time,urllib.parse,urllib.request
client=${JSON.stringify(client)}
base="http://127.0.0.1:8088/api/mcp/"
path=urllib.parse.quote(client,safe="")
# 阶段 Worker 的 TeamHarness 没有 message（那是 Leader 跨房间派活用的）；
# 它们在被 @mention 的 Team Room 里直接回复。禁止 taskflow 以免自报 SUCCESS。
allowed=["health","filesync"]
forbidden=set(["artifact","projectflow","roomflow","taskflow","message"])
def call(method,url,data=None):
    body=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(url,data=body,headers={"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(req,timeout=10) as response:
        return json.load(response)
call("PUT",base+"teamharness",{"tools":allowed})
configured=call("GET",base+"teamharness")
if configured.get("tools")!=allowed:
    raise RuntimeError("TeamHarness tool allowlist readback mismatch")
harness=[]
for _ in range(30):
    harness=sorted(item.get("name") for item in call("GET",base+"tools/teamharness") if item.get("enabled"))
    if harness==sorted(allowed):
        break
    time.sleep(1)
if harness!=sorted(allowed) or forbidden.intersection(harness):
    raise RuntimeError("unexpected TeamHarness tools: "+",".join(harness))
gateway=os.environ.get("CHATFLOWS_MCP_BASE_URL","").rstrip("/")
token=os.environ.get("HIGRESS_CONSUMER_TOKEN","").strip()
def bind_client():
    if gateway and token:
        call("PUT",base+path,{"enabled":True,"transport":"streamable_http","url":gateway+"/mcp-servers/"+client,"headers":{"Authorization":"Bearer "+token}})
    else:
        call("PUT",base+path,{})
matrix_base=__import__("os").environ["AGENTTEAMS_MATRIX_URL"].rstrip("/")
matrix_token=__import__("os").environ["AGENTTEAMS_WORKER_MATRIX_TOKEN"]
def matrix_get(path):
    req=urllib.request.Request(matrix_base+path,headers={"Authorization":"Bearer "+matrix_token})
    with urllib.request.urlopen(req,timeout=10) as response:
        return json.load(response)
team_rooms=[]
for room_id in matrix_get("/_matrix/client/v3/joined_rooms").get("joined_rooms",[]):
    quoted=urllib.parse.quote(room_id,safe="")
    try:
        name=matrix_get("/_matrix/client/v3/rooms/"+quoted+"/state/m.room.name/").get("name","")
        members=matrix_get("/_matrix/client/v3/rooms/"+quoted+"/joined_members").get("joined",{})
    except Exception:
        continue
    if name=="Team: chatflows-build-team" and "@manager:" not in " ".join(members):
        team_rooms.append(room_id)
if len(team_rooms)!=1:
    raise RuntimeError("exactly one manager-free chatflows Team Room required")
team_room=team_rooms[0]
policy={
  "default_effect":"deny",
  "client_overrides":[{
    "source_type":"channel",
    "source_value":"agentteams_matrix",
    "subject_type":"user",
    "subject_value":team_room,
    "effect":"allow"
  }],
  "tool_defaults":[],
  "tool_overrides":[]
}
saved=call("PUT",base+"policy/"+path,policy)
overrides=saved.get("client_overrides",[])
if saved.get("default_effect")!="deny" or not any(
    item.get("source_type")=="channel" and
    item.get("source_value")=="agentteams_matrix" and
    item.get("subject_type")=="user" and
    item.get("subject_value")==team_room and
    item.get("effect")=="allow"
    for item in overrides
):
    raise RuntimeError("Matrix-scoped MCP allow policy was not saved")
bind_client()
tools=[]
for _ in range(30):
    try:
        tools=call("GET",base+"tools/"+path)
        if tools:
            break
    except Exception:
        pass
    time.sleep(1)
if not tools:
    raise RuntimeError("MCP tools were not discovered")
print(json.dumps({"client":client,"default":saved["default_effect"],"teamRoom":"allow","toolCount":len(tools),"teamHarness":harness}))
`;

async function main(){
  for(const [worker,client] of assignments){
    const container='agentteams-worker-'+worker;
    let last;
    for(let attempt=0;attempt<60;attempt++){
      try{
        const extraEnv={};
        if(process.env.CHATFLOWS_MCP_BASE_URL?.trim())extraEnv.CHATFLOWS_MCP_BASE_URL=process.env.CHATFLOWS_MCP_BASE_URL.trim().replace(/\/$/,'');
        if(process.env.HIGRESS_CONSUMER_TOKEN?.trim())extraEnv.HIGRESS_CONSUMER_TOKEN=process.env.HIGRESS_CONSUMER_TOKEN.trim();
        const output=dockerPython(container,python(client),extraEnv).trim();
        const result=JSON.parse(output);
        process.stdout.write(`[PASS] ${worker}/${result.client} default=${result.default} teamRoom=${result.teamRoom} tools=${result.toolCount} harness=${(result.teamHarness||[]).join(',')}\n`);
        last=undefined;
        break;
      }catch(error){last=String(error.stderr??error.message??'').trim().slice(0,500);await wait(2000);}
    }
    if(last!==undefined)throw new Error(`${worker}/${client} configuration failed: ${last}`);
  }
  process.stdout.write('[PASS] all execution Workers allow their bound stage MCP client only from the manager-free Team Room\n');
}
main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
