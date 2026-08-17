/**
 * [全阶段共享] 类型契约
 *
 * 文档：docs/工程架构.md §5 核心契约 · docs/产品设计.md
 *
 * 阶段归属速查：
 * - Triage / NextAsk / Phase1Result / IntentGate → **P1**
 * - MatchResult / TemplateRecord → **P2**
 * - V0Preview / TemplateParam.example 细补 → **P2 后 / P3**
 * - TenantProfile / RequestContext / CatalogOption → 全阶段
 *
 * 约束：RequestContext / MatchResult / Phase1Result / EndToEndResult 均须携带 `client_code`。
 */

import { ProductPhase } from './product-phase';

/** SaaS 租户画像：渠道与已开通连接器（P2 硬过滤会用）。 */
export interface TenantProfile {
  client_code: string;
  channels: string[];
  connectors: string[];
}

/** 单次请求上下文：租户解析后贯穿 P1 Intent / P2 Match。 */
export interface RequestContext {
  client_code: string;
  tenant: TenantProfile;
  request_id?: string;
}

/**
 * [P1] 词表选项（industries / agent_families / capabilities / channels / business_goals）。
 * 供向导「选项」渲染：id 给程序，name/description 给人看。
 */
export interface CatalogOption {
  id: string;
  name: string;
  description?: string;
  /** 行业分组名（仅 industries，如「电商与零售」） */
  group?: string;
}

/**
 * [P1] 业务目标词表条目（catalogs/business_goals.yaml）。
 * 与 scene 解耦：跨行业多选「希望 AI 先办哪类事」。
 */
export interface BusinessGoalItem {
  id: string;
  name: string;
  description?: string;
  capability_hints?: string[];
  agent_family_hint?: string;
  summary_role_hint?: string;
}

/**
 * [P1] 向导「下一问」。
 * 由 WizardService 规则计算；模型只翻成 ask_user，不得增删 options。
 */
export interface NextAsk {
  /** 要补的槽位 key（如 industry / business_goals / desired_capabilities） */
  slot: string;
  label?: string;
  why?: string;
  /** 是否多选 */
  multi?: boolean;
  /** 选项来源词表名 */
  options_from?: string;
  /** 由 options_from 解析出的候选项（禁止模型改动） */
  options: CatalogOption[];
  /** 示例来源（scenes.<id>.typical_prompts） */
  example_from?: string;
  /** 可照抄的示例回复 */
  examples: string[];
}

/**
 * [P1] 粗补完成后的基本信息总结（展示 + 下游只读）。
 * 见 docs/工程架构.md §5.3。
 */
export interface WizardSummary {
  industry: { id: string; name: string; group?: string };
  business_goals: Array<{ id: string; name: string }>;
  business_brief?: string;
  role_positioning: string;
  core_capabilities: string[];
  current_focus: string;
  /** 配套知识库预设占位（非真实 KB 正文） */
  knowledge_packs_planned: string[];
}

/**
 * [P1] 「继续补充细节」结构化字段（可部分填写）。
 */
export interface WizardDetailSupplement {
  flagship_products?: string;
  primary_customers?: string;
  prohibitions?: string;
  goals?: string;
  escalate_scenes?: string;
}

/** [P1] 总结页之后的用户动作。 */
export type WizardNextAction = 'preview' | 'continue_detail' | 'done';

/**
 * [P1→P2 枢纽] 装机意图分诊结果。
 * P1 产出；P2 Match 只消费稳定字段（scene_id / channel / industry / known_slots…）。
 * next_ask / can_generate_v0 为 P1 向导字段，P2 可忽略。
 *
 * 命名说明：can_generate_v0 历史字段名；P1 语义 =「粗补是否达标可进下一阶段」。
 */
export interface Triage {
  scene_id: string;
  agent_family?: string;
  channel?: string;
  industry?: string;
  confidence: number;
  reason: string;
  known_slots?: Record<string, unknown>;
  missing_slots?: string[];
  ask_user?: string;
  risk_flags?: string[];
  /** [P1] 规则算出的下一问（粗补未齐时非空） */
  next_ask?: NextAsk | null;
  /**
   * [P1] 粗补是否达标（confidence≥阈值 且 required 粗槽齐）。
   * 历史名 can_generate_v0；真·v0 预览属 P2 命中后。
   */
  can_generate_v0?: boolean;
  /** [P3C 分流] 跨会话记忆诉求。 */
  needs_long_term_memory?: boolean;
  /** [P3C 分流] Skill 持续演进诉求。 */
  needs_skill_evolution?: boolean;
}

/**
 * [P1] 第一阶段主回包。
 * 主路径停在此处；next_action=preview 时由编排层再调 P2。
 */
