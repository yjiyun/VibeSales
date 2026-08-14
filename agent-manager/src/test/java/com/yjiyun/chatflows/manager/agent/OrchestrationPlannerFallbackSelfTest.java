package com.yjiyun.chatflows.manager.agent;

import com.yjiyun.chatflows.manager.OrchestratorAgent;
import com.yjiyun.chatflows.manager.artifact.FileTaskArtifactStore;
import com.yjiyun.chatflows.manager.matrix.MatrixClient;
import com.yjiyun.chatflows.manager.platform.PlatformClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

public final class OrchestrationPlannerFallbackSelfTest {
 public static void main(String[] args)throws Exception{
  var root=Files.createTempDirectory("planner-fallback");
  List<String> effects=new ArrayList<>();
  PlatformClient platform=(kind,name,yaml)->effects.add("apply:"+name);
  MatrixClient matrix=new MatrixClient(){public void send(String room,String message){effects.add("send:"+message);}public String receive(String room){return "{}";}};
  OrchestratorAgent orchestrator=new OrchestratorAgent(platform,matrix,new FileTaskArtifactStore(root));
  String team="apiVersion: agentteams.io/v1beta1\nkind: Team\nmetadata: {name: chatflows-build-team}\nspec: {leader: chatflows-leader, workers: [wizard-intent]}";
  FailingModel model=new FailingModel();
  OrchestrationPlanner planner=new OrchestrationPlanner(
    new DeterministicPlanner(orchestrator,team),new OrchestrationTools(orchestrator,matrix,team),
    new OrchestrationPlanner.Config(true,"scripted","https://model.higress.example/v1","0123456789abcdef",team,root.resolve("state").toString()),model);
  OrchestrationStore store=new OrchestrationStore(root.resolve("runs"));
  OrchestrationRun first=store.create("550e8400-e29b-41d4-a716-446655440001","acme","!room:local","llm");planner.start(first,"spec-1");
  if(!"deterministic".equals(first.view().get("planner"))||model.calls.get()!=1)throw new AssertionError("first failure did not fall back: "+first.view());
  OrchestrationRun second=store.create("550e8400-e29b-41d4-a716-446655440002","acme","!room:local","llm");planner.start(second,"spec-2");
  if(!"deterministic".equals(second.view().get("planner"))||model.calls.get()!=2||!"deterministic".equals(planner.mode()))throw new AssertionError("circuit did not open after two failures");
  OrchestrationRun third=store.create("550e8400-e29b-41d4-a716-446655440003","acme","!room:local",planner.mode());planner.start(third,"spec-3");
  if(model.calls.get()!=2||!"deterministic".equals(third.view().get("planner")))throw new AssertionError("open circuit still called model");
  long dispatched=effects.stream().filter(value->value.startsWith("send:")).count();
  if(dispatched!=3)throw new AssertionError("fallback dispatch count mismatch: "+effects);
  planner.close();
  System.out.println("[PASS] two LLM failures open deterministic fallback; each run dispatches P1 exactly once");
 }
 private static final class FailingModel implements Model {
  private final AtomicInteger calls=new AtomicInteger();
  public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){calls.incrementAndGet();return Flux.error(new IllegalStateException("planned model failure"));}
  public String getModelName(){return "failing";} public int getContextWindowSize(){return 32768;}
 }
}
