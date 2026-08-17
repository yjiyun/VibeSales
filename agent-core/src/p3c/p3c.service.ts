import { Injectable } from '@nestjs/common';
import { createHash, randomUUID } from 'crypto';
import { ArtifactStoreService } from '../artifacts/artifact-store.service';
import { AgentBlueprint, CheckItem, CheckReport, Guidance, SkillDefinition, Triage } from '../common/types';
import { SkillCatalogService } from './skill-catalog.service';

const BUILTIN_TOOLS = ['read_file', 'memory_search', 'load_skill_through_path'];

@Injectable()
export class P3cService {
  constructor(private readonly store: ArtifactStoreService,private readonly skills:SkillCatalogService) {}

  listSkillCandidates(industry: string | undefined, scenarios: string[]) { return this.skills.list(industry,scenarios); }

  listToolCandidates(clientCode: string) {
    return [{ name: 'crm_query', server: 'business-tools', visibility: 'tenant', clientCode }];
  }

  renderPersona(guidance: Guidance) {
    // Worker 传来的 guidance 常缺字段或字段名不符（它们自己拼 JSON），旧代码直接
    // .join() 会抛 "Cannot read properties of undefined (reading 'join')"，Worker 只
    // 看到「驱动内部 bug」。这里明确校验必填项并列出缺失，缺失即拒绝而不是崩在 join 上。
    const missing: string[] = [];
    if (typeof guidance?.role !== 'string' || !guidance.role) missing.push('role');
    if (typeof guidance?.tone !== 'string' || !guidance.tone) missing.push('tone');
    if (typeof guidance?.reply_length !== 'string' || !guidance.reply_length) missing.push('reply_length');
    if (!Array.isArray(guidance?.conditions)) missing.push('conditions[]');
    if (!Array.isArray(guidance?.escalation_conditions)) missing.push('escalation_conditions[]');
    if (missing.length) throw new Error('guidance is incomplete, pass the deriveGuidance result as-is; missing: ' + missing.join(', '));
    const escalations = guidance.escalation_conditions.join('；');
    return {
      agentsMd: '# 工作准则\n你是' + guidance.role + '。语气：' + guidance.tone + '，回复长度：' + guidance.reply_length + '。\n必须遵循：' + guidance.conditions.join('；') + '。\n转人工条件：' + escalations + '。',
      soulMd: '# 身份\n以' + guidance.role + '身份提供可靠帮助；遇到' + escalations + '必须转人工，不得自行承诺。',
    };
  }

  /** 四位专家同批启动、互不调用；合成顺序由 compose 主控统一裁决。 */
  async dispatchExperts(triage: Triage, guidance: Guidance, clientCode: string) {
    const batchId = randomUUID();
    const execute = async (role: string, produce: () => unknown | Promise<unknown>) => {
      const startedAt = Date.now();
      await Promise.resolve();
      return { role, batchId, startedAt, completedAt: Date.now(), payload: await produce() };
    };
    const [persona, business, skill, tool] = await Promise.all([
      execute('persona-expert', () => this.renderPersona(guidance)),
      execute('business-expert', () => ({
        scenarios: [triage.scene_id], conditions: guidance.conditions,
        escalationConditions: guidance.escalation_conditions,
      })),
      execute('skill-expert', () => this.listSkillCandidates(triage.industry, [triage.scene_id])),
      execute('tool-expert', () => this.listToolCandidates(clientCode)),
    ]);
    return { batchId, experts: [persona, business, skill, tool] };
  }

