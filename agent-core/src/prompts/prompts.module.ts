/**
 * PromptsModule — 导出 PromptsService（接待员 / Intent / Decide 共用）
 */

import { Global, Module } from '@nestjs/common';
import { PromptsService } from './prompts.service';

@Global()
@Module({
  providers: [PromptsService],
  exports: [PromptsService],
})
export class PromptsModule {}
