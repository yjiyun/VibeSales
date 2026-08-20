#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const YAML = require('../agent-core/node_modules/yaml');
const root = path.resolve(__dirname, '..');
const fail = msg => { throw new Error(msg); };
const readYaml = file => YAML.parse(fs.readFileSync(file, 'utf8'));

const k = readYaml(path.join(root, 'agentteams-resources/kustomization.yaml'));
const identities = readYaml(path.join(root, 'worker-packages/identities/agent-identities.yaml')).identities;
if (!Array.isArray(identities) || identities.length !== 13) fail('expected 13 Agent identities');
for (const i of identities) for (const field of ['id','kind','role','input','output','boundary']) if (!i[field]) fail('identity ' + (i.id || '?') + ' missing ' + field);
const resources = k.resources.map(r => path.join(root, 'agentteams-resources', r));
const docs = resources.map(file => ({ file, doc: readYaml(file) }));
const workers = docs.filter(x => x.doc.kind === 'Worker');
if (workers.length !== 11) fail('expected 11 Worker CRs, got ' + workers.length);
const leaders = workers.filter(x => x.doc.spec.role === 'team_leader');
if (leaders.length !== 1) fail('exactly one team_leader required');
for (const {file,doc} of workers) {
  if (doc.spec.runtime !== 'qwenpaw') fail(file + ': runtime must be qwenpaw');
  if (!doc.spec.identity || !doc.spec.soul || !Array.isArray(doc.spec.skills)) fail(file + ': identity/soul/skills required');
  if (!doc.spec.agents) fail(file + ': spec.agents required for default Chinese Room language');
  if (!doc.spec.agents.includes('简体中文')) fail(file + ': spec.agents must require Simplified Chinese');
  if (!doc.spec.agents.includes('跟协调者')) fail(file + ': spec.agents must override coordinator language rule');
  // 上一轮只写六类枚举（致谢/追问/阻塞/进度/汇报/派活），模型把工具旁白与英文报错分析当成枚举外，落回英文。
  // 语言规则必须是全覆盖式，并显式收住「工具返回英文报错」这个实测破功点。
  if (!doc.spec.agents.includes('过渡说明')) fail(file + ': spec.agents must cover inter-tool narration, not just report categories');
  if (!doc.spec.agents.includes('报错分析')) fail(file + ': spec.agents must cover error analysis in Chinese');
  if (!doc.spec.agents.includes('工具返回值')) fail(file + ': spec.agents must hold the rule when tool output is English');
  if (!doc.spec.agents.includes('原始 error')) fail(file + ': spec.agents must exempt raw error strings from translation');
  if (!doc.spec.soul.includes('Room 口语默认简体中文')) fail(file + ': soul must remind default Chinese Room language');
  for (const fragment of ['filesync','pull','shared/tasks/task-{当前run_id}/','spec.md','run_id','RUN_BLOCKED','禁止']) if (!doc.spec.soul.includes(fragment)) fail(file + ': explicit current-run FileSync protocol missing ' + fragment);
  for (const m of doc.spec.mcpServers ?? []) if (!/^https:\/\/[^/]*higress[^/]*\/mcp-servers\//.test(m.url)) fail(file + ': MCP must use Higress');
}
const teamIndex = docs.findIndex(x => x.doc.kind === 'Team');
const humanIndex = docs.findIndex(x => x.doc.kind === 'Human');
if (teamIndex < workers.length || humanIndex < teamIndex) fail('resource order must be workers -> team -> human');
const workerNames = new Set(workers.map(x => x.doc.metadata.name));
const team = docs[teamIndex].doc;
if (!workerNames.has(team.spec.leader) || team.spec.workers.some(w => !workerNames.has(w))) fail('Team has dangling Worker reference');
const human = docs[humanIndex].doc;
if (human.spec.permissionLevel !== 2 || !Array.isArray(human.spec.accessibleTeams) || !human.spec.accessibleTeams.includes(team.metadata.name)) fail('Human must use L2 Team-scoped permission for the Chatflows Team');

const skillNames = new Set();
const requiredLines = ['用途：','输入 / 输出：','调用条件：','依赖工具：','失败处理：','安全边界：','复用价值：','与协同流程的关系：'];
const skillRoot = path.join(root, 'worker-packages/skills');
for (const dir of fs.readdirSync(skillRoot)) {
  const file = path.join(skillRoot, dir, 'SKILL.md');
  if (!fs.existsSync(file)) continue;
  const text = fs.readFileSync(file, 'utf8');
  const name = text.match(/^name:\s*(.+)$/m)?.[1]?.trim();
  const description = text.match(/^description:\s*(.+)$/m)?.[1]?.trim();
  if (!name || !description) fail(file + ': frontmatter name/description required');
  for (const label of requiredLines) if (!text.includes(label)) fail(file + ': missing ' + label);
  skillNames.add(name);
}
for (const {file,doc} of workers) for (const skill of doc.spec.skills) if (!skillNames.has(skill)) fail(file + ': missing Skill ' + skill);

const mcpText = fs.readFileSync(path.join(root,'agent-core/src/mcp/mcp.service.ts'),'utf8');
for (const server of ['chatflows-p1','chatflows-p2','chatflows-p3','chatflows-p3b','chatflows-p3c','chatflows-p4']) if (!mcpText.includes("'"+server+"'")) fail('missing MCP server ' + server);
const runtimeTools = JSON.parse(fs.readFileSync(path.join(root,'agent-runtime/workspace/tools.json'),'utf8'));
const businessTools = runtimeTools.mcpServers?.['business-tools'];
if (businessTools?.transport !== 'http' || businessTools?.url !== '${RUNTIME_MCP_URL}' || businessTools?.headers?.Authorization !== 'Bearer ${RUNTIME_MCP_TOKEN}' || !businessTools?.enableTools?.includes('crm_query')) fail('runtime tools.json must register authenticated business-tools via env placeholders');
const p3b = fs.readFileSync(path.join(root,'agent-core/src/p3b/p3b.service.ts'),'utf8');
const flowCodec = fs.readFileSync(path.join(root,'agent-core/src/common/flow-package.ts'),'utf8');
const pipeline = fs.readFileSync(path.join(root,'agent-core/src/orchestration/pipeline.service.ts'),'utf8');
const mcpController = fs.readFileSync(path.join(root,'agent-core/src/mcp/mcp.controller.ts'),'utf8');
const schemaSql = fs.readFileSync(path.join(root,'agent-core/sql/001_agentteams.sql'),'utf8');
const managerControlDir=path.join(root,'agent-manager/src/main/java/com/yjiyun/chatflows/manager/control');
const managerControlFiles=fs.readdirSync(managerControlDir).filter(x=>x.endsWith('.java'));
const managerSources=managerControlFiles.map(x=>fs.readFileSync(path.join(managerControlDir,x),'utf8')).join('\n');
const pipelineControl=fs.readFileSync(path.join(managerControlDir,'PipelineControlClient.java'),'utf8');
const managerControlWithoutA4c=managerControlFiles.filter(x=>x!=='PipelineControlClient.java').map(x=>fs.readFileSync(path.join(managerControlDir,x),'utf8')).join('\n');
const managerApp = fs.readFileSync(path.join(root,'agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerApplication.java'),'utf8');
const managerConfig = fs.readFileSync(path.join(root,'agent-manager/src/main/java/com/yjiyun/chatflows/manager/ManagerConfig.java'),'utf8');
const consoleAuth=fs.readFileSync(path.join(root,'agent-console/src/shared/auth.js'),'utf8'),consoleApi=fs.readFileSync(path.join(root,'agent-console/src/shared/api.js'),'utf8');
// 归一化包名并剥掉首个 Javadoc（两侧只在「mirrors agent-manager / agent-runtime」措辞上不同）。
const normalizeJava = (text,part) => text.replaceAll('com.yjiyun.chatflows.'+part,'com.yjiyun.chatflows.X').replace(/\/\*\*[\s\S]*?\*\//,'').replace(/\s+/g,' ').trim();
const managerAuth = normalizeJava(fs.readFileSync(path.join(root,'agent-manager/src/main/java/com/yjiyun/chatflows/manager/security/AuthService.java'),'utf8'),'manager');
const runtimeAuth = normalizeJava(fs.readFileSync(path.join(root,'agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/security/AuthService.java'),'utf8'),'runtime');
// A23：两侧 HttpSupport 共享 query/json/error/bearer/dec 语义。manager 侧按主线 §5.3 额外持有
// RunIds/header/body/required/method 与 public 可见性，所以逐方法比对归一化实现，不做整文件字节相等。
const managerHttp = fs.readFileSync(path.join(root,'agent-manager/src/main/java/com/yjiyun/chatflows/manager/api/HttpSupport.java'),'utf8');
const runtimeHttp = fs.readFileSync(path.join(root,'agent-runtime/src/main/java/com/yjiyun/chatflows/runtime/api/HttpSupport.java'),'utf8');
const httpShared = ['query','json','error','bearer','dec'];
// 归一化：去可见性修饰、去包名、局部变量重命名为 X，压平空白。
const httpMethod = (text,name) => {
  const start = text.indexOf(name + '(HttpExchange') >= 0 ? text.indexOf(name + '(HttpExchange') : text.indexOf(name + '(String');
  if (start < 0) fail('A23 HttpSupport missing shared method ' + name);
  let depth = 0, end = text.indexOf('{', start);
  for (let i = end; i < text.length; i++) { if (text[i] === '{') depth++; else if (text[i] === '}' && --depth === 0) { end = i; break; } }
  return text.slice(start, end + 1)
    .replace(/com\.yjiyun\.chatflows\.(?:manager|runtime)/g,'X')
    .replace(/\b(?:byte\[\]|OutputStream)\s+\w+=/g, m => m.replace(/\s+\w+=/,' X='))
    .replace(/\b(?:body|b)\.length\b/g,'X.length')
    .replace(/\b(?:out|o)\.write\((?:body|b)\)/g,'X.write(X)')
    .replace(/\s+/g,' ').trim();
};
const mcpService = fs.readFileSync(path.join(root,'agent-core/src/mcp/mcp.service.ts'),'utf8');
const p3c = fs.readFileSync(path.join(root,'agent-core/src/p3c/p3c.service.ts'),'utf8');
const ids = text => [...text.matchAll(/(?:this\.item|item|check)\((\d+),/g)].map(m => Number(m[1]));
const p3bIds = [...new Set(ids(flowCodec))].sort((a,b) => a-b);
const p3cIds = [...new Set(ids(p3c))].sort((a,b) => a-b);
if (p3bIds.join(',') !== '1,2,3,4,5,6,7,8,9,10,11') fail('P3b checks must be #1-#11, got ' + p3bIds);
if (!p3b.includes('FlowPackageCodec.selfcheck(value)')) fail('P3b must delegate to shared FlowPackage selfcheck');
if ((pipeline.match(/putArtifact\(runId, 'flow_check'/g) ?? []).length !== 2) fail('P3 and P3B must both persist flow_check');
if (!pipeline.includes('executeFromWizardSubmission') || pipeline.includes('executeFromTriage') || /async execute\(input:.*match/s.test(pipeline)) fail('pipeline must start from P1 wizard submission and reject prebuilt Triage/Match bypasses');
if (!pipeline.includes("putArtifact(run.run_id, 'wizard_state'")) fail('pipeline must persist the complete P1 Phase1Result');
if (!mcpController.includes('MCP_SERVER_TOKEN') || !mcpController.includes("HttpException('unauthorized',401)")) fail('MCP backend must require Bearer authentication');
if (!mcpController.includes('runId must match _ctx.run_id') || !mcpController.includes('clientCode must match _ctx.client_code')) fail('MCP arguments must be bound to propagated task identity');
if (!fs.readFileSync(path.join(root,'agent-core/src/p4/p4.service.ts'),'utf8').includes('P4 import requires persisted Human APPROVED decision')) fail('P4 side effect must enforce persisted Human approval at the tool boundary');
if (!fs.readFileSync(path.join(root,'agent-core/src/p4/p4.service.ts'),'utf8').includes('publishBlueprint')) fail('P3C bind must publish the Blueprint so sandbox chat can resolve PUBLISHED bindings');
// A4c 端点白名单检查在下方以更严格的带引号字面量形式统一断言（主线口径）。
const javaRoot=path.join(root,'agent-manager/src/main/java');
const javaFiles=[];(function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name),stat=fs.statSync(file);if(stat.isDirectory())walk(file);else if(name.endsWith('.java'))javaFiles.push(file);}})(javaRoot);
const javaApiLiterals=javaFiles.flatMap(file=>[...fs.readFileSync(file,'utf8').matchAll(/"(\/api\/[^"]*)"/g)].map(match=>({file,value:match[1]})));
for(const item of javaApiLiterals){if(item.value.startsWith('/api/v1/pipeline')&&(!item.file.endsWith('PipelineControlClient.java')||!['/api/v1/pipeline/runs','/api/v1/pipeline/health','/api/v1/pipeline/'].some(prefix=>item.value.startsWith(prefix))))fail('A4c forbids Java manager Nest endpoint: '+item.value);}
if (!managerSources.includes('HmacSHA256') || !mcpService.includes('this.proofs.verify')) fail('P4 approval must use a cross-language HMAC proof');
if (!mcpService.includes("'PROCESSING'") || !fs.readFileSync(path.join(root,'agent-core/src/artifacts/artifact-store.service.ts'),'utf8').includes('pg_advisory_xact_lock')) fail('approval proof consumption must be atomic before P4 side effects');
if (!managerConfig.includes('AGENTTEAMS_HUMAN_IDS') || !managerApp.includes('c.humanIds')) fail('Matrix approval must enforce an explicit Human sender allowlist');
if(managerControlWithoutA4c.includes('/api/v1/pipeline'))fail('A4c permits Nest control calls only inside PipelineControlClient');
for(const endpoint of ['"/api/v1/pipeline/runs"','"/api/v1/pipeline/"+id(runId)+"/approval"','"/api/v1/pipeline/health"'])if(!pipelineControl.includes(endpoint))fail('A4c missing allowlisted control endpoint '+endpoint);
if(pipelineControl.includes('/api/v1/pipeline/start')||pipelineControl.includes('/wizard/')||pipelineControl.includes('/mcp'))fail('A4c forbids manager business calls to Nest');
if (managerAuth !== runtimeAuth || !managerAuth.includes('runtime and admin tokens must differ')) fail('A23 manager/runtime AuthService semantics drifted');
for (const name of httpShared) if (httpMethod(managerHttp,name) !== httpMethod(runtimeHttp,name)) fail('A23 manager/runtime HttpSupport drifted on shared method ' + name);
if (!managerHttp.includes('RunIds.requireV4') || runtimeHttp.includes('RunIds')) fail('A23 run_id validation belongs to the manager control plane only');
if(!consoleAuth.includes('managerAdminToken')||!consoleAuth.includes("auth.role==='admin'")||consoleApi.includes('headers(auth.managerToken)'))fail('Console must select the separate manager admin credential for admin role');
if(!managerConfig.includes('must not receive DASHSCOPE_API_KEY')||!managerConfig.includes('getOrDefault("ORCHESTRATOR_LLM","off")'))fail('A21/A24 manager must reject true key and default deterministic');
const renderScript=path.join(root,'scripts/render-agentteams-resource.js');
if (!fs.existsSync(renderScript)) fail('Worker Skill contracts must be bundled into rendered souls for self-contained deployment');
// 语言规则在 AGENTS.md 里排系统提示最前，后面跟着英文 Skill 契约 / TEAMS.md / env context。
// 渲染必须在 bundled 契约之后补一段中文复述，占住 SOUL.md 末尾的近位，否则规则被英文后文淹没。
if (!fs.readFileSync(renderScript,'utf8').includes('LANGUAGE_TAIL')) fail('render must restate the Chinese language rule after bundled Skill contracts');
// qwenpaw_worker 的 update.py:_apply_mcp_servers 只在 mcpServers[].headers 缺 Authorization 时
// 才用容器 env 的错误 gateway key 兜底覆盖，导致 client 401/inactive（driver_not_found 根因，
// 见 docs/agentteams/todo.md §5）。渲染必须支持显式注入 Authorization 头以永久压住这条兜底。
if (!fs.readFileSync(renderScript,'utf8').includes('mcpToken')) fail('render must support injecting MCP Authorization headers to suppress qwenpaw gateway-key fallback');
if (!fs.readFileSync(path.join(root,'agentteams-apply.sh'),'utf8').includes('HIGRESS_CONSUMER_TOKEN')) fail('agentteams-apply.sh must pass HIGRESS_CONSUMER_TOKEN to the resource renderer');
if (!fs.existsSync(path.join(root,'scripts/sync-agentteams-worker-skills.js'))) fail('REST apply must synchronize declared Worker Skill files into AgentTeams MinIO');
for (const helper of ['put-qwenpaw-mcp-credential.py','provision-agentteams-worker-mcp-credential.sh']) {
  if (!fs.existsSync(path.join(root, 'scripts', helper))) fail('missing secure Worker MCP credential helper: ' + helper);
}
for (const file of ['scripts/preflight-agentteams-integration.js','scripts/audit-agentteams-platform-readonly.js','scripts/run-agentteams-e2e.sh','scripts/run-agentteams-platform-e2e.js','scripts/test-agentteams-platform-e2e.mjs','docs/agentteams/e2e-spec.md']) if (!fs.existsSync(path.join(root,file))) fail('missing integration entrypoint '+file);
const platformE2eShell=fs.readFileSync(path.join(root,'scripts/run-agentteams-e2e.sh'),'utf8');
const platformE2eDriver=fs.readFileSync(path.join(root,'scripts/run-agentteams-platform-e2e.js'),'utf8');
if(/randomUUID|AGENTTEAMS_RUN_ID|AGENTTEAMS_RESUME|run\.sh["']?\s+(?:run|resume)/.test(platformE2eShell))fail('platform e2e must not generate/resume run_id outside Nest');
if(!platformE2eShell.includes('AGENTTEAMS_CONFIRM_APPLY')||!platformE2eShell.includes('discover-agentteams-runtime.js'))fail('platform e2e must guard shared apply and discover Team runtime state');
for(const marker of ['/api/v1/orchestrations','/api/v1/pipeline/','/api/v1/chat?','worker_usage_available: false'])if(!platformE2eDriver.includes(marker))fail('platform e2e missing contract '+marker);
const packageJson=JSON.parse(fs.readFileSync(path.join(root,'agent-core/package.json'),'utf8'));if(packageJson.scripts['start:prod']!=='node dist/main-mcp.js')fail('production Nest entrypoint must expose only the AgentTeams tool plane');
if(!packageJson.scripts['db:init']||!packageJson.scripts['test:postgres-contract'])fail('database initialization and RLS contract tests are required');
if (!managerApp.includes('proof=<redacted>') || managerApp.includes('System.out.println(message)')) fail('approval proof must never be printed to logs');
if (!p3c.includes('P3C_BUSINESS_MCP_URL') || p3c.includes("url: 'https://higress.local")) fail('P3C production Blueprint MCP URL must be configurable');
if (!schemaSql.includes('create policy artifact_tenant_isolation on artifact for select')) fail('artifact tenant policy must be SELECT-only so it cannot bypass kind INSERT checks');
if (!schemaSql.includes("current_user='worker_p4' and not(old.status='DRAFT' and new.status='STAGED')") || !schemaSql.includes("current_user='blueprint_admin'")) fail('P4 staging and Human publication DB roles must remain separated');
if (!schemaSql.includes('foreign key (run_id, client_code) references run(run_id, client_code)') || !schemaSql.includes('foreign key (blueprint_id, client_code) references agent_blueprint(blueprint_id, client_code)')) fail('cross-table references must include tenant identity');
if (!schemaSql.includes('foreign key (source_run_id, client_code) references run(run_id, client_code)') || !schemaSql.includes("new.payload->>'clientCode' is distinct from new.client_code")) fail('Blueprint source and JSON identity must be fail-closed');
if (!schemaSql.includes('runtime isolationScope is immutable after first Blueprint')) fail('A17 isolationScope immutability must be database-enforced');
for (const table of ['run','artifact','agent_blueprint','agent_binding']) if (!schemaSql.includes('alter table '+table+' force row level security')) fail(table+' must FORCE RLS even for accidental table owners');
if (!schemaSql.includes('create role chatflows_tenant_lookup nologin bypassrls') || !schemaSql.includes('alter function lookup_run_client(uuid) owner to chatflows_tenant_lookup')) fail('opaque run tenant lookup must use a dedicated NOLOGIN BYPASSRLS function owner');
if (p3cIds.join(',') !== '1,2,3,4,5,6,7,8,9,10,11,12,13') fail('P3C checks must be #1-#13, got ' + p3cIds);
console.log('[PASS] 11 Workers, 1 Leader, Team/Human dependency order');
console.log('[PASS] 13 Agent identities with complete contracts');
console.log('[PASS] ' + skillNames.size + ' Skills with complete eight-field contracts');
console.log('[PASS] 6 MCP servers, P3b 11 checks, P3C 13 checks');
