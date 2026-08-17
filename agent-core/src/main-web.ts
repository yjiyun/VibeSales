/**
 * [Web] HTTP 入口（唯一会 app.listen() 的进程）
 *
 * 与 `main.ts`（CLI，不 listen）平级：
 * - CLI：`npm run cli -- p1-wizard …`
 * - Web：`npm run web`（默认 http://127.0.0.1:3100）
 *
 * 环境变量：
 * - `WEB_PORT`（默认 3100）
 * - `WEB_HOST`（默认 127.0.0.1；所有 API 均要求 Bearer + role + actor）
 * - `WEB_CORS`（默认 on：允许本机 Vite dev server 跨端口联调）
 *
 * 退出前 flush log4js：文件 appender 异步写，直接退进程会丢尾部日志。
 * 文档：docs/工程架构.md §13 Web 层
 */

import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppWebModule } from './app-web.module';
import { LogService } from './common/log.service';
import { TraceService } from './common/trace.service';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppWebModule, {
    logger: ['error', 'warn'],
  });

  const port = Number(process.env.WEB_PORT ?? 3100);
  const host = process.env.WEB_HOST ?? '127.0.0.1';
  if ((process.env.WEB_CORS ?? 'on') !== 'off') {
    // 联调期前端跑在 Vite（5173），与后端不同端口；生产由 ServeStatic 同源托管
    app.enableCors({ origin: true, credentials: false });
  }

  const log = app.get(LogService, { strict: false });
  const trace = app.get(TraceService, { strict: false });

  // 关停信号：先 flush 日志再退出，别丢尾部几行
  let closing = false;
  const close = async (signal: string) => {
    if (closing) return;
    closing = true;
    trace.step('Web.Boot', 'shutdown', { signal });
    await log.shutdown();
    await app.close();
    process.exit(0);
  };
  process.on('SIGINT', () => void close('SIGINT'));
  process.on('SIGTERM', () => void close('SIGTERM'));

  await app.listen(port, host);
  trace.setFlow('web-boot');
  trace.step('Web.Boot', 'listening', {
    url: `http://${host}:${port}`,
    api: `http://${host}:${port}/api/health`,
  });
  // stderr 提示（stdout 留给 CLI 的 JSON 约定，Web 进程同样不写 stdout）
  process.stderr.write(
    `\n[web] 智能体助手已启动：http://${host}:${port}\n` +
      `[web] 健康检查：http://${host}:${port}/api/health\n` +
      `[web] API 已启用凭据绑定的租户鉴权\n\n`,
  );
}

bootstrap().catch((err) => {
  console.error(err);
  process.exit(1);
});
