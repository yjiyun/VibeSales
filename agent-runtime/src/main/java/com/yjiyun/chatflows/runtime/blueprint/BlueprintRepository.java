package com.yjiyun.chatflows.runtime.blueprint;
import java.util.Optional;
public interface BlueprintRepository {
 Optional<AgentBlueprint> resolvePublished(String clientCode,String userId,String runtimeAgentId);
 int projectedVersion(String clientCode,String userId,String runtimeAgentId);
 void markProjected(String clientCode,String userId,String runtimeAgentId,int version);
}
