/**
 * CatalogsModule — 导出 CatalogsService
 *
 * 词表服务在启动时加载；Templates / Intent 依赖本模块。
 */

import { Module } from '@nestjs/common';
import { CatalogsService } from './catalogs.service';

@Module({
  providers: [CatalogsService],
  exports: [CatalogsService],
})
export class CatalogsModule {}
