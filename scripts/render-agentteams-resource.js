#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path');
const [source,target,root,baseUrl]=process.argv.slice(2);
const privateHost=host=>host==='localhost'||host==='127.0.0.1'||host==='::1'||/^10\./.test(host)||/^192\.168\./.test(host)||/^172\.(1[6-9]|2\d|3[01])\./.test(host)||!host.includes('.');
let gateway;try{gateway=new URL(baseUrl);}catch{throw new Error('gateway origin invalid');}
if(!source||!target||!root||gateway.pathname!=='/'||gateway.search||gateway.hash||!(gateway.protocol==='https:'||(gateway.protocol==='http:'&&privateHost(gateway.hostname))))throw new Error('gateway must be an HTTPS origin or private-network HTTP origin');
let yaml=fs.readFileSync(source,'utf8').replaceAll('https://higress.example',baseUrl);
const skills=/^  skills: \[([^\]]*)\]$/m.exec(yaml);
if(skills){
 const names=skills[1].split(',').map(x=>x.trim()).filter(Boolean),sections=[];
 for(const name of names){if(!/^[a-z0-9][a-z0-9-]*$/.test(name))throw new Error('invalid Skill name: '+name);const file=path.join(root,'worker-packages','skills',name,'SKILL.md');if(!fs.existsSync(file))throw new Error('missing Skill package: '+file);sections.push('\n# Bundled Skill contract: '+name+'\n'+fs.readFileSync(file,'utf8').trim());}
 const bundled=sections.join('\n').split('\n').map(line=>'    '+line).join('\n')+'\n';
 const block=/^  soul: \|\n/m.exec(yaml);
 if(block){const marker=yaml.indexOf('\n  skills:',block.index);if(marker<0)throw new Error('Worker skills marker missing');yaml=yaml.slice(0,marker)+'\n'+bundled+yaml.slice(marker);}
 else {const scalar=/^  soul: (.+)$/m.exec(yaml);if(!scalar)throw new Error('Worker soul missing');const replacement='  soul: |\n    '+scalar[1]+'\n'+bundled;yaml=yaml.slice(0,scalar.index)+replacement+yaml.slice(scalar.index+scalar[0].length);}
}
if(/higress\.(?:example|local)/.test(yaml))throw new Error('unresolved Higress placeholder in '+source);
fs.writeFileSync(target,yaml);
