import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ArtifactStoreModule } from './artifacts/artifact-store.module';
import { BlueprintAdminModule } from './artifacts/blueprint-admin.module';
import { TraceModule } from './common/trace.module';
import { McpHealthController } from './mcp/health.controller';
import { McpModule } from './mcp/mcp.module';
import { OrchestrationModule } from './orchestration/orchestration.module';
import { PromptsModule } from './prompts/prompts.module';

/** Production AgentTeams tool plane: no DEMO WizardController or static UI. */
@Module({imports:[ConfigModule.forRoot({isGlobal:true,envFilePath:['.env','../.env']}),ArtifactStoreModule,BlueprintAdminModule,PromptsModule,McpModule,OrchestrationModule,TraceModule],controllers:[McpHealthController]})
export class AppMcpModule {}
