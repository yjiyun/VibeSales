/**
 * [阶段标注] 产品阶段常量（docs/产品设计.md §2）
 *
 * P1 向导+意图 → PASS Triage
 * P2 模板匹配 → MatchResult（+ 可选 v0）
 * P3/P3b/P3C 为三条条件分支，最终统一进入 P4。
 *
 * 文件/类/CLI 注释尽量引用本枚举，避免「向导里夹匹配」概念糊掉。
 */

/** 产品开发阶段。 */
export enum ProductPhase {
  /** 向导粗补 + 意图过闸 → Phase1Result */
  P1_WIZARD_INTENT = 'P1',
  /** 硬过滤 → 排序 → 裁决 → MatchResult */
  P2_TEMPLATE_MATCH = 'P2',
  /** 命中模板后的标注驱动个性化 */
  P3_TEMPLATE_PERSONALIZE = 'P3',
  /** 低匹配、固定 DAG 场景的工作流生成 */
  P3B_FLOW_GENERATE = 'P3B',
  /** 多轮工具/长期记忆/Skill 演进场景的 Blueprint 装配 */
  P3C_BLUEPRINT_COMPOSE = 'P3C',
  /** 导入、绑定与试运行 */
  P4_IMPORT_RUN = 'P4',
}

/** P1 粗补达标（可进入下一阶段）的置信度阈值。docs/P1-拆分.md §3 */
export const P1_COARSE_READY_THRESHOLD = 0.7;

/**
 * @deprecated 兼容旧名；语义等同 P1_COARSE_READY_THRESHOLD（粗补达标，非真出 v0）。
 * v0 预览属 P2 命中之后，见 PreviewService。
 */
export const V0_CONFIDENCE_THRESHOLD = P1_COARSE_READY_THRESHOLD;

export const PHASE_LABEL: Record<ProductPhase, string> = {
  [ProductPhase.P1_WIZARD_INTENT]: 'P1 向导+意图',
  [ProductPhase.P2_TEMPLATE_MATCH]: 'P2 模板匹配',
  [ProductPhase.P3_TEMPLATE_PERSONALIZE]: 'P3 模板个性化',
  [ProductPhase.P3B_FLOW_GENERATE]: 'P3b 工作流生成',
  [ProductPhase.P3C_BLUEPRINT_COMPOSE]: 'P3C Blueprint 装配',
  [ProductPhase.P4_IMPORT_RUN]: 'P4 导入与试运行',
};
