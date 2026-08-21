/**
 * QwenService — 千问（DashScope OpenAI 兼容）JSON 调用封装
 *
 * ## 文档
 * - `docs/工程架构.md` §1 技术选型与环境变量 · §12 日志与可观测
 *
 * ## 在整条链路中的位置
 * ```text
 * IntentService          → chatJson(分诊提示词) → triage
 * WizardLlmReceptionist  → chatJson(接待员提示词) → 话术/出题
 * DecideService          → chatJson(选型提示词) → template_id（须再校验 ∈ topk）
 * ```
 *
 * 本类是**唯一的大模型出口**，因此也是 token 记账的唯一入口：
 * 每次调用都会 `tokens.record(resp.usage, meta)` → `logs/token.log`。
 * 调用方通过 `meta.scope/purpose` 标明自己是哪个节点，便于按节点统计用量。
 *
 * 不引入 LangChain；直调 openai SDK + QWEN_BASE_URL。
 * Key 为空时 hasApiKey()=false，由上层降级（意图报错 / 裁决 rule_fallback）。
 */

import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import OpenAI from 'openai';
import { TokenUsageService } from '../common/token-usage.service';
import { TraceService } from '../common/trace.service';

/** 调用来源标记：写进 token.log，用于"哪个节点吃了多少 token"。 */
export interface ChatJsonMeta {
  /** 调用方模块，如 `P1.Intent` / `P1.WizardLlm` / `P2.Decide` */
  scope?: string;
  /** 调用用途，如 `scene_router` / `echoIndustry` / `template_decide` */
  purpose?: string;
  /** 覆盖默认 QWEN_MODEL；向导接待员按会话下拉传入 */
  model?: string;
}

export function validateQwenProductionGateway(baseUrl:string,consumerToken:string,trueDashScopeKey:string):void {if(!consumerToken||consumerToken.length<16)throw new Error('production Qwen requires QWEN_GATEWAY_TOKEN (Higress consumer token)');if(trueDashScopeKey)throw new Error('production Nest must not receive DASHSCOPE_API_KEY');let url:URL;try{url=new URL(baseUrl);}catch{throw new Error('production QWEN_BASE_URL must be a valid Higress URL');}const host=url.hostname.toLowerCase(),privateHost=host==='localhost'||host==='127.0.0.1'||host==='::1'||host.startsWith('10.')||host.startsWith('192.168.')||/^172\.(1[6-9]|2\d|3[01])\./.test(host)||!host.includes('.'),openAiPath=url.pathname.replace(/\/+$/,'')==='/v1'||url.pathname.includes('compatible');if(!(url.protocol==='https:'||(url.protocol==='http:'&&privateHost))||(!privateHost&&!host.includes('higress'))||!openAiPath)throw new Error('production QWEN_BASE_URL must target a Higress OpenAI-compatible route');}

@Injectable()
export class QwenService {
  private client: OpenAI | null = null;

  constructor(
    private readonly config: ConfigService,
    private readonly trace: TraceService,
    private readonly tokens: TokenUsageService,
  ) {if((process.env.ARTIFACT_STORE??'file').toLowerCase()==='postgres')this.validateProductionGateway();}

  /** 是否已配置 DASHSCOPE_API_KEY（不校验 Key 真伪）。 */
  hasApiKey(): boolean {
    const key = this.apiKey();
    return key.length > 0;
  }

  /** 懒创建 OpenAI 兼容客户端（baseURL 指向 DashScope）。 */
  private getClient(): OpenAI {
    if (!this.hasApiKey()) {
      throw new Error('DASHSCOPE_API_KEY is empty');
    }
    if (!this.client) {
      const apiKey = this.apiKey();
      const baseURL =
        this.config.get<string>('QWEN_BASE_URL') ??
        'https://dashscope.aliyuncs.com/compatible-mode/v1';
      this.client = new OpenAI({ apiKey, baseURL });
      this.trace.step('Qwen', 'client.init', {
        baseURL,
        model: this.config.get<string>('QWEN_MODEL') ?? 'deepseek-v4-flash',
        api_key_len: apiKey.length,
      });
    }
    return this.client;
  }

  private apiKey():string{return (this.config.get<string>('QWEN_GATEWAY_TOKEN')??this.config.get<string>('DASHSCOPE_API_KEY')??'').trim();}
  private validateProductionGateway():void{validateQwenProductionGateway((this.config.get<string>('QWEN_BASE_URL')??'').trim(),(this.config.get<string>('QWEN_GATEWAY_TOKEN')??'').trim(),(this.config.get<string>('DASHSCOPE_API_KEY')??'').trim());}

  /**
   * 以 json_object 模式调用聊天补全，解析为对象。
   * 超时由 QWEN_TIMEOUT_MS 控制；失败向上抛，由 Intent/Decide 决定降级策略。
   *
   * @param meta 调用来源（scope/purpose），用于 token 记账分组
   */
  async chatJson(
    system: string,
    user: string,
    meta: ChatJsonMeta = {},
  ): Promise<unknown> {
    const model =
      (meta.model ?? '').trim() ||
      this.config.get<string>('QWEN_MODEL') ||
      'deepseek-v4-flash';
    const timeoutMs = Number(
      this.config.get<string>('QWEN_TIMEOUT_MS') ?? 60000,
    );
    const scope = meta.scope ?? 'unknown';
    const purpose = meta.purpose ?? 'unknown';

    const started = Date.now();
    this.trace.step('Qwen', 'chatJson.start', {
      scope,
      purpose,
      model,
      timeoutMs,
      system_chars: system.length,
      user_chars: user.length,
    });
    // 提示词正文：只有 verbose 档位的 sink 才输出（LOG_FILE=verbose / LOG_STDERR=verbose）
    this.trace.detail('Qwen', 'chatJson.prompt', {
      scope,
      purpose,
      system,
      user,
    });

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const resp = await this.getClient().chat.completions.create(
        {
          model,
          temperature: 0.2,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user },
          ],
          response_format: { type: 'json_object' },
        },
        { signal: controller.signal },
      );
      const content = resp.choices[0]?.message?.content ?? '';
      const ms = Date.now() - started;
      // token 记账（唯一入口）：先记账再解析，解析失败也不丢用量
      this.tokens.record(resp.usage, { scope, purpose, model, ms });
      const parsed = this.safeParseJson(content);
      this.trace.step('Qwen', 'chatJson.done', {
        scope,
        purpose,
        ms,
        content_chars: content.length,
        usage: resp.usage,
        tokens_running_total: this.tokens.totals().total_tokens,
        parsed,
      });
      return parsed;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      const ms = Date.now() - started;
      this.tokens.recordError(msg, { scope, purpose, model, ms });
      this.trace.step('Qwen', 'chatJson.error', {
        scope,
        purpose,
        ms,
        error: msg,
      });
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  /** 解析模型输出：优先整段 JSON，失败则抽取首个 `{…}` 块。 */
  safeParseJson(raw: string): unknown {
    const text = raw.trim();
    try {
      return JSON.parse(text);
    } catch {
      const m = text.match(/\{[\s\S]*\}/);
      if (!m) throw new Error(`Qwen returned non-JSON: ${text.slice(0, 200)}`);
      return JSON.parse(m[0]);
    }
  }
}
