import { Controller, Get, HttpException } from '@nestjs/common';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';

@Controller()
export class McpHealthController {
  constructor(private readonly store:ArtifactStoreService){}
  @Get('healthz')
  async health() {
    const artifactStore=(process.env.ARTIFACT_STORE??'file').toLowerCase(),production=artifactStore==='postgres';
    const storage=await this.store.health();
    const configured={database:Boolean(process.env.DATABASE_URL?.trim()),blob:Boolean(process.env.MINIO_ENDPOINT?.trim()&&process.env.MINIO_BUCKET?.trim()),mcpAuth:Boolean(process.env.MCP_SERVER_TOKEN?.trim()),approvalSigning:Boolean(process.env.PIPELINE_APPROVAL_SIGNING_SECRET?.trim()),runtime:Boolean(process.env.AGENT_RUNTIME_URL?.trim()&&process.env.AGENT_RUNTIME_TOKEN?.trim())};
    let runtime=!production;
    if(production&&configured.runtime){try{const response=await fetch(new URL('/healthz',process.env.AGENT_RUNTIME_URL),{signal:AbortSignal.timeout(2500)});runtime=response.ok;}catch{runtime=false;}}
    const dependencies={database:storage.database,blob:storage.blob,runtime};
    const ok=!production||(Object.values(configured).every(Boolean)&&Object.values(dependencies).every(Boolean));
    if(!ok)throw new HttpException({ok:false,service:'chatflows-mcp',artifact_store:artifactStore,configured,dependencies},503);
    return{ok:true,service:'chatflows-mcp',artifact_store:artifactStore,configured,dependencies};
  }
}
