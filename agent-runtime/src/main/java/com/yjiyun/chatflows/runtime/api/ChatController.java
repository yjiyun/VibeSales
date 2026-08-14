package com.yjiyun.chatflows.runtime.api;
import com.fasterxml.jackson.databind.ObjectMapper; import com.sun.net.httpserver.*; import com.yjiyun.chatflows.runtime.security.AuthService; import io.agentscope.core.event.*; import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*; import java.util.concurrent.atomic.AtomicBoolean;
public final class ChatController implements HttpHandler {
 private final RuntimeService runtime; private final AuthService auth; private final ObjectMapper json=new ObjectMapper();
 public ChatController(RuntimeService r,AuthService a){runtime=r;auth=a;}
 public void handle(HttpExchange x)throws IOException{
  if(!"POST".equals(x.getRequestMethod())){HttpSupport.json(x,405,Map.of("error","POST required"));return;}
  try{auth.require(HttpSupport.bearer(x));Map<String,String> q=HttpSupport.query(x);String message=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-cache");x.getResponseHeaders().set("X-Accel-Buffering","no");x.sendResponseHeaders(200,0);try(OutputStream out=x.getResponseBody()){AtomicBoolean deltaSent=new AtomicBoolean();try{runtime.stream(req(q,"clientCode"),req(q,"userId"),req(q,"sessionId"),req(q,"runtimeAgentId"),message).doOnNext(event->{try{if(event instanceof TextBlockDeltaEvent delta){deltaSent.set(true);send(out,"message",Map.of("delta",delta.getDelta(),"eventId",event.getId()));}else if(event instanceof AgentResultEvent result&&!deltaSent.get()){send(out,"message",Map.of("delta",result.getResult().getTextContent(),"eventId",event.getId()));}else if(event instanceof RequireUserConfirmEvent){send(out,"approval_required",Map.of("eventId",event.getId()));}}catch(IOException e){throw new UncheckedIOException(e);}}).blockLast();send(out,"done",Map.of());}catch(Exception e){send(out,"error",Map.of("message",safe(e)));}}}catch(Exception e){HttpSupport.error(x,e);}
 }
 private void send(OutputStream out,String event,Object data)throws IOException{String frame="event: "+event+"\ndata: "+json.writeValueAsString(data)+"\n\n";out.write(frame.getBytes(StandardCharsets.UTF_8));out.flush();}
 private static String req(Map<String,String> q,String k){String v=q.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" required");return v;}
 private static String safe(Exception e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();String m=c.getMessage();return m==null?c.getClass().getSimpleName():m;}
}
