package com.yjiyun.chatflows.manager.control;
public record ApprovalDecision(String runId,String approvalId,String actor,boolean approved) {
 public ApprovalDecision{RunIds.requireV4(runId);if(approvalId==null||!approvalId.matches("[A-Za-z0-9_-]+")||actor==null||actor.isBlank())throw new IllegalArgumentException("approvalId/actor required");}
 public String decision(){return approved?"APPROVE":"DENY";}
}
