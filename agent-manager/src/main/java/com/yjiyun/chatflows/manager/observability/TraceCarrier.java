package com.yjiyun.chatflows.manager.observability;
import com.yjiyun.chatflows.manager.control.RunIds; import io.opentelemetry.api.trace.Span; import java.security.SecureRandom; import java.util.HexFormat;
public record TraceCarrier(String runId,String clientCode,String traceparent){
 public TraceCarrier{RunIds.requireV4(runId);if(clientCode==null||!clientCode.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("valid clientCode required");if(traceparent==null||!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"))throw new IllegalArgumentException("invalid traceparent");}
 public static TraceCarrier create(String run,String client){SecureRandom r=new SecureRandom();byte[] trace=new byte[16],span=new byte[8];r.nextBytes(trace);r.nextBytes(span);return new TraceCarrier(run,client,"00-"+HexFormat.of().formatHex(trace)+"-"+HexFormat.of().formatHex(span)+"-01");}
 public static TraceCarrier fromSpan(String run,String client,Span span){var c=span.getSpanContext();return c.isValid()?new TraceCarrier(run,client,"00-"+c.getTraceId()+"-"+c.getSpanId()+"-"+(c.isSampled()?"01":"00")):create(run,client);}
 public String metaJson(String phase){return "{\"run_id\":\""+runId+"\",\"client_code\":\""+clientCode+"\",\"phase\":\""+validPhase(phase)+"\",\"traceparent\":\""+traceparent+"\"}";}
 public String metaJson(){return metaJson("orchestration");}
 public String frontMatter(String phase){return "---\nrun_id: "+runId+"\nclient_code: "+clientCode+"\nphase: "+validPhase(phase)+"\ntraceparent: "+traceparent+"\n---\n";}
 public String frontMatter(){return frontMatter("orchestration");}
 private static String validPhase(String phase){if(phase==null||!phase.matches("P1|P2|P3|P3B|P3C|P4|orchestration"))throw new IllegalArgumentException("invalid phase");return phase;}
}
