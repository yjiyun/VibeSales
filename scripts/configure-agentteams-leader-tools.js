#!/usr/bin/env node
'use strict';

const {execFileSync}=require('child_process');

const container=process.env.AGENTTEAMS_LEADER_CONTAINER?.trim()||'agentteams-worker-chatflows-leader';
const allowed=['health','message','filesync'];
const forbidden=['roomflow','projectflow','taskflow'];
const python=String.raw`
import json,sys,time,urllib.parse,urllib.request
base="http://127.0.0.1:8088/api/mcp/"
allowed=json.loads(sys.argv[1])
forbidden=set(json.loads(sys.argv[2]))
def call(method,path,data=None):
    body=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(base+path,data=body,headers={"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(req,timeout=10) as response:
        return json.load(response)
call("PUT","teamharness",{"tools":allowed})
configured=call("GET","teamharness")
if configured.get("tools")!=allowed:
    raise RuntimeError("TeamHarness tool allowlist readback mismatch")
names=[]
for _ in range(30):
    names=sorted(item.get("name") for item in call("GET","tools/teamharness") if item.get("enabled"))
    if names==sorted(allowed):
        break
    time.sleep(1)
if names!=sorted(allowed):
    raise RuntimeError("unexpected TeamHarness tools: "+",".join(names))
if forbidden.intersection(names):
    raise RuntimeError("forbidden TeamHarness tool remains discoverable")
print(json.dumps({"allowed":names,"forbiddenVisible":[]}))
`;

function main(){
  let last='';
  for(let attempt=0;attempt<60;attempt++){
    try{
      const output=execFileSync('docker',['exec',container,'python','-c',python,JSON.stringify(allowed),JSON.stringify(forbidden)],{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
      const result=JSON.parse(output);
      process.stdout.write(`[PASS] Leader TeamHarness hard allowlist=${result.allowed.join(',')} forbidden=not-discoverable\n`);
      return;
    }catch(error){last=String(error.stderr??error.message??'').trim().slice(0,500);Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,2000);}
  }
  throw new Error('Leader TeamHarness hardening failed: '+last);
}

try{main();}catch(error){process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);}
