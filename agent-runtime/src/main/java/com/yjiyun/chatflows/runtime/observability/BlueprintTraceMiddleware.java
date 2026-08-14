package com.yjiyun.chatflows.runtime.observability;
import io.agentscope.core.agent.*; import io.agentscope.core.event.*; import io.agentscope.core.middleware.*; import io.opentelemetry.api.trace.Span; import io.opentelemetry.context.Context; import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator; import java.util.*; import java.util.concurrent.atomic.AtomicInteger; import java.util.function.Function; import reactor.core.publisher.Flux;
/**
 * Adds P3C provenance to AgentScope spans and mirrors the invocation to the AgentLoop side channel.
 * Token usage is accumulated across model calls so the run-scoped envelope carries this controllable
 * layer's share of the three-layer aggregation (A22); it creates no new exporter of its own.
 */
public final class BlueprintTraceMiddleware implements MiddlewareBase {
 public static final String CONTEXT_KEY="agentteams.otel.attributes";
 private final AgentLoopExporter exporter;
 public BlueprintTraceMiddleware(){this(new AgentLoopExporter(System.getenv()));}
 BlueprintTraceMiddleware(AgentLoopExporter exporter){this.exporter=Objects.requireNonNull(exporter);}
 public int order(){return 0;}
 public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext ctx,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next){return Flux.deferContextual(view->{Context otel=ContextPropagationOperator.getOpenTelemetryContextFromContextView(view,Context.current());Span span=Span.fromContext(otel);Map<String,String> source=attributes(ctx);source.forEach(span::setAttribute);span.setAttribute("agentteams.usage.scope","run");span.setAttribute("agentteams.worker_usage_available",false);String traceparent=span.getSpanContext().isValid()?"00-"+span.getSpanContext().getTraceId()+"-"+span.getSpanContext().getSpanId()+"-"+(span.getSpanContext().isSampled()?"01":"00"):null;Map<String,Object> attrs=new LinkedHashMap<>(source);AtomicInteger inputTotal=new AtomicInteger(),outputTotal=new AtomicInteger();return next.apply(input).doOnNext(event->{if(event instanceof ModelCallEndEvent end&&end.getUsage()!=null){int in=inputTotal.addAndGet(end.getUsage().getInputTokens()),out=outputTotal.addAndGet(end.getUsage().getOutputTokens());span.setAttribute("gen_ai.usage.input_tokens",in);span.setAttribute("gen_ai.usage.output_tokens",out);attrs.put("gen_ai.usage.input_tokens",in);attrs.put("gen_ai.usage.output_tokens",out);}if(event instanceof AgentEndEvent)exporter.emit("agent-runtime.chat",attrs,traceparent);});});}
 @SuppressWarnings("unchecked") public static Map<String,String> attributes(RuntimeContext ctx){Object value=ctx!=null?ctx.get(CONTEXT_KEY):null;if(!(value instanceof Map<?,?> raw))return Map.of();Map<String,String> out=new LinkedHashMap<>();raw.forEach((k,v)->{if(k instanceof String key&&v instanceof String text)out.put(key,text);});return Map.copyOf(out);}
}
