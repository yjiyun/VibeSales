package com.yjiyun.chatflows.runtime.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.*;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.util.Objects;
import com.yjiyun.chatflows.runtime.observability.BlueprintTraceMiddleware;

public final class AgentFactory implements AutoCloseable {
 private final HarnessAgent singleton;
 private final AutoCloseable resource;

 public AgentFactory(Path workspace,Model model){this(workspace,model,null);}
 public AgentFactory(Path workspace,Model model,AgentSkillRepository skills){singleton=localBuilder(workspace,model,skills).build();resource=null;}
 public static AgentFactory production(Path workspace,String modelId,DistributedStore store,AgentSkillRepository skills){return new AgentFactory(remoteBuilder(workspace,modelId,store,skills).build(),asCloseable(store));}
 public static AgentFactory production(Path workspace,Model model,DistributedStore store,AgentSkillRepository skills){return new AgentFactory(remoteBuilder(workspace,model,store,skills).build(),asCloseable(store));}
 public static AgentFactory distributed(Path workspace,Model model,DistributedStore store,AgentSkillRepository skills){return new AgentFactory(remoteBuilder(workspace,model,store,skills).build(),asCloseable(store));}
 private AgentFactory(HarnessAgent agent,AutoCloseable resource){singleton=agent;this.resource=resource;}
 public HarnessAgent agent(){return singleton;}
 public void close(){singleton.close();if(resource!=null)try{resource.close();}catch(Exception e){throw new IllegalStateException("distributed store close failed",e);}}

 private static HarnessAgent.Builder localBuilder(Path workspace,Model model,AgentSkillRepository skills){Objects.requireNonNull(model);var b=base(workspace).model(model).filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER).inheritEnv(false));if(model instanceof BlueprintAwareModel)b.additionalContextFile(".blueprint-identity");if(skills!=null)b.skillRepository(skills);return b;}
 private static HarnessAgent.Builder remoteBuilder(Path workspace,Object model,DistributedStore store,AgentSkillRepository skills){Objects.requireNonNull(store);var b=base(workspace).distributedStore(store).filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER).anonymousUserId("_anonymous"));if(model instanceof Model m)b.model(m);else b.model(String.valueOf(model));if(skills!=null)b.skillRepository(skills);return b;}
 private static HarnessAgent.Builder base(Path workspace){return HarnessAgent.builder().name("agent-runtime").workspace(workspace).middleware(new OtelTracingMiddleware()).middleware(new BlueprintTraceMiddleware()).additionalContextFile("SOUL.md").maxContextTokens(8000).compaction(CompactionConfig.builder().triggerMessages(50).keepMessages(20).build());}
 private static AutoCloseable asCloseable(DistributedStore store){return store instanceof AutoCloseable c?c:null;}
}
