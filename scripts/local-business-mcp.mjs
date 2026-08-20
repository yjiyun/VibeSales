#!/usr/bin/env node

import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';

const host = process.env.BUSINESS_MCP_HOST?.trim() || '127.0.0.1';
const port = Number(process.env.BUSINESS_MCP_PORT || 3200);
const token = process.env.BUSINESS_MCP_TOKEN?.trim();

if (!token || token.length < 16) {
  throw new Error('BUSINESS_MCP_TOKEN is required and must be at least 16 characters');
}

const tools = [
  {
    name: 'crm_query',
    description: '查询本地模拟 CRM 客户档案与订单摘要。',
    inputSchema: {
      type: 'object',
      additionalProperties: true,
      properties: {
        customer_id: { type: 'string' },
        phone: { type: 'string' },
        keyword: { type: 'string' },
      },
    },
  },
];

function authorized(headers) {
  const supplied = String(headers.authorization || '').replace(/^Bearer\s+/i, '');
  const left = Buffer.from(token);
  const right = Buffer.from(supplied);
  return left.length === right.length && timingSafeEqual(left, right);
}

function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(body));
}

function toolResult(args) {
  const customerId = String(args.customer_id || 'CUST-DEMO-001');
  const phone = String(args.phone || '13800000000');
  const keyword = String(args.keyword || '默认客户');
  return {
    customer_id: customerId,
    customer_name: keyword === '默认客户' ? '本地演示客户' : `${keyword}客户`,
    phone,
    level: 'A',
    tags: ['本地联调', '真模型闭环', '无需远端依赖'],
    latest_order: {
      order_id: 'ORDER-DEMO-1001',
      amount: 299,
      status: 'PAID',
      product: '体验装礼盒',
    },
    suggestions: [
      '先确认用户本次咨询诉求，再引用最近订单做个性化回复。',
      '如需推荐商品，优先使用最近一次已购品类的关联搭配。',
    ],
  };
}

const server = http.createServer((request, response) => {
  if (request.url === '/healthz' && request.method === 'GET') {
    return json(response, 200, { ok: true, service: 'local-business-mcp' });
  }

  const isMcpPath = request.url.includes('/mcp');
  if (!isMcpPath || request.method !== 'POST') {
    return json(response, 404, { error: 'not found' });
  }

  if (!authorized(request.headers)) {
    return json(response, 401, { error: 'unauthorized' });
  }

  let raw = '';
  request.on('data', chunk => { raw += chunk; });
  request.on('end', () => {
    let payload;
    try {
      payload = raw ? JSON.parse(raw) : {};
    } catch {
      return json(response, 400, { error: 'invalid json' });
    }

    const id = payload.id ?? null;
    if (payload.method === 'initialize') {
      return json(response, 200, {
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: '2025-03-26',
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'business-tools', version: '1.0.0-local' },
        },
      });
    }
    if (payload.method === 'notifications/initialized') {
      response.writeHead(204);
      response.end();
      return;
    }
    if (payload.method === 'tools/list') {
      return json(response, 200, { jsonrpc: '2.0', id, result: { tools } });
    }
    if (payload.method === 'tools/call') {
      const name = String(payload.params?.name || '');
      if (name !== 'crm_query') {
        return json(response, 200, { jsonrpc: '2.0', id, error: { code: -32601, message: `unknown tool: ${name}` } });
      }
      const value = toolResult(payload.params?.arguments || {});
      return json(response, 200, {
        jsonrpc: '2.0',
        id,
        result: {
          content: [{ type: 'text', text: JSON.stringify(value) }],
          structuredContent: value,
          isError: false,
        },
      });
    }
    return json(response, 200, { jsonrpc: '2.0', id, error: { code: -32601, message: 'method not found' } });
  });
});

server.listen(port, host, () => {
  process.stderr.write(`[local-business-mcp] listening on ${host}:${port}\n`);
});
