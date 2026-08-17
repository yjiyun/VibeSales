/**
 * QwenModule — 导出 QwenService（Intent / Decide 共用）
 */

import { Module } from '@nestjs/common';
import { QwenService } from './qwen.service';

@Module({
  providers: [QwenService],
  exports: [QwenService],
})
export class QwenModule {}
