/**
 * Route the authoritative Phase1Result through the configured orchestration mode.
 * Keeping this mapping outside the Vue component makes the local/platform split
 * executable as a contract instead of relying on template-level behaviour.
 */
export async function createBuildRun(phase1, mode, { managerApi, pipelineApi }) {
  if (!phase1 || phase1.gate !== 'PASS') throw new Error('只有 gate=PASS 的 Phase1Result 可以开始生成');
  if (!phase1.client_code) throw new Error('Phase1Result 缺少凭证绑定的 client_code');
  if (mode === 'platform') {
    return managerApi.create({
      client_code: phase1.client_code,
      spec: JSON.stringify({ phase: 'P1', phase1_result: phase1 }, null, 2),
    });
  }
  if (mode !== 'local') throw new Error(`不支持的 ORCHESTRATION_MODE：${mode}`);
  const summary = phase1.summary ?? {};
  return pipelineApi.start({
    client_code: phase1.client_code,
    channel: phase1.triage?.channel,
    industry_id: summary.industry?.id ?? phase1.triage?.industry,
    goal_ids: (summary.business_goals ?? []).map(goal => goal.id),
    business_brief: summary.business_brief,
    needs_long_term_memory: phase1.triage?.needs_long_term_memory === true,
    needs_skill_evolution: phase1.triage?.needs_skill_evolution === true,
  });
}
