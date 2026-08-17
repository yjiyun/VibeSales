import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppMcpModule } from './app-mcp.module';
import { LogService } from './common/log.service';

async function bootstrap(){
 const app=await NestFactory.create(AppMcpModule,{logger:['error','warn']});const port=Number(process.env.WEB_PORT??3100),host=process.env.WEB_HOST??'127.0.0.1';
 let closing=false;const close=async()=>{if(closing)return;closing=true;await app.get(LogService,{strict:false}).shutdown();await app.close();process.exit(0);};process.on('SIGINT',()=>void close());process.on('SIGTERM',()=>void close());
 await app.listen(port,host);process.stderr.write('[mcp] chatflows tool plane listening on '+host+':'+port+'\n');
}
bootstrap().catch(error=>{console.error(error);process.exit(1);});
