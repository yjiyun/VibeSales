/**
 * FlowContextService — 「一次流程」的运行时上下文（CLI 一次命令 / Web 一次请求）
 *
 * ## 文档
 * - `docs/工程架构.md` §12 日志与可观测 · §13 Web 层
 *
 * ## 为什么需要
 * CLI 时代 flow / 序号 / 计时 / token 记账都挂在单例上——进程里只有一条流程，够用。
 * 加了 HTTP 之后同一进程会有并发请求，单例状态会互相串号（A 的 token 记到 B 的汇总里）。
 * 本服务用 `AsyncLocalStorage` 把这些状态收进「当前流程」，让 Log / Trace / TokenUsage
 * 三个 Provider 都只读写 `flows.current()`：
 *
 * ```text
 * CLI      main.ts ─────────────────────────► 无 ALS store → 用 root 状态（行为同以前）
 * Web      FlowContextMiddleware.run(...) ──► 每请求一个 FlowState（flow/seq/计时/token 隔离）
 * ```
 *
 * Web 侧额外开 `collectEvents`：`trace.step` 除了 stderr + app.log，还把事件存进
 * `state.events`，由 Controller 随回包返回，前端「运行面板」即可看到本次请求在向导内部
 * 走过哪些模块与位置。
 */

import { Injectable } from '@nestjs/common';
import { AsyncLocalStorage } from 'async_hooks';

/** 日志级别（与 LogService 一致）。 */
export type FlowLevel = 'debug' | 'info' | 'warn' | 'error';

/**
 * 一条链路事件（`trace.step` 的结构化形态）。
 * 与 `logs/app.log` 的一行同源，供 Web 回包给前端渲染。
 */
export interface FlowEvent {
  /** 流程内自增序号（与 app.log 的 `#n` 一致） */
  seq: number;
  /** 北京时间 `2026-07-29 13:43:16.006`（口径见 `common/time.ts`） */
  ts: string;
  level: FlowLevel;
  /** 模块，如 `P1.Wizard` / `Qwen` */
  scope: string;
  /** 位置，如 `S3_brief.done` / `chatJson.error` */
  event: string;
  /** 距上一条 */
  delta_ms: number;
  /** 段内累计 */
  total_ms: number;
  /** 显式耗时（LLM / IO） */
  ms?: number;
  data?: unknown;
}

/** token 计数桶。 */
export interface TokenBucket {
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

/** 单次大模型调用的归一化记录。 */
export interface TokenCallRecord {
  scope: string;
  purpose: string;
  model: string;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  ms?: number;
  usage_missing?: boolean;
}

/** 一次流程内的 token 记账状态（由 TokenUsageService 读写）。 */
export interface FlowTokenState {
  total: TokenBucket;
  /** key = `scope/purpose` */
  byNode: Map<string, TokenBucket>;
  byModel: Map<string, TokenBucket>;
  records: TokenCallRecord[];
  errors: number;
  usageMissing: number;
  llmMs: number;
}

/** 一次流程的全部可观测状态。 */
export interface FlowState {
  /** 流程名：`p1` / `p1-wizard` / `p2-match` / `web` */
  flow: string;
  request_id: string;
  /** 打点自增序号 */
  seq: number;
  /** 当前段落起点（banner 重置） */
  sectionStart: number;
  /** 上一条 step 时间戳 */
  lastStepAt: number;
  tokens: FlowTokenState;
  /** 采集到的事件；仅 Web 层开启（CLI 为 undefined，零开销） */
  events?: FlowEvent[];
  /** events 上限，超出后丢弃并计数 */
  eventsLimit: number;
  eventsDropped: number;
  /**
   * 本流程是否允许调大模型；Web 按会话传入，避免并发请求互相改单例开关。
   * undefined = 用 Provider 自身默认。
   */
  preferLlm?: boolean;
  /** 向导接待员本回合使用的模型 id（会话下拉）。 */
  preferModel?: string;
  /** 自动附着到本流程每条 trace/usage 的关联字段（如 run_id/client_code）。 */
  correlation: Record<string, string>;
}

export interface FlowInit {
  flow: string;
  request_id?: string;
  collectEvents?: boolean;
  eventsLimit?: number;
  preferLlm?: boolean;
  preferModel?: string;
}

export function emptyTokenBucket(): TokenBucket {
  return { calls: 0, prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 };
}

function emptyTokenState(): FlowTokenState {
  return {
    total: emptyTokenBucket(),
    byNode: new Map(),
    byModel: new Map(),
    records: [],
    errors: 0,
    usageMissing: 0,
    llmMs: 0,
  };
}

/** 新建流程状态；`collectEvents` 才分配事件数组。 */
export function createFlowState(init: FlowInit): FlowState {
  return {
    flow: init.flow || 'unknown',
    request_id: (init.request_id ?? '').trim() || '-',
    seq: 0,
    sectionStart: 0,
    lastStepAt: 0,
    tokens: emptyTokenState(),
    events: init.collectEvents ? [] : undefined,
    eventsLimit: init.eventsLimit ?? 500,
    eventsDropped: 0,
    preferLlm: init.preferLlm,
    preferModel: init.preferModel,
    correlation: {},
  };
}

@Injectable()
export class FlowContextService {
  private readonly als = new AsyncLocalStorage<FlowState>();
  /** 无 ALS store 时的兜底状态：CLI 全程用它，Nest 启动期打点记为 flow=boot */
  private readonly root = createFlowState({ flow: 'boot' });

  /** 当前流程状态；不在任何 run() 内时返回 root。 */
  current(): FlowState {
    return this.als.getStore() ?? this.root;
  }

  /** 是否处于显式流程作用域（Web 请求内 true，CLI false）。 */
  isScoped(): boolean {
    return this.als.getStore() !== undefined;
  }

  /**
   * 在新流程作用域内执行；返回值原样透传（同步/异步皆可）。
   * 作用域内注册的回调（如 `res.on('finish')`）仍能读到同一状态。
   */
  run<T>(init: FlowInit, fn: (state: FlowState) => T): T {
    const state = createFlowState(init);
    return this.als.run(state, () => fn(state));
  }

  /** 重置流程名与序号（CLI 命令入口 `log.setFlow` 走这里）。 */
  reset(flow: string, requestId?: string): FlowState {
    const state = this.current();
    state.flow = flow || 'unknown';
    state.request_id = (requestId ?? '').trim() || '-';
    state.seq = 0;
    state.sectionStart = 0;
    state.lastStepAt = 0;
    return state;
  }

  /**
   * 取下一个流程内序号。
   * 无条件自增（与日志级别、开关无关），保证 app.log 的 `#n` 与 Web 事件序号同源。
   */
  nextSeq(state: FlowState = this.current()): number {
    state.seq += 1;
    return state.seq;
  }

  /** 追加一条事件（超过上限只计数不再存，避免长会话吃内存）。 */
  pushEvent(state: FlowState, ev: FlowEvent): void {
    if (!state.events) return;
    if (state.events.length >= state.eventsLimit) {
      state.eventsDropped += 1;
      return;
    }
    state.events.push(ev);
  }

  /** 取本流程已采集事件（副本）。 */
  events(): FlowEvent[] {
    return [...(this.current().events ?? [])];
  }

  bindCorrelation(values: Record<string, string | undefined>): void {
    const state = this.current();
    for (const [key, value] of Object.entries(values)) {
      if (value?.trim()) state.correlation[key] = value.trim();
    }
  }
}
