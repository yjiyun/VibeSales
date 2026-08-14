package com.yjiyun.chatflows.manager.matrix;
import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper;
public final class RestMatrixClientSelfTest {
 public static void main(String[] args)throws Exception{
  String leader="@chatflows-leader:matrix-local.agentteams.io:18080";JsonNode body=new ObjectMapper().readTree(RestMatrixClient.mentionBody(leader,"@chatflows-leader task ready\nnext"));
  if(!"m.text".equals(body.path("msgtype").asText())||!body.path("body").asText().contains("task ready")||body.path("m.mentions").path("user_ids").size()!=1||!leader.equals(body.path("m.mentions").path("user_ids").get(0).asText()))throw new AssertionError("Matrix mention payload invalid: "+body);
  boolean rejected=false;try{RestMatrixClient.mentionBody("chatflows-leader","x");new RestMatrixClient(java.net.URI.create("http://127.0.0.1"),new MatrixTokenProvider("token",null,null,null,null,null)).sendMention("room","chatflows-leader","x");}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new AssertionError("short Matrix id accepted");
  System.out.println("[PASS] Matrix payload carries full m.mentions.user_ids");
 }
}
