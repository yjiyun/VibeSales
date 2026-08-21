/**
 * span 中文别名映射（面板"Span 名称"列的显示名）。集中管理，新增打点时同步维护此表即可。
 *
 * 背景：OTLP span.name 是自由字符串，面板直接渲染它；而 `gen_ai.operation.name`（chat/execute_tool/
 * invoke_agent）是**类型枚举**，面板靠它分类/出图标——两者不同字段，改别名不动 operation.name。
 *
 * 命名格式：`{阶段}·{中文动作}·{调用|结果}`（MCP 工具入口层）或 `{阶段}·{中文动作}`（阶段内二级 span）。
 * 阶段号在前，一眼看出属于哪个环节；中文动作让非工程同学也能读懂。未收录的 span 回退 `{scope}.{event}`。
 */

// MCP 工具入口层（scope=MCP 的 tool.call/tool.result）：工具名 → 中文动作。
// 与 mcp.controller 的 6 个 chatflows-pN server 下的工具一一对应。
export const TOOL_ALIASES: Record<string, string> = {
  // P1 向导 + 意图
  ask: '意图问答', revise: '意图修订', buildPhase1Result: '构建意图结果',
  // P2 模板匹配
  match: '模板匹配', preview: '模板预览',
  // P3 模板个性化
  deriveGuidance: '派生指导', injectSections: '注入章节', writeBack: '校验回写',
  // P3b 直接生成工作流
  generate: '生成工作流', selfcheck: '结构自检',
  // P3C 全新架构（蓝图编排 + 4 专家）
  listSkillCandidates: '筛选技能', listToolCandidates: '筛选工具', renderPersona: '渲染人设',
  submitExpertResult: '提交专家产出',
  composeBlueprint: '合成蓝图', blueprintSelfcheck: '蓝图自检', persistBlueprint: '蓝图落库',
  // P4 Flow 实例化
  import: '导入发布', bindProject: '绑定项目', dryRun: '试运行',
};

