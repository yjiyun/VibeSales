package com.yjiyun.chatflows.runtime;
import java.net.URI; import java.net.http.*; import java.time.Duration;
public final class RuntimeHealthCheck {public static void main(String[] args)throws Exception{String port=System.getenv().getOrDefault("RUNTIME_PORT","8088");HttpResponse<Void> response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/healthz")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()!=200)System.exit(1);}}
