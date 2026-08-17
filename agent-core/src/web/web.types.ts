/**
 * [Web] HTTP 契约（前端唯一依据）
 *
 * 文档：`docs/工程架构.md` §13 Web 层
 *
 * 设计口径：**不为 UI 另造业务字段**。`WizardSummary` / `Phase1Result` / `MatchResult`
 * 直接复用 `common/types.ts`；本文件只加「一回合怎么问、这回合内部跑了什么」两类外壳：
 *
 * ```text
 * WizardTurn = 说了什么(messages) + 接下来问什么(question) + 业务产物(summary/result)
 *            + 这回合内部运行情况(runtime: events + token)
 * ```
 */

import { FlowEvent } from '../common/flow-context';
import { TokenSummary } from '../common/token-usage.service';
import {
  CatalogOption,
  EndToEndResult,
  Phase1Result,
  WizardDetailSupplement,
  WizardNextAction,
  WizardSummary,
} from '../common/types';

/**
 * 向导会话阶段（Web 版状态机）。
 * 与 CLI 的 S1→S5 同一条路径，只是把「等用户输入」显式化成一个可持久的停顿点。
 */
export type WizardStage =
  | 'S1_INDUSTRY'
  | 'S1_INDUSTRY_FREE'
  | 'S2_GOALS'
  | 'S3_BRIEF'
  | 'S4_CTA'
  | 'S5_DETAIL'
  | 'S5_CTA'
  | 'S6_REVISE'
  | 'DONE'
  | 'ABORTED';

/** 会话是否还在等输入。DONE 后仍可自由改写，因此 done 也可再 answer。 */
export type WizardStatus = 'asking' | 'done' | 'aborted';

/** 信息收集清单单项状态（左栏「信息收集」面板）。 */
export type WizardCollectStatus = 'done' | 'current' | 'pending' | 'skipped';

/** 信息收集清单单项。标题 / why 由服务端掌握，前端不得写死业务枚举。 */
export interface WizardCollectItem {
  /** industry / business_goals / business_brief / detail.<key> */
  key: string;
  label: string;
  /** 这一问会决定什么 */
  why?: string;
  status: WizardCollectStatus;
  /** 已收集到的人可读取值 */
  value?: string;
  /** 细补五项为可选，不计入进度分母 */
  optional?: boolean;
}

/**
 * 信息收集投影：进度 + 清单。
 * 由会话状态机 + 词表推导，挂在每一回合的 WizardTurn 上。
 */
export interface WizardCollect {
  /** 已完成的必填项数（进行中时 = 已完成数；全部做完时 = total） */
  step: number;
  /** 必填项总数 */
  total: number;
  current_key?: string;
  items: WizardCollectItem[];
}

/** 一条向导发言（markdown，前端用气泡渲染）。 */
export interface WizardMessage {
  /** 便于前端做 key / 定位 */
  id: string;
  role: 'assistant';
  /** markdown 正文 */
  content: string;
  /**
   * 语义分类，供 UI 换样式：
   * - `speech` 常规话术  - `echo` 回声确认  - `summary` 总结卡
   * - `question` 问题正文 - `notice` 系统提示（降级 / 结束语）
   */
  kind: 'speech' | 'echo' | 'summary' | 'question' | 'notice';
  /** 是否由大模型生成（false = 规则底稿），前端可标注「LLM」小徽章 */
  by_llm?: boolean;
}

/** 快捷回复按钮（跳过 / 结束细补 / CTA）。 */
export interface WizardQuickReply {
  label: string;
  value: string;
}

/**
 * 本回合要问什么。
 * 选项一律来自 `catalogs/*.yaml`（`options_from`），**前端不得自造 id**。
 */