// 其余所有 span：`{scope}.{event}` → 中文全名（自带阶段前缀）。
export const EVENT_ALIASES: Record<string, string> = {
  // 运行级 AGENT span（McpService.finishRunSpan，一次 run 一条，携带整条 run 的输入/输出摘要）
  'AgentRun.finished': '智能体搭建·整体运行',
  // 编排入口（local 模式 PipelineService）
  'AgentTeams.task.start': 'P1·搭建任务开始',
  // P1 向导交互（Web 会话 scope=Web.Wizard，src/web/wizard-session.service.ts）。
  // 这是产品实际走的路径（CLI 的 P1.Wizard 只用于本地调试），节点最密，逐条给中文名。
  'Web.Wizard.session.create': 'P1·Web 向导建会话',
  'Web.Wizard.session.snapshot': 'P1·Web 会话快照',
  'Web.Wizard.answer.received': 'P1·Web 收到回答',
  'Web.Wizard.aborted': 'P1·Web 向导中止',
  'Web.Wizard.done': 'P1·Web 向导完成',
  'Web.Wizard.S1_industry.ask': 'P1·Web 询问行业',
  'Web.Wizard.S1_industry.free': 'P1·Web 行业自由填写',
  'Web.Wizard.S1_industry.done': 'P1·Web 行业确定',
  'Web.Wizard.S2_goals.ask': 'P1·Web 询问目标',
  'Web.Wizard.S2_goals.done': 'P1·Web 目标确定',
  'Web.Wizard.S3_brief.ask': 'P1·Web 询问简述',
  'Web.Wizard.S3_brief.done': 'P1·Web 简述确定',
  // 摘要 ready/done 的 event 由 `${at}_summary.*` 拼出，at ∈ {S4,S5}，四个取值都在此枚举。
  'Web.Wizard.S4_summary.ready': 'P1·Web 摘要就绪',
  'Web.Wizard.S4_summary.done': 'P1·Web 摘要确认',
  'Web.Wizard.S5_summary.ready': 'P1·Web 细补摘要就绪',
  'Web.Wizard.S5_summary.done': 'P1·Web 细补摘要确认',
  'Web.Wizard.S5_detail.start': 'P1·Web 细节补充开始',
  'Web.Wizard.S5_detail.extract': 'P1·Web 细节抽取',
  'Web.Wizard.S5_detail.next': 'P1·Web 细节追问',
  'Web.Wizard.S5_detail.early_finish': 'P1·Web 细节提前结束',
  'Web.Wizard.S5_detail.done': 'P1·Web 细节补充完成',
  'Web.Wizard.brief_template.done': 'P1·Web 简述模板就绪',
  'Web.Wizard.preview.done': 'P1·Web 预览完成',
  'Web.Wizard.preview.failed': 'P1·Web 预览失败',
  'Web.Wizard.revise.preview_cta': 'P1·Web 改写·预览入口',
  'Web.Wizard.revise.patch_applied': 'P1·Web 改写·应用补丁',
  'Web.Wizard.revise.fallback_pick': 'P1·Web 改写·兜底选项',
  'Web.Wizard.revise.jump': 'P1·Web 改写·跳转',
  // P1 向导 LLM 接待员（scope=P1.WizardLlm）：LLM 润色/抽取，.fallback 表示降级到确定性文案。
  'P1.WizardLlm.echoIndustry.fallback': 'P1·LLM 行业回声·降级',
  'P1.WizardLlm.echoGoals.fallback': 'P1·LLM 目标回声·降级',
  'P1.WizardLlm.structureBrief.fallback': 'P1·LLM 简述结构化·降级',
  'P1.WizardLlm.polishSummary.fallback': 'P1·LLM 摘要润色·降级',
  'P1.WizardLlm.detailPrompts.fallback': 'P1·LLM 细节追问·降级',
  'P1.WizardLlm.normalizeIndustry.fallback': 'P1·LLM 行业归一·降级',
  'P1.WizardLlm.briefTemplate.ok': 'P1·LLM 简述模板',
  'P1.WizardLlm.briefTemplate.fallback': 'P1·LLM 简述模板·降级',
  'P1.WizardLlm.extractDetail.ok': 'P1·LLM 细节抽取',
  'P1.WizardLlm.extractDetail.fallback': 'P1·LLM 细节抽取·降级',
  'P1.WizardLlm.revisePatch.fallback': 'P1·LLM 改写补丁·降级',
  // P1 向导交互（CLI 入口 scope=P1.Wizard，本地调试用）
  'P1.Wizard.start': 'P1·向导开始',
  'P1.Wizard.input.received': 'P1·收到用户输入',
  'P1.Wizard.S1_industry.ask': 'P1·询问行业',
  'P1.Wizard.S1_industry.done': 'P1·行业确定',
  'P1.Wizard.S2_goals.done': 'P1·目标确定',
  'P1.Wizard.S3_brief.done': 'P1·简述确定',
  'P1.Wizard.S4_summary.done': 'P1·总结确定',
  'P1.Wizard.S5_detail.done': 'P1·细节补充完成',
  'P1.Wizard.done': 'P1·向导完成',
  'P1.Wizard.aborted': 'P1·向导中止',
  // P1 意图裁决（scope=P1.Intent）
  'P1.Intent.start': 'P1·意图开始',
  'P1.Intent.qwen.request': 'P1·意图模型请求',
  'P1.Intent.qwen.response': 'P1·意图模型响应',
  'P1.Intent.gate': 'P1·闸门判定',
  'P1.Intent.done': 'P1·意图完成',
  // P1 CLI 评估
  'P1.evaluate.done': 'P1·评估完成',
  // P1 阶段内二级 span
  'P1.Wizard.compute': 'P1·向导计算',
  'P1.gate.evaluated': 'P1·闸门计算',
  'P1.phase1.built': 'P1·意图结果就绪',
  // P2
  'P2.match.done': 'P2·匹配完成',
  'P2.path.decided': 'P2·分流决策',
  // P3
  'P3.guidance.derived': 'P3·派生指导',
  'P3.sections.injected': 'P3·注入章节',
  'P3.selfcheck': 'P3·结构自检',
  // P3b
  'P3B.flow.generated': 'P3B·生成工作流',
  'P3B.selfcheck': 'P3B·结构自检',
  // P3C
  'P3C.skills.listed': 'P3C·技能候选',
  'P3C.tools.listed': 'P3C·工具候选',
  'P3C.persona.rendered': 'P3C·渲染人设',
  'P3C.expert.submitted': 'P3C·专家产出已提交',
  'P3C.experts.collected': 'P3C·专家产出汇总',
  'P3C.blueprint.composed': 'P3C·合成蓝图',
  'P3C.selfcheck': 'P3C·蓝图自检',
  'P3C.blueprint.persisted': 'P3C·蓝图落库',
  // P4（含失败分级）
  'P4.approval.requested': 'P4·请求审批',
  'P4.approval.pending': 'P4·等待审批',
  'P4.approval.verified': 'P4·审批核验',
  'P4.approval.denied': 'P4·审批拒绝',
  'P4.import.done': 'P4·导入完成',
  'P4.bind.actor_resolved': 'P4·解析绑定人',
  'P4.bind.done': 'P4·绑定完成',
  'P4.dryrun.done': 'P4·试运行完成',
  'P4.blocked.error': 'P4·阻塞可重试',
  'P4.failed.error': 'P4·执行失败',
  // 匹配管线底层（P2 内部 service）
  'Match.start': '匹配·开始',
  'Match.templates.loaded': '匹配·加载模板',
  'Match.done': '匹配·完成',
  'Decide.branch': '裁决·分支',
  'Decide.result': '裁决·结果',
  'Decide.qwen.request': '裁决·模型请求',
  'Decide.qwen.response': '裁决·模型响应',
  'Decide.error_fallback': '裁决·降级兜底',
  'Filter.start': '过滤·开始',
  'Filter.done': '过滤·完成',
  'Rank.done': '排序·完成',
  // 平台横切
  'Tenant.resolve.start': '租户·解析开始',
  'Tenant.resolve.done': '租户·解析完成',
  'Catalogs.loaded': '词表·加载',
  'Prompts.loaded': '提示词·加载',
  'TemplateLoader.scan.done': '模板库·扫描完成',
  'TemplateLoader.warn.extra_root_duplicate': '模板库·告警·重复根',
  'TemplateLoader.warn.param_missing_example': '模板库·告警·参数缺样例',
  // v0 预览
  'Preview.built': '预览·命中模板',
  'Preview.custom_outline.built': '预览·定制轮廓',
  // Token 汇总
  'Token.summary': 'Token·用量汇总',
  // 进程生命周期
  'Web.Boot.listening': 'Web·已启动',
  'Web.Boot.shutdown': 'Web·关闭',
  // P2 CLI 匹配命令
  'P2.CLI.start': 'P2·CLI 开始',
  'P2.CLI.end': 'P2·CLI 结束',
  'P2.CLI.fail': 'P2·CLI 失败',
  'P2.CLI.fatal': 'P2·CLI 致命错误',
  'P2.CLI.list-templates': 'P2·CLI 列模板',
  'P2.CLI.request_context': 'P2·CLI 请求上下文',
  'P2.CLI.triage.bypass': 'P2·CLI 旁路 triage',
  'P2.CLI.utterance.resolved': 'P2·CLI 话术解析',
  // 大模型调用（scope=Qwen）
  'Qwen.client.init': '大模型·初始化',
  'Qwen.chatJson.start': '大模型·请求',
  'Qwen.chatJson.prompt': '大模型·提示词',
  'Qwen.chatJson.done': '大模型·响应',
  'Qwen.chatJson.error': '大模型·错误',
  'Qwen.usage': '大模型·用量',
};

