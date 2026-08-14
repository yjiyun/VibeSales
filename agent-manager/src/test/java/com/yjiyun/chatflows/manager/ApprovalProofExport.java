package com.yjiyun.chatflows.manager;
import com.yjiyun.chatflows.manager.control.*; import java.nio.file.*;
public final class ApprovalProofExport {public static void main(String[] args)throws Exception{if(args.length!=2)throw new IllegalArgumentException("output and secret required");ApprovalDecision d=new ApprovalDecision("550e8400-e29b-41d4-a716-446655440000","approval-cross-language","@admin:local",true);Files.writeString(Path.of(args[0]),new ApprovalProofSigner(args[1]).sign(d));System.out.println("[PASS] exported Java approval proof");}}
