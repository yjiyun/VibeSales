package com.yjiyun.chatflows.manager;
import java.nio.file.*; import java.util.*;
/** The orchestrator LLM must fail closed: only explicit off, or a fully configured Higress route (A21). */
public final class ManagerConfigSelfTest {
 public static void main(String[] args)throws Exception{
  Path team=Files.createTempFile("manager-team",".yaml");Files.writeString(team,"apiVersion: agentteams.io/v1beta1\nkind: Team\nmetadata: {name: test}\nspec: {leader: leader, workers: []}\n");
  Map<String,String> env=new HashMap<>(Map.ofEntries(
   Map.entry("AGENTTEAMS_CONTROLLER_URL","http://controller"),Map.entry("AGENTTEAMS_AUTH_TOKEN","controller-token"),Map.entry("AGENTTEAMS_MATRIX_URL","http://matrix"),Map.entry("AGENTTEAMS_MATRIX_ACCESS_TOKEN","matrix-token"),Map.entry("CHATFLOWS_TASK_FS_ENDPOINT","http://minio"),Map.entry("CHATFLOWS_TASK_FS_ACCESS_KEY","access"),Map.entry("CHATFLOWS_TASK_FS_SECRET_KEY","secret"),Map.entry("CHATFLOWS_TASK_FS_BUCKET","tasks"),Map.entry("CHATFLOWS_TASK_FS_PREFIX","teams/chatflows-build-team/shared/tasks"),Map.entry("CHATFLOWS_APPROVAL_SIGNING_SECRET","approval-secret-at-least-32-characters"),Map.entry("AGENTTEAMS_HUMAN_IDS","@human:local"),Map.entry("AGENTTEAMS_LEADER_IDS","@leader:local"),Map.entry("AGENTTEAMS_MANAGER_IDS","@manager:local"),Map.entry("MANAGER_AUTH_TOKEN","manager-auth-token-123"),Map.entry("MANAGER_ADMIN_TOKEN","manager-admin-token-456"),Map.entry("CHATFLOWS_NEST_URL","http://nest"),Map.entry("PIPELINE_CONTROL_TOKEN","pipeline-token-12345"),Map.entry("AGENTTEAMS_TEAM_FILE",team.toString()),Map.entry("ORCHESTRATOR_LLM","on"),Map.entry("ORCHESTRATOR_LLM_BASE_URL","https://model.higress.example/v1"),Map.entry("HIGRESS_CONSUMER_TOKEN","consumer-token-12345")
  ));
  if(!ManagerConfig.from(env,true).orchestratorLlm())throw new AssertionError("valid LLM configuration was disabled");
  expect(env,"DASHSCOPE_API_KEY","direct-secret", "DASHSCOPE_API_KEY");
  expect(env,"HIGRESS_CONSUMER_TOKEN","", "HIGRESS_CONSUMER_TOKEN");
  expect(env,"ORCHESTRATOR_LLM_BASE_URL","https://dashscope.aliyuncs.com/v1", "Higress");
  expect(env,"ORCHESTRATOR_LLM","auto", "must be on or off");
  Map<String,String> privateHop=new HashMap<>(env);privateHop.put("ORCHESTRATOR_LLM_BASE_URL","http://agentteams-higress:8080/v1");if(!ManagerConfig.from(privateHop,true).orchestratorLlm())throw new AssertionError("private-network Higress hop was rejected");
  env.put("ORCHESTRATOR_LLM","off");env.remove("HIGRESS_CONSUMER_TOKEN");env.remove("ORCHESTRATOR_LLM_BASE_URL");if(ManagerConfig.from(env,true).orchestratorLlm())throw new AssertionError("explicit off enabled LLM");
  System.out.println("[PASS] manager LLM mode fails closed unless explicitly off or fully configured for Higress");
 }
 private static void expect(Map<String,String> base,String key,String value,String message){Map<String,String> env=new HashMap<>(base);env.put(key,value);try{ManagerConfig.from(env,true);throw new AssertionError("invalid "+key+" accepted");}catch(IllegalStateException expected){if(!expected.getMessage().contains(message))throw new AssertionError(expected);}}
}
