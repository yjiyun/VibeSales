/**
 * [根模块] AppWebModule — DEMO Web 组装
 *
 * 与 {@link AppModule}（CLI）平级的第二个入口：只挂 HTTP 层，不注册
 * nest-commander 命令，避免 ServeStatic 影响 CLI 启动。
 *
 * ```text
 * HTTP → WebModule(Controller/Session) → P1 Wizard → (可选) P2 Match/Preview
 * ```
 *
 * 可观测四件套仍来自 TraceModule（@Global），所以一次 Web 请求内部的打点
 * 与 CLI 完全同源：stderr / logs/app.log / 回包 runtime.events。
 */

import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { TraceModule } from './common/trace.module';
import { PromptsModule } from './prompts/prompts.module';
import { WebModule } from './web/web.module';
import { ArtifactStoreModule } from './artifacts/artifact-store.module';
import { OrchestrationModule } from './orchestration/orchestration.module';
import { McpModule } from './mcp/mcp.module';
import { McpHealthController } from './mcp/health.controller';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: ['.env', '../.env'],
    }),
    ArtifactStoreModule,
    OrchestrationModule,
    McpModule,
    TraceModule,
    PromptsModule,
    WebModule,
  ],
  controllers: [McpHealthController],
})
export class AppWebModule {}
