package com.yjiyun.chatflows.manager.matrix;
import java.util.*;
public final class RoomTimelineSelfTest {
 public static void main(String[] args)throws Exception{
  String run="550e8400-e29b-41d4-a716-446655440000";
  String timeline="{\"chunk\":[{\"type\":\"m.room.message\",\"event_id\":\"$new\",\"sender\":\"@leader:local\",\"origin_server_ts\":200,\"content\":{\"msgtype\":\"m.text\",\"body\":\"NEW_RUN run_id="+run+"\"}},{\"type\":\"m.room.member\",\"sender\":\"@leader:local\"},{\"type\":\"m.room.message\",\"event_id\":\"$old\",\"sender\":\"@persona-expert:matrix.example\",\"origin_server_ts\":100,\"content\":{\"msgtype\":\"m.text\",\"body\":\"ack\"}}]}";
  Map<String,Object> view=RoomTimeline.view(timeline,run,"!team:local");
  @SuppressWarnings("unchecked") List<Map<String,Object>> messages=(List<Map<String,Object>>)view.get("messages");
  if(messages.size()!=2||!"ack".equals(messages.get(0).get("body"))||!("NEW_RUN run_id="+run).equals(messages.get(1).get("body"))||!Boolean.TRUE.equals(messages.get(1).get("for_run"))||Boolean.TRUE.equals(messages.get(0).get("for_run"))||!Integer.valueOf(2).equals(view.get("text_count"))||!"!team:local".equals(view.get("room_id"))||!run.equals(view.get("run_id"))||view.toString().contains("m.room.member")||view.toString().contains("chunk"))throw new AssertionError("room timeline projection failed: "+view);
  Map<String,Object> empty=RoomTimeline.view("{}",run,"!team:local");
  if(!((List<?>)empty.get("messages")).isEmpty()||!Integer.valueOf(0).equals(empty.get("text_count")))throw new AssertionError("empty timeline leaked: "+empty);
  String blocked="{\"chunk\":[{\"type\":\"m.room.message\",\"event_id\":\"$err\",\"sender\":\"@leader:local\",\"origin_server_ts\":2,\"content\":{\"msgtype\":\"m.text\",\"body\":\"Internal error\"}},{\"type\":\"m.room.message\",\"event_id\":\"$new\",\"sender\":\"@manager:local\",\"origin_server_ts\":1,\"content\":{\"msgtype\":\"m.text\",\"body\":\"NEW_RUN run_id="+run+"\"}}]}";
  @SuppressWarnings("unchecked") List<Map<String,Object>> blockedMsgs=(List<Map<String,Object>>)RoomTimeline.view(blocked,run,"!team:local").get("messages");
  if(blockedMsgs.size()!=2||!Boolean.TRUE.equals(blockedMsgs.get(0).get("for_run"))||!Boolean.TRUE.equals(blockedMsgs.get(1).get("for_run"))||!"Internal error".equals(blockedMsgs.get(1).get("body")))throw new AssertionError("Leader Internal error after NEW_RUN should be tagged for_run: "+blockedMsgs);
  String other="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  String chain="{\"chunk\":["
    +"{\"type\":\"m.room.message\",\"event_id\":\"$e2\",\"sender\":\"@leader:local\",\"origin_server_ts\":4,\"content\":{\"msgtype\":\"m.text\",\"body\":\"Internal error\"}},"
    +"{\"type\":\"m.room.message\",\"event_id\":\"$n2\",\"sender\":\"@manager:local\",\"origin_server_ts\":3,\"content\":{\"msgtype\":\"m.text\",\"body\":\"NEW_RUN run_id="+other+"\"}},"
    +"{\"type\":\"m.room.message\",\"event_id\":\"$e1\",\"sender\":\"@leader:local\",\"origin_server_ts\":2,\"content\":{\"msgtype\":\"m.text\",\"body\":\"Internal error\"}},"
    +"{\"type\":\"m.room.message\",\"event_id\":\"$n1\",\"sender\":\"@manager:local\",\"origin_server_ts\":1,\"content\":{\"msgtype\":\"m.text\",\"body\":\"NEW_RUN run_id="+run+"\"}}"
    +"]}";
  @SuppressWarnings("unchecked") List<Map<String,Object>> chainMsgs=(List<Map<String,Object>>)RoomTimeline.view(chain,run,"!team:local").get("messages");
  if(chainMsgs.size()!=4||!Boolean.TRUE.equals(chainMsgs.get(0).get("for_run"))||!Boolean.TRUE.equals(chainMsgs.get(1).get("for_run"))||Boolean.TRUE.equals(chainMsgs.get(2).get("for_run"))||Boolean.TRUE.equals(chainMsgs.get(3).get("for_run")))throw new AssertionError("for_run must not leak to later runs: "+chainMsgs);
  String shortId="{\"chunk\":[{\"type\":\"m.room.message\",\"event_id\":\"$wait\",\"sender\":\"@leader:local\",\"origin_server_ts\":5,\"content\":{\"msgtype\":\"m.text\",\"body\":\"`"+run.substring(0,8)+"` (acme_agri) — 等待 Human 审批 (approval_id=699838ba-409e-4802-8aea-7b0ceaabbafa)\"}}]}";
  @SuppressWarnings("unchecked") List<Map<String,Object>> shortMsgs=(List<Map<String,Object>>)RoomTimeline.view(shortId,run,"!team:local").get("messages");
  if(shortMsgs.size()!=1||!Boolean.TRUE.equals(shortMsgs.get(0).get("for_run")))throw new AssertionError("8-char run prefix must tag for_run: "+shortMsgs);
  System.out.println("[PASS] RoomTimeline projects m.room.message in chronological order without raw Matrix chunk");
 }
}
