package com.yjiyun.chatflows.runtime;
/**
 * A21: the production model route must terminate on Higress. Public HTTPS providers are rejected,
 * and plain HTTP is only tolerated for private-network hops (the compose gateway), never public hosts.
 */
public final class RuntimeGatewaySelfTest {public static void main(String[] args){
 reject("https://dashscope.aliyuncs.com/v1","direct DashScope accepted");
 reject("http://model.public.example/v1","insecure public gateway accepted");
 reject("https://model.higress.example/","gateway URL without model route accepted");
 RuntimeApplication.validateModelGateway("https://model.higress.example/v1");
 RuntimeApplication.validateModelGateway("http://agentteams-higress:8080/v1");
 RuntimeApplication.validateModelGateway("http://10.0.0.1:8080/v1");
 System.out.println("[PASS] production runtime model endpoint requires Higress HTTPS, allows private-network HTTP");}
 private static void reject(String url,String failure){try{RuntimeApplication.validateModelGateway(url);}catch(IllegalStateException e){return;}throw new AssertionError(failure);}
}
