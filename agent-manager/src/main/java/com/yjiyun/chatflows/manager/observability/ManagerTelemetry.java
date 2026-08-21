package com.yjiyun.chatflows.manager.observability;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * OTel GenAI/vibesales attributes for the Java orchestration edge. All telemetry is best-effort.
 */
public final class ManagerTelemetry {
 public static final String DEFAULT_SERVICE_NAME="vibe-sales-manager";
 private static final String KIND_AGENT="AGENT";
 private static final String KIND_TOOL="TOOL";
 private static final boolean CAPTURE_CONTENT=captureContent(System.getenv());
 private static final JsonFactory JSON=new JsonFactory();
 private static final Map<Span,SpanProfile> SPANS=Collections.synchronizedMap(new WeakHashMap<>());
 private static volatile boolean otlpMode=false;

 private ManagerTelemetry(){}

 private static Tracer tracer(){return GlobalOpenTelemetry.getTracer("com.yjiyun.chatflows.agent-manager","1.0.0");}

 static String operationName(String operation){
  return isToolOperation(operation)?"execute_tool":"invoke_agent";
 }

 static String spanKind(String operation){
  return isToolOperation(operation)?KIND_TOOL:KIND_AGENT;
 }

 static String toolName(String operation){
  switch(operation){
   case "dispatch": return "dispatch_phase";
   case "collect": return "collect_result";
   default: return operation;
  }
 }

 private static boolean isToolOperation(String operation){
  return "dispatch".equals(operation)||"collect".equals(operation);
 }

 static String displayName(String operation){
  switch(operation){
   case "dispatch": return "编排·派发 P1 任务";
   case "collect": return "编排·收取 Worker 结果";
   case "plan": return "编排·LLM 规划";
   case "plan.resume": return "编排·LLM 规划续跑";
   case "run.summary": return "编排·整体运行";
   default: return "编排·"+operation;
  }
 }

 public static Span start(String operation,String runId,String clientCode,String phase){
  try{
   String kind=spanKind(operation);
   String normalizedOperation=operationName(operation);
   Span span=tracer().spanBuilder(displayName(operation))
     .setSpanKind(SpanKind.INTERNAL)
     .setAttribute("gen_ai.span.kind",kind)
     .setAttribute("gen_ai.operation.name",normalizedOperation)
     .setAttribute("gen_ai.session.id",runId)
     .setAttribute("vibesales.operation",operation)
     .setAttribute("vibesales.run_id",runId)
     .setAttribute("vibesales.client_code",clientCode)
     .setAttribute("vibesales.phase",phase)
     .setAttribute("vibesales.agent","orchestrator")
     .startSpan();
   if(KIND_AGENT.equals(kind))span.setAttribute("gen_ai.agent.name","orchestrator");
   if(KIND_TOOL.equals(kind))span.setAttribute("gen_ai.tool.name",toolName(operation));
   SPANS.put(span,new SpanProfile(operation,kind));
   return span;
  }catch(Throwable ignored){return Span.getInvalid();}
 }

 public static void success(Span span){try{span.setStatus(StatusCode.OK);}catch(Throwable ignored){}}
 public static void failure(Span span,Throwable error){try{span.recordException(error);span.setStatus(StatusCode.ERROR,error.getClass().getSimpleName());}catch(Throwable ignored){}}
 public static void planner(Span span,String mode){try{span.setAttribute("vibesales.orchestration.planner",mode);}catch(Throwable ignored){}}
 public static void usage(Span span,int input,int output){try{span.setAttribute("gen_ai.usage.input_tokens",input);span.setAttribute("gen_ai.usage.output_tokens",output);}catch(Throwable ignored){}}

 static boolean captureContent(Map<String,String> env){String v=env.getOrDefault("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT","span_and_event").trim().toLowerCase();return !(v.equals("false")||v.equals("none")||v.isEmpty());}

 public static void input(Span span,String role,String text){writeInput(span,profileOf(span).operation(),role,text);}
 public static void output(Span span,String role,String text,String finishReason){writeOutput(span,profileOf(span).operation(),role,text,finishReason);}

 static void writeInput(Span span,String operation,String role,String text){
  if(!CAPTURE_CONTENT||text==null||text.isBlank())return;
  try{
   if(isToolOperation(operation)){
    span.setAttribute("gen_ai.tool.call.arguments",text);
    span.setAttribute("gen_ai.input.messages",messagesJson(role,text,null));
    return;
   }
   span.setAttribute("gen_ai.input.messages",messagesJson(role,text,null));
  }catch(Throwable ignored){}
 }

