import { auth,headers,managerToken } from './auth.js';
import { consumeSse } from './sse.js';
async function json(url,options={}){const response=await fetch(url,options),text=await response.text();let data;try{data=text?JSON.parse(text):null;}catch{throw new Error('HTTP '+response.status+' 返回非 JSON');}if(!response.ok)throw new Error(data?.message??data?.error??('HTTP '+response.status));return data;}
const call=(base,path,{method='GET',body,token=auth.wizardToken}={})=>json(base+path,{method,headers:headers(token,body!==undefined),body:body===undefined?undefined:JSON.stringify(body)});
export const wizardApi={health:()=>call('/api','/health'),catalogs:()=>call('/api','/catalogs'),createSession:body=>call('/api','/wizard/sessions',{method:'POST',body}),snapshot:id=>call('/api','/wizard/sessions/'+id),answer:(id,body)=>call('/api','/wizard/sessions/'+id+'/answer',{method:'POST',body}),preview:id=>call('/api','/wizard/sessions/'+id+'/preview',{method:'POST'}),briefTemplate:id=>call('/api','/wizard/sessions/'+id+'/template',{method:'POST'})};
export const managerApi={create:body=>call('/orchestration','/api/v1/orchestrations',{method:'POST',body,token:managerToken()}),get:id=>call('/orchestration','/api/v1/orchestrations/'+id,{token:managerToken()}),room:id=>call('/orchestration','/api/v1/orchestrations/'+id+'/room',{token:managerToken()}),approve:(id,body)=>call('/orchestration','/api/v1/orchestrations/'+id+'/approval',{method:'POST',body,token:managerToken()}),abort:(id,body)=>call('/orchestration','/api/v1/orchestrations/'+id+'/abort',{method:'POST',body,token:managerToken()}),nudge:id=>call('/orchestration','/api/v1/orchestrations/'+id+'/nudge',{method:'POST',body:{},token:managerToken()}),health:()=>call('/orchestration','/api/v1/health',{token:managerToken()})};
export const pipelineApi={start:body=>call('/api','/v1/pipeline/start',{method:'POST',body,token:auth.pipelineToken}),get:id=>call('/api','/v1/pipeline/'+id,{token:auth.pipelineToken}),approve:(id,body)=>call('/api','/v1/pipeline/'+id+'/approval',{method:'POST',body,token:auth.pipelineToken}),abort:(id,body)=>call('/api','/v1/pipeline/'+id+'/abort',{method:'POST',body,token:auth.pipelineToken})};
export async function managerEvents(id,onEvent,signal){const response=await fetch('/orchestration/api/v1/orchestrations/'+id+'/events',{headers:headers(managerToken()),signal});if(!response.ok)throw new Error('manager SSE HTTP '+response.status);await consumeSse(response,onEvent);}
export async function runtimeChat(query,message,onEvent){const params=new URLSearchParams(query),response=await fetch('/runtime/api/v1/chat?'+params,{method:'POST',headers:{authorization:'Bearer '+auth.runtimeToken,'content-type':'text/plain'},body:message});if(!response.ok)throw new Error('runtime HTTP '+response.status);await consumeSse(response,onEvent);}
export async function runtimeInspect(query){
  const params=new URLSearchParams(query);
  const response=await fetch('/runtime/api/v1/inspect?'+params,{
    headers:{
      authorization:'Bearer '+auth.runtimeAdminToken,
      'x-role':'admin',
      'x-actor':auth.actor,
    },
  });
  const text=await response.text();
  let data; try{data=text?JSON.parse(text):null;}catch{throw new Error('runtime inspect 返回非 JSON');}
  if(!response.ok)throw new Error(data?.error??data?.message??('runtime inspect HTTP '+response.status));
  return data;
}
