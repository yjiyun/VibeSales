package com.yjiyun.chatflows.manager.platform;
import com.fasterxml.jackson.databind.*; import com.fasterxml.jackson.databind.node.*; import com.fasterxml.jackson.dataformat.yaml.YAMLMapper; import java.io.IOException; import java.net.URI; import java.net.http.*; import java.util.*;
/** Authenticated Controller CR adapter. Converts declarative YAML to the frozen JSON REST contract. */
public final class RestPlatformClient implements PlatformClient {
 private static final Map<String,String> PATHS=Map.of("Worker","workers","Team","teams","Human","humans"); private static final ObjectMapper JSON=new ObjectMapper(),YAML=new YAMLMapper();
 private final URI base; private final String token; private final HttpClient http;
 public RestPlatformClient(URI base,String token){this(base,token,HttpClient.newHttpClient());} RestPlatformClient(URI base,String token,HttpClient http){this.base=Objects.requireNonNull(base);if(token==null||token.isBlank())throw new IllegalArgumentException("controller token required");this.token=token;this.http=http;}
 public void check()throws IOException,InterruptedException{HttpResponse<String> r=send("GET","/api/v1/workers",null);if(r.statusCode()/100!=2)throw new IOException("controller health failed: "+r.statusCode());}
 public void apply(String kind,String name,String yaml)throws IOException,InterruptedException{
  String path=PATHS.get(kind);if(path==null)throw new IllegalArgumentException("unsupported kind: "+kind);ObjectNode body=requestBody(kind,name,yaml);String collection="/api/v1/"+path,resource=collection+"/"+name;
  HttpResponse<String> existing=send("GET",resource,null);HttpResponse<String> result;
  // Team PUT 会按 workerMembers 重同步 Matrix 房间成员，把编排器用的 @manager 踢成 leave，
  // 随后 join/send 变成 403（invite-only / membership leave）。Human 同理只声明一次。
  if(existing.statusCode()/100==2){if(kind.equals("Human")||kind.equals("Team"))return;result=send("PUT",resource,JSON.writeValueAsString(body));}
  else if(existing.statusCode()==404)result=send("POST",collection,JSON.writeValueAsString(body));
  else throw new IOException("controller lookup failed: "+existing.statusCode()+" "+existing.body());
  if(result.statusCode()/100!=2)throw new IOException("controller rejected "+kind+" "+name+": "+result.statusCode()+" "+result.body());
 }
 public static ObjectNode requestBody(String expectedKind,String expectedName,String yaml)throws IOException{
  JsonNode cr=YAML.readTree(yaml);String kind=cr.path("kind").asText(),name=cr.path("metadata").path("name").asText();if(!expectedKind.equals(kind)||!expectedName.equals(name)||!name.matches("[a-z0-9][a-z0-9-]*"))throw new IllegalArgumentException("CR kind/name mismatch");JsonNode spec=cr.path("spec");if(!spec.isObject())throw new IllegalArgumentException("CR spec required");ObjectNode out=((ObjectNode)spec).deepCopy();out.put("name",name);
  if("Team".equals(kind)){ArrayNode workers=JSON.createArrayNode();String leader=spec.path("leader").asText();if(leader.isBlank())throw new IllegalArgumentException("Team leader required");workers.addObject().put("name",leader).put("role","team_leader");for(JsonNode w:spec.path("workers"))workers.addObject().put("name",w.asText()).put("role","worker");out.set("workerMembers",workers);out.remove("workers");out.remove("leader");}
  return out;
 }
 private HttpResponse<String> send(String method,String path,String body)throws IOException,InterruptedException{HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token).header("Accept","application/json");if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
}
