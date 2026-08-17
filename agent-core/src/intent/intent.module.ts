/**
 * [P1] IntentModule — 自然语言分诊（依赖 WizardModule 做规则闸门）
 *
 * 不依赖 MatchModule。
 */

import { Module } from '@nestjs/common';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { QwenModule } from '../qwen/qwen.module';
import { WizardModule } from '../wizard/wizard.module';
import { IntentService } from './intent.service';

@Module({
  imports: [CatalogsModule, QwenModule, WizardModule],
  providers: [IntentService],
  exports: [IntentService],
})
export class IntentModule {}
