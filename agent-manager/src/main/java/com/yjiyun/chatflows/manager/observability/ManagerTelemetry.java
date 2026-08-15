package com.yjiyun.chatflows.manager.observability;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Map;
/**
 * OTel GenAI/agentteams attributes for the Java orchestration edge. All telemetry is best-effort.
 *
 * <p>最终方案第二版：OTLP 标准直发为准。{@link #install} 在启动时按 {@code AGENTLOOP_PROTOCOL}
 * 注册带 OTLP exporter 的全局 SDK（与 agent-runtime 的 RuntimeTelemetry 同构）；{@code start} 产的
 * span 因此被真实导出。{@link #otlpMode()} 为 true 时，{@code OrchestratorAgent} 应停用 ROA 镜像
 * （P0-2 防双份）。
 *
 * <p>operation.name 归一（方案 §5.1）：编排器唯一的 agent 行为是 LLM 规划（plan / plan.resume），
 * 映射为面板认识的 {@code invoke_agent}；dispatch / collect 是编排动作，保留原 operation 名
 * （能上报成 trace，在调用树可见，但不占 AI Agent 面板的 agent/tool 节点——语义诚实）。
 */
public final class ManagerTelemetry {
 public static final String DEFAULT_SERVICE_NAME="vibe-sales-manager";
 private static volatile boolean otlpMode=false;
 private ManagerTelemetry(){}

 // 懒加载：不能在类加载时取 tracer——那会把 GlobalOpenTelemetry 锁成 no-op，使 install() 的
 // buildAndRegisterGlobal 抛 "already been called"。install() 先注册全局 SDK，之后首次 start() 才解析 tracer。
 private static Tracer tracer(){return GlobalOpenTelemetry.getTracer("com.yjiyun.chatflows.agent-manager","1.0.0");}

 /** operation → 面板 operation.name：LLM 规划归一为 invoke_agent，其余保留原名。 */
 private static String operationName(String operation){
  return operation.startsWith("plan")?"invoke_agent":operation;
 }

 public static Span start(String operation,String runId,String clientCode,String phase){try{return tracer().spanBuilder("agent-manager."+operation).setSpanKind(SpanKind.INTERNAL).setAttribute("gen_ai.operation.name",operationName(operation)).setAttribute("gen_ai.session.id",runId).setAttribute("agentteams.operation",operation).setAttribute("agentteams.run_id",runId).setAttribute("agentteams.client_code",clientCode).setAttribute("agentteams.phase",phase).setAttribute("agentteams.agent","orchestrator").startSpan();}catch(Throwable ignored){return Span.getInvalid();}}
 public static void success(Span span){try{span.setStatus(StatusCode.OK);}catch(Throwable ignored){}}
 public static void failure(Span span,Throwable error){try{span.recordException(error);span.setStatus(StatusCode.ERROR,error.getClass().getSimpleName());}catch(Throwable ignored){}}
 public static void planner(Span span,String mode){try{span.setAttribute("agentteams.orchestration.planner",mode);}catch(Throwable ignored){}}
 public static void usage(Span span,int input,int output){try{span.setAttribute("gen_ai.usage.input_tokens",input);span.setAttribute("gen_ai.usage.output_tokens",output);}catch(Throwable ignored){}}
 public static void end(Span span){try{span.end();}catch(Throwable ignored){}}

 /** OTLP 生效时为 true：OrchestratorAgent 应停用 ROA 镜像。 */
 public static boolean otlpMode(){return otlpMode;}

 /**
  * 启动引导：{@code AGENTLOOP_EXPORTER=on} 且 {@code AGENTLOOP_PROTOCOL=otlp} 时注册带 OTLP
  * exporter 的全局 SDK 并返回 true；off / roa 时不注册（保持现状）。凭证只从环境注入，不打印。
  */
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

 private static Resource resource(Map<String,String> env){
  AttributesBuilder attrs=Attributes.builder();
  attrs.put("service.name",serviceName(env));
  String raw=trim(env.get("OTEL_RESOURCE_ATTRIBUTES"));
  if(raw!=null)for(String pair:raw.split(",")){int idx=pair.indexOf('=');if(idx>0){String key=pair.substring(0,idx).trim(),value=pair.substring(idx+1).trim();if(!key.isEmpty()&&!key.equals("service.name"))attrs.put(key,value);}}
  return Resource.create(attrs.build());
 }
 private static String serviceName(Map<String,String> env){String name=trim(env.get("OTEL_SERVICE_NAME"));return name!=null?name:DEFAULT_SERVICE_NAME;}
 private static Map<String,String> parseHeaders(Map<String,String> env){
  java.util.LinkedHashMap<String,String> headers=new java.util.LinkedHashMap<>();
  String raw=trim(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
  if(raw!=null){for(String pair:raw.split(",")){int idx=pair.indexOf('=');if(idx>0)headers.put(pair.substring(0,idx).trim(),pair.substring(idx+1).trim());}return headers;}
  String licenseKey=trim(env.get("ARMS_LICENSE_KEY"));
  if(licenseKey!=null)headers.put("x-arms-license-key",licenseKey);
  return headers;
 }
 private static String safeHost(String endpoint){try{return java.net.URI.create(endpoint).getHost();}catch(RuntimeException e){return "(invalid)";}}
 private static String trim(String value){if(value==null)return null;String t=value.trim();return t.isEmpty()?null:t;}
}