// Flow 段落名（setFlow 的 flow 参数）→ 中文。产生 `流程·{中文} 开始`。
// mcp.chatflows-pN 由规则单独推导，不列这里。
// 值用纯名词，拼成 `流程·{名词} 开始`（不带动词，避免"启动…开始"重复）。
// banner 段落标题（`trace.banner(title)` 的 title）→ 中文。banner 也走 scope=Flow，
// 但 event 是自由标题而非 `{flow}.begin`，所以单独一张表。产生 `流程·{中文}`。
// Web 向导的 banner 带 stage 后缀（`Web wizard S1_INDUSTRY`），由 WEB_WIZARD_STAGE_ALIASES 拼。
export const BANNER_ALIASES: Record<string, string> = {
  'Match pipeline': '匹配管线',
  'Web wizard session.create': 'Web 向导建会话',
  'Web P2 preview': 'Web P2 预览',
  'Web brief template': 'Web 简述模板',
  'P1 wizard': 'P1 向导',
  'P1 evaluate': 'P1 评估',
  'P1 Intent triage': 'P1 意图裁决',
  'P2 CLI match': 'P2 CLI 匹配',
};

// Web 向导 stage（WizardStage 联合类型，见 src/web/web.types.ts）→ 中文，
// 用于 banner `Web wizard ${session.stage}`。
export const WEB_WIZARD_STAGE_ALIASES: Record<string, string> = {
  S1_INDUSTRY: '行业',
  S1_INDUSTRY_FREE: '行业自填',
  S2_GOALS: '目标',
  S3_BRIEF: '简述',
  S4_CTA: '摘要确认',
  S5_DETAIL: '细节补充',
  S5_CTA: '细补确认',
  S6_REVISE: '改写',
  DONE: '完成',
  ABORTED: '中止',
};

