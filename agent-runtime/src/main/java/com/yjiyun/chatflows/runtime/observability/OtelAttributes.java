package com.yjiyun.chatflows.runtime.observability;
import com.yjiyun.chatflows.runtime.blueprint.AgentBlueprint; import java.util.*;
public final class OtelAttributes {
 private OtelAttributes(){}
 public static Map<String,String> forCall(AgentBlueprint bp){Map<String,String> out=new LinkedHashMap<>();out.put("agentteams.client_code",bp.clientCode());out.put("agentteams.runtime_agent_id",bp.runtimeAgentId());out.put("agentteams.blueprint_id",bp.blueprintId());out.put("agentteams.blueprint_version",String.valueOf(bp.version()));out.put("gen_ai.system","dashscope");out.put("gen_ai.request.model",bp.runtime().model().replaceFirst("^dashscope:",""));String run=bp.meta()!=null?bp.meta().runId():null;if(run!=null&&!run.isBlank()){out.put("agentteams.run_id",run);out.put("gen_ai.session.id",run);}return Map.copyOf(out);}
}
