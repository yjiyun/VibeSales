package com.yjiyun.chatflows.manager.api;
import com.sun.net.httpserver.*; import com.yjiyun.chatflows.manager.ManagerComposition; import com.yjiyun.chatflows.manager.security.AuthService; import java.io.*; import java.time.Instant; import java.util.*;
public final class HealthController implements HttpHandler {
 @FunctionalInterface public interface PlaneCheck {void check()throws Exception;}
 private final PlaneCheck planes; private final AuthService auth;
 public HealthController(ManagerComposition composition,AuthService auth){this(composition::checkHttp,auth);}
 public HealthController(PlaneCheck planes,AuthService auth){this.planes=Objects.requireNonNull(planes);this.auth=auth;}
 public void handle(HttpExchange x)throws IOException{try{HttpSupport.method(x,"GET");auth.requireRole(HttpSupport.bearer(x),HttpSupport.header(x,"X-Role"),"orchestrator","human","admin","user");planes.check();HttpSupport.json(x,200,Map.of("ok",true,"planes",List.of("controller","matrix","minio","nest-control"),"ts",Instant.now().toString()));}catch(Exception e){HttpSupport.error(x,e);}finally{x.close();}}
}
