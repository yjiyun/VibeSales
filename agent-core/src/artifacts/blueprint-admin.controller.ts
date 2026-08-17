import { Body, Controller, Headers, HttpException, Post, Query } from '@nestjs/common';
import { timingSafeEqual } from 'crypto';
import { ArtifactStoreService } from './artifact-store.service';

@Controller('api/v1/blueprints')
export class BlueprintAdminController {
  constructor(private readonly store: ArtifactStoreService) {}

  @Post('publish')
  async publish(@Headers() headers: Record<string,string|undefined>, @Query('blueprintId') blueprintId: string, @Query('clientCode') clientCode: string) {
    const actor = this.authorize(headers);
    const bp = await this.store.publishBlueprint(this.required(blueprintId,'blueprintId'), this.required(clientCode,'clientCode'),actor);
    return { blueprintId: bp.blueprint_id, version: bp.version, status: bp.status };
  }

  @Post('rollback')
  async rollback(@Headers() headers: Record<string,string|undefined>, @Query('clientCode') clientCode: string, @Query('runtimeAgentId') runtimeAgentId: string, @Query('version') versionRaw: string) {
    const actor = this.authorize(headers);
    const version = Number(versionRaw); if (!Number.isInteger(version) || version < 1) throw new HttpException('version must be positive integer',400);
    const bp = await this.store.rollbackBlueprint(this.required(clientCode,'clientCode'), this.required(runtimeAgentId,'runtimeAgentId'), version,actor);
    return { blueprintId: bp.blueprint_id, version: bp.version, status: bp.status };
  }

  private authorize(headers:Record<string,string|undefined>):string {
    const expected=process.env.BLUEPRINT_ADMIN_TOKEN?.trim();if(!expected)throw new HttpException('admin endpoint disabled',503);
    const supplied=(headers.authorization??'').replace(/^Bearer\s+/i,'');
    const a=Buffer.from(expected),b=Buffer.from(supplied);if(a.length!==b.length||!timingSafeEqual(a,b))throw new HttpException('unauthorized',401);
    if(headers['x-role']!=='admin')throw new HttpException('admin role required',403);
    return this.required(headers['x-actor'],'X-Actor');
  }
  private required(value:string|undefined,name:string){if(!value?.trim())throw new HttpException(name+' required',400);return value.trim();}
}
