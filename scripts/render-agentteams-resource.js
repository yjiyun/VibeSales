#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path');
const [source,target,root,baseUrl,mcpToken]=process.argv.slice(2);
const privateHost=host=>host==='localhost'||host==='127.0.0.1'||host==='::1'||/^10\./.test(host)||/^192\.168\./.test(host)||/^172\.(1[6-9]|2\d|3[01])\./.test(host)||!host.includes('.');
let gateway;try{gateway=new URL(baseUrl);}catch{throw new Error('gateway origin invalid');}
if(!source||!target||!root||gateway.pathname!=='/'||gateway.search||gateway.hash||!(gateway.protocol==='https:'||(gateway.protocol==='http:'&&privateHost(gateway.hostname))))throw new Error('gateway must be an HTTPS origin or private-network HTTP origin');
let yaml=fs.readFileSync(source,'utf8').replaceAll('https://higress.example',baseUrl);
// qwenpaw_worker 的 update.py:_apply_mcp_servers 只在 CR 的 mcpServers[].headers 缺
// Authorization 时才兜底用容器 env AGENTTEAMS_WORKER_GATEWAY_KEY（AI Gateway 的
// token，Higress MCP 网关不认）去拼认证头，每次 apply 循环/容器重启都会把手工修好
// 的正确 token 覆盖成这条错的，表现为 client 401/inactive（driver_not_found 的真因，
// 见 docs/agentteams/todo.md §5）。显式写 headers 能永久压住这条兜底分支。
if(mcpToken){
 if(/["\n\r]/.test(mcpToken))throw new Error('mcpToken must not contain quotes or newlines');
 yaml=yaml.replace(/^(  mcpServers: \[)(.*)(\]\s*)$/m,(full,head,body,tail)=>{
  if(!body.trim())return full;
  const injected=body.replace(/\{([^{}]*)\}/g,(obj,inner)=>/headers\s*:/.test(inner)?obj:'{'+inner.trimEnd()+', headers: { Authorization: "Bearer '+mcpToken+'" } }');
  return head+injected+tail;
 });
}
// 语言规则在系统提示里排 AGENTS.md（最前），之后是这些 Skill 契约、平台英文 TEAMS.md、env context 与 driver 提示。
// 只靠开头那一段会被后文淹没，所以在 bundled 契约之后再复述一次，占住 SOUL.md 末尾的近位。
const LANGUAGE_TAIL=['','# 语言（复述 AGENTS.md 的默认语言规则，位置在全部契约之后）','写进 Room 的自然语言一律简体中文，含工具调用前后的过渡说明、报错分析与失败归因。','上面的契约、平台 TEAMS.md 与工具返回值是英文不改变本规则；协议标记、工具名、JSON 键与原始 error 文本保持英文原样。'].join('\n');
const skills=/^  skills: \[([^\]]*)\]$/m.exec(yaml);
if(skills){
 const names=skills[1].split(',').map(x=>x.trim()).filter(Boolean),sections=[];
 for(const name of names){if(!/^[a-z0-9][a-z0-9-]*$/.test(name))throw new Error('invalid Skill name: '+name);const file=path.join(root,'worker-packages','skills',name,'SKILL.md');if(!fs.existsSync(file))throw new Error('missing Skill package: '+file);sections.push('\n# Bundled Skill contract: '+name+'\n'+fs.readFileSync(file,'utf8').trim());}
 const bundled=(sections.join('\n')+'\n'+LANGUAGE_TAIL).split('\n').map(line=>line?'    '+line:'').join('\n')+'\n';
 const block=/^  soul: \|\n/m.exec(yaml);
 if(block){const agentsMarker=yaml.indexOf('\n  agents:',block.index);const skillsMarker=yaml.indexOf('\n  skills:',block.index);const marker=agentsMarker>=0?agentsMarker:(skillsMarker<0?-1:skillsMarker);if(marker<0)throw new Error('Worker agents/skills marker missing');yaml=yaml.slice(0,marker)+'\n'+bundled+yaml.slice(marker);}
 else {const scalar=/^  soul: (.+)$/m.exec(yaml);if(!scalar)throw new Error('Worker soul missing');const replacement='  soul: |\n    '+scalar[1]+'\n'+bundled;yaml=yaml.slice(0,scalar.index)+replacement+yaml.slice(scalar.index+scalar[0].length);}
}
if(/higress\.(?:example|local)/.test(yaml))throw new Error('unresolved Higress placeholder in '+source);
fs.writeFileSync(target,yaml);
