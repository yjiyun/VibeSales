import { Module } from '@nestjs/common';
import { MatchModule } from '../match/match.module'; import { P3Module } from '../p3/p3.module'; import { P3bModule } from '../p3b/p3b.module'; import { P3cModule } from '../p3c/p3c.module'; import { P4Module } from '../p4/p4.module'; import { PreviewModule } from '../preview/preview.module'; import { TenantModule } from '../tenant/tenant.module'; import { WizardModule } from '../wizard/wizard.module';
import { McpController } from './mcp.controller'; import { McpService } from './mcp.service';
@Module({ imports: [WizardModule,MatchModule,PreviewModule,TenantModule,P3Module,P3bModule,P3cModule,P4Module], controllers: [McpController], providers: [McpService], exports: [McpService] }) export class McpModule {}
