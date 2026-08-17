/** 向导接待员可选模型。与 agent-console `src/shared/wizard-models.js` 保持同一份名单。 */
export const WIZARD_LLM_MODELS = [
  'deepseek-v4-flash-0731',
  'qwen3.8-max',
  'qwen3.7-plus',
  'deepseek-v4-pro',
] as const;

export type WizardLlmModel = (typeof WIZARD_LLM_MODELS)[number];

export const DEFAULT_WIZARD_LLM_MODEL: WizardLlmModel = 'deepseek-v4-flash-0731';

export function resolveWizardLlmModel(value?: string): WizardLlmModel {
  const raw = String(value ?? '').trim();
  return (WIZARD_LLM_MODELS as readonly string[]).includes(raw)
    ? (raw as WizardLlmModel)
    : DEFAULT_WIZARD_LLM_MODEL;
}
