package com.yjiyun.chatflows.manager;
import com.fasterxml.jackson.databind.*; import com.yjiyun.chatflows.manager.control.*; import java.util.*;
public final class CompletionGateSelfTest {
 private static final ObjectMapper JSON=new ObjectMapper(); private static final String RUN="550e8400-e29b-41d4-a716-446655440000";
 public static void main(String[] args)throws Exception{
  String body="---\nrun_id: "+RUN+"\nstatus: SUCCEEDED\n---\n"+String.join("\n",List.of("wizard_state@1","triage@1","match_result@1","guidance@1","expert_dispatch@1","expert_result@4","blueprint_check@1","blueprint@1","approval@2","import_result@2","dry_run@1","evidence@1"));
  RunSupervisor.Result terminal=RunSupervisor.terminal(body,RUN);if(terminal==null)throw new AssertionError("valid terminal not parsed");
  JsonNode complete=JSON.readTree(snapshot("SUCCEEDED","P4",true));if(CompletionGate.rejectReason(terminal,complete).isPresent())throw new AssertionError("complete P3C/P4 snapshot rejected: "+CompletionGate.rejectReason(terminal,complete));
  JsonNode p1=JSON.readTree("{\"run\":{\"run_id\":\""+RUN+"\",\"status\":\"RUNNING\",\"current_phase\":\"P2\",\"build_path\":null},\"artifacts\":[]}");if(CompletionGate.rejectReason(terminal,p1).isEmpty())throw new AssertionError("P1 false success accepted");
  JsonNode noHuman=JSON.readTree(snapshot("SUCCEEDED","P4",false));if(CompletionGate.rejectReason(terminal,noHuman).isEmpty())throw new AssertionError("success without consumed Human approval accepted");
  RunSupervisor.Result failed=RunSupervisor.terminal("---\nrun_id: "+RUN+"\nstatus: FAILED\n---\nreason",RUN);if(CompletionGate.rejectReason(failed,p1).isPresent())throw new AssertionError("FAILED must not require success evidence");
  System.out.println("[PASS] completion gate rejects premature success and requires authoritative P3C/P4 Human evidence");
 }
 private static String snapshot(String status,String phase,boolean approved)throws Exception{
  List<Map<String,Object>> artifacts=new ArrayList<>();for(String kind:List.of("wizard_state","triage","match_result","guidance","expert_dispatch","blueprint_check","blueprint"))artifacts.add(artifact(kind,"blueprint-compose",Map.of()));
  for(String writer:List.of("persona-expert","business-expert","skill-expert","tool-expert"))artifacts.add(artifact("expert_result",writer,Map.of("role",writer)));
  artifacts.add(artifact("approval","flow-import-run",Map.of("status",approved?"CONSUMED":"PENDING","actor",approved?"@human:local":"")));
  artifacts.add(artifact("import_result","flow-import-run",Map.of("imported",Map.of("external_id","ext-1"),"binding",Map.of("user_id","u1"))));
  artifacts.add(artifact("dry_run","flow-import-run",Map.of("ok",true)));
  artifacts.add(artifact("evidence","flow-import-run",Map.of("event","P4_EXECUTED","dry_run_ok",true)));
  return JSON.writeValueAsString(Map.of("run",Map.of("run_id",RUN,"status",status,"current_phase",phase,"build_path","P3C"),"artifacts",artifacts));
 }
 private static Map<String,Object> artifact(String kind,String writer,Object payload){return Map.of("kind",kind,"written_by",writer,"payload",payload);}
}
