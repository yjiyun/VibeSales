package com.yjiyun.chatflows.manager.control;
import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import java.nio.charset.StandardCharsets; import java.util.Base64;
/** Produces the same short-lived HMAC credential verified by Nest ApprovalProofService. */
public final class ApprovalProofSigner {
 private final byte[] secret;
 public ApprovalProofSigner(String value){if(value==null||value.length()<32)throw new IllegalArgumentException("approval signing secret must be >=32 chars");secret=value.getBytes(StandardCharsets.UTF_8);}
 public String sign(ApprovalDecision d){long exp=System.currentTimeMillis()+15*60_000;String json="{\"run_id\":\""+esc(d.runId())+"\",\"approval_id\":\""+esc(d.approvalId())+"\",\"actor\":\""+esc(d.actor())+"\",\"decision\":\""+d.decision()+"\",\"exp\":"+exp+"}";String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));return encoded+"."+hmac(encoded);}
 private String hmac(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("HMAC unavailable",e);}}
 private static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
}