export interface WizardQuestion {
  stage: WizardStage;
  /** 槽位 key：industry / business_goals / business_brief / next_action / detail.<key> */
  key: string;
  /** 问题正文（markdown） */
  title: string;
  hint?: string;
  /** 单选 / 多选 / 纯文本 */
  input: 'single' | 'multi' | 'text';
  /** 词表来源名，便于前端展示「选项来自词表」 */
  options_from?: string;
  options?: CatalogOption[];
  /** 行业按 group 分组（其它槽位为空） */
  groups?: Array<{ group: string; options: CatalogOption[] }>;
  /** 允许「都不匹配 → 直接描述」 */
  allow_free?: boolean;
  /** 允许留空跳过 */
  skippable?: boolean;
  /** 可照抄示例（来自 scenes.typical_prompts） */
  examples?: string[];
  /** 「使用模板」按钮的文本：点击后直接填入输入框，用户可自行修改 */
  template_text?: string;
  /**
   * true = 点击「使用模板」时才调 `POST /wizard/sessions/:id/template`
   * 让模型结合行业/目标现场生成；接口失败则退回 `template_text`。
   */
  template_on_demand?: boolean;
  quick_replies?: WizardQuickReply[];
}

/**
 * 本回合的运行情况：需求「从 Web 请求到向导模块内部」的可视化载荷。
 * 与 `logs/app.log` 同源（同一次 `trace.step`），因此界面看到的顺序和日志一致。
 */
export interface WizardRuntime {
  flow: string;
  request_id: string;
  /** 本回合链路事件（模块 / 位置 / 耗时） */
  events: FlowEvent[];
  /** 事件超过上限被丢弃的条数 */
  events_dropped: number;
  /** 本回合 token 用量（无 LLM 调用时 null） */
  token: TokenSummary | null;
  /** 会话累计 token 用量 */
  token_session: TokenSummary | null;
  /** 本回合是否真的会调模型（有 Key 且会话开启） */
  llm: boolean;
  /** 服务端处理耗时 */
  took_ms: number;
}

/** 一个回合的完整回包。 */
export interface WizardTurn {
  session_id: string;
  client_code: string;
  stage: WizardStage;
  status: WizardStatus;
  /** 本回合向导说的话（按顺序渲染） */
  messages: WizardMessage[];
  /** status=asking 时必有；DONE 后进入 S6_REVISE 改写目标选择时也会有 */
  question?: WizardQuestion;
  /** 已生成的总结（S4 起有值，细补后会更新） */
  summary?: WizardSummary;
  detail?: WizardDetailSupplement;
  next_action?: WizardNextAction;
  /** 向导结束时的 P1 主回包（与 CLI stdout 同结构）；DONE 后改写会更新 */
  result?: Phase1Result;
  /**
   * [P2] 本回合顺带跑出的 P2 产物。
   * 只有 CTA「先看看效果」直串 P2 时非空（§8.7）；与 `PreviewTurn.result` 同结构，
   * 前端拿到即把 match 卡推进时间线，无需再点按钮。
   */
  preview?: EndToEndResult;
  /** 信息收集进度 + 清单（左栏面板） */
  collect: WizardCollect;
  /** 中途退出时记录退出位置 */
  aborted_at?: string;
  runtime: WizardRuntime;
}

/** 创建会话入参。 */
export interface CreateSessionBody {
  client_code?: string;
  /** 是否启用接待员 LLM（缺省 true；无 Key 时自动等同 false） */
  llm?: boolean;
  /** 向导接待员模型；须在服务端允许名单内，否则回落到默认 */
  model?: string;
  /** 覆盖租户 JSON 路径（联调用） */
  tenant?: string;
}

/** 回答入参：点选项传 `values`（词表 id），自由输入传 `text`。 */
export interface AnswerBody {
  text?: string;
  values?: string[];
}

/**
 * [P2] 预览回包：Web 编排层在向导结束后按需调 P2。
 *
 * `result` 里 `action=hit` 给 `v0_preview`，`action=custom` 给 `custom_outline`，
 * 两者互斥；`result.match.why_user` / `reject_summary` / `alternatives` 是
 * 面向用户的中文表达，界面优先用它们而不是英文 `why` / `reject_reasons`。
 */
export interface PreviewTurn {
  session_id: string;
  client_code: string;
  /** P2 端到端结果（match + v0_preview / custom_outline） */
  result: EndToEndResult;
  runtime: WizardRuntime;
}

/** 「使用模板」回包：用户点按钮时才按需生成（见 WizardQuestion.template_on_demand）。 */
export interface TemplateTurn {
  session_id: string;
  /** 可直接填进输入框的文本；模型不可用时是规则底稿 */
  template_text: string;
  /** true = 大模型生成，false = 规则底稿兜底 */
  by_llm: boolean;
  runtime: WizardRuntime;
}
