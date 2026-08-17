/**
 * [Web] WizardController — 向导 HTTP 接口
 *
 * 文档：`docs/工程架构.md` §13 Web 层
 *
 * ```text
 * GET  /api/health                        健康检查 + LLM/日志开关现状
 * GET  /api/catalogs                      词表（行业分组 / 业务目标），供前端首屏渲染
 * POST /api/wizard/sessions               新建会话 → 第一问
 * GET  /api/wizard/sessions/:id           读当前状态（刷新页面重放用）
 * POST /api/wizard/sessions/:id/answer    回答一问 → 推进一格
 * POST /api/wizard/sessions/:id/preview   向导完成后按需生成 P2 预览
 * POST /api/wizard/sessions/:id/template  点「使用模板」时按需生成业务简述模板
 * ```
 *
 * 本控制器只做参数校验与转发；会话推进与组包在
 * {@link WizardSessionService}，业务裁决仍在 P1/P2 服务内（C3/C11）。
 *
 * 所有端点都要求 Bearer / X-Role / X-Actor；client_code 只从服务端凭据映射取得，
 * 请求体同名字段会被覆盖，会话读取也会再次校验租户边界。
 */

import { Body, Controller, Get, Headers, HttpException, Param, Post } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { CatalogsService } from '../catalogs/catalogs.service';
import { beijingTimestamp } from '../common/time';
import { DEFAULT_WIZARD_LLM_MODEL, WIZARD_LLM_MODELS } from '../common/wizard-models';
import { QwenService } from '../qwen/qwen.service';
import { AnswerBody, CreateSessionBody, PreviewTurn, TemplateTurn, WizardTurn } from './web.types';
import { WizardSessionService } from './wizard-session.service';
import { WebAuthService, WebPrincipal } from './web-auth.service';
import { artifactInspectorEnabled } from '../common/artifact-inspector';

@Controller('api')
export class WizardController {
  constructor(
    private readonly sessions: WizardSessionService,
    private readonly catalogs: CatalogsService,
    private readonly qwen: QwenService,
    private readonly config: ConfigService,
    private readonly auth: WebAuthService,
  ) {}

  @Get('health')
  health(@Headers() headers: Record<string,string|string[]|undefined>) {
    const principal = this.auth.require(headers);
    return {
      ok: true,
      llm_available: this.qwen.hasApiKey(),
      model: this.config.get<string>('QWEN_MODEL') ?? DEFAULT_WIZARD_LLM_MODEL,
      models: [...WIZARD_LLM_MODELS],
      default_model: DEFAULT_WIZARD_LLM_MODEL,
      client_code: principal.clientCode,
      log_stderr: this.config.get<string>('LOG_STDERR') ?? null,
      log_file: this.config.get<string>('LOG_FILE') ?? null,
      artifact_inspector: artifactInspectorEnabled(),
      ts: beijingTimestamp(),
    };
  }

  /** 前端首屏拿词表（行业分组、业务目标）；选项一律以服务端词表为准。 */
  @Get('catalogs')
  catalogsPayload(@Headers() headers: Record<string,string|string[]|undefined>) {
    this.auth.require(headers);
    return {
      industries: this.catalogs.industriesGrouped(),
      business_goals: this.catalogs.optionsFor('business_goals'),
    };
  }

  @Post('wizard/sessions')
  create(@Headers() headers:Record<string,string|string[]|undefined>,@Body() body: CreateSessionBody): Promise<WizardTurn> {
    const principal=this.auth.require(headers);
    return this.sessions.create({...body,client_code:principal.clientCode});
  }

  @Get('wizard/sessions/:id')
  async snapshot(@Headers() headers:Record<string,string|string[]|undefined>,@Param('id') id: string): Promise<WizardTurn> {
    return this.requireTenant(await this.sessions.snapshot(id),this.auth.require(headers));
  }

  @Post('wizard/sessions/:id/answer')
  async answer(
    @Headers() headers:Record<string,string|string[]|undefined>,
    @Param('id') id: string,
    @Body() body: AnswerBody,
  ): Promise<WizardTurn> {
    const principal=this.auth.require(headers);await this.requireSession(id,principal);return this.requireTenant(await this.sessions.answer(id,body??{}),principal);
  }

  @Post('wizard/sessions/:id/preview')
  async preview(@Headers() headers:Record<string,string|string[]|undefined>,@Param('id') id: string): Promise<PreviewTurn> {
    const principal=this.auth.require(headers);await this.requireSession(id,principal);return this.requireTenant(await this.sessions.preview(id),principal);
  }

  /** 用户点「使用模板」时才调：现场生成业务简述模板文本。 */
  @Post('wizard/sessions/:id/template')
  async template(@Headers() headers:Record<string,string|string[]|undefined>,@Param('id') id: string): Promise<TemplateTurn> {
    const principal=this.auth.require(headers);await this.requireSession(id,principal);return this.sessions.briefTemplate(id);
  }
  private async requireSession(id:string,principal:WebPrincipal){this.requireTenant(await this.sessions.snapshot(id),principal);}
  private requireTenant<T extends {client_code:string}>(value:T,principal:WebPrincipal):T{if(value.client_code!==principal.clientCode)throw new HttpException('session not found',404);return value;}
}
