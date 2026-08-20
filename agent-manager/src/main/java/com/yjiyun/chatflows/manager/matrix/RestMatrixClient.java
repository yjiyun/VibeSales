package com.yjiyun.chatflows.manager.matrix;
import com.fasterxml.jackson.databind.ObjectMapper; import java.io.IOException; import java.net.*; import java.net.http.*; import java.nio.charset.StandardCharsets; import java.util.UUID;
public final class RestMatrixClient implements MatrixClient {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final URI base; private final MatrixTokenProvider tokens; private final HttpClient http; private final String selfUserId,inviteUserId,invitePassword;
 public RestMatrixClient(URI base,MatrixTokenProvider tokens){this(base,tokens,null,null,null);}
 public RestMatrixClient(URI base,MatrixTokenProvider tokens,String selfUserId,String inviteUserId,String invitePassword){this(base,tokens,selfUserId,inviteUserId,invitePassword,HttpClient.newHttpClient());}
 RestMatrixClient(URI base,MatrixTokenProvider tokens,String selfUserId,String inviteUserId,String invitePassword,HttpClient http){this.base=base;this.tokens=tokens;this.http=http;this.selfUserId=blankToNull(selfUserId);this.inviteUserId=blankToNull(inviteUserId);this.invitePassword=blankToNull(invitePassword);}
 public void join(String room)throws IOException,InterruptedException{ensureJoined(room);}
 public void send(String room,String msg)throws IOException,InterruptedException{sendBody(room,textBody(msg));}
 public void sendMention(String room,String userId,String msg)throws IOException,InterruptedException{if(userId==null||!userId.startsWith("@")||!userId.contains(":"))throw new IllegalArgumentException("full Matrix user id required for mention");sendBody(room,mentionBody(userId,msg));}
 public String receive(String room)throws IOException,InterruptedException{return requestAllowingInvite(room,"GET","/_matrix/client/v3/rooms/"+enc(room)+"/messages?dir=b&limit=50",null);}
 public String sync(String since,long timeoutMillis)throws IOException,InterruptedException{if(timeoutMillis<0||timeoutMillis>60000)throw new IllegalArgumentException("Matrix sync timeout must be 0..60000ms");String path="/_matrix/client/v3/sync?timeout="+timeoutMillis+(since==null||since.isBlank()?"":"&since="+enc(since));return request("GET",path,null);}
 public String whoAmI()throws IOException,InterruptedException{String user=JSON.readTree(request("GET","/_matrix/client/v3/account/whoami",null)).path("user_id").asText();if(user.isBlank())throw new IOException("Matrix whoami missing user_id");return user;}
 private void sendBody(String room,String body)throws IOException,InterruptedException{String tx=UUID.randomUUID().toString();requestAllowingInvite(room,"PUT","/_matrix/client/v3/rooms/"+enc(room)+"/send/m.room.message/"+tx,body);}
 static String textBody(String msg)throws IOException{return JSON.writeValueAsString(java.util.Map.of("msgtype","m.text","body",msg));}
 static String mentionBody(String userId,String msg)throws IOException{return JSON.writeValueAsString(java.util.Map.of("msgtype","m.text","body",msg,"m.mentions",java.util.Map.of("user_ids",java.util.List.of(userId))));}
 private String requestAllowingInvite(String room,String method,String path,String body)throws IOException,InterruptedException{try{return request(method,path,body);}catch(IOException e){if(!isForbidden(e)||!canInvite())throw e;ensureJoined(room);return request(method,path,body);}}
 private void ensureJoined(String room)throws IOException,InterruptedException{try{request("POST","/_matrix/client/v3/join/"+enc(room),"{}");}catch(IOException e){if(!isForbidden(e)||!canInvite())throw e;invite(room);request("POST","/_matrix/client/v3/join/"+enc(room),"{}");}}
 private void invite(String room)throws IOException,InterruptedException{
  HttpRequest login=HttpRequest.newBuilder(base.resolve("/_matrix/client/v3/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(MatrixTokenProvider.passwordLoginBody(inviteUserId,invitePassword))).build();
  HttpResponse<String> logged=http.send(login,HttpResponse.BodyHandlers.ofString());
  if(logged.statusCode()/100!=2)throw new IOException("Matrix invite login failed: "+logged.statusCode());
  String inviter=MatrixTokenProvider.passwordLoginToken(logged.body(),inviteUserId);
  HttpRequest req=HttpRequest.newBuilder(base.resolve("/_matrix/client/v3/rooms/"+enc(room)+"/invite")).header("Authorization","Bearer "+inviter).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of("user_id",selfUserId)))).build();
  HttpResponse<String> invited=http.send(req,HttpResponse.BodyHandlers.ofString());
  if(invited.statusCode()/100==2||alreadyInvited(invited.statusCode(),invited.body()))return;
  throw new IOException("Matrix invite failed: "+invited.statusCode());
 }
 private boolean canInvite(){return selfUserId!=null&&inviteUserId!=null&&invitePassword!=null&&!selfUserId.equals(inviteUserId);}
 private static boolean isForbidden(IOException e){return e.getMessage()!=null&&e.getMessage().contains("Matrix request failed: 403");}
 static boolean alreadyInvited(int status,String body){if(status!=403)return false;String text=body==null?"":body;return text.contains("already")||text.contains("is already");}
 private String request(String method,String path,String body)throws IOException,InterruptedException{HttpResponse<String> r=send(method,path,body,tokens.current());if(r.statusCode()==401)r=send(method,path,body,tokens.refresh());if(r.statusCode()/100!=2)throw new IOException("Matrix request failed: "+r.statusCode());return r.body();}
 private HttpResponse<String> send(String method,String path,String body,String token)throws IOException,InterruptedException{HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token);if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
 private static String blankToNull(String value){return value==null||value.isBlank()?null:value;}
}
