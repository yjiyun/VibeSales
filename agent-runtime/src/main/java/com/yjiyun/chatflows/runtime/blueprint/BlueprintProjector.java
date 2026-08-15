package com.yjiyun.chatflows.runtime.blueprint;
import com.yjiyun.chatflows.runtime.security.TenantToolPermissions; import io.agentscope.core.agent.RuntimeContext; import io.agentscope.harness.agent.HarnessAgent; import io.agentscope.harness.agent.filesystem.model.ReadResult; import java.util.*; import java.util.concurrent.*;
public final class BlueprintProjector {
 /** Workspace root — never under skills/, or Harness treats the filename as a Skill. */
 private static final String INLINE_MANIFEST=".blueprint-inline-manifest";
 private static final String LEGACY_INLINE_MANIFEST="skills/.blueprint-inline-manifest";
 private static final String SKILL_NAME="[a-z0-9][a-z0-9-]*";
 private final HarnessAgent agent; private final BlueprintRepository repo; private final TenantToolPermissions permissions; private final Map<String,Integer> ephemeral=new ConcurrentHashMap<>(); private final Map<String,Object> locks=new ConcurrentHashMap<>();
 public BlueprintProjector(HarnessAgent agent,BlueprintRepository repo,TenantToolPermissions permissions){this.agent=agent;this.repo=repo;this.permissions=permissions;}
 public AgentBlueprint projectPublished(String client,String user,String runtimeAgent,RuntimeContext rc){AgentBlueprint bp=repo.resolvePublished(client,user,runtimeAgent).orElseThrow(()->new IllegalStateException("no PUBLISHED blueprint binding"));permissions.configure(bp.clientCode(),bp.tools().allow(),bp.tools().deny());String key=client+":"+user+":"+runtimeAgent;synchronized(locks.computeIfAbsent(key,k->new Object())){if(repo.projectedVersion(client,user,runtimeAgent)!=bp.version()){projectFiles(bp,rc);repo.markProjected(client,user,runtimeAgent,bp.version());}}return bp;}
 public AgentBlueprint projectEphemeral(AgentBlueprint bp,RuntimeContext rc){permissions.configure(bp.clientCode(),bp.tools().allow(),bp.tools().deny());String key="dryrun:"+rc.getUserId()+":"+rc.getSessionId();synchronized(locks.computeIfAbsent(key,k->new Object())){if(!Objects.equals(ephemeral.get(key),bp.version())){projectFiles(bp,rc);ephemeral.put(key,bp.version());}}return bp;}
 private void projectFiles(AgentBlueprint bp,RuntimeContext rc){var ws=agent.getWorkspaceManager();var fs=ws.getFilesystem();Set<String> next=new TreeSet<>();for(AgentBlueprint.Skill s:bp.skills())if("inline".equals(s.source()))next.add(skillName(s.name()));Set<String> previous=readManifest(rc);for(String old:previous)if(!next.contains(old)){var result=fs.delete(rc,"skills/"+skillName(old));if(!result.isSuccess()&&fs.exists(rc,"skills/"+old))throw new IllegalStateException("failed to remove stale inline Skill: "+old+": "+result.error());}ws.writeUtf8WorkspaceRelative(rc,"AGENTS.md",safe(bp.prompt().agentsMd()));ws.writeUtf8WorkspaceRelative(rc,"SOUL.md",safe(bp.prompt().soulMd()));ws.writeUtf8WorkspaceRelative(rc,"knowledge/KNOWLEDGE.md",safe(bp.prompt().knowledgeMd()));for(AgentBlueprint.Skill s:bp.skills())if("inline".equals(s.source()))ws.writeUtf8WorkspaceRelative(rc,"skills/"+skillName(s.name())+"/SKILL.md",safe(s.skillMd()));ws.writeUtf8WorkspaceRelative(rc,INLINE_MANIFEST,String.join("\n",next));ws.writeUtf8WorkspaceRelative(rc,".blueprint-identity","runtimeAgentId="+bp.runtimeAgentId()+"\nskills="+bp.skills().stream().map(AgentBlueprint.Skill::name).collect(java.util.stream.Collectors.joining(",")));fs.delete(rc,LEGACY_INLINE_MANIFEST);}
 private Set<String> readManifest(RuntimeContext rc){Set<String> names=readManifestAt(rc,INLINE_MANIFEST);if(!names.isEmpty())return names;return readManifestAt(rc,LEGACY_INLINE_MANIFEST);}
 private Set<String> readManifestAt(RuntimeContext rc,String path){ReadResult result=agent.getWorkspaceManager().getFilesystem().read(rc,path,0,0);if(!result.isSuccess()||result.fileData()==null||result.fileData().content()==null)return Set.of();Set<String> names=new TreeSet<>();for(String line:result.fileData().content().split("\\R")){String n=line.trim();if(n.matches(SKILL_NAME))names.add(n);}return names;}
 public int projectedVersion(String client,String user,String agentId){return repo.projectedVersion(client,user,agentId);}
 /** Read-only inspect: never projects. Empty workspace files mean chat has not projected yet. */
 public Map<String,Object> inspectPublished(String client,String user,String agent,RuntimeContext rc){
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("inspector",true);
  Optional<AgentBlueprint> found=repo.resolvePublished(client,user,agent);
  out.put("published",found.isPresent());
  if(found.isEmpty())return out;
  AgentBlueprint bp=found.get();
  out.put("blueprintId",bp.blueprintId());
  out.put("version",bp.version());
  out.put("clientCode",bp.clientCode());
  out.put("runtimeAgentId",bp.runtimeAgentId());
  out.put("projectedVersion",repo.projectedVersion(client,user,agent));
  Map<String,String> prompt=new LinkedHashMap<>();
  prompt.put("agentsMd",safe(bp.prompt().agentsMd()));
  prompt.put("soulMd",safe(bp.prompt().soulMd()));
  prompt.put("knowledgeMd",safe(bp.prompt().knowledgeMd()));
  out.put("prompt",prompt);
  Map<String,String> workspace=new LinkedHashMap<>();
  workspace.put("agentsMd",readUtf8(rc,"AGENTS.md"));
  workspace.put("soulMd",readUtf8(rc,"SOUL.md"));
  workspace.put("knowledgeMd",readUtf8(rc,"knowledge/KNOWLEDGE.md"));
  workspace.put("identity",readUtf8(rc,".blueprint-identity"));
  out.put("workspace",workspace);
  Map<String,Boolean> match=new LinkedHashMap<>();
  match.put("agentsMd",workspace.get("agentsMd").equals(prompt.get("agentsMd")));
  match.put("soulMd",workspace.get("soulMd").equals(prompt.get("soulMd")));
  match.put("knowledgeMd",workspace.get("knowledgeMd").equals(prompt.get("knowledgeMd")));
  out.put("match",match);
  return out;
 }
 private String readUtf8(RuntimeContext rc,String path){ReadResult result=agent.getWorkspaceManager().getFilesystem().read(rc,path,0,0);if(!result.isSuccess()||result.fileData()==null||result.fileData().content()==null)return "";return result.fileData().content();}
 private static String safe(String s){return s==null?"":s;} private static String skillName(String n){if(n==null||!n.matches(SKILL_NAME))throw new IllegalArgumentException("invalid skill name");return n;}
}
