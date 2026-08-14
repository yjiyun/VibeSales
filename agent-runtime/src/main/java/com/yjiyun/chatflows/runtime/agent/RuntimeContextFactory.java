package com.yjiyun.chatflows.runtime.agent;
import io.agentscope.core.agent.RuntimeContext;
public final class RuntimeContextFactory {
 private static final String SAFE_ID="[A-Za-z0-9_-]+";
 public RuntimeContext create(String client,String user,String session,String agent){if(!safe(client)||!safe(user)||!safe(session)||!safe(agent))throw new IllegalArgumentException("invalid tenant/user/session/agent id");return RuntimeContext.builder().userId(client+":"+user).sessionId(agent+":"+session).put("client_code",client).put("runtime_agent_id",agent).build();}
 private static boolean safe(String value){return value!=null&&value.length()<=128&&value.matches(SAFE_ID);}
}
