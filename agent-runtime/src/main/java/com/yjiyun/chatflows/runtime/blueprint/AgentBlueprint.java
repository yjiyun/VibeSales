package com.yjiyun.chatflows.runtime.blueprint;
import java.util.*;
public record AgentBlueprint(String blueprintId,int version,String clientCode,String runtimeAgentId,Meta meta,Prompt prompt,List<Skill> skills,Tools tools,RuntimeSpec runtime){
 public AgentBlueprint{Objects.requireNonNull(blueprintId);Objects.requireNonNull(clientCode);Objects.requireNonNull(runtimeAgentId);Objects.requireNonNull(prompt);skills=List.copyOf(skills);Objects.requireNonNull(tools);Objects.requireNonNull(runtime);if(!"USER".equals(runtime.isolationScope()))throw new IllegalArgumentException("runtime isolationScope is immutable USER");}
 public record Prompt(String agentsMd,String soulMd,String knowledgeMd){}
 public record Meta(String industry,List<String> scenarios,String generatedBy,String runId){public Meta{scenarios=scenarios==null?List.of():List.copyOf(scenarios);}}
 public record Skill(String name,String source,String ref,String skillMd){}
 public record Tools(List<String> allow,List<String> deny,List<McpServer> mcpServers){public Tools{allow=List.copyOf(allow);deny=List.copyOf(deny);mcpServers=List.copyOf(mcpServers);}}
 public record McpServer(String name,String url,String transport){}
 public record RuntimeSpec(String model,String isolationScope,int maxContextTokens){}
}
