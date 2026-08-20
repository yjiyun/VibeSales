package com.yjiyun.chatflows.manager.observability;
import io.opentelemetry.api.common.AttributeKey; import io.opentelemetry.api.common.Attributes; import io.opentelemetry.api.trace.*; import io.opentelemetry.context.Context;
import java.util.*;
/** Locks the GenAI input/output.messages JSON shape (panel-recognized, §5.3) + escaping + capture toggle. */
public final class ManagerTelemetryMessagesSelfTest {
 public static void main(String[] args)throws Exception{
  // 1) capture toggle：默认开、显式 false/none 关，其余当开。
  if(!ManagerTelemetry.captureContent(Map.of()))throw new AssertionError("default must capture");
  if(!ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","span_and_event")))throw new AssertionError("span_and_event must capture");
  if(ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","false")))throw new AssertionError("false must not capture");
  if(ManagerTelemetry.captureContent(Map.of("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","none")))throw new AssertionError("none must not capture");

  // 2) input/output 写到面板认的属性键，且正文里的引号/换行被正确转义（不是裸字符串拼接）。
  RecordingSpan span=new RecordingSpan();
  ManagerTelemetry.input(span,"user","P1 spec:\nline \"with quotes\" and\ttab");
  ManagerTelemetry.output(span,"assistant","tool_calls: await_leader","tool_calls");
  String in=span.attrs.get("gen_ai.input.messages"),out=span.attrs.get("gen_ai.output.messages");
  if(in==null||out==null)throw new AssertionError("messages not written: "+span.attrs);
  // 面板 JSON 形状：[{role,parts:[{type:text,content}]}]
  if(!in.startsWith("[{")||!in.contains("\"role\":\"user\"")||!in.contains("\"type\":\"text\"")||!in.contains("\"parts\":["))throw new AssertionError(in);
  if(!out.contains("\"role\":\"assistant\"")||!out.contains("\"finish_reason\":\"tool_calls\""))throw new AssertionError(out);
  // 转义：换行/引号/制表符按 JSON 转义，正文里不得出现裸换行。
  if(!in.contains("\\n")||!in.contains("\\\"with quotes\\\"")||!in.contains("\\t"))throw new AssertionError("content not escaped: "+in);
  if(in.indexOf('\n')>=0)throw new AssertionError("raw newline leaked into attribute value");

  System.out.println("[PASS] manager GenAI input/output.messages shape + escaping + capture toggle");
 }
 /** 只记录 setAttribute(String) 的最小 Span 桩。 */
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
