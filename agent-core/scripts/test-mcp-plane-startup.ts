import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppMcpModule } from '../src/app-mcp.module';
import { McpController } from '../src/mcp/mcp.controller';

async function main() {
  process.env.ARTIFACT_STORE ??= 'file';
  process.env.MCP_SERVER_TOKEN ??= 'mcp-plane-startup-test-token';
  const app = await NestFactory.createApplicationContext(AppMcpModule, { logger: false });
  try {
    if (!app.get(McpController)) throw new Error('McpController not available');
  } finally {
    await app.close();
  }
  process.stdout.write('MCP plane startup: PASS\n');
}

main().then(() => process.exit(0)).catch(error => {
  console.error(error);
  process.exit(1);
});
