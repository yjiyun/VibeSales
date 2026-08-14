package com.yjiyun.chatflows.manager.api;
import com.fasterxml.jackson.databind.ObjectMapper; import com.sun.net.httpserver.HttpExchange; import com.yjiyun.chatflows.manager.control.RunIds; import com.yjiyun.chatflows.manager.security.ForbiddenException; import java.io.*; import java.net.URLDecoder; import java.nio.charset.StandardCharsets; import java.util.*;
public final class HttpSupport {
 public static final ObjectMapper JSON=new ObjectMapper(); private HttpSupport(){}
 public static Map<String,String> query(HttpExchange x){Map<String,String> out=new HashMap<>();String q=x.getRequestURI().getRawQuery();if(q==null)return out;for(String part:q.split("&",-1)){String[] kv=part.split("=",2);out.put(dec(kv[0]),kv.length>1?dec(kv[1]):"");}return out;}
 public static void json(HttpExchange x,int code,Object value)throws IOException{byte[] body=JSON.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(code,body.length);try(OutputStream out=x.getResponseBody()){out.write(body);}}
 public static void error(HttpExchange x,Exception e)throws IOException{int code=e instanceof ForbiddenException?403:e instanceof SecurityException?401:e instanceof NoSuchElementException?404:e instanceof IllegalArgumentException?400:e instanceof IOException?502:500;json(x,code,Map.of("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
 public static String bearer(HttpExchange x){return x.getRequestHeaders().getFirst("Authorization");}
 public static String header(HttpExchange x,String name){String value=x.getRequestHeaders().getFirst(name);return value==null?"":value.trim();}
 public static Map<String,Object> body(HttpExchange x)throws IOException{byte[] bytes=x.getRequestBody().readAllBytes();if(bytes.length==0)return new LinkedHashMap<>();return JSON.readValue(bytes,JSON.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));}
 public static String required(Map<String,Object> body,String key){Object value=body.get(key);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException(key+" required");return text.trim();}
 public static String id(String value){return RunIds.requireV4(value);}
 public static void method(HttpExchange x,String expected){if(!expected.equals(x.getRequestMethod()))throw new IllegalArgumentException("method must be "+expected);}
 private static String dec(String s){return URLDecoder.decode(s,StandardCharsets.UTF_8);}
}
