import { RuleSpec } from '../common/types';

/**
 * 与 agent-runtime `RuleCapabilityCatalog` 对齐的闭合词表。
 * Java 是真源：那边增删 ruleCode / param key 时必须同步这份，否则 P3C 会写出 runtime 拒收的声明。
 *
 * rules[] 只做开关和参数覆盖，不下发表达式（Rule#evaluate 的正确性由 Java 单测锁定）。
 */

/** 已接入编排主链，蓝图声明即当轮生效。 */
export const WIRED_RULES = new Set(['recovery-detection']);

/** 已实现且有单测，但编排链尚无调用点。 */
export const IMPLEMENTED_NOT_WIRED_RULES = new Set([
  'profile-completeness',
  'follow-up-round-limit',
  'intent-priority',
  'human-handoff-trigger',
  'queue-version-guard',
  'closure-writeback-required-fields',
]);

/** 每条规则接受的参数键；未列出的规则当前不接受任何覆盖。 */
export const PARAM_KEYS: Record<string, Set<string>> = {
  'recovery-detection': new Set(['continuationKeywords']),
  'follow-up-round-limit': new Set(['maxFollowUpRounds']),
  'profile-completeness': new Set(['forcedRoundThreshold']),
};

export type RuleClassification = 'wired' | 'implemented_not_wired' | 'unsupported';

export function allImplemented(): Set<string> {
  return new Set([...WIRED_RULES, ...IMPLEMENTED_NOT_WIRED_RULES]);
}

export function classify(ruleCode: string | undefined): RuleClassification {
  const code = (ruleCode ?? '').trim();
  if (WIRED_RULES.has(code)) return 'wired';
  if (IMPLEMENTED_NOT_WIRED_RULES.has(code)) return 'implemented_not_wired';
  return 'unsupported';
}

export function acceptsParam(ruleCode: string | undefined, paramKey: string): boolean {
  const accepted = PARAM_KEYS[(ruleCode ?? '').trim()];
  return Boolean(accepted?.has(paramKey));
}

/**
 * 按需求装配已接线规则。
 *
 * recovery-detection 的判断逻辑已在 runtime 常驻；蓝图条目只是声明 + 可选词表覆盖。
 * 空 params 表示走 Java 默认词表。未命中需求则不写——runtime 缺省同样走默认词表，
 * 产物不应假装「装上了」一条没有业务信号支撑的规则。
 *
 * 专家 JSON 里的 rules 不得作为输入（A6：词表 id 只出 catalog）。
 */
export function selectWiredRules(input: {
  needsLongTermMemory?: boolean;
  skillNames: readonly string[];
}): RuleSpec[] {
  const names = new Set(input.skillNames.map((name) => String(name ?? '').trim().toLowerCase()));
  const needsRecovery = input.needsLongTermMemory === true || names.has('recovery-handling');
  if (!needsRecovery) return [];
  return [{ ruleCode: 'recovery-detection', enabled: true, params: {} }];
}

export function validateRuleSpecs(rules: RuleSpec[] | undefined): { errors: string[]; warnings: string[] } {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!rules?.length) return { errors, warnings };
  const seen: string[] = [];
  for (const rule of rules) {
    const code = typeof rule?.ruleCode === 'string' ? rule.ruleCode.trim() : '';
    if (!code) {
      errors.push('rules[] contains an entry without a ruleCode');
      continue;
    }
    if (seen.includes(code)) {
      errors.push('duplicate ruleCode: ' + code);
      continue;
    }
    seen.push(code);
    const kind = classify(code);
    if (kind === 'unsupported') {
      errors.push(
        "rules[] declares '" + code + "' which this project does not implement; implemented rules are "
          + [...allImplemented()].join(', '),
      );
      continue;
    }
    const params = rule.params && typeof rule.params === 'object' && !Array.isArray(rule.params) ? rule.params : {};
    for (const key of Object.keys(params)) {
      if (!acceptsParam(code, key)) {
        const accepted = PARAM_KEYS[code];
        errors.push(
          "rule '" + code + "' does not accept param '" + key + "'; accepted keys are "
            + (accepted ? [...accepted].join(', ') : '(none)'),
        );
      }
    }
    const enabled = rule.enabled !== false;
    if (enabled && kind === 'implemented_not_wired') {
      warnings.push(
        "rules[] enables '" + code
          + "' which is implemented and unit-tested but has no call site in the orchestration chain yet; it will be recorded, not enforced",
      );
    }
  }
  return { errors, warnings };
}