export const FLOW_ALIASES: Record<string, string> = {
  'web-boot': 'Web 服务',
  'web': 'Web 请求',
  'p1': 'P1 意图评估',
  'p1-wizard': 'P1 向导',
  'p2-match': 'P2 匹配命令',
  'agentteams-pipeline': '搭建流水线',
};

/**
 * 计算 span 的中文显示名。优先级：
 * 1) MCP 工具入口层（scope=MCP + 已知 tool）→ `{phase}·{工具中文}·{调用|结果}`
 * 2) Flow 段落标记（scope=Flow）→ `流程·PN 开始` 等（规则推导，不逐条枚举）
 * 3) EVENT_ALIASES 命中 → 表中的中文全名
 * 4) 都未命中 → 回退 `{scope}.{event}`（英文语义名，仍好过 execute_tool）
 */
export function spanDisplayName(
  scope: string,
  event: string,
  tool?: string | number | boolean,
  phase?: string | number | boolean,
): string {
  const toolName = typeof tool === 'string' ? tool : undefined;
  const phaseTag = typeof phase === 'string' && phase ? phase : undefined;

  if (scope === 'MCP' && toolName && TOOL_ALIASES[toolName]) {
    const suffix = event === 'tool.call' ? '调用' : event === 'tool.result' ? '结果' : event;
    return [phaseTag, TOOL_ALIASES[toolName], suffix].filter(Boolean).join('·');
  }

  if (scope === 'Flow') {
    // setFlow 产生 `{flow}.begin`；MCP 平面是 `mcp.chatflows-pN.begin`。
    const mcp = /(?:mcp\.)?chatflows-p([0-9a-c]+)\.begin/i.exec(event);
    if (mcp) return `流程·P${mcp[1].toUpperCase()} 开始`;
    const begin = /^(.*)\.begin$/.exec(event);
    if (begin) return `流程·${FLOW_ALIASES[begin[1]] ?? begin[1]} 开始`;
    // banner（trace.banner）：event 是自由 title。
    if (BANNER_ALIASES[event]) return `流程·${BANNER_ALIASES[event]}`;
    // Web 向导 banner 带 stage 后缀：`Web wizard S1_INDUSTRY` → `流程·Web 向导·行业`。
    const stage = /^Web wizard (.+)$/.exec(event);
    if (stage) return `流程·Web 向导·${WEB_WIZARD_STAGE_ALIASES[stage[1]] ?? stage[1]}`;
    return `流程·${event}`;
  }

  return EVENT_ALIASES[`${scope}.${event}`] ?? `${scope}.${event}`;
}
