package com.yjiyun.chatflows.manager.matrix;
import com.fasterxml.jackson.databind.ObjectMapper; import java.io.IOException; import java.net.*; import java.net.http.*; import java.nio.charset.StandardCharsets; import java.util.UUID;
public final class RestMatrixClient implements MatrixClient {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final URI base; private final MatrixTokenProvider tokens; private final HttpClient http=HttpClient.newHttpClient();
 public RestMatrixClient(URI base,MatrixTokenProvider tokens){this.base=base;this.tokens=tokens;}
 public void join(String room)throws IOException,InterruptedException{request("POST","/_matrix/client/v3/join/"+enc(room),"{}");}
 public void send(String room,String msg)throws IOException,InterruptedException{sendBody(room,textBody(msg));}
 public void sendMention(String room,String userId,String msg)throws IOException,InterruptedException{if(userId==null||!userId.startsWith("@")||!userId.contains(":"))throw new IllegalArgumentException("full Matrix user id required for mention");sendBody(room,mentionBody(userId,msg));}
 public String receive(String room)throws IOException,InterruptedException{return request("GET","/_matrix/client/v3/rooms/"+enc(room)+"/messages?dir=b&limit=50",null);}
 public String sync(String since,long timeoutMillis)throws IOException,InterruptedException{if(timeoutMillis<0||timeoutMillis>60000)throw new IllegalArgumentException("Matrix sync timeout must be 0..60000ms");String path="/_matrix/client/v3/sync?timeout="+timeoutMillis+(since==null||since.isBlank()?"":"&since="+enc(since));return request("GET",path,null);}
 public String whoAmI()throws IOException,InterruptedException{String user=JSON.readTree(request("GET","/_matrix/client/v3/account/whoami",null)).path("user_id").asText();if(user.isBlank())throw new IOException("Matrix whoami missing user_id");return user;}
 private void sendBody(String room,String body)throws IOException,InterruptedException{String tx=UUID.randomUUID().toString();request("PUT","/_matrix/client/v3/rooms/"+enc(room)+"/send/m.room.message/"+tx,body);}
 static String textBody(String msg)throws IOException{return JSON.writeValueAsString(java.util.Map.of("msgtype","m.text","body",msg));}
 static String mentionBody(String userId,String msg)throws IOException{return JSON.writeValueAsString(java.util.Map.of("msgtype","m.text","body",msg,"m.mentions",java.util.Map.of("user_ids",java.util.List.of(userId))));}
 private String request(String method,String path,String body)throws IOException,InterruptedException{HttpResponse<String> r=send(method,path,body,tokens.current());if(r.statusCode()==401)r=send(method,path,body,tokens.refresh());if(r.statusCode()/100!=2)throw new IOException("Matrix request failed: "+r.statusCode());return r.body();}
 private HttpResponse<String> send(String method,String path,String body,String token)throws IOException,InterruptedException{HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token);if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