export interface Phase1Result {
  phase: ProductPhase.P1_WIZARD_INTENT;
  client_code: string;
  request_id?: string;
  /** 新向导结束多为 S1_SUMMARY / S1_DETAIL；S1_COARSE 兼容旧路径 */
  stage: 'S1_COARSE' | 'S1_SUMMARY' | 'S1_DETAIL';
  gate: IntentGate;
  triage: Triage;
  ask_user?: string;
  utterance?: string;
  error?: string;
  summary?: WizardSummary;
  detail?: WizardDetailSupplement;
  next_action?: WizardNextAction;
}

/**
 * [P2] 硬过滤剔除归因（用户版）。
 * 与 `MatchResult.reject_reasons`（英文调试串）同源，一条对一条：
 * 前者给界面讲人话，后者给工程排障，互不替代。
 */
export interface TemplateRejectReason {
  /** 被哪一道闸剔除（对应 TemplateFilterService 的四道顺序） */
  kind: 'scene' | 'channel' | 'connector' | 'stability';
  template_id: string;
  /** 模板展示名（中文），UI 上不要拿 template_id 当标题 */
  display_name: string;
  /** 一句中文归因，如「缺连接器：知识库」 */
  detail: string;
}

/** [P2] Top-K 里没被选中的候选（多候选时才有值）。 */
export interface MatchAlternative {
  template_id: string;
  display_name: string;
  /** 为什么没选它；规则路径下为排名说明 */
  why_not?: string;
}

/**
 * [P2] 模板匹配结果。
 * - hit：命中模板（via=rule|qwen|rule_fallback）
 * - custom：无合适模板 / 需定制
 *
 * 字段分两层，**不可混用**：
 * - 工程层 `why` / `reject_reasons` / `candidates_considered`：英文，给日志与 CLI
 * - 用户层 `why_user` / `reject_summary` / `alternatives`：中文，给界面
 */
export interface MatchResult {
  client_code: string;
  action: 'hit' | 'custom';
  via?: 'rule' | 'qwen' | 'rule_fallback';
  template_id?: string;
  display_name?: string;
  why?: string;
  diffs?: string[];
  required_params_missing?: string[];
  workflow_path?: string;
  candidates_considered?: string[];
  /** 硬过滤剔除原因，便于调试与验收 T3 */
  reject_reasons?: string[];
  /** 一句中文人话理由；规则路径也有值（不新增模型调用） */
  why_user?: string;
  /** reject_reasons 的用户版归因（规则翻译，不调模型） */
  reject_summary?: TemplateRejectReason[];
  /** Top-K 里没被选中的候选 */
  alternatives?: MatchAlternative[];
  /** [P3C 分流] 固定 DAG 无法表达的不定轮次工具编排。 */
  needs_multi_turn_tooling?: boolean;
}

/**
 * [P2] v0 预览：命中后用 BRIEF + meta 渲染（不属 P1）。
 */
export interface V0Preview {
  template_id: string;
  role: string;
  capabilities: string[];
  main_flow: string[];
  success_criteria: string[];
  missing_required: string[];
}

/**
 * [P2] `action=custom` 时的定制轮廓（**不是** v0 预览）。
 *
 * 命中不了标品也不能空手：用 P1 的 `summary` + `triage` 组织「如果定制会是这样」。
 * 纯规则拼装，不调模型、不承诺可交付。
 */
export interface CustomOutline {
  /** 为什么没有现成模板（中文归因，来自 reject_summary / 无候选） */
  why_no_template: string[];
  /** 角色定位（取 summary.role_positioning） */
  role: string;
  /** 能力诉求（summary.core_capabilities，业务语言） */
  capabilities: string[];
  /** 业务简述（summary.business_brief + 细补要点） */
  business_brief?: string;
  /** 下一步建议（换场景 / 走定制） */
  suggestions: string[];
}

/**
 * [P2 端到端] CLI `match` stdout 回包。
 * match 在 ASK/CUSTOM/ERROR 时可选（P1 闸门未过则无匹配结果）。
 */
export interface EndToEndResult {
  /** 标明本回包来自 P2 管线（可能含 P1 triage 子结果） */
  phase?: ProductPhase.P2_TEMPLATE_MATCH;
  client_code: string;
  request_id?: string;
  utterance?: string;
  triage?: Triage;
  /** P2 匹配结果；P1 闸门未过时可不填 */
  match?: MatchResult;
  gate?: IntentGate;
  ask_user?: string;
  error?: string;
  /** [P2] 命中后附 v0 预览 */
  v0_preview?: V0Preview;
  /** [P2] action=custom 时的定制轮廓（与 v0_preview 互斥） */
  custom_outline?: CustomOutline;
}

