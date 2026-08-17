/**
 * [P1] WizardModule — 向导规则引擎 + 可选 LLM 接待员
 *
 * 导出 WizardService / WizardLlmReceptionist。
 * 依赖 CatalogsModule + QwenModule；文案经全局 PromptsModule。
 * **不**依赖 MatchModule。
 */

import { Module } from '@nestjs/common';
import { CatalogsModule } from '../catalogs/catalogs.module';
import { QwenModule } from '../qwen/qwen.module';
import { WizardLlmReceptionist } from './wizard-llm-receptionist.service';
import { WizardService } from './wizard.service';

@Module({
  imports: [CatalogsModule, QwenModule],
  providers: [WizardService, WizardLlmReceptionist],
  exports: [WizardService, WizardLlmReceptionist],
})
export class WizardModule {}
