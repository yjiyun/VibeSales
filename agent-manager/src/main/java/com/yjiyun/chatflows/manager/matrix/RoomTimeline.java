package com.yjiyun.chatflows.manager.matrix;
import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper; import com.yjiyun.chatflows.manager.control.RunIds; import java.io.IOException; import java.util.*;
/** Projects Matrix /messages chunk into a Console-safe Team Room timeline. dir=b is newest-first. */
public final class RoomTimeline {
 private static final ObjectMapper JSON=new ObjectMapper();
 private RoomTimeline(){}
 public static Map<String,Object> view(String timeline,String runId,String roomId)throws IOException{
  JsonNode root=JSON.readTree(timeline==null||timeline.isBlank()?"{}":timeline);
  List<Map<String,Object>> messages=new ArrayList<>();
  for(JsonNode event:root.path("chunk")){
   if(!"m.room.message".equals(event.path("type").asText()))continue;
   String body=event.path("content").path("body").asText("");
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("event_id",event.path("event_id").asText(""));
   row.put("sender",event.path("sender").asText(""));
   row.put("body",body);
   row.put("origin_server_ts",event.path("origin_server_ts").asLong(0));
   String msgtype=event.path("content").path("msgtype").asText("");
   row.put("msgtype",msgtype.isBlank()?"m.text":msgtype);
   row.put("for_run",RunIds.mentionedIn(body,runId));
   messages.add(row);
  }
  Collections.reverse(messages);
  messages.sort(Comparator.comparingLong(row->(Long)row.get("origin_server_ts")));
  for(int i=1;i<messages.size();i++){
   Map<String,Object> row=messages.get(i);
   if(Boolean.TRUE.equals(row.get("for_run")))continue;
   String body=((String)row.get("body")).trim();
   if(!body.equalsIgnoreCase("Internal error")&&!body.contains("RUN_BLOCKED"))continue;
   if(Boolean.TRUE.equals(messages.get(i-1).get("for_run")))row.put("for_run",true);
  }
  int textCount=0;for(Map<String,Object> row:messages)if(!((String)row.get("body")).isBlank())textCount++;
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("run_id",runId==null?"":runId);
  out.put("room_id",roomId==null?"":roomId);
  out.put("messages",messages);
  out.put("text_count",textCount);
  return out;
 }
}
