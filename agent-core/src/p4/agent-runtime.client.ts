import { Injectable } from '@nestjs/common';
import { AgentBlueprint } from '../common/types';
import { runtimeSafeId } from '../common/runtime-id';

/** P4 → agent-runtime 冒烟边界。没有真实 runtime 地址/令牌时不得伪造成功。 */
@Injectable()
export class AgentRuntimeClient {
  async dryRun(blueprint: AgentBlueprint, userId: string): Promise<{ok:boolean;response?:string}> {
    const base = process.env.AGENT_RUNTIME_URL?.trim();
    // agent-runtime 自己用 RUNTIME_AUTH_TOKEN 这个名字签发/校验，部署 env 里也只有它；
    // 这里原先只读 AGENT_RUNTIME_TOKEN，名字对不上导致 dry-run 在混合栈里从来跑不通
    // （报 "AGENT_RUNTIME_URL/AGENT_RUNTIME_TOKEN required"，看起来像没配 URL）。
    const token = process.env.AGENT_RUNTIME_TOKEN?.trim() || process.env.RUNTIME_AUTH_TOKEN?.trim();
    if (!base || !token) throw new Error('AGENT_RUNTIME_URL and AGENT_RUNTIME_TOKEN (or RUNTIME_AUTH_TOKEN) are required for P3C dry-run');
    const safeUser = runtimeSafeId(userId);
    const url = new URL('/api/v1/dryrun?clientCode=' + encodeURIComponent(blueprint.clientCode) + '&userId=' + encodeURIComponent(safeUser) + '&runtimeAgentId=' + encodeURIComponent(blueprint.runtimeAgentId), base);
    const response = await fetch(url, { method:'POST', headers:{ authorization:'Bearer '+token, 'content-type':'application/json' }, body:JSON.stringify(blueprint) });
    const body = await response.text();
    if (!response.ok) {
      if (/401/.test(body)) {
        throw new Error(
          '真模型网关 401：RUNTIME_LLM_TOKEN 未被 Higress /v1/chat/completions 放行（MCP 工牌不能当模型凭证）。' +
            body.slice(0, 180),
        );
      }
      throw new Error('agent-runtime dry-run failed: HTTP ' + response.status + ' ' + body.slice(0, 300));
    }
    const parsed = JSON.parse(body) as {ok?:boolean;response?:string};
    if (parsed.ok !== true) throw new Error('agent-runtime dry-run returned not-ok');
    return { ok:true, response:parsed.response };
  }

  /**
   * Local runtime：把本次 Blueprint stage+publish 进内存仓，产物对话才能投影。
   * 未配 URL / admin token 时静默跳过。production runtime 没有 /ingest（走 PG 投影），
   * 对该路径的 404 同样跳过，不能把确认发布打成 500。
   */
  async ingest(blueprint: AgentBlueprint, actor: string): Promise<{blueprintId:string;status:string}|null> {
    const base = process.env.AGENT_RUNTIME_URL?.trim();
    const adminToken = process.env.AGENT_RUNTIME_ADMIN_TOKEN?.trim() || process.env.RUNTIME_ADMIN_TOKEN?.trim();
    if (!base || !adminToken) return null;
    const url = new URL('/api/v1/ingest', base);
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        authorization: 'Bearer ' + adminToken,
        'content-type': 'application/json',
        'x-role': 'admin',
        'x-actor': runtimeSafeId(actor) || 'p4-import',
      },
      body: JSON.stringify(blueprint),
    });
    const body = await response.text();
    if (response.status === 404) return null;
    if (!response.ok) throw new Error('agent-runtime ingest failed: HTTP ' + response.status + ' ' + body.slice(0,300));
    const parsed = JSON.parse(body) as {blueprintId?:string;status?:string};
    if (!parsed.blueprintId || parsed.status !== 'PUBLISHED') {
      throw new Error('agent-runtime ingest returned unexpected body');
    }
    return { blueprintId: parsed.blueprintId, status: parsed.status };
  }
}
