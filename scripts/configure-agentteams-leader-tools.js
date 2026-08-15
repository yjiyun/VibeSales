#!/usr/bin/env node
'use strict';

const {execFileSync}=require('child_process');

const container=process.env.AGENTTEAMS_LEADER_CONTAINER?.trim()||'agentteams-worker-chatflows-leader';
const allowed=['health','message','filesync'];
const forbidden=['roomflow','projectflow','taskflow','artifact'];
const python=`
import json,time,urllib.request
base="http://127.0.0.1:8088/api/mcp/"
allowed=${JSON.stringify(allowed)}
forbidden=set(${JSON.stringify(forbidden)})
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

function dockerPython(script){
  const ssh=process.env.AGENTTEAMS_DOCKER_SSH?.trim();
  const opts={input:script,encoding:'utf8',stdio:['pipe','pipe','pipe']};
  const execArgs=['exec','-i',container,'python','-'];
  if(!ssh)return execFileSync('docker',execArgs,opts);
  const sshArgs=['-o','StrictHostKeyChecking=accept-new'];
  if(process.env.SSHPASS){
    sshArgs.push('-o','PreferredAuthentications=password','-o','PubkeyAuthentication=no');
    return execFileSync('sshpass',['-e','ssh',...sshArgs,ssh,'docker',...execArgs],opts);
  }
  return execFileSync('ssh',['-o','BatchMode=yes',...sshArgs,ssh,'docker',...execArgs],opts);
}

function main(){
  let last='';
  for(let attempt=0;attempt<60;attempt++){
    try{
      const output=dockerPython(python).trim();
      const result=JSON.parse(output);
      process.stdout.write(`[PASS] Leader TeamHarness hard allowlist=${result.allowed.join(',')} forbidden=not-discoverable\n`);
      return;
    }catch(error){last=String(error.stderr??error.message??'').trim().slice(0,500);Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,2000);}
  }
  throw new Error('Leader TeamHarness hardening failed: '+last);
}

try{main();}catch(error){process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);}
