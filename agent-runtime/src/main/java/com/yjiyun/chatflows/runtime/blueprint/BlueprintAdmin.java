package com.yjiyun.chatflows.runtime.blueprint;
public interface BlueprintAdmin {
 record Result(String blueprintId,int version,String status){}
 Result publish(String blueprintId,String clientCode,String actor);
 Result rollback(String clientCode,String runtimeAgentId,int version,String actor);
}
