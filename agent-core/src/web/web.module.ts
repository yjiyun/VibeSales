/**
 * [Web] WebModule — HTTP 层装配
 *
 * 文档：`docs/工程架构.md` §13 Web 层
 *
 * - Controller：{@link WizardController}（`/api/**`）
 * - 会话状态机：{@link WizardSessionService}（内存态，重启即清）
 * - 静态资源：根仓 `agent-console/dist`（仅本地开发退路）
 *   （`/api/**` 排除在静态托管之外；产物不存在时 `/` 返回 404，
 *    此时用 Vite dev server + proxy 联调即可）
 *
 * Web 层不引入任何新业务逻辑：向导推进复用 P1（WizardModule），
 * 预览复用 P2（MatchModule / PreviewModule）。
 */

import { Module } from '@nestjs/common';
import { ServeStaticModule } from '@nestjs/serve-static';
import { join, resolve } from 'path';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { MatchModule } from '../match/match.module';
import { PreviewModule } from '../preview/preview.module';
import { QwenModule } from '../qwen/qwen.module';
import { TenantModule } from '../tenant/tenant.module';
import { WizardModule } from '../wizard/wizard.module';
import { WizardController } from './wizard.controller';
import { WizardSessionService } from './wizard-session.service';
import { WebAuthService } from './web-auth.service';

/** 默认指向根仓 agent-console/dist；容器可用 WEB_STATIC_ROOT 指向空退路目录。 */
export const WEB_STATIC_ROOT = process.env.WEB_STATIC_ROOT ? resolve(process.env.WEB_STATIC_ROOT) : join(__dirname, '..', '..', '..', 'agent-console', 'dist');

@Module({
  imports: [
    ServeStaticModule.forRoot({
      rootPath: WEB_STATIC_ROOT,
      exclude: ['/api*', '/mcp-servers*', '/healthz'],
    }),
    TenantModule,
    CatalogsModule,
    WizardModule,
    MatchModule,
    PreviewModule,
    QwenModule,
  ],
  controllers: [WizardController],
  providers: [WizardSessionService, WebAuthService],
})
export class WebModule {}
