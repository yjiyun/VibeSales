import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppMcpModule } from '../src/app-mcp.module';
import { BlueprintAdminController } from '../src/artifacts/blueprint-admin.controller';
import { McpController } from '../src/mcp/mcp.controller';
import { McpHealthController } from '../src/mcp/health.controller';
import { PipelineController } from '../src/orchestration/pipeline.controller';
import { WizardController } from '../src/web/wizard.controller';
import { validateQwenProductionGateway } from '../src/qwen/qwen.service';

async function main() {
  validateQwenProductionGateway('http://agentteams-higress:8080/compatible-mode/v1','consumer-token-at-least-16','');
  validateQwenProductionGateway('http://agentteams-controller:8080/v1','consumer-token-at-least-16','');
  for(const input of [['https://dashscope.aliyuncs.com/compatible-mode/v1','consumer-token-at-least-16',''],['http://agentteams-higress:8080/compatible-mode/v1','',''],['http://agentteams-higress:8080/compatible-mode/v1','consumer-token-at-least-16','true-key'],['http://agentteams-higress:8080/api/v1','consumer-token-at-least-16','']] as const){let rejected=false;try{validateQwenProductionGateway(input[0],input[1],input[2]);}catch{rejected=true;}if(!rejected)throw new Error('unsafe production model gateway accepted: '+input[0]);}
  process.env.ARTIFACT_STORE = 'file';
  const app = await NestFactory.createApplicationContext(AppMcpModule, {
    logger: false,
    abortOnError: false,
  });
  for (const provider of [McpController, McpHealthController, PipelineController, BlueprintAdminController]) {
    if (!app.get(provider, { strict: false })) throw new Error(provider.name + ' missing from production module');
  }
  let wizardLoaded = true;
  try { app.get(WizardController, { strict: false }); } catch { wizardLoaded = false; }
  if (wizardLoaded) throw new Error('unauthenticated WizardController loaded in production MCP plane');
  const health = await app.get(McpHealthController, { strict: false }).health() as any;
  if (!health.ok || health.service !== 'chatflows-mcp') throw new Error('production health contract failed');
  await app.close();
  process.stdout.write('[PASS] production MCP module excludes DEMO Wizard and exposes authenticated tool/control/admin planes\n');
}
main().then(() => process.exit(0)).catch(error => {
  process.stderr.write((error instanceof Error ? error.stack : String(error)) + '\n');
  process.exit(1);
});
