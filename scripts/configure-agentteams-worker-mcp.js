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
const python=String.raw`
import json,sys,time,urllib.parse,urllib.request
client=sys.argv[1]
base="http://127.0.0.1:8088/api/mcp/"
path=urllib.parse.quote(client,safe="")
def call(method,url,data=None):
    body=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(url,data=body,headers={"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(req,timeout=10) as response:
        return json.load(response)
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
print(json.dumps({"client":client,"default":saved["default_effect"],"teamRoom":"allow","toolCount":len(tools)}))
`;

async function main(){
  for(const [worker,client] of assignments){
    const container='agentteams-worker-'+worker;
    let last;
    for(let attempt=0;attempt<60;attempt++){
      try{
        const output=execFileSync('docker',['exec',container,'python','-c',python,client],{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
        const result=JSON.parse(output);
        process.stdout.write(`[PASS] ${worker}/${result.client} default=${result.default} teamRoom=${result.teamRoom} tools=${result.toolCount}\n`);
        last=undefined;
        break;
      }catch(error){last=String(error.stderr??error.message??'').trim().slice(0,500);await wait(2000);}
    }
    if(last!==undefined)throw new Error(`${worker}/${client} configuration failed: ${last}`);
  }
  process.stdout.write('[PASS] all execution Workers allow their bound stage MCP client only from the manager-free Team Room\n');
}
main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
