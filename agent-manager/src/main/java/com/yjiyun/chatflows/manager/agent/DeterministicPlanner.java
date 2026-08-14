package com.yjiyun.chatflows.manager.agent;
import com.yjiyun.chatflows.manager.OrchestratorAgent;
public final class DeterministicPlanner {
 private final OrchestratorAgent orchestrator; private final String teamYaml;
 public DeterministicPlanner(OrchestratorAgent orchestrator,String teamYaml){this.orchestrator=orchestrator;this.teamYaml=teamYaml;}
 public void start(OrchestrationRun run,String spec)throws Exception{orchestrator.apply("Team","chatflows-build-team",teamYaml);run.event("team_declared",java.util.Map.of("team","chatflows-build-team"));orchestrator.dispatch(run.roomId(),run.runId(),run.clientCode(),"P1",spec);run.artifact("spec.md","READY","P1 task specification");run.event("dispatch",java.util.Map.of("phase","P1"));}
}
