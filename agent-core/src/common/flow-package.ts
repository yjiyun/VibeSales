import * as fs from 'fs';
import * as path from 'path';
import { parse, stringify } from 'yaml';
import { strToU8, unzipSync, zipSync } from 'fflate';
import { createHash } from 'crypto';
import { CheckItem, CheckReport } from './types';

export interface FlowPackage { format:'coze'; manifestYaml:string; workflowFile:string; workflowYaml:string }

/** P3/P3B/P4 共用的唯一工作流包契约。 */
export class FlowPackageCodec {
  static fromTemplate(workflowPath:string):FlowPackage {
    const workflowFile=path.basename(workflowPath),root=path.dirname(path.dirname(workflowPath));
    const manifest=path.join(root,'MANIFEST.yml');
    if(!fs.existsSync(manifest)||!fs.existsSync(workflowPath))throw new Error('template package missing MANIFEST.yml/workflow YAML');
    return this.validateShape({format:'coze',manifestYaml:fs.readFileSync(manifest,'utf8'),workflowFile,workflowYaml:fs.readFileSync(workflowPath,'utf8')});
  }
  static create(workflow:Record<string,unknown>):FlowPackage {
    const id=String(workflow.id),name=String(workflow.name),mode=String(workflow.mode);
    const manifest={type:'Workflow',version:'1.0.0',main:{id:Number(id),name,desc:String(workflow.description??''),icon:String(workflow.icon??''),version:'',flowMode:mode==='chatflow'?3:0,commitId:''},sub:[]};
    return this.validateShape({format:'coze',manifestYaml:stringify(manifest,{indent:2}),workflowFile:name+'-draft.yaml',workflowYaml:stringify(workflow,{indent:2})});
  }
  static normalize(value:FlowPackage|string):FlowPackage {
    if(typeof value!=='string')return this.validateShape(value);
    const workflow=parse(value) as Record<string,unknown>;return this.create(workflow);
  }
  static selfcheck(value:FlowPackage|string):CheckReport {
    let pkg:FlowPackage|undefined,doc:any,manifest:any;
    try{pkg=this.normalize(value);doc=parse(pkg.workflowYaml);manifest=parse(pkg.manifestYaml);}catch{pkg=undefined;}
    const nodes:Array<any>=doc?.nodes??[],edges:Array<any>=doc?.edges??[],ids=nodes.map(n=>String(n.id??'')),unique=new Set(ids);
    const refs:Array<{node:any,ref:string,path:string}>=[];for(const node of nodes)for(const input of node?.parameters?.node_inputs??[]){const v=input?.input?.value;if(v?.ref_node)refs.push({node,ref:String(v.ref_node),path:String(v.path??'')});}
    const outputs=new Set<string>();for(const node of nodes)for(const name of Object.keys(node?.parameters?.node_outputs??{}))outputs.add(String(node.id)+'.'+name);
    const outputExists=(ref:string,p:string)=>outputs.has(ref+'.'+p)||outputs.has(ref+'.'+p.split('.')[0]);
    const placeholders:Array<{node:any,name:string}>=[];for(const node of nodes){if(node?.type!=='llm')continue;const params=node?.parameters??{},texts:string[]=[];if(Array.isArray(params.llmParam)){for(const field of params.llmParam)if((field?.name==='prompt'||field?.name==='systemPrompt')&&typeof field?.input?.value==='string')texts.push(field.input.value);}else{for(const value of [params.llmParam?.prompt,params.llmParam?.systemPrompt]){if(typeof value==='string')texts.push(value);else if(typeof value?.value?.content==='string')texts.push(value.value.content);}}for(const text of texts)for(const m of text.matchAll(/\{\{\s*([A-Za-z0-9_]+)\s*}}/g))placeholders.push({node,name:m[1]});}
    const inputNames=(n:any)=>new Set<string>((n?.parameters?.node_inputs??[]).map((i:any)=>String(i.name)));
    const item=(id:number,name:string,ok:boolean):CheckItem=>({id,name,ok,severity:'error'});
    const checks:CheckItem[]=[
      item(1,'包与 YAML schema 完整',Boolean(pkg&&manifest?.type==='Workflow'&&manifest?.version==='1.0.0'&&doc?.schema_version==='1.0.0'&&['workflow','chatflow'].includes(doc?.mode))),
      item(2,'节点 id 非空且唯一',ids.length>0&&ids.length===unique.size&&ids.every(Boolean)),
      item(3,'存在固定 start/end 节点',nodes.some(n=>n.type==='start'&&String(n.id)==='100001')&&nodes.some(n=>n.type==='end'&&String(n.id)==='900001')),
      item(4,'边引用节点有效',edges.every(e=>unique.has(String(e.source_node))&&unique.has(String(e.target_node)))),
      item(5,'拓扑无环',this.validTopology(ids,edges)),
      item(6,'提示词占位符有 node_inputs',placeholders.every(p=>inputNames(p.node).has(p.name))),
      item(7,'node_inputs 引用有上游输出',refs.every(r=>outputExists(r.ref,r.path))),
      item(8,'end 输入引用有效',nodes.filter(n=>n.type==='end').every(n=>(n?.parameters?.node_inputs??[]).every((i:any)=>!i?.input?.value?.ref_node||outputExists(String(i.input.value.ref_node),String(i.input.value.path))))),
      item(9,'HTTP 节点引用有效',nodes.filter(n=>n.type==='http').every(n=>(n?.parameters?.node_inputs??[]).every((i:any)=>!i?.input?.value?.ref_node||unique.has(String(i.input.value.ref_node))))),
      item(10,'数据依赖节点存在',refs.every(r=>unique.has(r.ref))),
      item(11,'list 输入声明 items',nodes.every(n=>(n?.parameters?.node_inputs??[]).every((i:any)=>i?.input?.type!=='list'||i?.input?.items!=null))),
    ];
    return {ok:checks.every(c=>c.ok),checks,subject_hash:pkg?this.subjectHash(pkg):undefined};
  }
  static subjectHash(value:FlowPackage|string):string {const pkg=this.normalize(value);return createHash('sha256').update(JSON.stringify({format:pkg.format,manifestYaml:pkg.manifestYaml,workflowFile:pkg.workflowFile,workflowYaml:pkg.workflowYaml})).digest('hex');}
  static zip(pkg:FlowPackage):Uint8Array {const p=this.validateShape(pkg);return zipSync({'MANIFEST.yml':strToU8(p.manifestYaml),['workflow/'+p.workflowFile]:strToU8(p.workflowYaml)},{level:6});}
  static unzip(data:Uint8Array):FlowPackage {const files=unzipSync(data),workflow=Object.keys(files).find(k=>/^workflow\/[^/]+\.ya?ml$/.test(k));if(!files['MANIFEST.yml']||!workflow)throw new Error('zip missing MANIFEST.yml/workflow/*.yaml');const dec=new TextDecoder();return this.validateShape({format:'coze',manifestYaml:dec.decode(files['MANIFEST.yml']),workflowFile:path.basename(workflow),workflowYaml:dec.decode(files[workflow])});}
  private static validateShape(pkg:FlowPackage):FlowPackage {if(pkg?.format!=='coze'||!pkg.manifestYaml?.trim()||!pkg.workflowYaml?.trim()||!pkg.workflowFile?.match(/^[A-Za-z0-9._-]+\.ya?ml$/))throw new Error('invalid FlowPackage');parse(pkg.manifestYaml);parse(pkg.workflowYaml);return pkg;}
  private static validTopology(ids:string[],edges:Array<any>):boolean{if(!ids.length)return false;const incoming=new Map(ids.map(i=>[i,0])),out=new Map(ids.map(i=>[i,[] as string[]]));for(const e of edges){const s=String(e.source_node),t=String(e.target_node);if(!incoming.has(t)||!out.has(s))return false;incoming.set(t,(incoming.get(t)??0)+1);out.get(s)!.push(t);}const q=ids.filter(i=>incoming.get(i)===0);let seen=0;while(q.length){const n=q.shift()!;seen++;for(const x of out.get(n)??[]){incoming.set(x,(incoming.get(x)??1)-1);if(incoming.get(x)===0)q.push(x);}}return seen===ids.length;}
}