  async composeBlueprint(input: { runId: string; clientCode: string; triage: Triage; guidance: Guidance; experts?:{persona:any;business:any;skills:any;tools:any} }): Promise<AgentBlueprint> {
    const persona = this.normalizePersona(input.experts?.persona, input.guidance);
    const candidates = await this.normalizeSkills(input.experts?.skills, input.triage);
    const skills: SkillDefinition[] = candidates.map(s => s.source === 'inline'
      ? { name: s.name, source: 'inline', skillMd: s.skillMd, requiredTools: s.requiredTools ?? [] }
      : { name: s.name, source: 'library', ref: s.ref, requiredTools: s.requiredTools ?? (s.name === 'human-handoff' ? [] : ['crm_query']) });
    const businessRules=this.normalizeBusinessRules(input.experts?.business,input.guidance);
    const {selectedTools,selectedServers}=this.normalizeTools(input.experts?.tools,input.clientCode);
    return {
      blueprintId: 'bp_' + randomUUID().replace(/-/g, ''), version: 0, clientCode: input.clientCode,
      runtimeAgentId: (input.triage.scene_id || 'custom') + '-' + input.clientCode,
      meta: { industry: input.triage.industry, scenarios: [input.triage.scene_id], generatedBy: 'blueprint-compose', runId: input.runId },
      // guidance 的原文条件必须逐字保留（selfcheck#13 按字面匹配）：专家规则更细，会把
      // guidance 措辞完全改写，只拼 businessRules 会让 #13 恒 warning。
      prompt: { agentsMd:String(persona.agentsMd??'')+'\n业务规则：'+businessRules.join('；')
          +'\nGuidance 约束：'+(input.guidance.conditions??[]).join('；')
          +'\nGuidance 转人工条件：'+(input.guidance.escalation_conditions??[]).join('；'),
        soulMd:String(persona.soulMd??''), knowledgeMd: '# 领域知识\n只使用经租户授权的知识与工具。\n'+businessRules.join('\n') }, skills,
      tools: { allow: [...BUILTIN_TOOLS, ...selectedTools], deny: [], mcpServers: selectedServers.map(name=>({ name, url: this.businessMcpUrl(), transport: 'streamableHttp' as const })) },
      runtime: { model: 'dashscope:qwen-plus', isolationScope: 'USER', maxContextTokens: 8000, compaction: { triggerMessages: 50, keepMessages: 20 } },
      guidance: input.guidance,
    };
  }

  // 四位专家是 LLM Worker，产出形状由它们自己的技能文档决定，不会恰好等于本服务的内部
  // 契约（persona 给 {agent_name,behavior_rules,...} 而非 {agentsMd,soulMd}；skill 给
  // {skills:[...]} 而非数组；tool 给 {allow,mcpServers:{...}} 而非候选数组）。旧代码用
  // Array.isArray / ?? 做形状判断，不匹配时静默退回库默认值，产出「空壳」Blueprint 而
  // 不报错：agentsMd 只剩「业务规则：」、skills 只有 human-handoff。Worker 于是判定
  // 「MCP 忽略了我的输入」，转而手写 blueprint.md 绕开 persistBlueprint，Nest 永远拿不到
  // blueprint，run 卡在 P3C。下面四个 normalize* 负责把专家的真实形状收敛到内部契约。

  /** persona：优先用已是内部契约的 {agentsMd,soulMd}，否则从专家人设字段渲染。 */
  private normalizePersona(value:any,guidance:Guidance):{agentsMd:string;soulMd:string}{
    if(value&&typeof value==='object'&&typeof value.agentsMd==='string'&&value.agentsMd.trim())
      return {agentsMd:value.agentsMd,soulMd:String(value.soulMd??'')};
    if(!value||typeof value!=='object')return this.renderPersona(guidance);
    const list=(v:any):string[]=>Array.isArray(v)?v.map(String).filter(Boolean):[];
    const role=String(value.role??guidance.role??''),brand=String(value.brand??''),name=String(value.agent_name??'');
    const tone=String(value.tone??guidance.tone??''),len=String(value.reply_length??guidance.reply_length??'');
    const rules=list(value.behavior_rules),bans=list(value.prohibitions);
    const escalations=list(value.escalation_triggers).length?list(value.escalation_triggers):list(guidance.escalation_conditions);
    const terms=Array.isArray(value.terminology_table)?value.terminology_table:[];
    const agents=['# 工作准则',
      (name?'你是'+name+(brand?'（'+brand+'）':''):'你是'+role)+'。语气：'+tone+'，回复长度：'+len+'。',
      value.address_style?'称呼：'+String(value.address_style)+'。':'',
      rules.length?'行为准则：\n'+rules.map(r=>'- '+r).join('\n'):'',
      bans.length?'禁止事项：\n'+bans.map(r=>'- '+r).join('\n'):'',
      escalations.length?'转人工条件：'+escalations.join('；')+'。':'',
      terms.length?'术语表条目数：'+terms.length:'',
    ].filter(Boolean).join('\n');
    const soul=['# 身份',
      '以'+(name||role)+'身份提供可靠帮助'+(value.domain?'，领域：'+String(value.domain):'')+'。',
      value.target_users?'服务对象：'+String(value.target_users)+'。':'',
      escalations.length?'遇到'+escalations.join('；')+'必须转人工，不得自行承诺。':'',
      value.long_term_memory?'需要长期记忆：'+String(value.memory_content??value.long_term_memory):'',
    ].filter(Boolean).join('\n');
    return {agentsMd:agents,soulMd:soul};
  }

