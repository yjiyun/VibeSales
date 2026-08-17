/**
 * PreviewModule — 导出 PreviewService（v0 预览渲染，v2 M3）
 *
 * 依赖 TemplatesModule（取命中模板的 BRIEF/params）与 CatalogsModule
 * （capability id → name）。被 CLI 在 action=hit 后调用。
 */

import { Module } from '@nestjs/common';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { TemplatesModule } from '../templates/templates.module';
import { PreviewService } from './preview.service';

@Module({
  imports: [TemplatesModule, CatalogsModule],
  providers: [PreviewService],
  exports: [PreviewService],
})
export class PreviewModule {}
