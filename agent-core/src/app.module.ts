/**
 * [根模块] AppModule — DEMO CLI 组装
 *
 * 阶段命令：
 * - P1：`p1`（fixtures 评估）、`p1-wizard` / `wizard`（交互粗补）
 * - P2：`match` / `p2-match`（模板匹配；可先跑 P1 Intent）
 *
 * ```text
 * P1: Tenant → Wizard / Intent → Phase1Result
 * P2: Tenant → (可选 P1 Intent) → Match → Filter → Rank → Decide → (+ v0)
 * ```
 */

import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { CatalogsModule } from './catalogs/catalogs.module';
import { P1Command } from './cli/p1.command';
import { P2MatchCommand } from './cli/p2-match.command';
import { TraceModule } from './common/trace.module';
import { IntentModule } from './intent/intent.module';
import { MatchModule } from './match/match.module';
import { PreviewModule } from './preview/preview.module';
import { PromptsModule } from './prompts/prompts.module';
import { QwenModule } from './qwen/qwen.module';
import { TenantModule } from './tenant/tenant.module';
import { TemplatesModule } from './templates/templates.module';
import { WizardModule } from './wizard/wizard.module';
import { P1WizardCommand } from './wizard/p1-wizard.command';
import { ArtifactStoreModule } from './artifacts/artifact-store.module';
import { OrchestrationModule } from './orchestration/orchestration.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: ['.env', '../.env'],
    }),
    ArtifactStoreModule,
    TraceModule,
    PromptsModule,
    CatalogsModule,
    TenantModule,
    TemplatesModule,
    QwenModule,
    WizardModule,
    IntentModule,
    MatchModule,
    PreviewModule,
    OrchestrationModule,
  ],
  providers: [P1Command, P1WizardCommand, P2MatchCommand],
})
export class AppModule {}
