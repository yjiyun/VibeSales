package com.yjiyun.chatflows.manager.observability;
import io.opentelemetry.api.GlobalOpenTelemetry; import io.opentelemetry.api.trace.*;
/** OTel GenAI/agentteams attributes for the Java orchestration edge. All telemetry is best-effort. */
public final class ManagerTelemetry {
 private static final Tracer TRACER=GlobalOpenTelemetry.getTracer("com.yjiyun.chatflows.agent-manager","1.0.0");
 private ManagerTelemetry(){}
 public static Span start(String operation,String runId,String clientCode,String phase){try{return TRACER.spanBuilder("agent-manager."+operation).setSpanKind(SpanKind.INTERNAL).setAttribute("gen_ai.operation.name",operation).setAttribute("agentteams.run_id",runId).setAttribute("agentteams.client_code",clientCode).setAttribute("agentteams.phase",phase).setAttribute("agentteams.agent","orchestrator").startSpan();}catch(Throwable ignored){return Span.getInvalid();}}
 public static void success(Span span){try{span.setStatus(StatusCode.OK);}catch(Throwable ignored){}}
 public static void failure(Span span,Throwable error){try{span.recordException(error);span.setStatus(StatusCode.ERROR,error.getClass().getSimpleName());}catch(Throwable ignored){}}
 public static void planner(Span span,String mode){try{span.setAttribute("agentteams.orchestration.planner",mode);}catch(Throwable ignored){}}
 public static void usage(Span span,int input,int output){try{span.setAttribute("gen_ai.usage.input_tokens",input);span.setAttribute("gen_ai.usage.output_tokens",output);}catch(Throwable ignored){}}
 public static void end(Span span){try{span.end();}catch(Throwable ignored){}}
}
