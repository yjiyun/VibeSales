import { Injectable } from '@nestjs/common';
import { createHash } from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { FlowPackage, FlowPackageCodec } from '../common/flow-package';
import { BuildPath } from '../common/types';

export interface FlowImportResult { kind:'workflow'; external_id:string; status:'IMPORTED'; source:BuildPath; receipt:unknown }

/** Frozen YunFlow /api/workflow/import_zip boundary. */
@Injectable()
export class FlowPlatformClient {
  async import(runId:string,clientCode:string,source:BuildPath,payload:unknown):Promise<FlowImportResult>{
    const pkg=FlowPackageCodec.normalize(payload as FlowPackage),check=FlowPackageCodec.selfcheck(pkg);if(!check.ok)throw new Error('invalid flow package: '+check.checks.filter(c=>!c.ok).map(c=>'#'+c.id).join(','));
    const zip=FlowPackageCodec.zip(pkg),mode=(process.env.FLOW_PLATFORM_MODE??'local').toLowerCase();
    if(mode==='local')return this.localImport(runId,clientCode,source,pkg,zip);
    if(mode!=='production')throw new Error('FLOW_PLATFORM_MODE must be local or production');
    const base=process.env.YUNFLOW_BASE_URL?.trim(),token=process.env.YUNFLOW_TOKEN?.trim();if(!base||!token)throw new Error('YUNFLOW_BASE_URL/YUNFLOW_TOKEN required in production mode');
    const form=new FormData();form.append('format','coze');const space=process.env.YUNFLOW_SPACE_ID?.trim();if(space)form.append('space_id',space);form.append('file',new Blob([zip as BlobPart],{type:'application/zip'}),'agentteams-'+runId+'.zip');
    const response=await fetch(new URL('/api/workflow/import_zip',base),{method:'POST',headers:{authorization:'Bearer '+token},body:form});const text=await response.text();if(!response.ok)throw new Error('YunFlow import failed: HTTP '+response.status+' '+text.slice(0,300));
    const receipt=JSON.parse(text) as Record<string,unknown>,workflowId=String(receipt.workflow_id??'');if(!workflowId)throw new Error('YunFlow import response missing workflow_id');
    return {kind:'workflow',external_id:workflowId,status:'IMPORTED',source,receipt};
  }
  async dryRun(externalId:string,payload:unknown){
    const pkg=FlowPackageCodec.normalize(payload as FlowPackage),mode=(process.env.FLOW_PLATFORM_MODE??'local').toLowerCase();
    if(mode==='local')return {ok:FlowPackageCodec.selfcheck(pkg).ok,response:'DRY_RUN_OK',workflow_id:externalId,mode:'local'};
    if(mode!=='production')throw new Error('FLOW_PLATFORM_MODE must be local or production');
    const base=process.env.YUNFLOW_BASE_URL?.trim(),token=process.env.YUNFLOW_TOKEN?.trim(),template=process.env.YUNFLOW_DRY_RUN_PATH?.trim();if(!base||!token||!template)throw new Error('YUNFLOW_BASE_URL/YUNFLOW_TOKEN/YUNFLOW_DRY_RUN_PATH required in production mode');if(!template.includes('{workflow_id}'))throw new Error('YUNFLOW_DRY_RUN_PATH must contain {workflow_id}');
    const endpoint=template.replace('{workflow_id}',encodeURIComponent(externalId));const response=await fetch(new URL(endpoint,base),{method:'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify({input:{query:'health check'}})});const text=await response.text();if(!response.ok)throw new Error('YunFlow dry-run failed: HTTP '+response.status+' '+text.slice(0,300));return {ok:true,response:JSON.parse(text),workflow_id:externalId,mode:'production'};
  }
  private localImport(runId:string,clientCode:string,source:BuildPath,pkg:FlowPackage,zip:Uint8Array):FlowImportResult{
    const digest=createHash('sha256').update(zip).digest('hex'),workflowId='flow-'+digest.slice(0,16),root=path.resolve(process.env.FLOW_PROJECT_ROOT??'var/flow-projects'),project=path.join(root,workflowId);fs.mkdirSync(path.join(project,'workflow'),{recursive:true});
    fs.writeFileSync(path.join(project,'MANIFEST.yml'),pkg.manifestYaml);fs.writeFileSync(path.join(project,'workflow',pkg.workflowFile),pkg.workflowYaml);fs.writeFileSync(path.join(project,'package.zip'),zip);fs.writeFileSync(path.join(project,'receipt.json'),JSON.stringify({workflow_id:workflowId,run_id:runId,client_code:clientCode,source,sha256:digest},null,2));
    const decoded=FlowPackageCodec.unzip(fs.readFileSync(path.join(project,'package.zip')));if(decoded.workflowYaml!==pkg.workflowYaml)throw new Error('local zip verification failed');
    return {kind:'workflow',external_id:workflowId,status:'IMPORTED',source,receipt:{sha256:digest,package:path.join(project,'package.zip')}};
  }
}
