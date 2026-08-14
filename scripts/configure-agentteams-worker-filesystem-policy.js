#!/usr/bin/env node
'use strict';

const {execFileSync}=require('child_process');

const workers=[
  'wizard-intent',
  'template-match',
  'template-personalize',
  'flow-generate',
  'blueprint-compose',
  'flow-import-run',
  'persona-expert',
  'business-expert',
  'skill-expert',
  'tool-expert',
];
const leader='chatflows-leader';
const python=String.raw`
import json,pathlib,sys,urllib.request
from qwenpaw.governance import ResourceGovernor
from qwenpaw.governance.policy import GovernanceAction,GovernanceRule,ToolCallSpec

marker="AgentTeams execution Workers may not mutate task files or run shell code"
with urllib.request.urlopen("http://127.0.0.1:8088/api/agents/default",timeout=10) as response:
    workspace=pathlib.Path(json.load(response)["workspace_dir"]).resolve()
if workspace.name!="default" or ".qwenpaw/workspaces" not in workspace.as_posix():
    raise RuntimeError("unexpected QwenPaw default workspace")

governor=ResourceGovernor(str(workspace))
governor.start()
rules=[
    "Write(**)",
    "Edit(**)",
    "Append(**)",
    "DesktopScreenshot(**)",
    "Bash(**)",
    "RecallHistoryPython(**)",
]
for match in reversed(rules):
    governor.add_rule(GovernanceRule(match=match,action=GovernanceAction.DENY,reason=marker))

policy=governor.policy
saved=[rule.match for rule in policy.user_rules if rule.action is GovernanceAction.DENY and rule.reason==marker]
if saved[:len(rules)]!=rules:
    raise RuntimeError("AgentTeams deny rules were not persisted at highest priority")

task_result=workspace/"shared/tasks/task-00000000-0000-0000-0000-000000000000/result.md"
checks={
    "Write":str(task_result),
    "Edit":str(task_result),
    "Append":str(task_result),
    "DesktopScreenshot":str(task_result),
    "Bash":"printf forged > "+str(task_result),
    "RecallHistoryPython":"open("+repr(str(task_result))+", 'w').write('forged')",
}
for tool,target in checks.items():
    decision=governor.assert_policy(ToolCallSpec(tool,target,"default","agentteams-policy-check"))
    if decision.action is not GovernanceAction.DENY or decision.source!="user_rules" or decision.reason!=marker:
        raise RuntimeError(tool+" mutation path is not denied")
read=governor.assert_policy(ToolCallSpec("Read",str(task_result),"default","agentteams-policy-check"))
if read.action is not GovernanceAction.ALLOW:
    raise RuntimeError("read-only task artifact access must remain allowed")
print(json.dumps({"workspace":workspace.name,"denied":list(checks),"read":"allow"}))
`;
const leaderCheck=String.raw`
import json,pathlib,urllib.request,yaml
marker="AgentTeams execution Workers may not mutate task files or run shell code"
with urllib.request.urlopen("http://127.0.0.1:8088/api/agents/default",timeout=10) as response:
    workspace=pathlib.Path(json.load(response)["workspace_dir"]).resolve()
import hashlib
policy=workspace.parents[1]/"governance"/(workspace.name+"_"+hashlib.sha256(str(workspace).encode()).hexdigest()[:12])/"policy.yaml"
data=yaml.safe_load(policy.read_text()) if policy.exists() else {}
if any(rule.get("reason")==marker for rule in data.get("user_rules",[])):
    raise RuntimeError("execution-Worker filesystem deny rules leaked to Leader")
print(json.dumps({"leader":"write-boundary-retained"}))
`;

function run(container,source){
  return JSON.parse(execFileSync('docker',['exec',container,'/opt/venv/qwenpaw/bin/python','-c',source],{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim());
}

function main(){
  for(const worker of workers){
    const result=run('agentteams-worker-'+worker,python);
    process.stdout.write(`[PASS] ${worker} task mutation denied=${result.denied.join(',')} read=${result.read}\n`);
  }
  run('agentteams-worker-'+leader,leaderCheck);
  process.stdout.write('[PASS] all 10 execution Workers have a runtime-enforced immutable task workspace; Leader retains the final result writer boundary\n');
}

try{main();}catch(error){
  const detail=String(error.stderr??error.message??error).trim().slice(0,1000);
  process.stderr.write('[FAIL] execution Worker filesystem policy configuration failed: '+detail+'\n');
  process.exit(1);
}