 static void writeOutput(Span span,String operation,String role,String text,String finishReason){
  if(!CAPTURE_CONTENT||text==null||text.isBlank())return;
  try{
   if(isToolOperation(operation)){
    span.setAttribute("gen_ai.tool.call.result",text);
    span.setAttribute("gen_ai.output.messages",messagesJson(role,text,finishReason));
    return;
   }
   span.setAttribute("gen_ai.output.messages",messagesJson(role,text,finishReason));
  }catch(Throwable ignored){}
 }

 public static void emitRunSummary(String runId,String clientCode,String input,String output,String status){
  Span span=start("run.summary",runId,clientCode,"orchestration");
  try{
   span.setAttribute("vibesales.run_status",status==null?"":status);
   input(span,"user",truncate(input));
   output(span,"assistant",truncate(output),status);
   success(span);
  }catch(Throwable error){
   failure(span,error);
  }finally{
   end(span);
  }
 }

 private static String messagesJson(String role,String text,String finishReason)throws IOException{
  StringWriter w=new StringWriter();
  try(JsonGenerator g=JSON.createGenerator(w)){
   g.writeStartArray();
   g.writeStartObject();
   g.writeStringField("role",role);
   g.writeArrayFieldStart("parts");
   g.writeStartObject();
   g.writeStringField("type","text");
   g.writeStringField("content",text);
   g.writeEndObject();
   g.writeEndArray();
   if(finishReason!=null)g.writeStringField("finish_reason",finishReason);
   g.writeEndObject();
   g.writeEndArray();
  }
  return w.toString();
 }

 public static void end(Span span){
  try{
   SPANS.remove(span);
   span.end();
  }catch(Throwable ignored){}
 }

 public static boolean otlpMode(){return otlpMode;}

 public static boolean install(Map<String,String> env){
  String mode=env.getOrDefault("AGENTLOOP_EXPORTER","off").trim().toLowerCase();
  if(!mode.equals("on"))return false;
  String protocol=env.getOrDefault("AGENTLOOP_PROTOCOL","otlp").trim().toLowerCase();
  if(!protocol.equals("otlp"))return false;
  String endpoint=trim(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
  if(endpoint==null)throw new IllegalStateException("AGENTLOOP_PROTOCOL=otlp + EXPORTER=on requires OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
  var exporter=OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
  for(Map.Entry<String,String> header:parseHeaders(env).entrySet())exporter.addHeader(header.getKey(),header.getValue());
  SdkTracerProvider tracerProvider=SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(exporter.build()).build()).setResource(Resource.getDefault().merge(resource(env))).build();
  OpenTelemetrySdk sdk=OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
  Runtime.getRuntime().addShutdownHook(new Thread(()->sdk.getSdkTracerProvider().shutdown().join(10,java.util.concurrent.TimeUnit.SECONDS)));
  otlpMode=true;
  System.err.println("[manager-telemetry] OTLP on: service="+serviceName(env)+" endpoint="+safeHost(endpoint));
  return true;
 }

 private static SpanProfile profileOf(Span span){
  SpanProfile profile=SPANS.get(span);
  return profile!=null?profile:new SpanProfile("plan",KIND_AGENT);
 }

 private static Resource resource(Map<String,String> env){
  AttributesBuilder attrs=Attributes.builder();
  attrs.put("service.name",serviceName(env));
  String raw=trim(env.get("OTEL_RESOURCE_ATTRIBUTES"));
  if(raw!=null)for(String pair:raw.split(",")){int idx=pair.indexOf('=');if(idx>0){String key=pair.substring(0,idx).trim(),value=pair.substring(idx+1).trim();if(!key.isEmpty()&&!key.equals("service.name"))attrs.put(key,value);}}
  return Resource.create(attrs.build());
 }

 private static String serviceName(Map<String,String> env){String name=trim(env.get("OTEL_SERVICE_NAME"));return name!=null?name:DEFAULT_SERVICE_NAME;}

 private static Map<String,String> parseHeaders(Map<String,String> env){
  Map<String,String> headers=new LinkedHashMap<>();
  String raw=trim(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
  if(raw!=null){for(String pair:raw.split(",")){int idx=pair.indexOf('=');if(idx>0)headers.put(pair.substring(0,idx).trim(),pair.substring(idx+1).trim());}return headers;}
  String licenseKey=trim(env.get("ARMS_LICENSE_KEY"));
  if(licenseKey!=null)headers.put("x-arms-license-key",licenseKey);
  return headers;
 }

 private static String truncate(String value){
  if(value==null)return null;
  String text=value.trim();
  return text.length()<=8000?text:text.substring(0,8000);
 }

 private static String safeHost(String endpoint){try{return java.net.URI.create(endpoint).getHost();}catch(RuntimeException e){return "(invalid)";}}
 private static String trim(String value){if(value==null)return null;String t=value.trim();return t.isEmpty()?null:t;}

 private record SpanProfile(String operation,String kind){}
}
