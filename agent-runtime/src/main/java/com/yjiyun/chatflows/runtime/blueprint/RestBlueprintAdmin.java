package com.yjiyun.chatflows.runtime.blueprint;
import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper; import java.net.*; import java.net.http.*; import java.nio.charset.StandardCharsets; import java.util.Objects;
/** Publication writes stay in the control plane; runtime forwards only after local admin auth. */
public final class RestBlueprintAdmin implements BlueprintAdmin {
 private final URI base; private final String token; private final HttpClient http=HttpClient.newHttpClient(); private final ObjectMapper json=new ObjectMapper();
 public RestBlueprintAdmin(URI base,String token){this.base=Objects.requireNonNull(base);this.token=Objects.requireNonNull(token);}
 public Result publish(String id,String client,String actor){return call("publish","blueprintId="+enc(id)+"&clientCode="+enc(client),actor);}
 public Result rollback(String client,String agent,int version,String actor){return call("rollback","clientCode="+enc(client)+"&runtimeAgentId="+enc(agent)+"&version="+version,actor);}
 private Result call(String path,String query,String actor){try{HttpRequest req=HttpRequest.newBuilder(base.resolve("/api/v1/blueprints/"+path+"?"+query)).header("Authorization","Bearer "+token).header("X-Role","admin").header("X-Actor",actor).POST(HttpRequest.BodyPublishers.noBody()).build();HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());if(res.statusCode()/100!=2)throw new IllegalStateException("control plane HTTP "+res.statusCode());JsonNode n=json.readTree(res.body());return new Result(n.path("blueprintId").asText(),n.path("version").asInt(),n.path("status").asText());}catch(Exception e){throw new IllegalStateException("blueprint control-plane call failed",e);}}
 private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
