/**
 * MatchModule — 匹配子系统（MatchService + DecideService）
 *
 * 依赖 TemplatesModule（Loader/Filter/Rank）与 QwenModule（多候选裁决）。
 * 主路径与 --triage 旁路均只注入 MatchService（C3）。
 */

import { Module } from '@nestjs/common';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { QwenModule } from '../qwen/qwen.module';
import { TemplatesModule } from '../templates/templates.module';
import { DecideService } from './decide.service';
import { MatchService } from './match.service';

@Module({
  // CatalogsModule：DecideService 要把 id 翻成中文名（why_user 归因文案）
  imports: [TemplatesModule, CatalogsModule, QwenModule],
  providers: [MatchService, DecideService],
  exports: [MatchService, DecideService],
})
export class MatchModule {}
