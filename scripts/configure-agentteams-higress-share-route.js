#!/usr/bin/env node
'use strict';
const fs=require('fs'),{execFileSync}=require('child_process');
const envFile=process.argv[2];if(!envFile)throw new Error('usage: configure-agentteams-higress.js <integration.env>');
const parse=file=>Object.fromEntries(fs.readFileSync(file,'utf8').split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!line.startsWith('#')&&line.includes('=')).map(line=>{const at=line.indexOf('=');return[line.slice(0,at),line.slice(at+1)];}));
const values=parse(envFile),required=key=>{const value=values[key]?.trim();if(!value)throw new Error(key+' required');return value;};
const inspect=name=>{const raw=execFileSync('docker',['inspect','--format','{{json .Config.Env}}',name],{encoding:'utf8',stdio:['ignore','pipe','pipe']});return Object.fromEntries(JSON.parse(raw).map(line=>{const at=line.indexOf('=');return[line.slice(0,at),line.slice(at+1)];}));};
const controller=inspect('agentteams-controller'),admin=controller.AGENTTEAMS_ADMIN_USER,password=controller.AGENTTEAMS_ADMIN_PASSWORD;if(!admin||!password)throw new Error('Higress admin credentials unavailable');
const base='http://127.0.0.1:18001',servers=['chatflows-p1','chatflows-p2','chatflows-p3','chatflows-p3b','chatflows-p3c','chatflows-p4'];
const sharedConsumer=(values.HIGRESS_CHATFLOWS_CONSUMER_NAME?.trim()||'chatflows-mcp-local');
const redact=text=>{let out=text;for(const key of ['MCP_SERVER_TOKEN','HIGRESS_CONSUMER_TOKEN']){const secret=values[key]?.trim();if(secret)out=out.split(secret).join('[REDACTED]');}return out;};
const request=async(path,init={})=>{const response=await fetch(base+path,init),text=await response.text();if(!response.ok)throw new Error(path+' HTTP '+response.status+(text?' '+redact(text).slice(0,500):''));return text?JSON.parse(text):{};};
const patchIngress=routeName=>{const url='https://localhost:18443/apis/networking.k8s.io/v1/namespaces/higress-system/ingresses/'+encodeURIComponent(routeName);try{const raw=execFileSync('docker',['exec','agentteams-controller','curl','-skS','--fail-with-body',url],{encoding:'utf8',stdio:['ignore','pipe','pipe']}),ingress=JSON.parse(raw);ingress.metadata.annotations={...(ingress.metadata.annotations??{}),'higress.io/enable-header-control':'true','higress.io/request-header-control-update':'Authorization Bearer '+required('MCP_SERVER_TOKEN')};execFileSync('docker',['exec','-i','agentteams-controller','curl','-skS','--fail-with-body','-X','PUT','-H','Content-Type: application/json','--data-binary','@-',url],{input:JSON.stringify(ingress),encoding:'utf8',stdio:['pipe','pipe','pipe']});}catch(error){throw new Error(routeName+' Ingress header-control update failed: '+redact(String(error.stderr??error.message??'')).slice(0,500));}};
const curlQuote=value=>'"'+String(value).replace(/(["\\])/g,'\\$1').replace(/\r/g,'\\r').replace(/\n/g,'\\n')+'"';
const gatewayRequest=(url,token)=>{const config=['silent','show-error','request = "POST"','header = "Content-Type: application/json"','header = "Accept: application/json, text/event-stream"',...(token?['header = '+curlQuote('Authorization: Bearer '+token)]:[]),'data = '+curlQuote(JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list'})),'write-out = "\\n%{http_code}"','url = '+curlQuote(url)].join('\n')+'\n';try{const raw=execFileSync('docker',['exec','-i','agentteams-controller','curl','--config','-'],{input:config,encoding:'utf8',stdio:['pipe','pipe','pipe']}),at=raw.lastIndexOf('\n');if(at<0)throw new Error('missing HTTP status');return{status:Number(raw.slice(at+1)),text:raw.slice(0,at)};}catch(error){throw new Error('gateway probe failed: '+redact(String(error.stderr??error.message??'')).slice(0,500));}};
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function main(){
 const login=await fetch(base+'/session/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:admin,password,autoLogin:false})});if(!login.ok)throw new Error('Higress login HTTP '+login.status);const cookie=login.headers.get('set-cookie');if(!cookie)throw new Error('Higress login cookie missing');const headers={cookie,accept:'application/json'},jsonHeaders={...headers,'content-type':'application/json'};
 const consumerReply=await request('/v1/consumers',{headers}),consumers=new Set((consumerReply.data??[]).map(x=>x.name));if(!consumers.has(sharedConsumer))throw new Error('Higress shared consumer missing: '+sharedConsumer);
 for(const server of servers){
  const mcpReply=await request('/v1/mcpServer/'+server,{headers}),mcp=mcpReply.data;if(!mcp)throw new Error(server+' MCP route missing');mcp.consumerAuthInfo={enable:true,type:'key-auth',strategyConfigId:null,allowedConsumers:[sharedConsumer]};
  await request('/v1/mcpServer',{method:'PUT',headers:jsonHeaders,body:JSON.stringify(mcp)});
  const routeName='mcp-server-'+server+'.internal',routeReply=await request('/v1/routes/'+routeName,{headers});if(!routeReply.data)throw new Error(routeName+' generated route missing');patchIngress(routeName);
 }
 const gateway=required('CHATFLOWS_MCP_BASE_URL').replace(/\/$/,'');
 for(const server of servers){
  const url=gateway+'/mcp-servers/'+server;let lastError;for(let attempt=0;attempt<20;attempt++){const denied=gatewayRequest(url),accepted=gatewayRequest(url,required('HIGRESS_CONSUMER_TOKEN'));try{const body=JSON.parse(accepted.text);if((denied.status===401||denied.status===403)&&accepted.status>=200&&accepted.status<300&&Array.isArray(body.result?.tools)&&body.result.tools.length>0){lastError=null;break;}lastError=server+' auth probe denied='+denied.status+' accepted='+accepted.status;}catch{lastError=server+' returned non-JSON, accepted='+accepted.status;}await wait(1000);}if(lastError)throw new Error(lastError);
 }
 process.stdout.write('[PASS] Higress enforces shared-consumer MCP auth and injects a separate Nest upstream token for 6 routes\n');
}
main().catch(error=>{process.stderr.write('[FAIL] '+(error instanceof Error?error.message:String(error))+'\n');process.exit(1);});
