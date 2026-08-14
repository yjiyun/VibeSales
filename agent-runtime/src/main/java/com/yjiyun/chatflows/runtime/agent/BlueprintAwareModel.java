package com.yjiyun.chatflows.runtime.agent;
import io.agentscope.core.message.MsgRole; import io.agentscope.core.message.TextBlock; import io.agentscope.core.model.*; import java.util.*; import java.util.stream.Collectors; import reactor.core.publisher.Flux;
/**
 * 产物探针：证明投影了哪份 Blueprint（runtimeAgentId / soul 标题 / Skill），不是原文回声。
 * 真模型验收不要用这个；verify-all 链路烟测继续用 DeterministicModel。
 */
public final class BlueprintAwareModel implements Model {
 public static final String ID="blueprint-aware-test";
 public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> m,List<ToolSchema> t,GenerateOptions o){
  String system=m==null?"":m.stream().filter(msg->msg.getRole()==MsgRole.SYSTEM).map(io.agentscope.core.message.Msg::getTextContent).filter(Objects::nonNull).collect(Collectors.joining("\n"));
  String last=m==null||m.isEmpty()?"":Optional.ofNullable(m.get(m.size()-1).getTextContent()).orElse("");
  String agent=extract(system,"runtimeAgentId=");
  String skills=extract(system,"skills=");
  String soul=system.lines().map(String::trim).filter(line->line.startsWith("#")).findFirst().orElse("");
  String text="BLUEPRINT_OK runtimeAgentId="+(agent.isBlank()?"unknown":agent)+" soul="+soul+" skills="+skills+" sysChars="+system.length()+"\nUSER: "+last;
  return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).usage(new ChatUsage(1,1,0)).finishReason("stop").build());
 }
 public String getModelName(){return ID;} public int getContextWindowSize(){return 32768;}
 private static String extract(String system,String key){int at=system.indexOf(key);if(at<0)return "";int start=at+key.length();int end=system.indexOf('\n',start);return (end<0?system.substring(start):system.substring(start,end)).trim();}
}
