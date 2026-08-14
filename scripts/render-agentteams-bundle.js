#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
const root=path.resolve(__dirname,'..'),manifest=path.join(root,'agentteams-resources/kustomization.yaml'),output=path.resolve(process.argv[2]||'/rendered'),base=process.env.CHATFLOWS_MCP_BASE_URL?.trim();
if(!base)throw new Error('CHATFLOWS_MCP_BASE_URL required');
const resources=fs.readFileSync(manifest,'utf8').split(/\r?\n/).map(line=>/^\s*-\s+(.+?)\s*$/.exec(line)?.[1]).filter(Boolean);
fs.mkdirSync(output,{recursive:true});const files=[];
resources.forEach((relative,index)=>{const target=String(index).padStart(3,'0')+'-'+path.basename(relative),source=path.join(path.dirname(manifest),relative);execFileSync(process.execPath,[path.join(root,'scripts/render-agentteams-resource.js'),source,path.join(output,target),root,base],{stdio:'inherit'});files.push(target);});
fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify({version:1,files},null,2)+'\n');
process.stdout.write('[PASS] rendered '+files.length+' AgentTeams resources in dependency order\n');
