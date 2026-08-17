/**
 * TenantModule — 导出 TenantService（CLI 解析 client_code 时使用）
 */

import { Module } from '@nestjs/common';
import { TenantService } from './tenant.service';

@Module({
  providers: [TenantService],
  exports: [TenantService],
})
export class TenantModule {}
