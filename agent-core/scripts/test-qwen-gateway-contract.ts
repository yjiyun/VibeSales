/**
 * Qwen 生产网关合同（v4 统一口径）
 *
 * v4 只认 `QWEN_GATEWAY_TOKEN`：`QWEN_MODE` / `HIGRESS_CONSUMER_TOKEN` 已废弃。
 * 生产校验由 `ARTIFACT_STORE=postgres` 触发（见 `QwenService` 构造器），
 * 校验逻辑本体是主线导出的 `validateQwenProductionGateway`。
 */

import { ConfigService } from '@nestjs/config';
import { QwenService, validateQwenProductionGateway } from '../src/qwen/qwen.service';

function rejects(baseUrl: string, gatewayToken: string, dashscopeKey: string, fragment: string): void {
  try {
    validateQwenProductionGateway(baseUrl, gatewayToken, dashscopeKey);
  } catch (error) {
    if (error instanceof Error && error.message.includes(fragment)) return;
    throw error;
  }
  throw new Error(`expected rejection containing ${fragment}`);
}

function accepts(baseUrl: string, gatewayToken: string): void {
  validateQwenProductionGateway(baseUrl, gatewayToken, '');
}

function construct(): QwenService {
  return new QwenService(new ConfigService(), {} as any, {} as any);
}

function main() {
  const token = 'consumer-token-0123456789';

  // 1. 缺少 QWEN_GATEWAY_TOKEN（或过短）必须拒绝。
  rejects('https://model.higress.example/v1', '', '', 'QWEN_GATEWAY_TOKEN');
  rejects('https://model.higress.example/v1', 'short', '', 'QWEN_GATEWAY_TOKEN');

  // 2. 生产 Nest 不得收到真实 DashScope Key。
  rejects('https://model.higress.example/v1', token, 'must-not-be-read', 'DASHSCOPE_API_KEY');

  // 3. 公网直连 DashScope 不是 Higress 路由。
  rejects('https://dashscope.aliyuncs.com/compatible-mode/v1', token, '', 'Higress');

  // 4. 非 OpenAI 兼容路径必须拒绝。
  rejects('https://model.higress.example/anything', token, '', 'Higress');

  // 5. 生产 Higress HTTPS 通过；容器私网 HTTP 也通过（A21：compose 内网关不判死）。
  accepts('https://model.higress.example/v1', token);
  accepts('http://agentteams-higress:8080/v1', token);
  accepts('http://10.0.0.1:8080/v1', token);

  // 6. 运行时凭证变量名：QWEN_GATEWAY_TOKEN 优先，本地开发仍可用 DASHSCOPE_API_KEY。
  for (const key of ['QWEN_MODE', 'HIGRESS_CONSUMER_TOKEN', 'DASHSCOPE_API_KEY', 'QWEN_GATEWAY_TOKEN', 'QWEN_BASE_URL']) delete process.env[key];
  process.env.ARTIFACT_STORE = 'file';
  process.env.QWEN_GATEWAY_TOKEN = token;
  if (!construct().hasApiKey()) throw new Error('QWEN_GATEWAY_TOKEN was not recognized');
  delete process.env.QWEN_GATEWAY_TOKEN;
  process.env.DASHSCOPE_API_KEY = 'local-direct-key';
  if (!construct().hasApiKey()) throw new Error('local direct-development mode was disabled');

  // 7. 已废弃变量不得再被识别为凭证。
  delete process.env.DASHSCOPE_API_KEY;
  process.env.QWEN_MODE = 'production';
  process.env.HIGRESS_CONSUMER_TOKEN = token;
  if (construct().hasApiKey()) throw new Error('deprecated HIGRESS_CONSUMER_TOKEN is still read as a credential');
  delete process.env.QWEN_MODE;
  delete process.env.HIGRESS_CONSUMER_TOKEN;

  process.stdout.write('[PASS] production Qwen requires QWEN_GATEWAY_TOKEN on a Higress OpenAI route; QWEN_MODE/HIGRESS_CONSUMER_TOKEN are dead\n');
}
main();
