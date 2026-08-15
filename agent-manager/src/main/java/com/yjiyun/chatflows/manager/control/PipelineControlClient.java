package com.yjiyun.chatflows.manager.control;
import com.fasterxml.jackson.databind.*; import java.io.*; import java.net.*; import java.net.http.*; import java.util.*;
/** A4c allowlist: manager may call Nest only for run issuance, approval, abort and health. */
public final class PipelineControlClient {
 private static final ObjectMapper JSON=new ObjectMapper(); private final URI base; private final String token; private final HttpClient http;
 public PipelineControlClient(URI base,String token){this(base,token,HttpClient.newHttpClient());}
 PipelineControlClient(URI base,String token,HttpClient http){this.base=Objects.requireNonNull(base);if(token==null||token.length()<16)throw new IllegalArgumentException("pipeline control token must be >=16 chars");this.token=token;this.http=http;}
 public IssuedRun createRun(String clientCode,String actor)throws IOException,InterruptedException{return createRun(clientCode,actor,null);}
 public IssuedRun createRun(String clientCode,String actor,String spec)throws IOException,InterruptedException{Map<String,Object> body=new LinkedHashMap<>();body.put("client_code",clientCode);JsonNode phase1=phase1FromSpec(spec);if(phase1!=null)body.put("phase1_result",JSON.convertValue(phase1,Map.class));JsonNode n=send("POST","/api/v1/pipeline/runs",body,"orchestrator",actor);return new IssuedRun(required(n,"run_id"),required(n,"client_code"));}
 public static JsonNode phase1FromSpec(String spec){if(spec==null||spec.isBlank())return null;try{JsonNode node=JSON.readTree(spec);JsonNode phase1=node.path("phase1_result");return phase1.isObject()&&"PASS".equals(phase1.path("gate").asText())?phase1:null;}catch(Exception ignored){return null;}}
 public JsonNode approval(String runId,String approvalId,boolean approved,String actor)throws IOException,InterruptedException{return send("POST","/api/v1/pipeline/"+id(runId)+"/approval",Map.of("approval_id",approvalId,"approved",approved),"human",actor);}
 public JsonNode abort(String runId,String actor,String reason)throws IOException,InterruptedException{if(reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("abort reason required and must be <=500 chars");return send("POST","/api/v1/pipeline/"+id(runId)+"/abort",Map.of("reason",reason.trim()),"admin",actor);}
 public JsonNode runSnapshot(String runId)throws IOException,InterruptedException{return send("GET","/api/v1/pipeline/"+id(runId),null,"orchestrator","agent-manager");}
 public void check()throws IOException,InterruptedException{send("GET","/api/v1/pipeline/health",null,"orchestrator","agent-manager");}
 private JsonNode send(String method,String path,Object body,String role,String actor)throws IOException,InterruptedException{HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token).header("X-Role",role).header("X-Actor",actor).header("Accept","application/json");if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));HttpResponse<String> r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("Nest control request failed: "+r.statusCode()+" "+r.body());return r.body().isBlank()?JSON.createObjectNode():JSON.readTree(r.body());}
 private static String required(JsonNode node,String key)throws IOException{String value=node.path(key).asText();if(value.isBlank())throw new IOException("Nest response missing "+key);return value;}
 private static String id(String value){return RunIds.requireV4(value);}
 public record IssuedRun(String runId,String clientCode){}
}
