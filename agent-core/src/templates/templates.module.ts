/**
 * TemplatesModule — 模板子系统 Nest 模块
 *
 * 聚合：
 * - TemplateLoaderService  扫盘 + catalogs 校验
 * - TemplateFilterService  硬过滤
 * - TemplateRankService    规则排序 Top-K
 *
 * 依赖 CatalogsModule（词表须先于 Loader 可用）。
 * 由 MatchModule / CLI 注入使用；匹配流水线见 MatchService。
 */

import { Module } from '@nestjs/common';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { TemplateFilterService } from './template-filter.service';
import { TemplateLoaderService } from './template-loader.service';
import { TemplateRankService } from './template-rank.service';

@Module({
  imports: [CatalogsModule],
  providers: [
    TemplateLoaderService,
    TemplateFilterService,
    TemplateRankService,
  ],
  exports: [
    TemplateLoaderService,
    TemplateFilterService,
    TemplateRankService,
  ],
})
export class TemplatesModule {}
