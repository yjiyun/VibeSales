import { Body, Controller, Headers, HttpException, Param, Post } from '@nestjs/common';
import { McpService } from './mcp.service';
import { TraceService } from '../common/trace.service';
import { randomUUID, timingSafeEqual } from 'crypto';

interface RpcRequest { jsonrpc?: string; id?: string | number | null; method?: string; params?: { name?: string; arguments?: Record<string, unknown> } }

const MCP_AGENT:Record<string,string>={
  'chatflows-p1':'wizard-intent','chatflows-p2':'template-match','chatflows-p3':'template-personalize',
  'chatflows-p3b':'flow-generate','chatflows-p3c':'blueprint-compose','chatflows-p4':'flow-import-run',
};
export function agentForMcpServer(server:string):string {const agent=MCP_AGENT[server];if(!agent)throw new Error('unknown MCP server: '+server);return agent;}

@Controller('mcp-servers')
export class McpController {
  constructor(private readonly mcp: McpService, private readonly trace: TraceService) {}

  @Post(':server/mcp')
  async rpc(@Param('server') server: string, @Body() req: RpcRequest, @Headers() headers: Record<string,string|undefined> = {}) {
    this.authorize(headers);
    const id = req.id ?? null;
    try {
      if (req.method === 'initialize') return { jsonrpc: '2.0', id, result: { protocolVersion: '2025-03-26', capabilities: { tools: { listChanged: false } }, serverInfo: { name: server, version: '1.0.0' } } };
      if (req.method === 'notifications/initialized') return undefined;
      if (req.method === 'tools/list') return { jsonrpc: '2.0', id, result: { tools: this.mcp.list(server) } };
      if (req.method === 'tools/call') {
        const args = req.params?.arguments ?? {};
        const ctx = args._ctx as Record<string, unknown> | undefined;
        const runId = String(ctx?.run_id ?? '').trim();
        if (!runId) throw new Error('_ctx.run_id is required');
        const clientCode = String(ctx?.client_code ?? '').trim();
        if (!clientCode) throw new Error('_ctx.client_code is required');
        if (args.runId !== undefined && String(args.runId) !== runId) throw new Error('runId must match _ctx.run_id');
        if (args.clientCode !== undefined && String(args.clientCode) !== clientCode) throw new Error('clientCode must match _ctx.client_code');
        const traceparent = String(ctx?.traceparent ?? '').trim();
        const requestId = randomUUID();
        const callArgs = { ...args, _ctx: { ...ctx, request_id: requestId } };
        const tool = String(req.params?.name ?? '');
        const phase = server.replace('chatflows-p','P').toUpperCase(),agent=agentForMcpServer(server);
        const inputApproval=args.approval as Record<string,unknown>|undefined;
        this.trace.setFlow('mcp.' + server, requestId);
        this.trace.step('MCP', 'tool.call', {
          run_id: runId, client_code: clientCode, request_id: requestId,
          traceparent: traceparent || undefined, agent,
          server, tool, phase, approval_id:inputApproval?.approval_id,
          approval_state:inputApproval?.decision==='DENY'?'denied':inputApproval?.decision==='APPROVE'?'approved':undefined,
        });
        const value = await this.mcp.call(server, tool, callArgs);
        const output=value&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
        const approvalId=String(output.approval_id??inputApproval?.approval_id??'')||undefined;
        const approvalState=output.status==='pending_approval'?'pending_approval':inputApproval?.decision==='DENY'?'denied':inputApproval?.decision==='APPROVE'?'approved':undefined;
        this.trace.step('MCP','tool.result',{run_id:runId,client_code:clientCode,request_id:requestId,traceparent:traceparent||undefined,agent,server,tool,phase,approval_id:approvalId,approval_state:approvalState});
        // MCP 规范要求 structuredContent 是对象；直接塞数组（listSkillCandidates /
        // listToolCandidates 这类返回数组的工具）会让客户端校验 CallToolResult 失败
        // （pydantic dict_type），Worker 只看到「driver 端序列化 bug」而拿不到候选。
        // 数组包成 { items }，标量包成 { value }，对象原样透传。
        const structured = Array.isArray(value) ? { items: value }
          : value !== null && typeof value === 'object' ? value as Record<string, unknown>
          : { value };
        return { jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }], structuredContent: structured, isError: false, _meta: { request_id:requestId, run_id:runId, traceparent:traceparent||undefined, approval_id:approvalId, approval_state:approvalState } } };
      }
      return { jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found' } };
    } catch (error) {
      return { jsonrpc: '2.0', id, error: { code: -32000, message: error instanceof Error ? error.message : String(error) } };
    }
  }

  private authorize(headers:Record<string,string|undefined>) {
    const expected=process.env.MCP_SERVER_TOKEN?.trim();if(!expected)throw new HttpException('MCP endpoint disabled',503);
    const supplied=(headers.authorization??'').replace(/^Bearer\s+/i,'');const a=Buffer.from(expected),b=Buffer.from(supplied);
    if(a.length!==b.length||!timingSafeEqual(a,b))throw new HttpException('unauthorized',401);
  }
}