  /** skills：接受数组、{skills:[...]}；专家给的 inline Skill 补齐 frontmatter 后原样保留。 */
  private async normalizeSkills(value:any,triage:Triage):Promise<Array<SkillDefinition&{ref?:string}>>{
    const raw=Array.isArray(value)?value:Array.isArray(value?.skills)?value.skills:null;
    if(!raw||!raw.length)return (await this.listSkillCandidates(triage.industry,[triage.scene_id])).map(s=>({name:s.name,source:'library' as const,ref:s.ref}));
    const seen=new Set<string>(),out:Array<SkillDefinition&{ref?:string}>=[];
    for(const item of raw){
      const name=this.skillName(item?.name);if(!name||seen.has(name))continue;seen.add(name);
      const tools=Array.isArray(item?.required_tools)?item.required_tools.map(String)
        :Array.isArray(item?.requiredTools)?item.requiredTools.map(String):[];
      if(item?.ref&&item?.type!=='inline'&&item?.source!=='inline'){out.push({name,source:'library',ref:String(item.ref),requiredTools:tools});continue;}
      out.push({name,source:'inline',skillMd:this.skillMd(name,item),requiredTools:tools});
    }
    return out.length?out:(await this.listSkillCandidates(triage.industry,[triage.scene_id])).map(s=>({name:s.name,source:'library' as const,ref:s.ref}));
  }

  /** selfcheck#4 要求 Skill 名 ^[a-z0-9][a-z0-9-]*$，专家常写 snake_case。 */
  private skillName(value:unknown):string{
    return String(value??'').trim().toLowerCase().replace(/[_\s]+/g,'-').replace(/[^a-z0-9-]/g,'').replace(/^-+|-+$/g,'');
  }

