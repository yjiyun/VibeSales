/**
 * TraceModule — 全局可注入的可观测四件套
 *
 * - {@link FlowContextService}  「一次流程」的运行时上下文（CLI 命令 / Web 请求）
 * - {@link TraceService}       链路打点（stderr）+ 分发到文件日志 + Web 事件采集
 * - {@link LogService}         log4js 文本日志（logs/app.log、logs/token.log）
 * - {@link TokenUsageService}  大模型 token 记账与本次流程汇总
 * - {@link TokenLedgerService} 工程累计台账（logs/token-total.json，跨进程跨重启）
 *
 * 依赖方向：Trace → Log → FlowContext，TokenUsage → Log / FlowContext / TokenLedger。
 *
 * @Global：Catalogs / Templates / Qwen / Intent / Match / Wizard / CLI / Web 均可直接注入，
 * 无需重复 import。
 */

import { Global, Module } from '@nestjs/common';
import { FlowContextService } from './flow-context';
import { LogService } from './log.service';
import { TokenLedgerService } from './token-ledger.service';
import { TokenUsageService } from './token-usage.service';
import { TraceService } from './trace.service';

@Global()
@Module({
  providers: [
    FlowContextService,
    LogService,
    TokenLedgerService,
    TokenUsageService,
    TraceService,
  ],
  exports: [
    FlowContextService,
    LogService,
    TokenLedgerService,
    TokenUsageService,
    TraceService,
  ],
})
export class TraceModule {}
