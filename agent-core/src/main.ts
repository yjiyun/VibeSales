/**
 * [全阶段] CLI 入口（不 HTTP listen）
 *
 * nest-commander → AppModule：
 * - P1：`p1` / `p1-wizard`（别名 `wizard`）
 * - P2：`match`（别名 `p2-match`）
 *
 * 启动：`npm run cli -- p1 …` / `npm run cli -- match …`
 * 文档：docs/产品设计.md · docs/工程架构.md §10 CLI
 */

import 'reflect-metadata';
import { CommandFactory } from 'nest-commander';
import { AppModule } from './app.module';
import { LogService } from './common/log.service';

/**
 * 用 runWithoutClosing 拿到容器，命令跑完后 flush 日志再关闭：
 * log4js 的文件 appender 是异步写，直接 exit 可能丢尾部几行。
 */
async function bootstrap(): Promise<void> {
  const app = await CommandFactory.runWithoutClosing(AppModule, {
    logger: ['error', 'warn'],
  });
  try {
    await app.get(LogService, { strict: false }).shutdown();
  } finally {
    await app.close();
  }
}

bootstrap()
  .then(() => process.exit(process.exitCode ?? 0))
  .catch(async (err) => {
    console.error(err);
    process.exit(1);
  });