/** 模板稳定性：影响 Rank 基础分。[P2] */
export type Stability = 'draft' | 'beta' | 'ga';

/** [P2/P3] meta.params 中的开通参数声明。 */
export interface TemplateParam {
  key: string;
  label?: string;
  required?: boolean;
  default?: unknown;
  /** [P3] 细补示例 */
  example?: string;
}

/**
 * [P2] 内存中的模板记录（meta + BRIEF，不含 workflow 正文）。
 */
export interface TemplateRecord {
  template_id: string;
  display_name: string;
  stability: Stability;
  scene_ids: string[];
  agent_family?: string;
  industries: string[];
  channels: string[];
  capabilities: string[];
  connectors_required: string[];
  connectors_optional: string[];
  search_text: string;
  brief: string;
  root_path: string;
  coze_export?: string;
  params: TemplateParam[];
  meta_summary: Record<string, unknown>;
}

/**
 * [P1] scene 的粗补槽位声明（scenes.yaml slots[]）。
 */
export interface SceneSlot {
  key: string;
  label?: string;
  source?: string;
  required?: boolean;
  multi?: boolean;
  why?: string;
}

/** [P1] catalogs/scenes.yaml 单条。 */
export interface SceneCatalogItem {
  id: string;
  name: string;
  summary: string;
  agent_family?: string;
  industries?: string[];
  typical_prompts?: string[];
  status?: string;
  slots?: SceneSlot[];
  ask_order?: string[];
}

/** [P1] 意图闸门：PASS 才可进入 P2 Match。 */
export type IntentGate = 'PASS' | 'ASK' | 'CUSTOM' | 'ERROR';

/** [P1] IntentService.recognize 返回。 */
export interface IntentResult {
  gate: IntentGate;
  triage?: Triage;
  ask_user?: string;
  error?: string;
}

// ============================================================================
// AgentTeams P3-P4 契约
// ============================================================================

export type BuildPath = 'P3' | 'P3B' | 'P3C' | 'EARLY_EXIT';
export type ArtifactKind =
  | 'wizard_state'
  | 'triage'
  | 'match_result'
  | 'guidance'
  | 'personalized_package'
  | 'flow_yaml'
  | 'flow_check'
  | 'blueprint'
  | 'blueprint_check'
  | 'expert_dispatch'
  | 'expert_result'
  | 'import_result'
  | 'dry_run'
  | 'approval'
  | 'evidence';

export interface RunRecord {
  run_id: string;
  client_code: string;
  status: 'RUNNING' | 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED' | 'ABORTED';
  current_phase: ProductPhase;
  build_path?: BuildPath;
  created_at: string;
  updated_at: string;
}

export interface Artifact<T = unknown> {
  artifact_id: string;
  run_id: string;
  client_code: string;
  kind: ArtifactKind;
  version: number;
  payload: T;
  written_by: string;
  created_at: string;
}

export interface Guidance {
  role: string;
  tone: 'professional' | 'friendly' | 'concise' | 'warm' | 'energetic' | 'calm';
  reply_length: 'short' | 'medium' | 'long';
  conditions: string[];
  escalation_conditions: string[];
}

export interface SkillDefinition {
  name: string;
  source: 'library' | 'inline';
  ref?: string;
  skillMd?: string;
  requiredTools?: string[];
}

export interface AgentBlueprint {
  blueprintId: string;
  version: number;
  clientCode: string;
  runtimeAgentId: string;
  meta: { industry?: string; scenarios: string[]; generatedBy: string; runId: string };
  prompt: { agentsMd: string; soulMd: string; knowledgeMd?: string };
  skills: SkillDefinition[];
  tools: {
    allow: string[];
    deny: string[];
    mcpServers: Array<{ name: string; url: string; transport: 'streamableHttp' }>;
  };
  runtime: {
    model: string;
    isolationScope: 'SESSION' | 'USER' | 'AGENT';
    maxContextTokens: number;
    compaction: { triggerMessages: number; keepMessages: number };
  };
  guidance?: Guidance;
}

export interface CheckItem { id: number; name: string; ok: boolean; severity: 'error' | 'warning'; detail?: string }
export interface CheckReport { ok: boolean; checks: CheckItem[]; subject_hash?: string }

export interface AgentBlueprintRecord {
  blueprint_id: string; client_code: string; runtime_agent_id: string; version: number;
  status: 'DRAFT' | 'STAGED' | 'PUBLISHED' | 'RETIRED'; payload: AgentBlueprint;
  source_run_id: string; written_by: string; created_at: string; updated_at: string;
}

export interface AgentBinding {
  client_code: string; user_id: string; runtime_agent_id: string; blueprint_id: string;
  projected_version?: number; projected_at?: string;
}
