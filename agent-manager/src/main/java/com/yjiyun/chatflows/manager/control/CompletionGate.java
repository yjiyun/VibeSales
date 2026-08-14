package com.yjiyun.chatflows.manager.control;
import com.fasterxml.jackson.databind.JsonNode; import java.util.*;
/** Rejects a Markdown SUCCEEDED claim unless the authoritative Nest run proves the complete P1-P4 DAG. */
public final class CompletionGate {
 private static final Set<String> COMMON=Set.of("wizard_state","triage","match_result","guidance"),P4=Set.of("approval","import_result","dry_run","evidence");
 private CompletionGate(){}
 public static Optional<String> rejectReason(RunSupervisor.Result terminal,JsonNode snapshot){
  if(terminal==null||!"SUCCEEDED".equals(terminal.status()))return Optional.empty();
  JsonNode run=snapshot.path("run"),artifacts=snapshot.path("artifacts");String runId=terminal.runId(),path=run.path("build_path").asText();
  if(!runId.equals(run.path("run_id").asText())||!"SUCCEEDED".equals(run.path("status").asText())||!"P4".equals(run.path("current_phase").asText()))return Optional.of("authoritative run is not SUCCEEDED at P4");
  if(!Set.of("P3","P3B","P3C").contains(path)||!artifacts.isArray())return Optional.of("authoritative build path/artifacts invalid");
  Map<String,List<JsonNode>> byKind=new HashMap<>();for(JsonNode artifact:artifacts)byKind.computeIfAbsent(artifact.path("kind").asText(),k->new ArrayList<>()).add(artifact);
  Set<String> required=new LinkedHashSet<>(COMMON);required.addAll(P4);required.addAll(switch(path){case "P3"->Set.of("personalized_package","flow_check");case "P3B"->Set.of("flow_yaml","flow_check");default->Set.of("expert_dispatch","expert_result","blueprint_check","blueprint");});
  for(String kind:required)if(!byKind.containsKey(kind))return Optional.of("authoritative artifact missing: "+kind);
  if("P3C".equals(path)){Set<String> experts=new HashSet<>();for(JsonNode artifact:byKind.get("expert_result"))experts.add(artifact.path("written_by").asText());if(!experts.containsAll(Set.of("persona-expert","business-expert","skill-expert","tool-expert")))return Optional.of("four P3C expert results are not authoritative");}
  JsonNode approval=latest(byKind,"approval").path("payload"),imported=latest(byKind,"import_result").path("payload"),dryRun=latest(byKind,"dry_run").path("payload"),evidence=latest(byKind,"evidence").path("payload");
  if(!"CONSUMED".equals(approval.path("status").asText())||approval.path("actor").asText().isBlank())return Optional.of("Human approval was not consumed");
  if(imported.path("imported").path("external_id").asText().isBlank()||imported.path("binding").isMissingNode()||imported.path("binding").isNull())return Optional.of("P4 import or binding missing");
  if(!dryRun.path("ok").asBoolean(false))return Optional.of("runtime dry-run did not succeed");
  if(!"P4_EXECUTED".equals(evidence.path("event").asText())||!evidence.path("dry_run_ok").asBoolean(false))return Optional.of("P4 execution evidence missing");
  Set<String> pointers=new HashSet<>();for(RunSupervisor.ArtifactPointer pointer:RunSupervisor.artifacts(terminal.summary()))pointers.add(pointer.kind());if(!pointers.containsAll(required))return Optional.of("result.md omits required artifact pointers");
  return Optional.empty();
 }
 private static JsonNode latest(Map<String,List<JsonNode>> byKind,String kind){List<JsonNode> values=byKind.get(kind);return values.get(values.size()-1);}
}
