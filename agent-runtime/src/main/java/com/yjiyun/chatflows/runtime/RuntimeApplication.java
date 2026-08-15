package com.yjiyun.chatflows.runtime;

import com.sun.net.httpserver.HttpServer;
import com.yjiyun.chatflows.runtime.agent.*;
import com.yjiyun.chatflows.runtime.api.*;
import com.yjiyun.chatflows.runtime.blueprint.*;
import com.yjiyun.chatflows.runtime.security.*;
import com.yjiyun.chatflows.runtime.skill.*;
import com.yjiyun.chatflows.runtime.store.RedisDistributedStore;
import com.yjiyun.chatflows.runtime.observability.BlueprintTraceMiddleware;
import com.yjiyun.chatflows.runtime.observability.RuntimeTelemetry;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public final class RuntimeApplication {
 public static void main(String[] args)throws Exception{
  // 先引导 AgentLoop 观测：OTLP 模式注册全局 SDK 并停用 ROA 镜像（避免 P0-2 双份）。
  BlueprintTraceMiddleware.setOtlpMode(RuntimeTelemetry.install(System.getenv()));
 String token=required("RUNTIME_AUTH_TOKEN"),adminToken=required("RUNTIME_ADMIN_TOKEN");
  String stateHome=System.getenv("AGENTSCOPE_STATE_HOME");if(stateHome!=null&&!stateHome.isBlank())System.setProperty("agentscope.state.home",stateHome);
  String model=System.getenv().getOrDefault("RUNTIME_MODEL","dashscope:qwen-plus");
  Path workspace=Path.of(System.getenv().getOrDefault("AGENTSCOPE_WORKSPACE","workspace"));
  String mode=System.getenv().getOrDefault("RUNTIME_MODE","production");
  BlueprintRepository repo;
  BlueprintAdmin admin;
  AgentFactory factory;
  java.util.function.BooleanSupplier readiness;
  InMemoryBlueprintRepository localStore=null;
  if("local".equals(mode)){
   InMemoryBlueprintRepository memory=new InMemoryBlueprintRepository();
   localStore=memory; repo=memory; admin=memory;
   factory=switch(model){case "deterministic-test"->new AgentFactory(workspace,new DeterministicModel(),ReadOnlySkillRepository.defaults());case "blueprint-aware-test"->new AgentFactory(workspace,new BlueprintAwareModel(),ReadOnlySkillRepository.defaults());default->new AgentFactory(workspace,resolveModel(model),ReadOnlySkillRepository.defaults());};
   String seed=System.getenv("RUNTIME_LOCAL_BLUEPRINT");if(seed!=null&&!seed.isBlank()){AgentBlueprint bp=BlueprintJson.read(Path.of(seed));memory.stage(bp,"local-seed");memory.publish(bp.blueprintId(),bp.clientCode(),"local-seed");}
   readiness=()->true;
  }else if("production".equals(mode)){
   if(System.getenv("DASHSCOPE_API_KEY")!=null&&!System.getenv("DASHSCOPE_API_KEY").isBlank())throw new IllegalStateException("production runtime must not receive DASHSCOPE_API_KEY; use Higress consumer credentials");
   validateMcpGateway(required("RUNTIME_MCP_URL"));required("RUNTIME_MCP_TOKEN");
   String databaseUrl=jdbcUrl(runtimeDatabaseUrl()),databaseUser=required("DATABASE_USER"),databasePassword=required("DATABASE_PASSWORD");
   JdbcBlueprintRepository jdbcRepo=new JdbcBlueprintRepository(databaseUrl,databaseUser,databasePassword);repo=jdbcRepo;
   admin=new RestBlueprintAdmin(URI.create(required("BLUEPRINT_ADMIN_URL")),required("BLUEPRINT_ADMIN_TOKEN"));
   RedisDistributedStore distributed=new RedisDistributedStore(URI.create(required("REDIS_URL")));
   if(!jdbcRepo.isReady())throw new IllegalStateException("PostgreSQL runtime schema/role not ready");
   readiness=()->jdbcRepo.isReady()&&distributed.isReady();
   factory=AgentFactory.production(workspace,gatewayModel(model,required("RUNTIME_LLM_BASE_URL"),required("RUNTIME_LLM_TOKEN")),distributed,new JdbcSkillRepository(databaseUrl,databaseUser,databasePassword));
  }else throw new IllegalStateException("RUNTIME_MODE must be local or production");

  TenantToolPermissions permissions=new TenantToolPermissions();
  BlueprintProjector projector=new BlueprintProjector(factory.agent(),repo,permissions);
  RuntimeService service=new RuntimeService(factory.agent(),projector,permissions);
  AuthService auth=new AuthService(token,adminToken);
  int port=Integer.parseInt(System.getenv().getOrDefault("RUNTIME_PORT","8088"));
  String host=System.getenv().getOrDefault("RUNTIME_HOST","127.0.0.1");
  HttpServer server=HttpServer.create(new InetSocketAddress(host,port),0);
  server.createContext("/healthz",exchange->{boolean ready=readiness.getAsBoolean();byte[] body=("{\"ok\":"+ready+",\"service\":\"agent-runtime\",\"inspector\":"+InspectorFlag.enabled()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(ready?200:503,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});
  server.createContext("/api/v1/chat",new ChatController(service,auth));
  server.createContext("/api/v1/dryrun",new DryRunController(service,auth));
  server.createContext("/api/v1/publish",new PublishController(admin,auth));
  server.createContext("/api/v1/rollback",new PublishController(admin,auth));
  if(localStore!=null)server.createContext("/api/v1/ingest",new IngestController(localStore,auth));
  if(InspectorFlag.enabled())server.createContext("/api/v1/inspect",new InspectController(projector,auth));
  server.setExecutor(Executors.newCachedThreadPool());
  Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);factory.close();}));
  server.start();
  System.err.println("agent-runtime listening on "+host+":"+port+" mode="+mode);
 }
 /**
  * runtime 的库地址优先取 AGENT_RUNTIME_DATABASE_URL：DATABASE_URL 是给 Nest（node-pg）
  * 用的，内嵌了 chatflows_app_login 的凭证，而 runtime 必须以 DATABASE_USER
  * （agent_runtime_login，只读）连接。两者混用会让 runtime 拿到写权限账号。
  */
 private static String runtimeDatabaseUrl(){String specific=System.getenv("AGENT_RUNTIME_DATABASE_URL");return specific!=null&&!specific.isBlank()?specific.trim():required("DATABASE_URL");}
 /**
  * JDBC 只认 `jdbc:postgresql://…`。env 里常写成 libpq 风格的 `postgresql://…`，
  * DriverManager 会直接 "No suitable driver found"，而 isReady() 把异常吞成 false，
  * 表象是「PostgreSQL runtime schema/role not ready」，很容易误查到表和角色上去。
  * 这里补前缀并剥掉内嵌凭证（凭证只走 DATABASE_USER / DATABASE_PASSWORD）。
  */
 static String jdbcUrl(String raw){
  String value=raw.trim();
  if(value.startsWith("jdbc:"))return value;
  if(!value.startsWith("postgresql://")&&!value.startsWith("postgres://"))throw new IllegalStateException("runtime database URL must be a PostgreSQL URL, got: "+value);
  URI parsed=URI.create(value);
  if(parsed.getHost()==null)throw new IllegalStateException("runtime database URL has no host: "+value);
  StringBuilder out=new StringBuilder("jdbc:postgresql://").append(parsed.getHost());
  if(parsed.getPort()>0)out.append(':').append(parsed.getPort());
  out.append(parsed.getPath()==null||parsed.getPath().isBlank()?"/":parsed.getPath());
  if(parsed.getQuery()!=null&&!parsed.getQuery().isBlank())out.append('?').append(parsed.getQuery());
  return out.toString();
 }
 private static io.agentscope.core.model.Model resolveModel(String id){if(!ModelRegistry.canResolve(id))throw new IllegalStateException("model provider unavailable: "+id);return ModelRegistry.resolve(id);}
 static io.agentscope.core.model.Model gatewayModel(String id,String baseUrl,String token){validateModelGateway(baseUrl);if(token.length()<16)throw new IllegalStateException("RUNTIME_LLM_TOKEN must be a gateway consumer token of at least 16 characters");String name=id.startsWith("dashscope:")?id.substring("dashscope:".length()):id;if(name.isBlank()||name.contains(":"))throw new IllegalStateException("production runtime supports an OpenAI-compatible model name or dashscope:<name>");return OpenAIChatModel.builder().modelName(name).apiKey(token).baseUrl(baseUrl).stream(true).nativeStructuredOutputWithTools(false).build();}
 private static void validateMcpGateway(String value){URI uri=validatedHigress(value);if(uri.getPath()==null||!uri.getPath().startsWith("/mcp-servers/"))throw new IllegalStateException("RUNTIME_MCP_URL must use a Higress /mcp-servers/ path");}
 static void validateModelGateway(String value){URI uri=validatedHigress(value);if(uri.getPath()==null||uri.getPath().isBlank()||"/".equals(uri.getPath()))throw new IllegalStateException("RUNTIME_LLM_BASE_URL must include the Higress model route");}
 private static URI validatedHigress(String value){URI uri=URI.create(value);String host=uri.getHost(),lower=host==null?"":host.toLowerCase();boolean privateHost=!lower.isBlank()&&(lower.equals("localhost")||lower.equals("127.0.0.1")||lower.equals("::1")||lower.startsWith("10.")||lower.startsWith("192.168.")||lower.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")||!lower.contains("."));boolean higress=lower.contains("higress");boolean secure="https".equalsIgnoreCase(uri.getScheme())||("http".equalsIgnoreCase(uri.getScheme())&&privateHost);if(!secure||(!privateHost&&!higress))throw new IllegalStateException("gateway URL must target Higress over HTTPS or private-network HTTP");return uri;}
 private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException(key+" required");return value;}
}
