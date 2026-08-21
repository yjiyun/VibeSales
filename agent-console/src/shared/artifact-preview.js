export function latestArtifact(artifacts, kind, predicate) {
  const matched = (artifacts ?? []).filter(
    (item) => item.kind === kind && (!predicate || predicate(item)),
  );
  return matched.at(-1);
}

export function expertRole(item) {
  return item?.payload?.role ?? item?.written_by ?? 'expert';
}

export function expertBody(item) {
  const payload = item?.payload ?? {};
  return payload.payload ?? payload;
}

export function stageStatus(artifacts, kinds) {
  const hits = kinds
    .map((kind) => latestArtifact(artifacts, kind))
    .filter(Boolean);
  if (!hits.length) return 'missing';
  const check = hits.find((item) => item.kind === 'blueprint_check' || item.kind === 'flow_check');
  if (check && check.payload?.ok === false) return 'fail';
  return 'ok';
}

export function previewText(item) {
  if (!item) return '';
  const kind = item.kind;
  const payload = item.payload ?? {};
  if (kind === 'triage') {
    return [
      `industry: ${payload.industry ?? '—'}`,
      `scene_id: ${payload.scene_id ?? '—'}`,
      `gate: ${payload.gate ?? '—'}`,
      `needs_long_term_memory: ${payload.needs_long_term_memory === true}`,
    ].join('\n');
  }
  if (kind === 'match_result') {
    return [
      `action: ${payload.action ?? '—'}`,
      `template_id: ${payload.template_id ?? '—'}`,
      `build_path: ${payload.build_path ?? '—'}`,
    ].join('\n');
  }
  if (kind === 'guidance') {
    return [
      `role: ${payload.role ?? '—'}`,
      `tone: ${payload.tone ?? '—'}`,
      `reply_length: ${payload.reply_length ?? '—'}`,
      `escalation: ${(payload.escalation_conditions ?? []).join('；')}`,
    ].join('\n');
  }
  if (kind === 'expert_result') {
    const body = expertBody(item);
    return body.soulMd || body.agentsMd || JSON.stringify(body, null, 2);
  }
  if (kind === 'blueprint') {
    return [
      `blueprintId: ${payload.blueprintId ?? '—'}`,
      `runtimeAgentId: ${payload.runtimeAgentId ?? '—'}`,
      `skills: ${(payload.skills ?? []).map((s) => s.name).filter(Boolean).join(', ') || '—'}`,
      `rules: ${formatRules(payload.rules)}`,
      '',
      payload.prompt?.soulMd || '',
    ].join('\n');
  }
  if (kind === 'blueprint_check' || kind === 'flow_check') {
    return (payload.checks ?? [])
      .map((c) => `#${c.id} ${c.name} ${c.ok ? 'OK' : 'FAIL'}`)
      .join('\n');
  }
  if (kind === 'approval') {
    return `status=${payload.status ?? '—'} approval_id=${payload.approval_id ?? '—'}`;
  }
  try {
    return JSON.stringify(payload, null, 2);
  } catch {
    return String(payload);
  }
}

export function formatRules(rules) {
  if (!Array.isArray(rules) || !rules.length) return '—';
  return rules
    .map((rule) => {
      const code = rule?.ruleCode || '(missing)';
      const on = rule?.enabled === false ? 'off' : 'on';
      return `${code} (${on})`;
    })
    .join(', ');
}

export const STAGES = [
  { id: 'p1', label: 'P1 triage', kinds: ['triage'] },
  { id: 'p2', label: 'P2 match', kinds: ['match_result'] },
  { id: 'experts', label: '专家输出', kinds: ['expert_result'] },
  { id: 'blueprint', label: 'Blueprint', kinds: ['blueprint'] },
  { id: 'check', label: '自检', kinds: ['blueprint_check', 'flow_check'] },
  { id: 'publish', label: '发布', kinds: ['approval', 'import_result'] },
];
