package com.yjiyun.chatflows.manager.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import java.util.HashMap;
import java.util.Map;

/** Locks manager-side GenAI attribute semantics. */
public final class ManagerTelemetryMessagesSelfTest {
 public static void main(String[] args)throws Exception{
  if(!ManagerTelemetry.captureContent(Map.of()))throw new AssertionError("default must capture");
  if(!ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","span_and_event")))throw new AssertionError("span_and_event must capture");
  if(ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","false")))throw new AssertionError("false must not capture");
  if(ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","none")))throw new AssertionError("none must not capture");

  eq(ManagerTelemetry.spanKind("plan"),"AGENT","plan kind");
  eq(ManagerTelemetry.operationName("plan"),"invoke_agent","plan operation");
  eq(ManagerTelemetry.spanKind("dispatch"),"TOOL","dispatch kind");
  eq(ManagerTelemetry.operationName("dispatch"),"execute_tool","dispatch operation");
  eq(ManagerTelemetry.toolName("collect"),"collect_result","collect tool name");

  RecordingSpan agentSpan=new RecordingSpan();
  ManagerTelemetry.writeInput(agentSpan,"plan","user","P1 spec:\nline \"with quotes\" and\ttab");
  ManagerTelemetry.writeOutput(agentSpan,"plan","assistant","tool_calls: await_leader","tool_calls");
  String in=agentSpan.attrs.get("gen_ai.input.messages"),out=agentSpan.attrs.get("gen_ai.output.messages");
  if(in==null||out==null)throw new AssertionError("agent messages not written: "+agentSpan.attrs);
  if(!in.startsWith("[{")||!in.contains("\"role\":\"user\"")||!in.contains("\"type\":\"text\"")||!in.contains("\"parts\":["))throw new AssertionError(in);
  if(!out.contains("\"role\":\"assistant\"")||!out.contains("\"finish_reason\":\"tool_calls\""))throw new AssertionError(out);
  if(!in.contains("\\n")||!in.contains("\\\"with quotes\\\"")||!in.contains("\\t"))throw new AssertionError("content not escaped: "+in);
  if(in.indexOf('\n')>=0)throw new AssertionError("raw newline leaked into attribute value");
  if(agentSpan.attrs.containsKey("gen_ai.tool.call.arguments")||agentSpan.attrs.containsKey("gen_ai.tool.call.result"))throw new AssertionError("agent span leaked tool attributes: "+agentSpan.attrs);

  RecordingSpan toolSpan=new RecordingSpan();
  ManagerTelemetry.writeInput(toolSpan,"dispatch","user","dispatch phase P1");
  ManagerTelemetry.writeOutput(toolSpan,"dispatch","assistant","worker accepted","success");
  eq(toolSpan.attrs.get("gen_ai.tool.call.arguments"),"dispatch phase P1","tool arguments");
  eq(toolSpan.attrs.get("gen_ai.tool.call.result"),"worker accepted","tool result");
  if(!toolSpan.attrs.containsKey("gen_ai.input.messages")||!toolSpan.attrs.containsKey("gen_ai.output.messages"))throw new AssertionError("tool span should keep compatibility messages: "+toolSpan.attrs);

  System.out.println("[PASS] manager GenAI kind/operation/body semantics + escaping + capture toggle");
 }

 private static void eq(Object got,Object want,String label){if(!java.util.Objects.equals(got,want))throw new AssertionError(label+": got "+got+" want "+want);}

 private static final class RecordingSpan implements Span{
  final Map<String,String> attrs=new HashMap<>();
  @Override public <T> Span setAttribute(AttributeKey<T> key,T value){if(value!=null)attrs.put(key.getKey(),String.valueOf(value));return this;}
  @Override public Span addEvent(String name,Attributes attributes){return this;}
  @Override public Span addEvent(String name,Attributes attributes,long timestamp,java.util.concurrent.TimeUnit unit){return this;}
  @Override public Span setStatus(StatusCode statusCode,String description){return this;}
  @Override public Span recordException(Throwable exception,Attributes additionalAttributes){return this;}
  @Override public Span updateName(String name){return this;}
  @Override public void end(){}
  @Override public void end(long timestamp,java.util.concurrent.TimeUnit unit){}
  @Override public SpanContext getSpanContext(){return SpanContext.getInvalid();}
  @Override public boolean isRecording(){return true;}
 }
}
