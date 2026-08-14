package com.yjiyun.chatflows.manager.control;
import com.fasterxml.jackson.databind.*; import java.io.IOException; import java.util.*; import java.util.regex.*;
/** Strict Matrix HITL parser: ignores prose, bots and decisions for another run. */
public final class ApprovalTimeline {
 private static final ObjectMapper JSON=new ObjectMapper();private static final Pattern COMMAND=Pattern.compile("^(APPROVE|DENY)\\s+run_id=([A-Za-z0-9_-]+)\\s+approval_id=([A-Za-z0-9_-]+)$",Pattern.CASE_INSENSITIVE);
 private ApprovalTimeline(){}
 public static ApprovalDecision latest(String timeline,String runId,String approvalId,Set<String> allowedHumans)throws IOException{if(allowedHumans==null||allowedHumans.isEmpty())throw new IllegalArgumentException("Human approver allowlist required");JsonNode root=JSON.readTree(timeline);for(JsonNode event:root.path("chunk")){if(!"m.room.message".equals(event.path("type").asText())||!"m.text".equals(event.path("content").path("msgtype").asText()))continue;Matcher m=COMMAND.matcher(event.path("content").path("body").asText().trim());if(!m.matches()||!runId.equals(m.group(2))||!approvalId.equals(m.group(3)))continue;String actor=event.path("sender").asText();if(!allowedHumans.contains(actor))continue;return new ApprovalDecision(runId,approvalId,actor,"APPROVE".equalsIgnoreCase(m.group(1)));}throw new IllegalStateException("no exact authorized Human approval decision for run/approval "+runId+"/"+approvalId);}
}
