#!/usr/bin/env node
'use strict';

// 守护 Worker MCP client 的 active 状态，防止 qwenpaw 把 credential 漂回无效 token
// 导致跑 run 中途 502（见 docs/agentteams/测试用例/platform_bug.md §3.8/§3.10）。
//
// 为什么需要守护：fix-agentteams-worker-mcp-token.js 是一次性修复，但 qwenpaw 会
// 周期性/事件性重建 MCP driver 并从 credential store 重新加载，把有效 token 覆盖回
// 漂移值，client 变 status=inactive、GET tools 返回 502。一个 run 往往跨十几分钟、
// 多个阶段 Worker，前几棒在 active 窗口内跑通、后面某一棒撞上漂移就 RUN_BLOCKED。
//
// 关键设计：**只在探测到 inactive/502 时才 PUT**。PUT 会重建 client 并连带重置该
// Worker 的 agentloop（正在进行的任务会丢），所以绝不能无条件周期性 PUT——那会打断
// 正常工作。健康时只读探测，零副作用。
//
// 用法：
//   set -a; source docs/agentteams/local-development.env.local; set +a
//   AGENTTEAMS_DOCKER_SSH=pad@10.0.0.1 node scripts/watch-agentteams-worker-mcp-token.js
// 可选 env：
//   WATCH_INTERVAL_MS  探测间隔，默认 20000
//   WATCH_ONCE=1       只巡检一次就退出（适合 run 前的最后一道校验）
//
// 依赖 env：HIGRESS_CONSUMER_TOKEN、CHATFLOWS_MCP_BASE_URL、AGENTTEAMS_DOCKER_SSH（可选，
// 无 SSH key 时配合 SSHPASS）。与 configure/fix 两个脚本同一套。

const { execFileSync } = require('child_process');

const assignments = [
  ['wizard-intent', 'chatflows-p1'],
  ['template-match', 'chatflows-p2'],
  ['template-personalize', 'chatflows-p3'],
  ['flow-generate', 'chatflows-p3b'],
  ['blueprint-compose', 'chatflows-p3c'],
  ['flow-import-run', 'chatflows-p4'],
  ['persona-expert', 'chatflows-p3c'],
  ['business-expert', 'chatflows-p3c'],
  ['skill-expert', 'chatflows-p3c'],
  ['tool-expert', 'chatflows-p3c'],
];

const gateway = (process.env.CHATFLOWS_MCP_BASE_URL || '').trim().replace(/\/$/, '');
const token = (process.env.HIGRESS_CONSUMER_TOKEN || '').trim();
if (!gateway) throw new Error('CHATFLOWS_MCP_BASE_URL is required');
if (!token) throw new Error('HIGRESS_CONSUMER_TOKEN is required');
const intervalMs = Number(process.env.WATCH_INTERVAL_MS ?? 20000);
if (!Number.isFinite(intervalMs) || intervalMs < 5000) throw new Error('WATCH_INTERVAL_MS must be >= 5000');
const once = process.env.WATCH_ONCE === '1';

// 只读探测：返回工具数，或 null 表示不健康。绝不写。
const probeScript = String.raw`
import json,os,urllib.request
client=os.environ["CLIENT"]
try:
    r=urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088/api/mcp/tools/"+client,method="GET"),timeout=12)
    tools=json.load(r)
    print(json.dumps({"ok":True,"tools":len(tools) if isinstance(tools,list) else 0}))
except Exception as error:
    print(json.dumps({"ok":False,"error":str(error)[:120]}))
`;

// 修复：明文 Bearer 直接 PUT（绕开 restore_masked_value 掩码判定），再轮询到 active。
const repairScript = String.raw`
import json,os,time,urllib.request
base="http://127.0.0.1:8088/api/mcp/"
client=os.environ["CLIENT"]
gw=os.environ["GW"].rstrip("/")
tok=os.environ["HCT"]
def call(method,path,data=None):
    body=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(base+path,data=body,headers={"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(req,timeout=20) as r:
        return r.status,r.read().decode()
call("PUT",client,{"enabled":True,"transport":"streamable_http","url":gw+"/mcp-servers/"+client,"headers":{"Authorization":"Bearer "+tok}})
count=None
for _ in range(20):
    try:
        st,b=call("GET","tools/"+client)
        tools=json.loads(b)
        if isinstance(tools,list) and tools:
            count=len(tools);break
    except Exception:
        pass
    time.sleep(1)
print(json.dumps({"repaired":count is not None,"tools":count}))
`;

function dockerPython(container, script, extraEnv) {
  const ssh = process.env.AGENTTEAMS_DOCKER_SSH?.trim();
  const envArgs = Object.entries(extraEnv || {}).flatMap(([key, value]) => {
    if (!/^[A-Z0-9_]+$/.test(key)) throw new Error('invalid env name');
    return ['-e', key + '=' + value];
  });
  const opts = { input: script, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] };
  const execArgs = ['exec', '-i', ...envArgs, container, 'python3', '-'];
  if (!ssh) return execFileSync('docker', execArgs, opts);
  const sshArgs = ['-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=15'];
  if (process.env.SSHPASS) {
    sshArgs.push('-o', 'PreferredAuthentications=password', '-o', 'PubkeyAuthentication=no');
    return execFileSync('sshpass', ['-e', 'ssh', ...sshArgs, ssh, 'docker', ...execArgs], opts);
  }
  return execFileSync('ssh', ['-o', 'BatchMode=yes', ...sshArgs, ssh, 'docker', ...execArgs], opts);
}

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const stamp = () => new Date().toISOString().slice(11, 19);

function runJson(container, script, extraEnv) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try { return JSON.parse(dockerPython(container, script, extraEnv).trim()); }
    catch { /* SSH 偶发失败，重试 */ }
  }
  return null;
}

async function sweep() {
  let unhealthy = 0, repaired = 0;
  for (const [worker, client] of assignments) {
    const container = 'agentteams-worker-' + worker;
    const probe = runJson(container, probeScript, { CLIENT: client });
    if (probe?.ok) continue;
    unhealthy += 1;
    process.stdout.write(`[${stamp()}] UNHEALTHY ${worker}/${client}: ${probe?.error ?? 'probe failed'} — repairing\n`);
    const fix = runJson(container, repairScript, { CLIENT: client, GW: gateway, HCT: token });
    if (fix?.repaired) { repaired += 1; process.stdout.write(`[${stamp()}] REPAIRED ${worker}/${client} tools=${fix.tools}\n`); }
    else process.stdout.write(`[${stamp()}] REPAIR-FAILED ${worker}/${client}\n`);
  }
  if (unhealthy === 0) process.stdout.write(`[${stamp()}] all ${assignments.length} stage MCP clients healthy\n`);
  return { unhealthy, repaired };
}

async function main() {
  process.stdout.write(`[watch] interval=${intervalMs}ms once=${once} clients=${assignments.length}\n`);
  process.stdout.write('[watch] 只在 inactive/502 时才 PUT 修复（PUT 会重置该 Worker 的 agentloop）\n');
  for (;;) {
    await sweep();
    if (once) return;
    await wait(intervalMs);
  }
}

main().catch(error => { process.stderr.write('[FAIL] ' + (error instanceof Error ? error.message : String(error)) + '\n'); process.exit(1); });
