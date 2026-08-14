package com.yjiyun.chatflows.runtime.skill;
import io.agentscope.core.skill.AgentSkill; import io.agentscope.core.skill.repository.*; import java.util.*;
public final class ReadOnlySkillRepository implements AgentSkillRepository {
 private final Map<String,AgentSkill> skills; private final String source;
 public ReadOnlySkillRepository(String source,List<AgentSkill> values){this.source=source;Map<String,AgentSkill> m=new LinkedHashMap<>();for(AgentSkill s:values)m.put(s.getName(),s);skills=Map.copyOf(m);}
 public AgentSkill getSkill(String name){return skills.get(name);} public List<String> getAllSkillNames(){return new ArrayList<>(skills.keySet());} public List<AgentSkill> getAllSkills(){return new ArrayList<>(skills.values());}
 public boolean save(List<AgentSkill> s,boolean f){throw new UnsupportedOperationException("read-only skill repository");} public boolean delete(String n){throw new UnsupportedOperationException("read-only skill repository");} public boolean skillExists(String n){return skills.containsKey(n);} public AgentSkillRepositoryInfo getRepositoryInfo(){return new AgentSkillRepositoryInfo("memory",source,false);} public String getSource(){return source;} public void setWriteable(boolean w){if(w)throw new UnsupportedOperationException("read-only skill repository");} public boolean isWriteable(){return false;}
 public static ReadOnlySkillRepository defaults(){return new ReadOnlySkillRepository("local-market",List.of(skill("human-handoff","当用户要求人工或出现高风险时转人工。"),skill("product-recommend","当用户询问商品选择时提供推荐。"),skill("after-sales","当用户咨询售后时按政策处理。")));}
 public static AgentSkill skill(String name,String description){return new AgentSkill(name,description,"# "+name+"\n"+description,Map.of(),"market");}
}
