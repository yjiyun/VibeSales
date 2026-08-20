#!/usr/bin/env node
'use strict';

// 根治 Worker MCP client "OAuth 401 → status=inactive → GET tools 502" 的持久修复。
//
// 背景（见 docs/agentteams/测试用例/platform_bug.md §3.8）：
// qwenpaw MCP driver 的 token 不内联在 driver card，而是引用 credential store
// (credentials.yaml 的 mcp/<client>/secrets/authorization，加密存储)。
// configure-agentteams-worker-mcp.js 用 PUT /api/mcp/<client> 带 headers.Authorization
// 想更新它，但 qwenpaw 的 build_mcp_credential_record 会对每个 header value 走
// restore_masked_value(new, old)：一旦新值被 classify 判成 masked/掩码，就丢弃新值、
// 保留旧 credential。于是 driver 一直用旧的、Higress 不认的 token 去连 → OAuth 401
// → client 无法 active（status=inactive）→ GET tools 返回 502 → Worker 以为 MCP 不可用
// 转而本地 write_file + taskflow 幻觉完成，产物写不进 Nest，向导空等 WAITING_HUMAN。
//
// 修法：用**明文** "Bearer <HIGRESS_CONSUMER_TOKEN>" 直接 PUT，绕开掩码判定，credential
// 被真正改写为有效 token，driver 重连成功并稳定 active。configure 脚本负责 policy/harness，
// 本脚本负责把每个 stage client 的 credential 钉到有效 token。容器重建或 qwenpaw 把
// credential 漂回后重跑本脚本。
//
// ⚠️ 顺序（血泪踩坑）：本脚本必须放在**清会话（reset-agentteams-worker-sessions.js）之后**。
// 清会话发 /new 会重建 agentloop → driver 从 credential store 重新加载 → 覆盖本脚本刚写的
// 有效 token，client 重新变 inactive/502。正确顺序：改技能 → mc cp MinIO → 重启 Worker →
// configure-worker-mcp + configure-leader-tools → **停 watch 守护** → reset-worker-sessions →
// 本脚本 → 重启 watch 守护。
// 若先跑本脚本再清会话，会出现「某一棒恰好在 active 窗口跑通、下一棒撞上被覆盖的 token 报
// RUN_BLOCKED」的假性成功。跑 run 前再补跑一次本脚本最稳。
// 另外：清会话期间必须停掉 watch-agentteams-worker-mcp-token.js —— 它修 token 的 PUT 会重置
// agentloop，撞上 /new 确认窗口会让该 Worker 确认超时（清会话直接 FAIL）。
//
// 依赖 env：HIGRESS_CONSUMER_TOKEN、CHATFLOWS_MCP_BASE_URL、AGENTTEAMS_DOCKER_SSH（可选，
// 无 SSH key 时配合 SSHPASS）。与 configure-agentteams-worker-mcp.js 同一套。

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

// 明文 PUT + 探测 tools active。token 经 env 注入容器，不落进脚本文本。
const python = String.raw`
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
if count is None:
    raise RuntimeError("MCP client "+client+" did not become active after plaintext credential PUT")
print(json.dumps({"client":client,"tools":count}))
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
  const sshArgs = ['-o', 'StrictHostKeyChecking=accept-new'];
  if (process.env.SSHPASS) {
    sshArgs.push('-o', 'PreferredAuthentications=password', '-o', 'PubkeyAuthentication=no');
    return execFileSync('sshpass', ['-e', 'ssh', ...sshArgs, ssh, 'docker', ...execArgs], opts);
  }
  return execFileSync('ssh', ['-o', 'BatchMode=yes', ...sshArgs, ssh, 'docker', ...execArgs], opts);
}

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  for (const [worker, client] of assignments) {
    const container = 'agentteams-worker-' + worker;
    let last;
    for (let attempt = 0; attempt < 30; attempt++) {
      try {
        const output = dockerPython(container, python, { CLIENT: client, GW: gateway, HCT: token }).trim();
        const result = JSON.parse(output);
        process.stdout.write(`[PASS] ${worker}/${result.client} active tools=${result.tools}\n`);
        last = undefined;
        break;
      } catch (error) {
        last = String(error.stderr ?? error.message ?? '').trim().slice(0, 300);
        await wait(2000);
      }
    }
    if (last !== undefined) throw new Error(`${worker}/${client} token fix failed: ${last}`);
  }
  process.stdout.write('[PASS] all stage MCP clients pinned to a valid Higress token and active\n');
}

main().catch(error => { process.stderr.write('[FAIL] ' + (error instanceof Error ? error.message : String(error)) + '\n'); process.exit(1); });
