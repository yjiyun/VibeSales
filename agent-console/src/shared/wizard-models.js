/** 与 Nest `src/common/wizard-models.ts` 同一份名单。 */
export const WIZARD_LLM_MODELS = [
  'deepseek-v4-flash-0731',
  'qwen3.8-max',
  'qwen3.7-plus',
  'deepseek-v4-pro',
];

export const DEFAULT_WIZARD_LLM_MODEL = 'deepseek-v4-flash-0731';

export function resolveWizardLlmModel(value) {
  const raw = String(value ?? '').trim();
  return WIZARD_LLM_MODELS.includes(raw) ? raw : DEFAULT_WIZARD_LLM_MODEL;
}
