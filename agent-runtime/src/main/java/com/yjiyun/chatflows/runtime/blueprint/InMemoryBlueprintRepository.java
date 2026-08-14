package com.yjiyun.chatflows.runtime.blueprint;
import java.util.*; import java.util.concurrent.*;
public final class InMemoryBlueprintRepository implements BlueprintRepository, BlueprintAdmin { private record Key(String client,String agent,int version){} private final Map<Key,AgentBlueprint> staged=new ConcurrentHashMap<>(); private final Map<String,Key> published=new ConcurrentHashMap<>(); private final Map<String,Integer> projected=new ConcurrentHashMap<>(); private final List<String> audit=new CopyOnWriteArrayList<>();
 public AgentBlueprint stage(AgentBlueprint b,String actor){staged.put(new Key(b.clientCode(),b.runtimeAgentId(),b.version()),b);audit.add("STAGE:"+b.blueprintId()+":"+actor);return b;}
 public BlueprintAdmin.Result publish(String id,String client,String actor){AgentBlueprint b=staged.values().stream().filter(x->x.blueprintId().equals(id)&&x.clientCode().equals(client)).findFirst().orElseThrow();published.put(client+"/"+b.runtimeAgentId(),new Key(client,b.runtimeAgentId(),b.version()));audit.add("PUBLISH:"+id+":"+actor);return new BlueprintAdmin.Result(b.blueprintId(),b.version(),"PUBLISHED");}
 public BlueprintAdmin.Result rollback(String client,String agent,int version,String actor){Key k=new Key(client,agent,version);AgentBlueprint b=Optional.ofNullable(staged.get(k)).orElseThrow();published.put(client+"/"+agent,k);audit.add("ROLLBACK:"+b.blueprintId()+":"+actor);return new BlueprintAdmin.Result(b.blueprintId(),b.version(),"PUBLISHED");}
 public Optional<AgentBlueprint> resolvePublished(String client,String user,String agent){Key k=published.get(client+"/"+agent);return Optional.ofNullable(k).map(staged::get);}
 public int projectedVersion(String client,String user,String agent){return projected.getOrDefault(client+"/"+user+"/"+agent,0);}
 public void markProjected(String client,String user,String agent,int version){projected.put(client+"/"+user+"/"+agent,version);}
 public List<String> audit(){return List.copyOf(audit);}
}
