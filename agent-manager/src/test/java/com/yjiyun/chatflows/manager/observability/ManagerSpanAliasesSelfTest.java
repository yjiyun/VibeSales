package com.yjiyun.chatflows.manager.observability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/**
 * 锁定编排端（vibe-sales-manager）span 显示名为中文。
 *
 * <p>机械守卫：扫描 src/main 里全部 {@code ManagerTelemetry.start("...")} 调用点，逐个断言
 * {@code displayName} 能给出中文名。新增 operation 若忘了在 switch 里补一行，本测试失败并列出缺项
 * —— 不靠人工枚举，也不靠看控制台截图（那是之前连续漏配的根因）。
 */
public final class ManagerSpanAliasesSelfTest {
 public static void main(String[] args)throws IOException{
  // 1) 已知 operation 都是中文，且不含英文前缀残留。
  for(String op:List.of("dispatch","collect","plan","plan.resume")){
   String name=ManagerTelemetry.displayName(op);
   cjk(name,"displayName("+op+")");
   if(name.contains("agent-manager."))throw new AssertionError("显示名残留英文前缀: "+name);
  }
  // 2) 未收录 operation 也带中文前缀（不落回纯英文），且保留原操作名不丢身份。
  String fb=ManagerTelemetry.displayName("brand.new.op");
  cjk(fb,"fallback");
  if(!fb.contains("brand.new.op"))throw new AssertionError("回退丢了操作名: "+fb);

  // 3) operation.name 枚举不受改名影响：plan* → invoke_agent，其余保留原名（面板靠它分类/出图标）。
  //    displayName 与 operationName 是两个字段，改前者不能污染后者。
  eq(ManagerTelemetry.displayName("plan").equals(ManagerTelemetry.displayName("plan.resume")),false,"plan 与 plan.resume 应可区分");

  // 4) 机械扫描：src/main 下所有 start("op") 的 op 都必须命中 switch（即不落回退分支）。
  Set<String> ops=scanOperations(Path.of("src","main","java"));
  if(ops.isEmpty())throw new AssertionError("扫描器没找到任何 ManagerTelemetry.start(\"...\") 调用点，可能已失效");
  List<String> uncovered=new ArrayList<>();
  for(String op:ops)if(ManagerTelemetry.displayName(op).equals("编排·"+op))uncovered.add(op);
  if(!uncovered.isEmpty())throw new AssertionError("以下 operation 未在 displayName 里配中文名（落到回退）: "+uncovered);

  System.out.println("[PASS] manager span 中文别名：全部 "+ops.size()+" 个 operation 均有中文名（机械扫描守卫），回退安全");
 }
 private static Set<String> scanOperations(Path root)throws IOException{
  if(!Files.isDirectory(root))throw new AssertionError("找不到源码目录（测试须在 agent-manager 模块根目录下运行）: "+root.toAbsolutePath());
  Pattern p=Pattern.compile("ManagerTelemetry\\.start\\(\\s*\"([^\"]+)\"");
  Set<String> ops=new TreeSet<>();
  try(var walk=Files.walk(root)){
   for(Path f:walk.filter(x->x.toString().endsWith(".java")).toList()){
    Matcher m=p.matcher(Files.readString(f,StandardCharsets.UTF_8));
    while(m.find())ops.add(m.group(1));
   }
  }
  return ops;
 }
 private static void cjk(String s,String what){if(s==null||!s.codePoints().anyMatch(cp->cp>=0x4E00&&cp<=0x9FFF))throw new AssertionError(what+" 不是中文: "+s);}
 private static void eq(Object got,Object want,String what){if(!Objects.equals(got,want))throw new AssertionError(what+": got "+got+" want "+want);}
}