  /** selfcheck#6/#7/#8：frontmatter 齐备、描述含触发词、正文无绝对路径。 */
  private skillMd(name:string,item:any):string{
    const desc=String(item?.description??item?.desc??'').trim()||('用于'+name+' 场景');
    const trigger=String(item?.trigger??'').trim();
    const front='---\nname: '+name+'\ndescription: '+(/当|如果|用于|when|use when/i.test(desc)?desc:'当'+(trigger||'命中该场景')+'时用于'+desc)+'\n---\n';
    const section=(title:string,v:any):string=>{
      const lines=Array.isArray(v)?v.map(String):v?[String(v)]:[];
      return lines.length?'\n## '+title+'\n'+lines.map(l=>'- '+l.replace(/(^|[\s'"])(\/|[A-Za-z]:\\)/g,'$1')).join('\n')+'\n':'';
    };
    return front+'# '+name+'\n'+desc+'\n'
      +section('触发条件',trigger)+section('输入',item?.input)+section('输出',item?.output)
      +section('关键逻辑',item?.key_logic??item?.logic)+section('失败处理',item?.failure_handling)
      +section('安全边界',item?.security);
  }

  /** business：专家给的是按业务目标分组的对象；抽出所有可读规则文本。 */
  private normalizeBusinessRules(value:any,guidance:Guidance):string[]{
    const fallback=[...(guidance.conditions??[]),...(guidance.escalation_conditions??[])].map(String);
    if(!value||typeof value!=='object')return fallback;
    if(Array.isArray(value.conditions)||Array.isArray(value.escalationConditions))
      return [...(Array.isArray(value.conditions)?value.conditions:[]),...(Array.isArray(value.escalationConditions)?value.escalationConditions:[])].map(String);
    const skip=new Set(['client_code','template_id','build_path','channel','industry','business_goals']);
    const rules:string[]=[];
    const flatten=(prefix:string,v:any,depth:number):void=>{
      if(depth>3||v==null)return;
      if(typeof v==='string'||typeof v==='number'||typeof v==='boolean'){const t=String(v).trim();if(t)rules.push(prefix?prefix+'：'+t:t);return;}
      if(Array.isArray(v)){for(const item of v)flatten(prefix,item,depth+1);return;}
      if(typeof v==='object')for(const[k,inner]of Object.entries(v))flatten(prefix?prefix+'.'+k:k,inner,depth+1);
    };
    for(const[key,v]of Object.entries(value)){if(skip.has(key))continue;flatten(key,v,0);}
    const out=[...new Set(rules)];
    return out.length?out:fallback;
  }

  /** tools：接受候选数组、或专家给的 {allow,mcpServers:{name:{...}}} 授权面形状。 */
  private normalizeTools(value:any,clientCode:string):{selectedTools:string[];selectedServers:string[]}{
    const pick=(candidates:any[]):{selectedTools:string[];selectedServers:string[]}=>({
      selectedTools:[...new Set(candidates.map(t=>String(t?.name??'')).filter(Boolean))],
      selectedServers:[...new Set(candidates.map(t=>String(t?.server??'')).filter(Boolean))],
    });
    if(Array.isArray(value))return pick(value);
    if(value&&typeof value==='object'){
      const allow=Array.isArray(value.allow)?value.allow.map(String).filter((t:string)=>t&&t!=='*'):[];
      const servers:string[]=value.mcpServers&&typeof value.mcpServers==='object'&&!Array.isArray(value.mcpServers)
        ? Object.keys(value.mcpServers)
        : Array.isArray(value.mcpServers)?value.mcpServers.map((s:any)=>String(s?.name??s)).filter((s:string)=>Boolean(s)):[];
      // mcpServers 里声明的 tools 也算已授权工具（专家常只在 server 下列，不重复进 allow）
      const nested=value.mcpServers&&typeof value.mcpServers==='object'&&!Array.isArray(value.mcpServers)
        ? Object.values<any>(value.mcpServers).flatMap(s=>Array.isArray(s?.tools)?s.tools.map((t:any)=>String(t)):[]) as string[]
        : [];
      const tools=[...new Set([...allow,...nested])].filter(Boolean);
      if(tools.length||servers.length)return {selectedTools:tools,selectedServers:[...new Set(servers)]};
    }
    return pick(this.listToolCandidates(clientCode));
  }

  private businessMcpUrl(): string {
    const configured=process.env.P3C_BUSINESS_MCP_URL?.trim();
    if(configured)return configured;
    if((process.env.ARTIFACT_STORE??'file').toLowerCase()==='file')return 'https://higress.local/mcp-servers/business-tools/mcp';
    throw new Error('P3C_BUSINESS_MCP_URL required for production Blueprint generation');
  }

  async blueprintSelfcheck(bp: AgentBlueprint): Promise<CheckReport> {
    const names = bp.skills.map(s => s.name); const deps = bp.skills.flatMap(s => s.requiredTools ?? []);
    const capability = new Set([...bp.tools.allow, ...bp.tools.mcpServers.map(s => s.name), ...this.listToolCandidates(bp.clientCode).map(t => t.name)]);
    const check = (id:number,name:string,ok:boolean,severity:'error'|'warning'='error',detail?:string):CheckItem => ({id,name,ok,severity,detail});
    const promptAll = bp.prompt.agentsMd + ' ' + bp.prompt.soulMd + ' ' + bp.skills.map(s => s.skillMd ?? '').join(' ');
    const checks: CheckItem[] = [
      check(1, '租户与 runtimeAgentId 合法', /^[a-zA-Z0-9_-]+$/.test(bp.clientCode) && /^[a-zA-Z0-9_-]+$/.test(bp.runtimeAgentId)),
      check(2, 'AGENTS.md 非空且在预算内', bp.prompt.agentsMd.trim().length > 0 && bp.prompt.agentsMd.length <= bp.runtime.maxContextTokens * 4),
      check(3, 'SOUL 与 AGENTS 无明显矛盾', !(/必须转人工/.test(bp.prompt.agentsMd) && /禁止转人工/.test(bp.prompt.soulMd)), 'warning'),
      check(4, 'Skill 名称唯一且合法', new Set(names).size === names.length && names.every(n => /^[a-z0-9][a-z0-9-]*$/.test(n))),
      check(5, 'library Skill 引用存在', await this.skills.referencesExist(bp.skills)),
      check(6, 'inline Skill frontmatter 合法', bp.skills.filter(s => s.source === 'inline').every(s => /^---\s*[\s\S]*name:\s*.+[\s\S]*description:\s*.+[\s\S]*---/.test(s.skillMd ?? ''))),
      check(7, 'inline Skill 描述含触发条件', bp.skills.filter(s => s.source === 'inline').every(s => /当|如果|用于|when|use when/i.test(s.skillMd ?? '')), 'warning'),
      check(8, 'Skill 无绝对路径', bp.skills.every(s => !/(^|[\s'"])(\/|[A-Za-z]:\\)/.test(s.skillMd ?? ''))),
      check(9, '工具白名单保留内置工具', BUILTIN_TOOLS.every(t => bp.tools.allow.includes(t))),
      check(10, 'Skill 依赖工具可用', deps.every(d => capability.has(d))),
      check(11, 'MCP 只经受信网关', bp.tools.mcpServers.every(s => this.validGatewayToolUrl(s.url))),
      check(12, '隔离范围合法且固定', ['SESSION','USER','AGENT'].includes(bp.runtime.isolationScope)),
      check(13, '转人工条件已表达', (bp.guidance?.escalation_conditions ?? []).every(c => promptAll.includes(c)), 'warning'),
    ];
    return { ok: checks.every(c => c.ok || c.severity === 'warning'), checks, subject_hash:this.blueprintHash(bp) };
  }

  async persistBlueprint(runId: string, bp: AgentBlueprint) {
    const report = await this.blueprintSelfcheck(bp); if (!report.ok) throw new Error('blueprint selfcheck failed: ' + report.checks.filter(c=>!c.ok).map(c=>'#'+c.id).join(','));
    await this.store.putArtifact(runId,'blueprint_check',report,'blueprint-compose');
    return this.store.persistBlueprint(runId, bp);
  }

  blueprintHash(bp:AgentBlueprint):string{const{version:_,...content}=bp;return createHash('sha256').update(JSON.stringify(this.canonical(content))).digest('hex');}
  private canonical(value:any):any{if(Array.isArray(value))return value.map(item=>this.canonical(item));if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().map(key=>[key,this.canonical(value[key])]));return value;}

  private validGatewayToolUrl(value:string):boolean{try{const u=new URL(value),h=u.hostname;const privateHost=h==='localhost'||h==='127.0.0.1'||h==='::1'||/^10\./.test(h)||/^192\.168\./.test(h)||/^172\.(1[6-9]|2\d|3[01])\./.test(h)||!h.includes('.');return (u.protocol==='https:'||(u.protocol==='http:'&&privateHost))&&u.pathname.startsWith('/mcp-servers/');}catch{return false;}}
}
