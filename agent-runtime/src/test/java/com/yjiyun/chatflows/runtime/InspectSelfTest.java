package com.yjiyun.chatflows.runtime;
import com.yjiyun.chatflows.runtime.agent.*; import com.yjiyun.chatflows.runtime.blueprint.*; import com.yjiyun.chatflows.runtime.security.TenantToolPermissions; import io.agentscope.core.agent.RuntimeContext; import java.nio.file.*; import java.util.*;
public final class InspectSelfTest {
 public static void main(String[] args)throws Exception{
  Path ws=Files.createTempDirectory("inspect-workspace");
  System.setProperty("agentscope.state.home",ws.resolve("state").toString());
  Files.writeString(ws.resolve("AGENTS.md"),"seed");
  Files.writeString(ws.resolve("SOUL.md"),"seed");
  Files.writeString(ws.resolve("tools.json"),"{\"mcpServers\":{}}");
  try(AgentFactory f=new AgentFactory(ws,new DeterministicModel())){
   InMemoryBlueprintRepository repo=new InMemoryBlueprintRepository();
   TenantToolPermissions permissions=new TenantToolPermissions();
   BlueprintProjector p=new BlueprintProjector(f.agent(),repo,permissions);
   RuntimeContextFactory contexts=new RuntimeContextFactory();
   AgentBlueprint bp=new AgentBlueprint("bp-inspect",1,"tenant-a","agent-a",
    new AgentBlueprint.Meta("test",List.of(),"test","run-inspect"),
    new AgentBlueprint.Prompt("# A-style","# soul A-style","# knowledge A"),
    List.of(new AgentBlueprint.Skill("inline-help","inline",null,"---\nname: inline-help\ndescription: 当需要帮助时使用\n---\nhelp")),
    new AgentBlueprint.Tools(List.of("read_file","memory_search","load_skill_through_path"),List.of(),List.of()),
    new AgentBlueprint.RuntimeSpec("deterministic-test","USER",8000));
   RuntimeContext before=contexts.create("tenant-a","alice","inspect-before","agent-a");
   Map<String,Object> unpublished=p.inspectPublished("tenant-a","alice","agent-a",before);
   if(Boolean.TRUE.equals(unpublished.get("published")))throw new AssertionError("unpublished inspect reported published");
   repo.stage(bp,"builder");
   repo.publish(bp.blueprintId(),bp.clientCode(),"admin");
   Map<String,Object> pending=p.inspectPublished("tenant-a","alice","agent-a",before);
   @SuppressWarnings("unchecked") Map<String,Boolean> pendingMatch=(Map<String,Boolean>)pending.get("match");
   if(!Boolean.TRUE.equals(pending.get("published"))||Boolean.TRUE.equals(pendingMatch.get("soulMd")))throw new AssertionError("unprojected workspace should not match prompt");
   RuntimeContext after=contexts.create("tenant-a","alice","inspect-after","agent-a");
   p.projectPublished("tenant-a","alice","agent-a",after);
   Map<String,Object> ok=p.inspectPublished("tenant-a","alice","agent-a",after);
   @SuppressWarnings("unchecked") Map<String,String> prompt=(Map<String,String>)ok.get("prompt");
   @SuppressWarnings("unchecked") Map<String,String> workspace=(Map<String,String>)ok.get("workspace");
   @SuppressWarnings("unchecked") Map<String,Boolean> match=(Map<String,Boolean>)ok.get("match");
   if(!workspace.get("soulMd").equals(prompt.get("soulMd"))||!Boolean.TRUE.equals(match.get("soulMd")))throw new AssertionError("projected soul mismatch: "+workspace.get("soulMd"));
   if(!workspace.get("agentsMd").contains("A-style"))throw new AssertionError(workspace.get("agentsMd"));
   System.out.println("[PASS] inspect reads PUBLISHED prompt vs projected workspace without extra projection");
  }
 }
}
