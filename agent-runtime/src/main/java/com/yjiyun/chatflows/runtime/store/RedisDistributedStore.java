package com.yjiyun.chatflows.runtime.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import redis.clients.jedis.*;

/** Redis implementation of AgentScope's two production persistence contracts. */
public final class RedisDistributedStore implements DistributedStore, AutoCloseable {
 private static final String ROOT="chatflows:agent-runtime:";
 private final JedisPool pool;
 private final AgentStateStore state;
 private final BaseStore files;

 public RedisDistributedStore(URI redisUri){pool=new JedisPool(redisUri);try(Jedis j=pool.getResource()){if(!"PONG".equals(j.ping()))throw new IllegalStateException("Redis PING failed");}state=new RedisStateStore(pool);files=new RedisBaseStore(pool);}
 public AgentStateStore agentStateStore(){return state;}
 public BaseStore baseStore(){return files;}
 public boolean isReady(){try(Jedis j=pool.getResource()){return "PONG".equals(j.ping());}catch(Exception e){return false;}}
 public void close(){pool.close();}

 private static String enc(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
 private static String dec(String value){return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}
 private static String user(String value){return value==null||value.isBlank()?"__anon__":value;}
 private static String sessionKey(String user,String session){if(session==null||session.isBlank())throw new IllegalArgumentException("sessionId required");return ROOT+"state:"+enc(user(user))+":"+enc(session);}

 private static final class RedisStateStore implements AgentStateStore {
  private final JedisPool pool; RedisStateStore(JedisPool p){pool=p;}
  public void save(String user,String session,String key,State value){try(Jedis j=pool.getResource()){j.hset(sessionKey(user,session),"one:"+key,JsonUtils.getJsonCodec().toJson(value));}}
  public void save(String user,String session,String key,List<? extends State> values){List<String> encoded=new ArrayList<>();for(State value:values)encoded.add(JsonUtils.getJsonCodec().toJson(value));try(Jedis j=pool.getResource()){j.hset(sessionKey(user,session),"list:"+key,JsonUtils.getJsonCodec().toJson(encoded));}}
  public <T extends State> Optional<T> get(String user,String session,String key,Class<T> type){try(Jedis j=pool.getResource()){String raw=j.hget(sessionKey(user,session),"one:"+key);return raw==null?Optional.empty():Optional.of(JsonUtils.getJsonCodec().fromJson(raw,type));}}
  public <T extends State> List<T> getList(String user,String session,String key,Class<T> type){try(Jedis j=pool.getResource()){String raw=j.hget(sessionKey(user,session),"list:"+key);if(raw==null)return List.of();List<String> encoded=JsonUtils.getJsonCodec().fromJson(raw,new TypeReference<List<String>>(){});List<T> out=new ArrayList<>(encoded.size());for(String value:encoded)out.add(JsonUtils.getJsonCodec().fromJson(value,type));return out;}}
  public boolean exists(String user,String session){try(Jedis j=pool.getResource()){return j.exists(sessionKey(user,session));}}
  public void delete(String user,String session){try(Jedis j=pool.getResource()){j.del(sessionKey(user,session));}}
  public void delete(String user,String session,String key){try(Jedis j=pool.getResource()){j.hdel(sessionKey(user,session),"one:"+key,"list:"+key);}}
  public Set<String> listSessionIds(String user){String prefix=ROOT+"state:"+enc(user(user))+":";Set<String> out=new HashSet<>();try(Jedis j=pool.getResource()){String cursor=ScanParams.SCAN_POINTER_START;ScanParams params=new ScanParams().match(prefix+"*").count(200);do{ScanResult<String> page=j.scan(cursor,params);cursor=page.getCursor();for(String key:page.getResult())out.add(dec(key.substring(prefix.length())));}while(!"0".equals(cursor));}return out;}
 }

 private static final class RedisBaseStore implements BaseStore {
  private static final String PUT="local v=redis.call('HGET',KEYS[1],'version'); local n=(v and tonumber(v) or 0)+1; redis.call('HSET',KEYS[1],'version',n,'itemKey',ARGV[1],'value',ARGV[2]); return n";
  private static final String CAS="local v=redis.call('HGET',KEYS[1],'version'); local c=(v and tonumber(v) or 0); if c~=tonumber(ARGV[1]) then return 0 end; redis.call('HSET',KEYS[1],'version',c+1,'itemKey',ARGV[2],'value',ARGV[3]); return 1";
  private final JedisPool pool; RedisBaseStore(JedisPool p){pool=p;}
  private String prefix(List<String> ns){return ROOT+"fs:"+enc(String.join("\u0000",ns))+":";}
  private String redisKey(List<String> ns,String key){return prefix(ns)+enc(key);}
  public StoreItem get(List<String> ns,String key){try(Jedis j=pool.getResource()){Map<String,String> h=j.hgetAll(redisKey(ns,key));if(h.isEmpty())return null;Map<String,Object> value=JsonUtils.getJsonCodec().fromJson(h.get("value"),new TypeReference<Map<String,Object>>(){});return new StoreItem(h.getOrDefault("itemKey",key),value,Long.parseLong(h.getOrDefault("version","0")));}}
  public void put(List<String> ns,String key,Map<String,Object> value){try(Jedis j=pool.getResource()){j.eval(PUT,List.of(redisKey(ns,key)),List.of(key,JsonUtils.getJsonCodec().toJson(value)));}}
  public boolean putIfVersion(List<String> ns,String key,Map<String,Object> value,long expected){try(Jedis j=pool.getResource()){Object result=j.eval(CAS,List.of(redisKey(ns,key)),List.of(Long.toString(expected),key,JsonUtils.getJsonCodec().toJson(value)));return Long.valueOf(1).equals(result);}}
  public List<StoreItem> search(List<String> ns,int limit,int offset){String prefix=prefix(ns);List<String> keys=new ArrayList<>();try(Jedis j=pool.getResource()){String cursor=ScanParams.SCAN_POINTER_START;ScanParams params=new ScanParams().match(prefix+"*").count(Math.max(100,limit));do{ScanResult<String> page=j.scan(cursor,params);cursor=page.getCursor();keys.addAll(page.getResult());}while(!"0".equals(cursor));Collections.sort(keys);int start=Math.min(Math.max(0,offset),keys.size()),end=Math.min(start+Math.max(0,limit),keys.size());List<StoreItem> out=new ArrayList<>();for(String redisKey:keys.subList(start,end)){Map<String,String> h=j.hgetAll(redisKey);Map<String,Object> value=JsonUtils.getJsonCodec().fromJson(h.get("value"),new TypeReference<Map<String,Object>>(){});out.add(new StoreItem(h.get("itemKey"),value,Long.parseLong(h.getOrDefault("version","0"))));}return out;}}
  public void delete(List<String> ns,String key){try(Jedis j=pool.getResource()){j.del(redisKey(ns,key));}}
 }
}
