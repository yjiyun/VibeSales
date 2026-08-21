/** 与 Nest `src/common/wizard-models.ts` 同一份名单。 */
export const WIZARD_LLM_MODELS = [
  'deepseek-v4-flash',
];

export const DEFAULT_WIZARD_LLM_MODEL = 'deepseek-v4-flash';

export function resolveWizardLlmModel(value) {
  const raw = String(value ?? '').trim();
  return WIZARD_LLM_MODELS.includes(raw) ? raw : DEFAULT_WIZARD_LLM_MODEL;
}
