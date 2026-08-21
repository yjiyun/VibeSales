package com.vibesales.salesagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibesales.salesagent.agent.middleware.LlmTraceMiddleware;
import com.vibesales.salesagent.agent.middleware.RecoveryPromptContext;
import com.vibesales.salesagent.blueprint.AgentBlueprint;
import com.vibesales.salesagent.blueprint.AgentBlueprintRepository;
import com.vibesales.salesagent.blueprint.BlueprintCatalogItem;
import com.vibesales.salesagent.blueprint.BlueprintSelection;
import com.vibesales.salesagent.blueprint.BlueprintSourceFactory;
import com.vibesales.salesagent.blueprint.FallbackPromptAssets;
import com.vibesales.salesagent.blueprint.ResolvedBlueprint;
import com.vibesales.salesagent.blueprint.RuntimeAgentTemplateCatalog;
import com.vibesales.salesagent.blueprint.TenantWorkspaceProjector;
import com.vibesales.salesagent.config.AppConfig;
import com.vibesales.salesagent.context.CustomerContext;
import com.vibesales.salesagent.context.RuntimeContextMapper;
import com.vibesales.salesagent.knowledge.AccountKnowledgeBindingRepository;
import com.vibesales.salesagent.knowledge.BailianKnowledgeSearchService;
import com.vibesales.salesagent.knowledge.TenantKnowledgeContext;
import com.vibesales.salesagent.model.ModelFactory;
import com.vibesales.salesagent.observability.RuntimeTelemetry;
import com.vibesales.salesagent.progress.ExecutionProgressListener;
import com.vibesales.salesagent.progress.ExecutionProgressUpdate;
import com.vibesales.salesagent.rule.closure.ClosureWritebackRequiredFieldsRule;
import com.vibesales.salesagent.rule.handoff.HumanHandoffTriggerRule;
import com.vibesales.salesagent.rule.profile.FollowUpRoundLimitRule;
import com.vibesales.salesagent.rule.profile.ProfileCompletenessRule;
import com.vibesales.salesagent.rule.recovery.RecoveryDetectionRule;
import com.vibesales.salesagent.rule.taskboard.IntentPriorityRule;
import com.vibesales.salesagent.rule.taskboard.QueueVersionGuardRule;
import com.vibesales.salesagent.skill.RecoveryDecision;
import com.vibesales.salesagent.skill.RecoveryHandlingService;
import com.vibesales.salesagent.skill.SkillRepositoryFactory;
import com.vibesales.salesagent.tool.RuntimeToolScope;
import com.vibesales.salesagent.tool.history.GetHistorySummaryTool;
import com.vibesales.salesagent.tool.history.HistorySummarySnapshot;
import com.vibesales.salesagent.tool.history.HistorySummaryWriteRequest;
import com.vibesales.salesagent.tool.history.HistorySummaryWriteResult;
import com.vibesales.salesagent.tool.history.SaveHistorySummaryTool;
import com.vibesales.salesagent.tool.knowledge.RetrieveKnowledgeBaseTool;
import com.vibesales.salesagent.tool.profile.CustomerProfileSnapshot;
import com.vibesales.salesagent.tool.profile.GetCustomerProfileTool;
import com.vibesales.salesagent.tool.rulecontext.GetRuleContextTool;
import com.vibesales.salesagent.tool.rulecontext.RuleContextSnapshot;
import com.vibesales.salesagent.tool.session.CreateOrResumeSessionTool;
import com.vibesales.salesagent.tool.session.SessionBootstrapSnapshot;
import com.vibesales.salesagent.tool.taskboard.GetIntentQueueTool;
import com.vibesales.salesagent.tool.taskboard.IntentQueueSyncResult;
import com.vibesales.salesagent.tool.taskboard.IntentQueueSyncUpdate;
import com.vibesales.salesagent.tool.taskboard.IntentTaskSnapshot;
import com.vibesales.salesagent.tool.taskboard.SyncIntentQueueTool;
import com.vibesales.salesagent.tool.telemetry.ToolTelemetry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 当前正式项目里的主 Agent 编排骨架。
 *
 * <p>它负责把页面输入串到 CustomerContext、RuntimeContext、3 个只读 Tool、恢复判断服务，再拼成
 * 一个统一输出结果——这个类本身不应该出现任何业务场景分支判断，具体业务规则应该由 Skill/Tool/Rule
 * 承载（见 03-配置驱动架构设计.md 第1.1节的编排层红线）。
 *
 * <p>本轮从 {@code ReActAgent} 迁移到 {@code HarnessAgent}：真实模型调用已经验证通过，这一步是为了
 * 接入 {@code skillRepository(...)}，让 {@code resources/skills/} 下的 {@code SKILL.md} 真正参与
 * 模型推理，而不是停留在"挂在项目里但从未被加载"的摆设状态。
 */
public final class CustomerServiceOrchestratorAgent {
    private static final Set<String> SINGLE_STAGE_INTENT_CODES =
            Set.of(
                    "transfer_to_human",
                    "allergy_quality",
                    "return_exchange",
                    "product_usage",
                    "membership",
                    "package_card",
                    "product_recommend",
                    "daily_response",
                    "out_of_scope",
                    "general_consultation");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeContextMapper runtimeContextMapper;
    private final GetHistorySummaryTool getHistorySummaryTool;
    private final GetIntentQueueTool getIntentQueueTool;
    private final GetCustomerProfileTool getCustomerProfileTool;
    private final GetRuleContextTool getRuleContextTool;
    private final CreateOrResumeSessionTool createOrResumeSessionTool;
    private final SaveHistorySummaryTool saveHistorySummaryTool;
    private final SyncIntentQueueTool syncIntentQueueTool;
    private final RecoveryHandlingService recoveryHandlingService;
    /** 未覆盖词表的租户共用这一个实例；见 {@link #intentPriorityRuleFor}。 */
    private final IntentPriorityRule defaultIntentPriorityRule = new IntentPriorityRule();
    /** 未覆盖阈值的租户共用这两个实例，语义同 {@link #defaultIntentPriorityRule}。 */
    private final ProfileCompletenessRule defaultProfileCompletenessRule =
            new ProfileCompletenessRule();
    private final FollowUpRoundLimitRule defaultFollowUpRoundLimitRule =
            new FollowUpRoundLimitRule();
    /**
     * 转人工硬触发全租户共用一个实例：这条规则没有可覆盖参数，所以也没有
     * {@code humanHandoffTriggerRuleFor(binding)} 这样的按租户取实例方法。
     */
    private final HumanHandoffTriggerRule defaultHumanHandoffTriggerRule =
            new HumanHandoffTriggerRule();
    /** 任务板乐观锁校验同样无参可配，全租户共用一个实例。 */
    private final QueueVersionGuardRule defaultQueueVersionGuardRule = new QueueVersionGuardRule();
    /** 收口必填项校验同样无参可配：四个必填字段是解析前提而不是租户偏好。 */
    private final ClosureWritebackRequiredFieldsRule defaultClosureWritebackRequiredFieldsRule =
            new ClosureWritebackRequiredFieldsRule();
    private final AgentBlueprintRepository blueprintRepository;
    private final AccountKnowledgeBindingRepository accountKnowledgeBindingRepository;
    private final RuntimeAgentTemplateCatalog runtimeAgentTemplateCatalog;
    private final TenantWorkspaceProjector workspaceProjector;
    private final Model model;
    private final String agentName;

    /**
     * 服务全部租户的<b>唯一</b> Agent 实例。
     *
     * <p>租户差异不在实例上，而在每请求投影出的 {@code <userId>/AGENTS.md} 与
     * {@code <userId>/skills/} 上（见 {@link SalesAgentFactory}）。
     */
    private final HarnessAgent sharedAgent;

    /**
     * LLM 输入输出埋点中间件。与 {@link #sharedAgent} 同生命周期：中间件在 {@code onModelCall} 里
     * 按 {@code replyId} 暂存快照，{@link AgentStreamAccumulator} 在 {@code ModelCallEndEvent} 到达时
     * 取走。因为累加器是静态内部类，这里把实例通过构造参数传进去。
     */
    private final LlmTraceMiddleware llmTraceMiddleware = new LlmTraceMiddleware();

    /** 没命中蓝图时下发的兜底 Skill；从 classpath 读一次即可，内容不随租户变化。 */
    private final List<AgentSkill> fallbackSkills;
    private final FallbackPromptAssets.Content fallbackPrompt;
    private final MultiStagePromptAssets multiStagePromptAssets = new MultiStagePromptAssets();

    public CustomerServiceOrchestratorAgent(AppConfig config) {
        this.runtimeContextMapper = new RuntimeContextMapper();
        // 三个只读 Tool 共享同一个后端调用作用域；后端未配置时 scope.available() 为 false，
        // 各 Tool 自行回退到占位快照，装配代码这里不需要写任何分支判断
        RuntimeToolScope runtimeToolScope = RuntimeToolScope.from(config);
        this.getHistorySummaryTool = new GetHistorySummaryTool(runtimeToolScope);
        this.getIntentQueueTool = new GetIntentQueueTool(runtimeToolScope);
        this.getCustomerProfileTool = new GetCustomerProfileTool(runtimeToolScope);
        this.getRuleContextTool = new GetRuleContextTool(runtimeToolScope);
        this.createOrResumeSessionTool = new CreateOrResumeSessionTool(runtimeToolScope);
        this.saveHistorySummaryTool = new SaveHistorySummaryTool(runtimeToolScope);
        this.syncIntentQueueTool = new SyncIntentQueueTool(runtimeToolScope);
        this.recoveryHandlingService = new RecoveryHandlingService();
        this.blueprintRepository =
                new AgentBlueprintRepository(BlueprintSourceFactory.create(config));
        this.accountKnowledgeBindingRepository = new AccountKnowledgeBindingRepository();
        this.runtimeAgentTemplateCatalog = new RuntimeAgentTemplateCatalog();
        this.model = ModelFactory.createDefaultModel(config);
        this.workspaceProjector = new TenantWorkspaceProjector(config.resolvedWorkspaceRoot());
        this.agentName = safe(config.appName());
        this.fallbackSkills = SkillRepositoryFactory.loadDefaultSkills();
        this.fallbackPrompt = new FallbackPromptAssets().load();
        // 共享 Agent 不再持有 Prompt 正文；身份与工作准则都从命名空间 workspace 里按请求读取。
        this.sharedAgent =
                new SalesAgentFactory()
                        .createSharedAgent(
                                model, workspaceProjector.workspaceRoot(), llmTraceMiddleware);
        this.sharedAgent
                .getToolkit()
                .registerTool(new RetrieveKnowledgeBaseTool(new BailianKnowledgeSearchService(config)));
        AistioRuntimeBridge.maybeAttach(this.sharedAgent);
    }

    /** 索引里登记的全部租户作用域，供 debug 接口列出可选项。 */
    public List<Map<String, String>> listBlueprintScopes() {
        return blueprintRepository.listScopes();
    }

    public List<BlueprintCatalogItem> listBlueprintCatalog(
            String clientCode, String cluster, String sceneCode, String runtimeAgentId) {
        return blueprintRepository.listCatalog(clientCode, cluster, sceneCode, runtimeAgentId, true);
    }

    /** 解析并投影指定租户作用域的蓝图；校验失败会抛出 {@link IllegalStateException}。 */
    public Optional<ResolvedBlueprint> resolveBlueprint(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return resolveBlueprint(
                clientCode, cluster, sceneCode, runtimeAgentId, version, BlueprintSelection.scoped());
    }

    public Optional<ResolvedBlueprint> resolveBlueprint(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection) {
        return blueprintRepository.resolve(
                clientCode, cluster, sceneCode, runtimeAgentId, version, selection);
    }

    /** 只加载 + 校验，不投影：校验失败时 debug 接口仍要能返回完整错误清单。 */
    public Optional<AgentBlueprintRepository.Inspection> inspectBlueprint(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return inspectBlueprint(
                clientCode, cluster, sceneCode, runtimeAgentId, version, BlueprintSelection.scoped());
    }

    public Optional<AgentBlueprintRepository.Inspection> inspectBlueprint(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection) {
        return blueprintRepository.inspect(
                clientCode, cluster, sceneCode, runtimeAgentId, version, selection);
    }

    /** 当前可见的运行时 Agent 模板目录，先由代码内置样例和已注册 Blueprint 共同组成。 */
    public List<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> listRuntimeAgentTemplates() {
        return runtimeAgentTemplateCatalog.listTemplates(blueprintRepository.listScopes());
    }

    /** 工作台当前运行资产摘要。 */
    public RuntimeBindingSummary summarizeRuntimeBinding(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return summarizeRuntimeBinding(
                clientCode, cluster, sceneCode, runtimeAgentId, version, BlueprintSelection.scoped());
    }

    public RuntimeBindingSummary summarizeRuntimeBinding(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection) {
        String requestedClientCode = safe(clientCode);
        String requestedCluster = safe(cluster);
        String requestedSceneCode = safe(sceneCode);
        String requestedRuntimeAgentId =
                !safe(runtimeAgentId).isEmpty() ? safe(runtimeAgentId) : requestedSceneCode;
        String requestedVersion = safe(version);
        BlueprintSelection effectiveSelection =
                selection == null ? BlueprintSelection.scoped() : selection;

        List<Map<String, String>> tenantScopes =
                filterTenantScopes(
                        blueprintRepository.listScopes(), requestedClientCode, requestedCluster);
        List<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> scopedTemplates =
                filterTemplatesForTenant(
                        runtimeAgentTemplateCatalog.listTemplates(tenantScopes),
                        tenantScopes,
                        requestedRuntimeAgentId);
        Map<String, List<String>> versionsByRuntimeAgent =
                collectVersionsByRuntimeAgent(tenantScopes);

        Optional<ResolvedBlueprint> resolved =
                resolveBlueprint(
                        requestedClientCode,
                        requestedCluster,
                        requestedSceneCode,
                        requestedRuntimeAgentId,
                        requestedVersion,
                        effectiveSelection);
        String effectiveRuntimeAgentId =
                resolved.map(ResolvedBlueprint::runtimeAgentId).orElse(requestedRuntimeAgentId);
        RuntimeAgentTemplateCatalog.RuntimeAgentTemplate selectedTemplate =
                findTemplate(scopedTemplates, effectiveRuntimeAgentId)
                        .orElseGet(
                                () ->
                                        findTemplate(
                                                        runtimeAgentTemplateCatalog.listTemplates(List.of()),
                                                        effectiveRuntimeAgentId)
                                                .orElse(null));

        List<RuntimeBindingOption> runtimeAgents =
                scopedTemplates.stream()
                        .map(
                                template ->
                                        new RuntimeBindingOption(
                                                template.runtimeAgentId(),
                                                template.displayName(),
                                                template.category(),
                                                template.description(),
                                                template.enabled(),
                                                versionsByRuntimeAgent.getOrDefault(
                                                        safe(template.runtimeAgentId()), List.of()),
                                                template.runtimeAgentId().equals(effectiveRuntimeAgentId)))
                        .toList();

        RuntimeBindingCurrent current =
                new RuntimeBindingCurrent(
                        requestedClientCode,
                        requestedCluster,
                        requestedSceneCode,
                        requestedRuntimeAgentId,
                        requestedVersion,
                        effectiveRuntimeAgentId,
                        selectedTemplate == null ? "" : safe(selectedTemplate.displayName()),
                        selectedTemplate == null ? "" : safe(selectedTemplate.category()),
                        resolved.map(ResolvedBlueprint::blueprintId).orElse(""),
                        resolved.map(ResolvedBlueprint::blueprintIdentity).orElse(""),
                        resolved.map(item -> safe(item.blueprint().runtimeModeOrDefault())).orElse(""),
                        resolved.map(item -> item.blueprint().stages().size()).orElse(0),
                        resolved.map(ResolvedBlueprint::matchLevel).orElse("fallback"),
                        resolved.map(ResolvedBlueprint::sourceType).orElse(""),
                        resolved.map(ResolvedBlueprint::sourcePath).orElse(""),
                        resolved.map(ResolvedBlueprint::selectionId)
                                .orElse(
                                        effectiveSelection.isPinned()
                                                ? effectiveSelection.selectionId()
                                                : ""),
                        effectiveSelection.selectorModeOrDefault(),
                        resolved.map(item -> String.valueOf(item.version())).orElse(""),
                        resolved.isPresent());
        return new RuntimeBindingSummary(current, runtimeAgents);
    }

    public ChatResponse handle(CustomerContext customerContext, String userMessage) {
        return handle(
                customerContext,
                userMessage,
                BlueprintSelection.scoped(),
                ExecutionProgressListener.noop(),
                null);
    }

    public ChatResponse handle(
            CustomerContext customerContext,
            String userMessage,
            ExecutionProgressListener progressListener) {
        return handle(
                customerContext,
                userMessage,
                BlueprintSelection.scoped(),
                progressListener,
                null);
    }

    public ChatResponse handle(
            CustomerContext customerContext,
            String userMessage,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        return handle(
                customerContext,
                userMessage,
                BlueprintSelection.scoped(),
                progressListener,
                agentEventListener);
    }

    public ChatResponse handle(
            CustomerContext customerContext,
            String userMessage,
            BlueprintSelection selection,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        return handle(
                customerContext, userMessage, selection, progressListener, agentEventListener, null);
    }

    /**
     * 用调用方直传的 Blueprint 原文跑一轮对话，不经 {@link AgentBlueprintRepository} 的
     * 作用域查询——供 {@code /api/v1/dryrun} 使用：P4 dry-run 时 Blueprint 可能还没（或刚)持久化，
     * 校验的是"这份 Blueprint 原文能不能跑通"，不是"这个租户作用域当前绑定的是哪份"。
     */
    public ChatResponse handleWithBlueprint(
            AgentBlueprint adHocBlueprint,
            CustomerContext customerContext,
            String userMessage,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        return handle(
                customerContext,
                userMessage,
                BlueprintSelection.scoped(),
                progressListener,
                agentEventListener,
                Objects.requireNonNull(adHocBlueprint, "adHocBlueprint"));
    }

    private ChatResponse handle(
            CustomerContext customerContext,
            String userMessage,
            BlueprintSelection selection,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener,
            AgentBlueprint adHocBlueprint) {
        try (RuntimeTelemetry.RunScope telemetry =
                        RuntimeTelemetry.startAgentRun(agentName, customerContext, userMessage);
                ToolTelemetry.Scope ignored = ToolTelemetry.install(progressListener)) {
            try {
                BootstrapContext bootstrap =
                        runStageGroup(
                                progressListener,
                                "runtime_bootstrap",
                                "运行时引导",
                                "bootstrap",
                                () -> {
                                    RuntimeContext runtimeContext =
                                            runStep(
                                                    progressListener,
                                                    "orchestration",
                                                    "context.map",
                                                    "映射运行时上下文",
                                                    () -> runtimeContextMapper.map(customerContext),
                                                    context ->
                                                            Map.of(
                                                                    "sessionId", safe(context.getSessionId()),
                                                                    "userId", safe(context.getUserId())));
                                    TenantAgentBinding agentBinding =
                                            runStep(
                                                    progressListener,
                                                    "orchestration",
                                                    "blueprint.resolve",
                                                    "解析租户蓝图",
                                                    () ->
                                                            adHocBlueprint == null
                                                                    ? resolveTenantBinding(customerContext, selection)
                                                                    : resolveAdHocBinding(
                                                                            customerContext, adHocBlueprint),
                                                    binding ->
                                                            ioDetail(
                                                                    blueprintResolveInput(
                                                                            customerContext, selection),
                                                                    binding.toTimelineDetail(),
                                                                    binding.toTimelineDetail()));
                                    return new BootstrapContext(runtimeContext, agentBinding);
                                });
                RuntimeContext runtimeContext = bootstrap.runtimeContext();
                TenantAgentBinding agentBinding = bootstrap.agentBinding();
                TenantKnowledgeContext knowledgeContext =
                        runStep(
                                progressListener,
                                "orchestration",
                                "knowledge.bind",
                                "绑定租户知识库",
                                () -> knowledgeContextFor(customerContext, agentBinding),
                                context ->
                                        ioDetail(
                                                Map.of(
                                                        "clientCode",
                                                                customerContext.normalizedClientCode(),
                                                        "cluster",
                                                                customerContext.normalizedCluster(),
                                                        "runtimeAgentId",
                                                                resolveTelemetryAgentName(agentBinding)),
                                                context.toTimelineDetail(),
                                                context.toTimelineDetail()));
                runtimeContext.put(TenantKnowledgeContext.class, knowledgeContext);
                telemetry.setAgentName(resolveTelemetryAgentName(agentBinding));
                if (agentBinding.isMultiStage()) {
                    return finishTelemetry(
                            telemetry,
                            handleMultiStage(
                                    customerContext,
                                    userMessage,
                                    progressListener,
                                    agentEventListener,
                                    runtimeContext,
                                    agentBinding));
                }
                SingleStageExecutionResult singleStageResult =
                        runStageGroup(
                                progressListener,
                                "single_agent",
                                "单阶段执行",
                                "single_agent",
                                () -> {
                                runStep(
                                        progressListener,
                                        "orchestration",
                                        "workspace.project",
                                        "投影租户提示词",
                                        () ->
                                                workspaceProjector.project(
                                                        runtimeContext.getUserId(),
                                                        projectionContentFor(agentBinding)),
                                        projection ->
                                                workspaceProjectDetail(
                                                        runtimeContext.getUserId(), projection));
                                SessionBootstrapSnapshot sessionSnapshot =
                                        runSoftStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.session.ensure",
                                                "准备业务会话",
                                                () -> createOrResumeSessionTool.ensureSession(customerContext),
                                                snapshot ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "conversationId",
                                                                                customerContext
                                                                                        .normalizedConversationId(),
                                                                        "chatUser",
                                                                                customerContext
                                                                                        .normalizedChatUser(),
                                                                        "userId",
                                                                                customerContext.normalizedUserId()),
                                                                Map.of(
                                                                        "sessionId", safe(snapshot.sessionId()),
                                                                        "sessionCode", safe(snapshot.sessionCode()),
                                                                        "status", safe(snapshot.status()),
                                                                        "isNewSession", snapshot.isNewSession(),
                                                                        "isNewCustomer", snapshot.isNewCustomer(),
                                                                        "sessionAction",
                                                                                safe(snapshot.sessionAction()),
                                                                        "matchedCustomerBy",
                                                                                safe(
                                                                                        snapshot
                                                                                                .matchedCustomerBy()))),
                                                new SessionBootstrapSnapshot(
                                                        "", "", "skipped", false, false, "skipped", ""));
                                HistorySummarySnapshot history =
                                        runStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.history.load",
                                                "读取历史摘要",
                                                () -> getHistorySummaryTool.load(customerContext),
                                                snapshot ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "conversationId",
                                                                                customerContext
                                                                                        .normalizedConversationId(),
                                                                        "chatUser",
                                                                                customerContext
                                                                                        .normalizedChatUser()),
                                                                Map.of(
                                                                        "recoveryPending",
                                                                                snapshot.recoveryPending(),
                                                                        "activeIntentCode",
                                                                                safe(
                                                                                        snapshot
                                                                                                .activeIntentCode()),
                                                                        "summaryPreview",
                                                                                trimPreview(
                                                                                        snapshot.summaryText()))));
                                IntentTaskSnapshot queue =
                                        runStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.queue.load",
                                                "读取任务板",
                                                () -> getIntentQueueTool.load(customerContext),
                                                snapshot ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "conversationId",
                                                                                customerContext
                                                                                        .normalizedConversationId(),
                                                                        "chatUser",
                                                                                customerContext
                                                                                        .normalizedChatUser()),
                                                                Map.of(
                                                                        "queueVersion",
                                                                                safe(snapshot.queueVersion()),
                                                                        "totalTasks", snapshot.totalTasks(),
                                                                        "activeTasks", snapshot.activeTasks(),
                                                                        "suspendedTasks",
                                                                                snapshot.suspendedTasks())));
                                CustomerProfileSnapshot profile =
                                        runStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.profile.load",
                                                "加载客户画像",
                                                () -> getCustomerProfileTool.load(customerContext),
                                                snapshot ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "sceneCode",
                                                                                customerContext
                                                                                        .normalizedSceneCode(),
                                                                        "chatUser",
                                                                                customerContext
                                                                                        .normalizedChatUser()),
                                                                Map.of(
                                                                        "profileVersion",
                                                                                safe(snapshot.profileVersion()),
                                                                        "hasConcern", snapshot.hasConcern(),
                                                                        "hasTargetBenefit",
                                                                                snapshot.hasTargetBenefit(),
                                                                        "hasCoreNeed", snapshot.hasCoreNeed(),
                                                                        "hasSkinType", snapshot.hasSkinType(),
                                                                        "hasBudget", snapshot.hasBudget(),
                                                                        "hasCategoryPreference",
                                                                                snapshot
                                                                                        .hasCategoryPreference())));
                                RecoveryHandlingService tenantRecoveryService =
                                        recoveryServiceFor(agentBinding);
                                RecoveryDecision recovery =
                                        runStep(
                                                progressListener,
                                                "orchestration",
                                                "rule.recovery.evaluate",
                                                "恢复判断",
                                                () -> tenantRecoveryService.evaluate(userMessage, history, queue),
                                                decision ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "userMessage",
                                                                                trimPreview(userMessage),
                                                                        "historyRecoveryPending",
                                                                                history.recoveryPending(),
                                                                        "queueVersion",
                                                                                safe(queue.queueVersion()),
                                                                        "keywordSource",
                                                                                agentBinding
                                                                                        .recoveryKeywordSource(),
                                                                        "continuationKeywords",
                                                                                tenantRecoveryService
                                                                                        .continuationKeywords()),
                                                                Map.of(
                                                                        "recoveryMode",
                                                                                safe(decision.recoveryMode()),
                                                                        "targetIntent",
                                                                                safe(
                                                                                        decision
                                                                                                .targetIntent()))));
                                Msg userMsg =
                                        runStep(
                                                progressListener,
                                                "orchestration",
                                                "prompt.compose",
                                                "组装模型输入",
                                                () -> {
                                                    runtimeContext.put(
                                                            RecoveryPromptContext.class,
                                                            new RecoveryPromptContext(
                                                                    recovery, history, queue, profile));
                                                    return buildUserMsg(userMessage);
                                                },
                                                // 必须产出 input / output 键：只放平铺字段的话前端
                                                // 「输入 / 输出」面板读不到 detail.input / detail.output，
                                                // 两栏会直接显示"无"，让人误以为这一步没拿到数据。这个节点
                                                // 恰好是判断"注入了什么"的关键，所以把四份快照一并留痕。
                                                composed ->
                                                        ioDetail(
                                                                promptComposeInput(
                                                                        userMessage,
                                                                        recovery,
                                                                        history,
                                                                        queue,
                                                                        profile),
                                                                promptComposeOutput(composed),
                                                                Map.of(
                                                                        "injectionMode",
                                                                                "RuntimeContext + Middleware",
                                                                        "userMessageLength",
                                                                                safe(userMessage).length())));
                                String rawReply =
                                        runAgentWithTimeline(
                                                sharedAgent,
                                                userMsg,
                                                runtimeContext,
                                                progressListener,
                                                agentEventListener);
                                String fallbackIntentCode = deriveIntentCode(recovery, history);
                                SingleStageAgentOutput agentOutput =
                                        parseSingleStageAgentOutput(rawReply, fallbackIntentCode);
                                String reply = agentOutput.reply();
                                String resolvedIntentCode = agentOutput.intentCode();
                                HistorySummaryWriteRequest historyWriteRequest =
                                        buildHistorySummaryWriteRequest(
                                                userMessage,
                                                reply,
                                                recovery,
                                                history,
                                                resolvedIntentCode,
                                                customerContext,
                                                agentOutput.needHumanHandoff());
                                HistorySummaryWriteResult persistedHistory =
                                        runSoftStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.history.save",
                                                "写回历史摘要",
                                                () -> saveHistorySummaryTool.save(customerContext, historyWriteRequest),
                                                result ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "conversationId",
                                                                                customerContext
                                                                                        .normalizedConversationId(),
                                                                        "chatUser",
                                                                                customerContext
                                                                                        .normalizedChatUser(),
                                                                        "historySummary",
                                                                                trimPreview(
                                                                                        historyWriteRequest
                                                                                                .historySummary()),
                                                                        "lastIntent",
                                                                                safe(
                                                                                        historyWriteRequest
                                                                                                .lastIntent())),
                                                                Map.of(
                                                                        "summaryVersion",
                                                                                result.summaryVersion(),
                                                                        "lastIntent",
                                                                                safe(result.lastIntent()),
                                                                        "lastNextStep",
                                                                                safe(result.lastNextStep()),
                                                                        "historySummary",
                                                                                trimPreview(
                                                                                        result
                                                                                                .historySummary()))),
                                                unwrittenHistorySummary(
                                                        customerContext, historyWriteRequest));
                                IntentQueueSyncResult syncedQueue =
                                        runSoftStep(
                                                progressListener,
                                                "orchestration",
                                                "tool.queue.sync",
                                                "同步任务板",
                                                () ->
                                                        syncIntentQueueTool.sync(
                                                                customerContext,
                                                                sessionSnapshot.sessionId(),
                                                                queue.queueVersion(),
                                                                buildIntentQueueUpdates(
                                                                        customerContext,
                                                                        userMessage,
                                                                        reply,
                                                                        resolvedIntentCode)),
                                                result ->
                                                        toolDetail(
                                                                Map.of(
                                                                        "sessionId",
                                                                                safe(
                                                                                        sessionSnapshot
                                                                                                .sessionId()),
                                                                        "queueVersion",
                                                                                safe(queue.queueVersion()),
                                                                        "updates",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "intentCode",
                                                                                                resolvedIntentCode,
                                                                                                "action",
                                                                                                inferQueueAction(
                                                                                                        history,
                                                                                                        resolvedIntentCode)))),
                                                                Map.of(
                                                                        "queueVersion",
                                                                                result.queueVersion(),
                                                                        "activeIntentCode",
                                                                                safe(
                                                                                        result
                                                                                                .activeIntentCode()),
                                                                        "activeIntentKey",
                                                                                safe(
                                                                                        result
                                                                                                .activeIntentKey()),
                                                                        "taskBoardSummary",
                                                                                trimPreview(
                                                                                        result
                                                                                                .taskBoardSummary()))),
                                                new IntentQueueSyncResult(
                                                        customerContext.normalizedConversationId(),
                                                        customerContext.normalizedChatUser(),
                                                        parseQueueVersion(queue.queueVersion()),
                                                        resolvedIntentCode,
                                                        resolvedIntentCode,
                                                        ""));
                                return new SingleStageExecutionResult(
                                        reply, recovery, profile, persistedHistory, syncedQueue);
                            });
                return finishTelemetry(
                        telemetry,
                        new ChatResponse(
                                singleStageResult.reply(),
                                customerContext.normalizedConversationId(),
                                customerContext.normalizedRobotConversationId(),
                                customerContext.normalizedChatUser(),
                                customerContext.normalizedRobotKey(),
                                customerContext.normalizedConversationName(),
                                customerContext.normalizedMessageId(),
                                singleStageResult.recovery().recoveryMode(),
                                singleStageResult.recovery().targetIntent(),
                                singleStageResult.persistedHistory().historySummary(),
                                singleStageResult.profile().summary(),
                                String.valueOf(singleStageResult.syncedQueue().queueVersion()),
                                resolvedBlueprintSummary(agentBinding)));
            } catch (RuntimeException | Error exception) {
                telemetry.failure(exception);
                throw exception;
            }
        }
    }

    /**
     * 按请求上下文里的 {@code clientCode + cluster} 定位本轮该生效的蓝图。
     *
     * <p>只解析蓝图，不再装配 Agent——Agent 只有一个，且在构造期就装好了。蓝图缺失时标记
     * {@code source=fallback}，本轮改投影 fallback Prompt 与默认 Skill；不能让
     * "蓝图没生效"表现成一次看起来正常的对话。
     */
    private TenantAgentBinding resolveTenantBinding(
            CustomerContext customerContext, BlueprintSelection selection) {
        String clientCode = customerContext.normalizedClientCode();
        String cluster = safe(customerContext.cluster());
        // 不能用 normalizedSceneCode()：它在没传 sceneCode 时兜底成 "sales-service"，这个默认值只
        // 适合日志/展示场景。蓝图匹配把它当"请求指定的场景"用，会把没传场景的请求错误地限定成
        // "sales-service"，导致蓝图的 meta.scenarios 对不上、候选被过滤掉，静默落回 fallback 提示词。
        String sceneCode = safe(customerContext.sceneCode());
        String runtimeAgentId = customerContext.normalizedRuntimeAgentId();
        String version = customerContext.normalizedBlueprintVersion();
        String userId = customerContext.normalizedUserId();
        return new TenantAgentBinding(
                blueprintRepository
                        .resolve(clientCode, cluster, sceneCode, runtimeAgentId, version, selection, userId)
                        .orElse(null),
                clientCode,
                cluster,
                runtimeAgentId,
                version,
                selection == null ? BlueprintSelection.scoped() : selection);
    }

    /** {@link #resolveTenantBinding} 的 ad-hoc 版本：直接校验/投影传入的 Blueprint 原文，不查作用域。 */
    private TenantAgentBinding resolveAdHocBinding(
            CustomerContext customerContext, AgentBlueprint adHocBlueprint) {
        ResolvedBlueprint resolved = blueprintRepository.resolveAdHoc(adHocBlueprint);
        String runtimeAgentId = customerContext.normalizedRuntimeAgentId();
        return new TenantAgentBinding(
                resolved,
                resolved.requestedClientCode(),
                resolved.requestedCluster(),
                runtimeAgentId.isEmpty() ? resolved.runtimeAgentId() : runtimeAgentId,
                String.valueOf(resolved.version()),
                BlueprintSelection.pinned(resolved.blueprintId()));
    }

    /**
     * 本轮要投影的内容。
     *
     * <p>兜底分支同样要投影，不能"没蓝图就不写文件"：漏写会让共享 Agent 回落到 workspace 根目录的
     * 公共 {@code AGENTS.md}，也就是读到上一个租户或全局残留的提示词。
     */
    private ChatResponse handleMultiStage(
            CustomerContext customerContext,
            String userMessage,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener,
            RuntimeContext runtimeContext,
            TenantAgentBinding agentBinding) {
        ResolvedBlueprint blueprint =
                agentBinding.blueprint() == null
                        ? null
                        : agentBinding.blueprint();
        if (blueprint == null) {
            throw new IllegalStateException("multi_stage requires a resolved blueprint");
        }

        MultiStageExecutionContext executionContext =
                runStageGroup(
                        progressListener,
                        "preload_context",
                        "前置上下文准备",
                        "tool",
                        () -> {
                            SessionBootstrapSnapshot sessionSnapshot =
                                    runSoftStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.session",
                                            "前置上下文准备：业务会话",
                                            () -> createOrResumeSessionTool.ensureSession(customerContext),
                                            snapshot ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "conversationId",
                                                                            customerContext
                                                                                    .normalizedConversationId(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser(),
                                                                    "userId",
                                                                            customerContext.normalizedUserId()),
                                                            Map.of(
                                                                    "sessionId", safe(snapshot.sessionId()),
                                                                    "sessionCode", safe(snapshot.sessionCode()),
                                                                    "status", safe(snapshot.status()),
                                                                    "isNewSession", snapshot.isNewSession(),
                                                                    "isNewCustomer",
                                                                            snapshot.isNewCustomer())),
                                            new SessionBootstrapSnapshot(
                                                    "", "", "skipped", false, false, "skipped", ""));
                            HistorySummarySnapshot history =
                                    runStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.history",
                                            "前置上下文准备：历史摘要",
                                            () -> getHistorySummaryTool.load(customerContext),
                                            snapshot ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "conversationId",
                                                                            customerContext
                                                                                    .normalizedConversationId(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser()),
                                                            Map.of(
                                                                    "recoveryPending",
                                                                            snapshot.recoveryPending(),
                                                                    "activeIntentCode",
                                                                            safe(snapshot.activeIntentCode()),
                                                                    "summaryPreview",
                                                                            trimPreview(snapshot.summaryText()))));
                            IntentTaskSnapshot queue =
                                    runStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.queue",
                                            "前置上下文准备：任务板",
                                            () -> getIntentQueueTool.load(customerContext),
                                            snapshot ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "conversationId",
                                                                            customerContext
                                                                                    .normalizedConversationId(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser()),
                                                            Map.of(
                                                                    "queueVersion",
                                                                            safe(snapshot.queueVersion()),
                                                                    "totalTasks", snapshot.totalTasks(),
                                                                    "activeTasks", snapshot.activeTasks(),
                                                                    "suspendedTasks",
                                                                            snapshot.suspendedTasks())));
                            CustomerProfileSnapshot profile =
                                    runStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.profile",
                                            "前置上下文准备：客户画像",
                                            () -> getCustomerProfileTool.load(customerContext),
                                            snapshot ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "sceneCode",
                                                                            customerContext
                                                                                    .normalizedSceneCode(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser()),
                                                            Map.of(
                                                                    "profileVersion",
                                                                            safe(snapshot.profileVersion()),
                                                                    "hasConcern", snapshot.hasConcern(),
                                                                    "hasTargetBenefit",
                                                                            snapshot.hasTargetBenefit(),
                                                                    "hasCoreNeed", snapshot.hasCoreNeed(),
                                                                    "hasSkinType", snapshot.hasSkinType(),
                                                                    "hasBudget", snapshot.hasBudget(),
                                                                    "hasCategoryPreference",
                                                                            snapshot
                                                                                    .hasCategoryPreference())));
                            RuleContextSnapshot ruleContext =
                                    runSoftStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.rule_context",
                                            "前置上下文准备：规则上下文",
                                            () -> getRuleContextTool.load(customerContext),
                                            snapshot ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "sceneCode",
                                                                            customerContext
                                                                                    .normalizedSceneCode(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser()),
                                                            Map.of(
                                                                    "fromBackend", snapshot.fromBackend(),
                                                                    "ruleVersion",
                                                                            safe(snapshot.ruleVersion()),
                                                                    "allowedProductIds",
                                                                            snapshot.allowedProductIds(),
                                                                    "constraintsCount",
                                                                            snapshot
                                                                                    .recommendationConstraints()
                                                                                    .size())),
                                            RuleContextSnapshot.unavailable(
                                                    customerContext.normalizedSceneCode()));
                            RecoveryHandlingService tenantRecoveryService =
                                    recoveryServiceFor(agentBinding);
                            RecoveryDecision deterministicRecovery =
                                    runStep(
                                            progressListener,
                                            "stage",
                                            "preload_context.recovery_signal",
                                            "前置上下文准备：恢复信号",
                                            () ->
                                                    tenantRecoveryService.evaluate(
                                                            userMessage, history, queue),
                                            decision ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "userMessage",
                                                                            trimPreview(userMessage),
                                                                    "historyRecoveryPending",
                                                                            history.recoveryPending(),
                                                                    "queueVersion",
                                                                            safe(queue.queueVersion()),
                                                                    "keywordSource",
                                                                            agentBinding
                                                                                    .recoveryKeywordSource(),
                                                                    "continuationKeywords",
                                                                            tenantRecoveryService
                                                                                    .continuationKeywords()),
                                                            Map.of(
                                                                    "recoveryMode",
                                                                            safe(decision.recoveryMode()),
                                                                    "targetIntent",
                                                                            safe(
                                                                                    decision
                                                                                            .targetIntent()))));
                            return new MultiStageExecutionContext(
                                    customerContext,
                                    userMessage,
                                    sessionSnapshot,
                                    history,
                                    queue,
                                    profile,
                                    ruleContext,
                                    deterministicRecovery);
                        });
        SessionBootstrapSnapshot sessionSnapshot = executionContext.sessionSnapshot();
        HistorySummarySnapshot history = executionContext.history();
        IntentTaskSnapshot queue = executionContext.queue();
        CustomerProfileSnapshot profile = executionContext.profile();
        RecoveryDecision deterministicRecovery = executionContext.deterministicRecovery();
        runtimeContext.put(
                RecoveryPromptContext.class,
                new RecoveryPromptContext(deterministicRecovery, history, queue, profile));

        RecoveryStageResult recoveryStageResult = RecoveryStageResult.notExecuted();
        if (shouldEnterRecoveryConfirmStage(history, queue)) {
            recoveryStageResult =
                    executeRecoveryConfirmStage(
                            blueprint, executionContext, runtimeContext, progressListener, agentEventListener);
        }

        IntentRouteStageResult intentStageResult = IntentRouteStageResult.skipped();
        if (!recoveryStageResult.replyToPrevious()) {
            intentStageResult =
                    executeIntentRouteStage(
                            blueprint,
                            agentBinding,
                            executionContext,
                            runtimeContext,
                            progressListener,
                            agentEventListener);
        }

        String resolvedIntentCode =
                firstNonBlank(
                        recoveryStageResult.resumeIntentCode(),
                        firstNonBlank(
                                intentStageResult.intentCode(),
                                deriveIntentCode(deterministicRecovery, history)));
        ProfileGateResult profileGateResult =
                evaluateProfileGate(agentBinding, executionContext, progressListener);
        HandoffDecision handoffDecision =
                evaluateHandoffTrigger(
                        agentBinding, resolvedIntentCode, intentStageResult, progressListener);
        BusinessProcessStageResult businessStageResult =
                executeBusinessProcessStage(
                        blueprint,
                        executionContext,
                        runtimeContext,
                        progressListener,
                        agentEventListener,
                        resolvedIntentCode,
                        recoveryStageResult,
                        intentStageResult,
                        profileGateResult,
                        handoffDecision);

        String reply =
                firstNonBlank(
                        safe(businessStageResult.reply()),
                        "模型已返回空结果，请稍后重试。");

        HistorySummaryWriteRequest historyWriteRequest =
                buildMultiStageHistorySummaryWriteRequest(
                        userMessage,
                        reply,
                        businessStageResult,
                        deterministicRecovery,
                        history,
                        resolvedIntentCode,
                        customerContext,
                        handoffDecision.shouldHandoff());
        ResultCloseContext resultClose =
                runStageGroup(
                        progressListener,
                        "result_close",
                        "结果收口",
                        "tool",
                        () -> {
                            // 乐观锁校验挪到两次写回之前：收口必填项校验要检查"真正会回传的
                            // queueVersion"，那个值由这一步算出。放在 history 之后的话必填项校验
                            // 就只能拿前置上下文里那份可能已过期的版本号去检查，检查的和写的不是
                            // 同一个值。代价是重读比原先早了一次 saveHistorySummary 的时间，
                            // 但摘要写回不碰任务板，冲突窗口实际没变宽。
                            QueueVersionDecision queueVersionDecision =
                                    evaluateQueueVersionGuard(
                                            agentBinding,
                                            customerContext,
                                            queue.queueVersion(),
                                            progressListener);
                            ClosureWritebackDecision closureDecision =
                                    evaluateClosureWriteback(
                                            agentBinding,
                                            historyWriteRequest.historySummary(),
                                            resolvedIntentCode,
                                            inferQueueAction(history, resolvedIntentCode),
                                            queueVersionDecision.effectiveQueueVersion(),
                                            progressListener);
                            HistorySummaryWriteResult persistedHistory =
                                    runSoftStep(
                                            progressListener,
                                            "stage",
                                            "result_close.history",
                                            "结果收口：写回历史摘要",
                                            () ->
                                                    closureDecision.historyWritable()
                                                            ? saveHistorySummaryTool.save(
                                                                    customerContext,
                                                                    historyWriteRequest)
                                                            : unwrittenHistorySummary(
                                                                    customerContext,
                                                                    historyWriteRequest),
                                            result ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "conversationId",
                                                                            customerContext
                                                                                    .normalizedConversationId(),
                                                                    "chatUser",
                                                                            customerContext
                                                                                    .normalizedChatUser(),
                                                                    "historySummary",
                                                                            trimPreview(
                                                                                    historyWriteRequest
                                                                                            .historySummary()),
                                                                    "lastIntent",
                                                                            safe(
                                                                                    historyWriteRequest
                                                                                            .lastIntent()),
                                                                    "skippedByClosureRule",
                                                                            !closureDecision
                                                                                    .historyWritable()),
                                                            Map.of(
                                                                    "summaryVersion",
                                                                            result.summaryVersion(),
                                                                    "lastIntent",
                                                                            safe(result.lastIntent()),
                                                                    "lastNextStep",
                                                                            safe(result.lastNextStep()),
                                                                    "historySummary",
                                                                            trimPreview(
                                                                                    result.historySummary()))),
                                            unwrittenHistorySummary(
                                                    customerContext, historyWriteRequest));
                            IntentQueueSyncResult syncedQueue =
                                    runSoftStep(
                                            progressListener,
                                            "stage",
                                            "result_close.queue",
                                            "结果收口：同步任务板",
                                            () ->
                                                    syncIntentQueueTool.sync(
                                                            customerContext,
                                                            sessionSnapshot.sessionId(),
                                                            queueVersionDecision
                                                                    .effectiveQueueVersion(),
                                                            buildIntentQueueUpdates(
                                                                    customerContext,
                                                                    userMessage,
                                                                    reply,
                                                                    resolvedIntentCode)),
                                            result ->
                                                    toolDetail(
                                                            Map.of(
                                                                    "sessionId",
                                                                            safe(sessionSnapshot.sessionId()),
                                                                    "queueVersion",
                                                                            safe(
                                                                                    queueVersionDecision
                                                                                            .effectiveQueueVersion()),
                                                                    "updates",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "intentCode",
                                                                                            resolvedIntentCode,
                                                                                            "action",
                                                                                            inferQueueAction(
                                                                                                    history,
                                                                                                    resolvedIntentCode)))),
                                                            Map.of(
                                                                    "queueVersion",
                                                                            result.queueVersion(),
                                                                    "activeIntentCode",
                                                                            safe(
                                                                                    result
                                                                                            .activeIntentCode()),
                                                                    "activeIntentKey",
                                                                            safe(
                                                                                    result
                                                                                            .activeIntentKey()),
                                                                    "taskBoardSummary",
                                                                            trimPreview(
                                                                                    result
                                                                                            .taskBoardSummary()))),
                                            new IntentQueueSyncResult(
                                                    customerContext.normalizedConversationId(),
                                                    customerContext.normalizedChatUser(),
                                                    parseQueueVersion(
                                                            queueVersionDecision
                                                                    .effectiveQueueVersion()),
                                                    resolvedIntentCode,
                                                    resolvedIntentCode,
                                                    ""));
                            return new ResultCloseContext(persistedHistory, syncedQueue);
                        });
        HistorySummaryWriteResult persistedHistory = resultClose.persistedHistory();
        IntentQueueSyncResult syncedQueue = resultClose.syncedQueue();

        return new ChatResponse(
                reply,
                customerContext.normalizedConversationId(),
                customerContext.normalizedRobotConversationId(),
                customerContext.normalizedChatUser(),
                customerContext.normalizedRobotKey(),
                customerContext.normalizedConversationName(),
                customerContext.normalizedMessageId(),
                recoveryStageResult.replyToPrevious()
                        ? "resume-existing-intent"
                        : deterministicRecovery.recoveryMode(),
                resolvedIntentCode,
                persistedHistory.historySummary(),
                profile.summary(),
                String.valueOf(syncedQueue.queueVersion()),
                resolvedBlueprintSummary(agentBinding));
    }

    private boolean shouldEnterRecoveryConfirmStage(
            HistorySummarySnapshot history, IntentTaskSnapshot queue) {
        return history.recoveryPending() || queue.activeTasks() > 0;
    }

    private RecoveryStageResult executeRecoveryConfirmStage(
            ResolvedBlueprint blueprint,
            MultiStageExecutionContext executionContext,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        AgentBlueprint.StageSpec stage = requireStage(blueprint, "recovery_confirm");
        String rawReply =
                runLlmStage(
                        blueprint,
                        stage,
                        buildRecoveryStageAgentsMd(blueprint, stage, executionContext),
                        selectStageSkills(blueprint, stage),
                        executionContext.userMessage(),
                        runtimeContext,
                        progressListener,
                        agentEventListener);
        RecoveryStageResult parsed = parseRecoveryStageResult(rawReply);
        if (parsed.replyToPrevious() || !parsed.resumeIntentCode().isBlank() || !parsed.reason().isBlank()) {
            return parsed;
        }
        RecoveryDecision fallback = executionContext.deterministicRecovery();
        return new RecoveryStageResult(
                fallback.recoveryMessage(),
                safe(fallback.targetIntent()),
                "fallback-from-rule");
    }

    private IntentRouteStageResult executeIntentRouteStage(
            ResolvedBlueprint blueprint,
            TenantAgentBinding agentBinding,
            MultiStageExecutionContext executionContext,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        AgentBlueprint.StageSpec stage = requireStage(blueprint, "intent_route");
        String rawReply =
                runLlmStage(
                        blueprint,
                        stage,
                        buildIntentRouteAgentsMd(blueprint, stage, executionContext),
                        selectStageSkills(blueprint, stage),
                        executionContext.userMessage(),
                        runtimeContext,
                        progressListener,
                        agentEventListener);
        IntentRouteStageResult parsed = parseIntentRouteStageResult(rawReply);
        if (parsed.unresolved()) {
            // 模型什么都没判出来：这里不进规则，规则的语义是"给我候选我来排"，
            // 一个候选都没有时落哪个意图是编排策略（见 IntentPriorityRule 类注释）
            String fallbackIntent =
                    looksLikeDirectRecommendation(executionContext.userMessage())
                            ? "recommendation_consulting"
                            : "general_consultation";
            return new IntentRouteStageResult(
                    fallbackIntent,
                    fallbackIntent,
                    "fallback-from-heuristic",
                    parsed.confidence(),
                    parsed.priorityLabel(),
                    parsed.evidence(),
                    "",
                    parsed.handoffSignals());
        }
        return applyIntentPriorityRule(agentBinding, executionContext, parsed, progressListener);
    }

    /**
     * 用 {@code intent-priority} 对模型声明的主意图做确定性补正。
     *
     * <p>为什么补正必须在 Java 侧做一遍而不是信任模型：典型 case「用了你们水乳脸红了，还有别的推荐吗」
     * 里推荐意图和过敏投诉同时出现，模型很容易判成 {@code product_recommend}，但过敏必须优先——这属于
     * 每轮结论都相同的固定纪律，让模型每轮重新推理只是在给它机会判错。
     *
     * <p>蓝图没启用这条规则时原样返回，只在时间线上留一个 {@code skipped} 节点：既不改行为，
     * 又能让"我明明配了怎么没生效"在时间线上被看见。
     */
    private IntentRouteStageResult applyIntentPriorityRule(
            TenantAgentBinding agentBinding,
            MultiStageExecutionContext executionContext,
            IntentRouteStageResult parsed,
            ExecutionProgressListener progressListener) {
        if (!agentBinding.intentPriorityEnabled()) {
            runStep(
                    progressListener,
                    "orchestration",
                    "rule.intent.priority",
                    "意图优先级补正",
                    () -> parsed,
                    result ->
                            toolDetail(
                                    Map.of(
                                            "declaredIntentCode", safe(result.intentCode()),
                                            "ruleEnabled", false),
                                    Map.of(
                                            "topPriorityIntentCode", safe(result.intentCode()),
                                            "skipReason", "rule_not_enabled_by_blueprint")));
            return parsed;
        }

        IntentPriorityRule rule = intentPriorityRuleFor(agentBinding);
        IntentPriorityRule.Input input =
                new IntentPriorityRule.Input(
                        // 模型只给一个主意图，branch 与它不同时也算一个候选：分支和主意图分歧
                        // 本身就是"多个意图共现"的信号，交给优先级表排而不是默默取其一
                        List.of(safe(parsed.intentCode()), safe(parsed.branch())),
                        safe(parsed.intentCode()),
                        safe(parsed.confidence()),
                        safe(executionContext.userMessage()));
        IntentPriorityRule.Output output =
                runStep(
                        progressListener,
                        "orchestration",
                        "rule.intent.priority",
                        "意图优先级补正",
                        () -> rule.evaluate(input).output(),
                        result ->
                                toolDetail(
                                        Map.of(
                                                "declaredIntentCode", safe(parsed.intentCode()),
                                                "declaredBranch", safe(parsed.branch()),
                                                "confidence", safe(parsed.confidence()),
                                                "userMessage", trimPreview(executionContext.userMessage()),
                                                "keywordSource", agentBinding.intentKeywordSource(),
                                                "allergyUsageMarkers", rule.allergyUsageMarkers()),
                                        Map.of(
                                                "topPriorityIntentCode",
                                                        safe(result.topPriorityIntentCode()),
                                                "sortedIntentCodes", result.sortedIntentCodes(),
                                                "violationType", safe(result.violationType()),
                                                "priorityLabel", safe(result.priorityLabel()),
                                                "evidence", result.evidence())));

        String corrected = safe(output.topPriorityIntentCode());
        if (corrected.isEmpty()) {
            return parsed;
        }
        boolean changed = !corrected.equals(safe(parsed.intentCode()));
        return new IntentRouteStageResult(
                corrected,
                // 分支跟着主意图走：onResult 是按分支查的，主意图被补正而分支没跟上会
                // 让本轮走到与结论不符的下游阶段
                changed ? corrected : firstNonBlank(safe(parsed.branch()), corrected),
                changed ? "corrected-by-intent-priority" : safe(parsed.reason()),
                safe(parsed.confidence()),
                firstNonBlank(safe(output.priorityLabel()), safe(parsed.priorityLabel())),
                output.evidence().isEmpty() ? parsed.evidence() : output.evidence(),
                safe(output.violationType()),
                parsed.handoffSignals());
    }

    /**
     * 画像双闸：一个节点里把 {@code profile-completeness} 与 {@code follow-up-round-limit} 一起算完。
     *
     * <p>为什么合成一个节点而不是两个：两条规则吃的是同一对输入（画像快照 + 追问轮次），
     * 且结论只有合起来才可用——"画像还不够"要配上"但已经问到上限了"才知道这轮该继续追问还是
     * 带着残缺画像先给方案。分成两个节点会让时间线上必须来回对照两处才能复原这个判断。
     * 它们的<b>开关和阈值仍然分开</b>，蓝图可以只开一闸。
     *
     * <p>追问轮次没有独立的后端字段可读，用 {@code addMsgCount} 推出的轮次序号近似
     * （见 {@code buildMultiStageHistorySummaryWriteRequest} 里 {@code collectTurns} 的同一来源）。
     * 这是近似而不是真值，所以轮次一并写进时间线，好让"为什么这轮被强制放行了"可查。
     */
    private ProfileGateResult evaluateProfileGate(
            TenantAgentBinding agentBinding,
            MultiStageExecutionContext executionContext,
            ExecutionProgressListener progressListener) {
        int followUpRoundCount =
                parseTurnIndex(executionContext.customerContext().normalizedAddMsgCount());
        boolean completenessEnabled = agentBinding.profileCompletenessEnabled();
        boolean roundLimitEnabled = agentBinding.followUpRoundLimitEnabled();
        if (!completenessEnabled && !roundLimitEnabled) {
            ProfileGateResult skipped = ProfileGateResult.notEvaluated(followUpRoundCount);
            runStep(
                    progressListener,
                    "orchestration",
                    "rule.profile.gate",
                    "画像充分度与追问闸门",
                    () -> skipped,
                    result ->
                            toolDetail(
                                    Map.of(
                                            "followUpRoundCount", result.followUpRoundCount(),
                                            "profileCompletenessEnabled", false,
                                            "followUpRoundLimitEnabled", false),
                                    Map.of("skipReason", "rule_not_enabled_by_blueprint")));
            return skipped;
        }

        CustomerProfileSnapshot profile = executionContext.profile();
        ProfileCompletenessRule completenessRule = profileCompletenessRuleFor(agentBinding);
        FollowUpRoundLimitRule roundLimitRule = followUpRoundLimitRuleFor(agentBinding);
        return runStep(
                progressListener,
                "orchestration",
                "rule.profile.gate",
                "画像充分度与追问闸门",
                () -> {
                    boolean canRecommend = true;
                    List<String> missingFields = List.of();
                    if (completenessEnabled) {
                        ProfileCompletenessRule.Output output =
                                completenessRule
                                        .evaluate(
                                                new ProfileCompletenessRule.Input(
                                                        profile, followUpRoundCount))
                                        .output();
                        canRecommend = output.canRecommend();
                        missingFields = output.missingFields();
                    }
                    boolean shouldStopAsking = false;
                    if (roundLimitEnabled) {
                        shouldStopAsking =
                                roundLimitRule
                                        .evaluate(
                                                new FollowUpRoundLimitRule.Input(
                                                        followUpRoundCount, profile))
                                        .output()
                                        .shouldStopAsking();
                    }
                    return new ProfileGateResult(
                            completenessEnabled,
                            canRecommend,
                            missingFields,
                            roundLimitEnabled,
                            shouldStopAsking,
                            followUpRoundCount);
                },
                result -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("followUpRoundCount", followUpRoundCount);
                    input.put("profileCompletenessEnabled", completenessEnabled);
                    input.put("followUpRoundLimitEnabled", roundLimitEnabled);
                    input.put("thresholdSource", agentBinding.profileThresholdSource());
                    input.put("forcedRoundThreshold", completenessRule.forcedRoundThreshold());
                    input.put("maxFollowUpRounds", roundLimitRule.maxFollowUpRounds());
                    // 六个信号一起进 input：GetCustomerProfileTool 目前仍是占位实现、六个信号恒 false，
                    // 结论会一直是"画像不足"。把原始信号摊出来才能一眼分辨"画像真的缺"和"读画像的
                    // 工具还没接"，否则这个闸门看起来像是判错了
                    input.put("hasConcern", profile.hasConcern());
                    input.put("hasTargetBenefit", profile.hasTargetBenefit());
                    input.put("hasCoreNeed", profile.hasCoreNeed());
                    input.put("hasSkinType", profile.hasSkinType());
                    input.put("hasBudget", profile.hasBudget());
                    input.put("hasCategoryPreference", profile.hasCategoryPreference());
                    input.put("profileVersion", safe(profile.profileVersion()));
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("canRecommend", result.canRecommend());
                    output.put("missingFields", result.missingFields());
                    output.put("shouldStopAsking", result.shouldStopAsking());
                    return toolDetail(input, output);
                });
    }

    /**
     * 转人工硬触发：把"客户明确要求人工 / severe 过敏 / 高敏感医疗 / 情绪激烈或超范围"四个信号
     * 合并成一个确定性结论。
     *
     * <p>四个信号的来源刻意不同。{@code explicitHumanRequest} 由九码优先级表算出的
     * {@code intentCode == transfer_to_human} 推出——它已经是确定性结论，再问模型一遍只是给它一次
     * 和主意图自相矛盾的机会。另外三个必须靠语义识别（"这句话算不算情绪激烈"、"这是不是孕期用药
     * 咨询"），由意图识别阶段的结构化输出给出（见 {@code HandoffSignals}）。
     *
     * <p>放在业务处理之前算：结论要作为约束进业务处理提示词，业务处理阶段才能"直接消费结论"而不是
     * 自己再判一遍是否该交接（{@code guyu-business-process.md} 的「规则结论不重算」一节）。
     */
    private HandoffDecision evaluateHandoffTrigger(
            TenantAgentBinding agentBinding,
            String resolvedIntentCode,
            IntentRouteStageResult intentStageResult,
            ExecutionProgressListener progressListener) {
        boolean explicitHumanRequest = "transfer_to_human".equals(safe(resolvedIntentCode));
        HandoffSignals signals = intentStageResult.handoffSignals();
        if (!agentBinding.humanHandoffTriggerEnabled()) {
            HandoffDecision skipped = HandoffDecision.notEvaluated();
            runStep(
                    progressListener,
                    "orchestration",
                    "rule.handoff.trigger",
                    "转人工硬触发",
                    () -> skipped,
                    result ->
                            toolDetail(
                                    Map.of(
                                            "humanHandoffTriggerEnabled", false,
                                            "explicitHumanRequest", explicitHumanRequest,
                                            "severeAllergy", signals.severeAllergy(),
                                            "sensitiveMedicalContext",
                                                    signals.sensitiveMedicalContext(),
                                            "emotionalOrOutOfScope",
                                                    signals.emotionalOrOutOfScope()),
                                    Map.of("skipReason", "rule_not_enabled_by_blueprint")));
            return skipped;
        }

        return runStep(
                progressListener,
                "orchestration",
                "rule.handoff.trigger",
                "转人工硬触发",
                () -> {
                    HumanHandoffTriggerRule.Output output =
                            defaultHumanHandoffTriggerRule
                                    .evaluate(
                                            new HumanHandoffTriggerRule.Input(
                                                    explicitHumanRequest,
                                                    signals.severeAllergy(),
                                                    signals.sensitiveMedicalContext(),
                                                    signals.emotionalOrOutOfScope()))
                                    .output();
                    return new HandoffDecision(
                            true, output.shouldHandoff(), safe(output.triggerReason()));
                },
                result -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("humanHandoffTriggerEnabled", true);
                    // 四个信号都摊出来：规则是"首个命中即返回"，只看 triggerReason 说不清
                    // 是否还有别的信号也命中了，回溯"这轮为什么交接"时需要看全
                    input.put("explicitHumanRequest", explicitHumanRequest);
                    input.put("severeAllergy", signals.severeAllergy());
                    input.put("sensitiveMedicalContext", signals.sensitiveMedicalContext());
                    input.put("emotionalOrOutOfScope", signals.emotionalOrOutOfScope());
                    input.put("resolvedIntentCode", safe(resolvedIntentCode));
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("shouldHandoff", result.shouldHandoff());
                    output.put("triggerReason", result.triggerReason());
                    return toolDetail(input, output);
                });
    }

    /**
     * 收口必填项校验：摘要/意图/任务动作/版本号四个字段在两次写回之前先查一遍。
     *
     * <p>放在 {@code result_close.history} 与 {@code result_close.queue} <b>之前</b>，因为它要挡的正是
     * 这两次写回。{@code queueVersion} 取自乐观锁节点算出的 {@code effectiveQueueVersion} 而不是前置
     * 上下文那份，否则冲突时"检查的版本号"和"真正回传的版本号"不是同一个值，检查就白做了。
     *
     * <p><b>只有摘要缺失会真的拦住写回</b>，其余三项只留痕。理由是三者的后果不对等：
     * 空摘要写进去，下一轮读摘要的那次 LLM 调用就少了全部历史上下文，比"这轮不写、下一轮沿用上一份"
     * 严重得多；而 {@code queueVersion} 在首轮会话里本来就是空的（{@code GetIntentQueueTool} 还没拿到
     * 后端版本号），把它当成拦截条件等于让任务板永远没法建起来。{@code intentCode} 与
     * {@code taskStatus} 由确定性代码算出，理论上不会为空，真为空说明是代码缺陷，拦住写回也修不了它，
     * 所以同样只留痕等人看时间线。
     */
    private ClosureWritebackDecision evaluateClosureWriteback(
            TenantAgentBinding agentBinding,
            String summaryText,
            String intentCode,
            String taskStatus,
            String queueVersion,
            ExecutionProgressListener progressListener) {
        if (!agentBinding.closureWritebackRequiredFieldsEnabled()) {
            ClosureWritebackDecision skipped = ClosureWritebackDecision.notEvaluated();
            runStep(
                    progressListener,
                    "orchestration",
                    "rule.closure.required_fields",
                    "收口必填项校验",
                    () -> skipped,
                    result ->
                            toolDetail(
                                    Map.of(
                                            "closureWritebackRequiredFieldsEnabled", false,
                                            "summaryTextLength", safe(summaryText).length(),
                                            "intentCode", safe(intentCode),
                                            "taskStatus", safe(taskStatus),
                                            "queueVersion", safe(queueVersion)),
                                    Map.of(
                                            "skipReason", "rule_not_enabled_by_blueprint",
                                            "historyWritable", result.historyWritable())));
            return skipped;
        }

        return runStep(
                progressListener,
                "orchestration",
                "rule.closure.required_fields",
                "收口必填项校验",
                () -> {
                    ClosureWritebackRequiredFieldsRule.Output output =
                            defaultClosureWritebackRequiredFieldsRule
                                    .evaluate(
                                            new ClosureWritebackRequiredFieldsRule.Input(
                                                    summaryText, intentCode, taskStatus, queueVersion))
                                    .output();
                    return new ClosureWritebackDecision(
                            true, output.isComplete(), output.missingFields());
                },
                result -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("closureWritebackRequiredFieldsEnabled", true);
                    // 摘要正文可能很长，时间线上只需要"是不是空的"，所以给长度不给原文
                    input.put("summaryTextLength", safe(summaryText).length());
                    input.put("intentCode", safe(intentCode));
                    input.put("taskStatus", safe(taskStatus));
                    input.put("queueVersion", safe(queueVersion));
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("isComplete", result.complete());
                    output.put("missingFields", result.missingFields());
                    // 缺哪个字段决定拦不拦，所以结论要单独摊出来，别让人自己去对照 missingFields
                    output.put("historyWritable", result.historyWritable());
                    return toolDetail(input, output);
                });
    }

    /**
     * 任务板乐观锁校验：写回之前再读一次远端 {@code queueVersion}，和前置上下文读到的那份比对。
     *
     * <p>为什么要多一次读：{@code preload_context.queue} 到 {@code result_close.queue} 之间隔着
     * 整条 LLM 链，几秒到几十秒。场景卡片8（多意图并发）下另一路会话可能已经改过任务板，
     * 拿着过期版本号硬写就会覆盖别人挂起的任务——11 号文档把它登记成强制约束而不是可选项。
     * 这次读的代价换的是"不静默覆盖"，而且规则可以按租户关掉（不接任务板的租户不必付这次读）。
     *
     * <p>检测到冲突时按规则注释的要求<b>改用远端最新版本号</b>写回，而不是拿旧版本号强行覆盖。
     * 这是本节点唯一会改变后续行为的地方，所以冲突与否、用了哪个版本号都写进时间线。
     *
     * <p>重读失败时 {@code GetIntentQueueTool} 会回落成 {@code bootstrap-<conversationId>} 的空队列。
     * 那不是"远端版本变了"而是"没读到"，不能当冲突处理——否则一次网络抖动就会把写回的版本号
     * 换成一个根本不存在于后端的值。
     */
    private QueueVersionDecision evaluateQueueVersionGuard(
            TenantAgentBinding agentBinding,
            CustomerContext customerContext,
            String localQueueVersion,
            ExecutionProgressListener progressListener) {
        String local = safe(localQueueVersion);
        if (!agentBinding.queueVersionGuardEnabled()) {
            QueueVersionDecision skipped = QueueVersionDecision.notEvaluated(local);
            runStep(
                    progressListener,
                    "orchestration",
                    "rule.queue.version",
                    "任务板乐观锁校验",
                    () -> skipped,
                    result ->
                            toolDetail(
                                    Map.of(
                                            "queueVersionGuardEnabled", false,
                                            "localQueueVersion", local),
                                    Map.of(
                                            "skipReason", "rule_not_enabled_by_blueprint",
                                            "effectiveQueueVersion",
                                                    result.effectiveQueueVersion())));
            return skipped;
        }

        return runStep(
                progressListener,
                "orchestration",
                "rule.queue.version",
                "任务板乐观锁校验",
                () -> {
                    String remote = safe(getIntentQueueTool.load(customerContext).queueVersion());
                    if (remote.isBlank()
                            || (remote.startsWith("bootstrap-") && !remote.equals(local))) {
                        return QueueVersionDecision.remoteUnreadable(local, remote);
                    }
                    boolean conflict =
                            defaultQueueVersionGuardRule
                                    .evaluate(new QueueVersionGuardRule.Input(local, remote))
                                    .output()
                                    .isConflict();
                    return new QueueVersionDecision(
                            true, conflict, local, remote, conflict ? remote : local);
                },
                result -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("queueVersionGuardEnabled", true);
                    input.put("localQueueVersion", result.localQueueVersion());
                    input.put("remoteQueueVersion", result.remoteQueueVersion());
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("conflict", result.conflict());
                    output.put("effectiveQueueVersion", result.effectiveQueueVersion());
                    output.put("remoteReadable", result.remoteReadable());
                    return toolDetail(input, output);
                });
    }

    private BusinessProcessStageResult executeBusinessProcessStage(
            ResolvedBlueprint blueprint,
            MultiStageExecutionContext executionContext,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener,
            String resolvedIntentCode,
            RecoveryStageResult recoveryStageResult,
            IntentRouteStageResult intentStageResult,
            ProfileGateResult profileGateResult,
            HandoffDecision handoffDecision) {
        AgentBlueprint.StageSpec stage = requireStage(blueprint, "business_process");
        String rawReply =
                runLlmStage(
                        blueprint,
                        stage,
                        buildBusinessProcessAgentsMd(
                                blueprint,
                                stage,
                                executionContext,
                                resolvedIntentCode,
                                recoveryStageResult,
                                intentStageResult,
                                profileGateResult,
                                handoffDecision),
                        selectStageSkills(blueprint, stage),
                        executionContext.userMessage(),
                        runtimeContext,
                        progressListener,
                        agentEventListener);
        BusinessProcessStageResult parsed = parseBusinessProcessStageResult(rawReply, resolvedIntentCode);
        if (!parsed.reply().isBlank()) {
            return parsed;
        }
        return new BusinessProcessStageResult(
                trimPreview(rawReply), "", resolvedIntentCode, "继续跟进当前客户诉求");
    }

    private String runLlmStage(
            ResolvedBlueprint blueprint,
            AgentBlueprint.StageSpec stage,
            String stageAgentsMd,
            List<AgentSkill> stageSkills,
            String userMessage,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        return runStageGroup(
                progressListener,
                stage.stageKey(),
                stage.displayName(),
                safe(stage.stageType()),
                () -> {
                    runStep(
                            progressListener,
                            "stage",
                            stage.stageKey() + ".project",
                            stage.displayName() + "：投影阶段提示词",
                            () ->
                                    workspaceProjector.project(
                                            runtimeContext.getUserId(),
                                            new TenantWorkspaceProjector.Content(
                                                    stageAgentsMd,
                                                    blueprint.soulMd(),
                                                    stageSkills,
                                                    blueprint.blueprintId() + ":" + stage.stageKey(),
                                                    blueprint.version())),
                            projection ->
                                    workspaceProjectDetail(
                                            runtimeContext.getUserId(), projection));
                    Msg userMsg =
                            runStep(
                                    progressListener,
                                    "stage",
                                    stage.stageKey() + ".prompt_compose",
                                    stage.displayName() + "：组装输入",
                                    () -> buildUserMsg(userMessage),
                                    // 同单阶段的 orchestration:prompt.compose：必须产出 input / output 键，
                                    // 否则前端两栏是空的。多阶段这一步不注入 RecoveryPromptContext
                                    // （阶段提示词走 stageAgentsMd），所以输入只记原话与阶段身份。
                                    composed ->
                                            ioDetail(
                                                    Map.of(
                                                            "stageKey", safe(stage.stageKey()),
                                                            "userMessage", safe(userMessage)),
                                                    promptComposeOutput(composed),
                                                    Map.of(
                                                            "stageKey", safe(stage.stageKey()),
                                                            "userMessageLength",
                                                                    safe(userMessage).length())));
                    return runAgentWithTimeline(
                            sharedAgent,
                            userMsg,
                            runtimeContext,
                            progressListener,
                            agentEventListener,
                            "stage." + safe(stage.stageKey()) + ".run",
                            stage.displayName());
                });
    }

    private AgentBlueprint.StageSpec requireStage(ResolvedBlueprint blueprint, String stageKey) {
        return blueprint.blueprint().stages().stream()
                .filter(stage -> stage != null)
                .filter(stage -> stageKey.equals(safe(stage.stageKey())))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "multi_stage blueprint "
                                                + blueprint.blueprintId()
                                                + " is missing stage '"
                                                + stageKey
                                                + "'"));
    }

    private List<AgentSkill> selectStageSkills(
            ResolvedBlueprint blueprint, AgentBlueprint.StageSpec stage) {
        if (stage.skills().isEmpty()) {
            return List.of();
        }
        return blueprint.skills().skills().stream()
                .filter(skill -> stage.skills().contains(skill.getName()))
                .toList();
    }

    private String buildRecoveryStageAgentsMd(
            ResolvedBlueprint blueprint,
            AgentBlueprint.StageSpec stage,
            MultiStageExecutionContext executionContext) {
        return composeStageAgentsMd(
                blueprint.agentsMd(),
                stage,
                resolveStagePrompt(stage),
                List.of(
                        "当前用户消息：" + safe(executionContext.userMessage()),
                        "历史摘要：" + safe(executionContext.history().summaryText()),
                        "恢复待处理：" + executionContext.history().recoveryPending(),
                        "当前活跃意图：" + safe(executionContext.history().activeIntentCode()),
                        "任务板摘要：总任务="
                                + executionContext.queue().totalTasks()
                                + "，进行中="
                                + executionContext.queue().activeTasks()
                                + "，挂起="
                                + executionContext.queue().suspendedTasks()));
    }

    private String buildIntentRouteAgentsMd(
            ResolvedBlueprint blueprint,
            AgentBlueprint.StageSpec stage,
            MultiStageExecutionContext executionContext) {
        return composeStageAgentsMd(
                blueprint.agentsMd(),
                stage,
                resolveStagePrompt(stage),
                List.of(
                        "当前用户消息：" + safe(executionContext.userMessage()),
                        "历史摘要：" + safe(executionContext.history().summaryText()),
                        "画像摘要：" + safe(executionContext.profile().summary()),
                        "任务板摘要：总任务="
                                + executionContext.queue().totalTasks()
                                + "，进行中="
                                + executionContext.queue().activeTasks()
                                + "，挂起="
                                + executionContext.queue().suspendedTasks()));
    }

    private String buildBusinessProcessAgentsMd(
            ResolvedBlueprint blueprint,
            AgentBlueprint.StageSpec stage,
            MultiStageExecutionContext executionContext,
            String resolvedIntentCode,
            RecoveryStageResult recoveryStageResult,
            IntentRouteStageResult intentStageResult,
            ProfileGateResult profileGateResult,
            HandoffDecision handoffDecision) {
        return composeStageAgentsMd(
                blueprint.agentsMd(),
                stage,
                resolveStagePrompt(stage),
                List.of(
                        "当前用户消息：" + safe(executionContext.userMessage()),
                        "业务意图：" + safe(resolvedIntentCode),
                        "恢复确认结果："
                                + (recoveryStageResult.replyToPrevious() ? "续接上一轮" : "未续接或未执行"),
                        "意图识别结果："
                                + firstNonBlank(
                                        safe(intentStageResult.intentCode()), "未执行或由恢复链直达业务处理"),
                        // 优先级分档与命中证据要进提示词：确定性规则算出"这是高优先级过敏投诉"之后，
                        // 业务处理阶段必须知道，否则模型会按自己那套语气继续当成普通咨询答
                        "意图优先级：" + firstNonBlank(safe(intentStageResult.priorityLabel()), "未判定"),
                        intentStageResult.evidence().isEmpty()
                                ? ""
                                : "命中关键词：" + String.join("、", intentStageResult.evidence()),
                        intentStageResult.violationType().isBlank()
                                ? ""
                                : "确定性规则已修正模型意图判断（" + safe(intentStageResult.violationType()) + "），以上意图为最终结论",
                        "画像摘要：" + safe(executionContext.profile().summary()),
                        // 画像双闸的结论必须写成"能不能推荐 / 该不该继续追问"这样的指令，而不是只报
                        // canRecommend=false：模型看到布尔值不会自己推出"那就先追问缺的那组信号"
                        profileGateResult.completenessEvaluated()
                                ? (profileGateResult.canRecommend()
                                        ? "画像充分度：已充分，可以给具体推荐"
                                        : "画像充分度：还不充分，缺失信号组＝"
                                                + String.join("、", profileGateResult.missingFields())
                                                + "，优先补齐后再推荐")
                                : "",
                        profileGateResult.roundLimitEvaluated()
                                ? (profileGateResult.shouldStopAsking()
                                        ? "追问闸门：已达上限（当前第 "
                                                + profileGateResult.followUpRoundCount()
                                                + " 轮），本轮不要再追问，直接基于已知信息给方案"
                                        : "追问闸门：未达上限（当前第 "
                                                + profileGateResult.followUpRoundCount()
                                                + " 轮），可以继续追问，但一轮最多问一个问题")
                                : "",
                        // 交接结论也写成指令：既要让模型知道"这轮要交接"，也要挡住它把这件事
                        // 说给客户听——human-handoff 技能的纪律是可以真交接、但对客文本里不能
                        // 出现「转人工」三个字
                        handoffDecision.evaluated()
                                ? (handoffDecision.shouldHandoff()
                                        ? "人工接手：本轮需要交接（触发原因＝"
                                                + describeHandoffReason(handoffDecision.triggerReason())
                                                + "）。请按 human-handoff 技能整理内部工单，"
                                                + "对客文本只做安抚与承接，不要出现「转人工」「人工客服」这类字样"
                                        : "人工接手：本轮不需要交接，正常按业务分支回复")
                                : "",
                        "规则上下文：" + safe(executionContext.ruleContext().promptText()),
                        "允许推荐 productId："
                                + String.join(", ", executionContext.ruleContext().allowedProductIds())));
    }

    /**
     * 把 {@code triggerReason} 原因码翻成中文写进提示词。
     *
     * <p>原因码本身（{@code severe_allergy} 之类）是给时间线和写回字段用的机器标识，直接塞进提示词
     * 会让模型自己去猜含义；不同原因对应的对客口径差别很大（过敏要先安抚停用、情绪激烈要先共情），
     * 所以这里给出中文语义而不是原样透传。
     */
    private static String describeHandoffReason(String triggerReason) {
        return switch (safe(triggerReason)) {
            case "explicit_human_request" -> "客户明确要求人工";
            case "severe_allergy" -> "严重过敏反应";
            case "sensitive_medical_context" -> "高敏感医疗场景";
            case "emotional_or_out_of_scope" -> "情绪激烈或超出服务范围";
            case "" -> "未命中";
            default -> triggerReason;
        };
    }

    private String resolveStagePrompt(AgentBlueprint.StageSpec stage) {        String inlinePrompt = safe(stage.prompt());
        if (!inlinePrompt.isBlank()) {
            return inlinePrompt;
        }
        return multiStagePromptAssets.loadRequired(stage.promptRef());
    }

    private String composeStageAgentsMd(
            String baseAgentsMd,
            AgentBlueprint.StageSpec stage,
            String stagePrompt,
            List<String> contextLines) {
        StringBuilder builder = new StringBuilder();
        if (baseAgentsMd != null && !baseAgentsMd.isBlank()) {
            builder.append(baseAgentsMd.trim());
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("## 当前阶段\n")
                .append("- stageKey: ")
                .append(safe(stage.stageKey()))
                .append('\n')
                .append("- 阶段名称: ")
                .append(safe(stage.displayName()))
                .append('\n');
        if (stage.description() != null && !stage.description().isBlank()) {
            builder.append("- 阶段说明: ").append(stage.description().trim()).append('\n');
        }
        builder.append("\n").append(stagePrompt.trim()).append("\n\n## 阶段上下文\n");
        for (String line : contextLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            builder.append("- ").append(line.trim()).append('\n');
        }
        return builder.toString().trim();
    }

    private RecoveryStageResult parseRecoveryStageResult(String rawReply) {
        JsonNode json = parseStageJson(rawReply);
        String decision = safe(json.path("decision").asText(""));
        return new RecoveryStageResult(
                "reply_to_previous".equals(decision),
                safe(json.path("resumeIntentCode").asText("")),
                safe(json.path("reason").asText("")));
    }

    private IntentRouteStageResult parseIntentRouteStageResult(String rawReply) {
        JsonNode json = parseStageJson(rawReply);
        return new IntentRouteStageResult(
                safe(json.path("intentCode").asText("")),
                safe(json.path("branch").asText("")),
                safe(json.path("reason").asText("")),
                safe(json.path("confidence").asText("")),
                safe(json.path("priorityLabel").asText("")),
                readTextArray(json.path("evidence")),
                "",
                // 三个语义信号缺省即 false：模型漏输出时宁可不触发转人工，也不要凭一个
                // 读不到的字段把客户丢给人工队列
                new HandoffSignals(
                        json.path("severeAllergy").asBoolean(false),
                        json.path("sensitiveMedicalContext").asBoolean(false),
                        json.path("emotionalOrOutOfScope").asBoolean(false)));
    }

    /** 读 JSON 字符串数组；不是数组或元素为空一律跳过，不让脏值把下游断言搅乱。 */
    private static List<String> readTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = safe(item.asText(""));
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private BusinessProcessStageResult parseBusinessProcessStageResult(
            String rawReply, String fallbackIntentCode) {
        JsonNode json = parseStageJson(rawReply);
        return new BusinessProcessStageResult(
                safe(json.path("reply").asText("")),
                safe(json.path("historySummary").asText("")),
                firstNonBlank(safe(json.path("intentCode").asText("")), fallbackIntentCode),
                safe(json.path("nextStep").asText("")));
    }

    static SingleStageAgentOutput parseSingleStageAgentOutput(
            String rawReply, String fallbackIntentCode) {
        JsonNode json = parseLooseJson(rawReply);
        boolean explicitHumanHandoff = json.path("needHumanHandoff").asBoolean(false);
        String intentCode =
                normalizeSingleStageIntentCode(
                        safe(json.path("intentCode").asText("")),
                        fallbackIntentCode,
                        explicitHumanHandoff);
        String reply = firstNonBlank(safe(json.path("reply").asText("")), safe(rawReply));
        boolean needHumanHandoff = explicitHumanHandoff || "transfer_to_human".equals(intentCode);
        return new SingleStageAgentOutput(reply, intentCode, needHumanHandoff);
    }

    private JsonNode parseStageJson(String rawReply) {
        String normalized = safe(rawReply);
        if (normalized.startsWith("```")) {
            int start = normalized.indexOf('\n');
            int end = normalized.lastIndexOf("```");
            if (start >= 0 && end > start) {
                normalized = normalized.substring(start + 1, end).trim();
            }
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static JsonNode parseLooseJson(String rawReply) {
        String normalized = safe(rawReply);
        if (normalized.startsWith("```")) {
            int start = normalized.indexOf('\n');
            int end = normalized.lastIndexOf("```");
            if (start >= 0 && end > start) {
                normalized = normalized.substring(start + 1, end).trim();
            }
        }
        try {
            return new ObjectMapper().readTree(normalized);
        } catch (Exception exception) {
            return new ObjectMapper().createObjectNode();
        }
    }

    private static String normalizeSingleStageIntentCode(
            String rawIntentCode, String fallbackIntentCode, boolean explicitHumanHandoff) {
        String normalized = safe(rawIntentCode).trim();
        if ("recommendation_consulting".equals(normalized)) {
            return "product_recommend";
        }
        if (explicitHumanHandoff && normalized.isBlank()) {
            return "transfer_to_human";
        }
        if (SINGLE_STAGE_INTENT_CODES.contains(normalized)) {
            return normalized;
        }
        return safe(fallbackIntentCode).isBlank() ? "general_consultation" : fallbackIntentCode;
    }

    private static HistorySummaryWriteRequest buildMultiStageHistorySummaryWriteRequest(
            String userMessage,
            String reply,
            BusinessProcessStageResult businessStageResult,
            RecoveryDecision recovery,
            HistorySummarySnapshot history,
            String resolvedIntentCode,
            CustomerContext customerContext,
            boolean needHumanHandoff) {
        if (businessStageResult.historySummary().isBlank()) {
            return buildHistorySummaryWriteRequest(
                    userMessage,
                    reply,
                    recovery,
                    history,
                    resolvedIntentCode,
                    customerContext,
                    needHumanHandoff);
        }
        String nextStep =
                firstNonBlank(
                        safe(businessStageResult.nextStep()),
                        safe(recovery.targetIntent()).isBlank()
                                ? "继续跟进当前客户诉求并等待下一轮消息"
                                : "继续跟进意图 " + resolvedIntentCode);
        return new HistorySummaryWriteRequest(
                businessStageResult.historySummary(),
                safe(userMessage),
                safe(reply),
                resolvedIntentCode,
                "sales_customer_agent_multistage",
                nextStep,
                "chat_workbench",
                "assistant_replied",
                needHumanHandoff,
                parseTurnIndex(customerContext.normalizedAddMsgCount()) + 1,
                compactQuestionFocus(userMessage),
                looksLikeDirectRecommendation(userMessage),
                !safe(userMessage).isBlank(),
                false,
                safe(recovery.targetIntent()),
                safe(history.activeIntentCode()),
                safe(recovery.recoveryMode()));
    }

    private TenantWorkspaceProjector.Content projectionContentFor(TenantAgentBinding binding) {
        ResolvedBlueprint blueprint = binding.blueprint();
        if (blueprint == null) {
            return new TenantWorkspaceProjector.Content(
                    fallbackPrompt.agentsMd(),
                    fallbackPrompt.soulMd(),
                    fallbackSkills,
                    "fallback:prompt-assets",
                    0);
        }
        return new TenantWorkspaceProjector.Content(
                blueprint.agentsMd(),
                blueprint.soulMd(),
                blueprint.skills().skills(),
                blueprint.blueprintId(),
                blueprint.version());
    }

    /**
     * 取本轮该用的恢复判断服务。
     *
     * <p>蓝图投影出了 {@code recovery-detection} 的词表就用租户版，否则复用构造期那个默认实例——
     * 不要为"没覆盖参数"的租户也新建一个和默认完全一样的对象。
     */
    private RecoveryHandlingService recoveryServiceFor(TenantAgentBinding binding) {
        return binding.blueprintRecoveryRule()
                .map(RecoveryHandlingService::new)
                .orElse(recoveryHandlingService);
    }

    /** 取本轮该用的意图优先级规则，语义同 {@link #recoveryServiceFor}。 */
    private IntentPriorityRule intentPriorityRuleFor(TenantAgentBinding binding) {
        return binding.blueprintIntentPriorityRule().orElse(defaultIntentPriorityRule);
    }

    /** 取本轮该用的画像充分度规则，语义同 {@link #recoveryServiceFor}。 */
    private ProfileCompletenessRule profileCompletenessRuleFor(TenantAgentBinding binding) {
        return binding.blueprintProfileCompletenessRule().orElse(defaultProfileCompletenessRule);
    }

    /** 取本轮该用的追问轮次上限规则，语义同 {@link #recoveryServiceFor}。 */
    private FollowUpRoundLimitRule followUpRoundLimitRuleFor(TenantAgentBinding binding) {
        return binding.blueprintFollowUpRoundLimitRule().orElse(defaultFollowUpRoundLimitRule);
    }

    private static ChatResponse.ResolvedBlueprintSummary resolvedBlueprintSummary(
            TenantAgentBinding binding) {
        if (binding == null || binding.blueprint() == null) {
            return new ChatResponse.ResolvedBlueprintSummary(
                    binding == null ? BlueprintSelection.MODE_SCOPED : binding.selection().selectorModeOrDefault(),
                    binding != null && binding.selection().isPinned()
                            ? safe(binding.selection().selectionId())
                            : "",
                    "",
                    "",
                    0,
                    "",
                    "fallback");
        }
        ResolvedBlueprint blueprint = binding.blueprint();
        return new ChatResponse.ResolvedBlueprintSummary(
                binding.selection().selectorModeOrDefault(),
                firstNonBlank(blueprint.selectionId(), binding.selection().selectionId()),
                blueprint.blueprintId(),
                blueprint.blueprint().runtimeModeOrDefault(),
                blueprint.blueprint().stages().size(),
                blueprint.sourceType(),
                blueprint.matchLevel());
    }

    private List<Map<String, String>> filterTenantScopes(
            List<Map<String, String>> scopes, String clientCode, String cluster) {
        if (safe(clientCode).isEmpty()) {
            return List.of();
        }
        return scopes.stream()
                .filter(scope -> clientCode.equals(safe(scope.get("clientCode"))))
                .filter(
                        scope -> {
                            String scopeCluster = safe(scope.get("cluster"));
                            return safe(cluster).isEmpty()
                                    || scopeCluster.isEmpty()
                                    || scopeCluster.equals(safe(cluster));
                        })
                .toList();
    }

    private List<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> filterTemplatesForTenant(
            List<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> templates,
            List<Map<String, String>> tenantScopes,
            String requestedRuntimeAgentId) {
        LinkedHashSet<String> allowedRuntimeAgentIds = new LinkedHashSet<>();
        for (Map<String, String> scope : tenantScopes) {
            String runtimeAgentId = safe(scope.get("runtimeAgentId"));
            if (!runtimeAgentId.isEmpty()) {
                allowedRuntimeAgentIds.add(runtimeAgentId);
            }
        }
        if (!safe(requestedRuntimeAgentId).isEmpty()) {
            allowedRuntimeAgentIds.add(safe(requestedRuntimeAgentId));
        }
        if (allowedRuntimeAgentIds.isEmpty()) {
            return List.of();
        }
        return templates.stream()
                .filter(template -> allowedRuntimeAgentIds.contains(safe(template.runtimeAgentId())))
                .toList();
    }

    private Optional<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> findTemplate(
            List<RuntimeAgentTemplateCatalog.RuntimeAgentTemplate> templates, String runtimeAgentId) {
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        if (normalizedRuntimeAgentId.isEmpty()) {
            return Optional.empty();
        }
        return templates.stream()
                .filter(template -> normalizedRuntimeAgentId.equals(safe(template.runtimeAgentId())))
                .findFirst();
    }

    private Map<String, List<String>> collectVersionsByRuntimeAgent(List<Map<String, String>> tenantScopes) {
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (Map<String, String> scope : tenantScopes) {
            String runtimeAgentId = safe(scope.get("runtimeAgentId"));
            String version = safe(scope.get("version"));
            if (runtimeAgentId.isEmpty() || version.isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(runtimeAgentId, ignored -> new LinkedHashSet<>()).add(version);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    /**
     * 本轮命中的蓝图及请求作用域。
     *
     * @param blueprint 命中的蓝图；{@code null} 表示走了兜底提示词
     */
    private record TenantAgentBinding(
            ResolvedBlueprint blueprint,
            String requestedClientCode,
            String requestedCluster,
            String requestedRuntimeAgentId,
            String requestedVersion,
            BlueprintSelection selection) {

        boolean isMultiStage() {
            return blueprint != null && blueprint.blueprint().isMultiStage();
        }

        /** 蓝图投影出的恢复判断规则；{@code empty} 表示本轮用 Java 侧默认词表。 */
        Optional<RecoveryDetectionRule> blueprintRecoveryRule() {
            return blueprint == null
                    ? Optional.empty()
                    : blueprint.rules().recoveryDetectionRule();
        }

        /** 时间线上要能一眼看出这轮的续接词表到底来自谁，否则"配了没生效"看不出来。 */
        String recoveryKeywordSource() {
            return blueprintRecoveryRule().isPresent() ? "blueprint" : "java-default";
        }

        /** 蓝图投影出的意图优先级规则；{@code empty} 表示本轮用 Java 侧默认词表。 */
        Optional<IntentPriorityRule> blueprintIntentPriorityRule() {
            return blueprint == null ? Optional.empty() : blueprint.rules().intentPriorityRule();
        }

        /** 本轮是否真的启用了 {@code intent-priority}：蓝图没声明它就不该做确定性补正。 */
        boolean intentPriorityEnabled() {
            return blueprint != null
                    && blueprint.rules().effectiveRuleCodes().contains("intent-priority");
        }

        /** 意图关键词表来源，语义同 {@link #recoveryKeywordSource()}。 */
        String intentKeywordSource() {
            return blueprintIntentPriorityRule().isPresent() ? "blueprint" : "java-default";
        }

        /** 蓝图投影出的画像充分度规则；{@code empty} 表示本轮用 Java 侧默认阈值。 */
        Optional<ProfileCompletenessRule> blueprintProfileCompletenessRule() {
            return blueprint == null ? Optional.empty() : blueprint.rules().profileCompletenessRule();
        }

        /** 蓝图投影出的追问轮次上限规则；{@code empty} 表示本轮用 Java 侧默认阈值。 */
        Optional<FollowUpRoundLimitRule> blueprintFollowUpRoundLimitRule() {
            return blueprint == null ? Optional.empty() : blueprint.rules().followUpRoundLimitRule();
        }

        /**
         * 本轮是否启用了画像充分度判断。
         *
         * <p>和追问轮次上限分开判断：蓝图完全可以只开"能不能推荐"而不开"该不该继续追问"，
         * 把两者绑成一个开关会让这种配置表达不出来。
         */
        boolean profileCompletenessEnabled() {
            return blueprint != null
                    && blueprint.rules().effectiveRuleCodes().contains("profile-completeness");
        }

        boolean followUpRoundLimitEnabled() {
            return blueprint != null
                    && blueprint.rules().effectiveRuleCodes().contains("follow-up-round-limit");
        }

        /** 画像双闸阈值来源，语义同 {@link #recoveryKeywordSource()}。 */
        String profileThresholdSource() {
            return blueprintProfileCompletenessRule().isPresent()
                            || blueprintFollowUpRoundLimitRule().isPresent()
                    ? "blueprint"
                    : "java-default";
        }

        /**
         * 本轮是否启用了转人工硬触发判断。
         *
         * <p>没有对应的 {@code blueprintXxxRule()}：这条规则不接受任何参数覆盖，
         * 四个触发条件都是安全阀而不是租户偏好，所以只有"开没开"这一个维度。
         */
        boolean humanHandoffTriggerEnabled() {
            return blueprint != null
                    && blueprint.rules().effectiveRuleCodes().contains("human-handoff-trigger");
        }

        /** 本轮是否启用了任务板乐观锁校验；同样没有可覆盖参数，只有开关。 */
        boolean queueVersionGuardEnabled() {
            return blueprint != null
                    && blueprint.rules().effectiveRuleCodes().contains("queue-version-guard");
        }

        /** 本轮是否启用了收口必填项校验；同样没有可覆盖参数，只有开关。 */
        boolean closureWritebackRequiredFieldsEnabled() {
            return blueprint != null
                    && blueprint
                            .rules()
                            .effectiveRuleCodes()
                            .contains("closure-writeback-required-fields");
        }

        /**
         * 时间线明细。键顺序刻意让 {@code matchLevel} / {@code blueprintId} / {@code rules} 靠前
         * ——工作台"补充明细"只渲染前 8 个键，排在后面等于看不见（见
         * {@link ResolvedBlueprint#toTimelineDetail()}）。所以这里把 {@code source} 之外的本地字段
         * 放到蓝图字段之后，而不是插在最前面。
         */
        Map<String, Object> toTimelineDetail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (blueprint == null) {
                detail.put("source", "fallback");
                detail.put("clientCode", requestedClientCode);
                detail.put("cluster", requestedCluster);
                detail.put("requestedRuntimeAgentId", requestedRuntimeAgentId);
                detail.put("requestedVersion", requestedVersion);
                detail.put("selectorMode", selection.selectorModeOrDefault());
                detail.put("selectionId", selection.selectionId());
                detail.put("agentName", SalesAgentFactory.DEFAULT_AGENT_NAME);
                detail.put("reason", "no blueprint registered for this tenant scope");
                return java.util.Collections.unmodifiableMap(detail);
            }
            detail.putAll(blueprint.toTimelineDetail());
            detail.put("source", "blueprint");
            detail.put("selectorMode", selection.selectorModeOrDefault());
            detail.put("agentName", SalesAgentFactory.DEFAULT_AGENT_NAME);
            return java.util.Collections.unmodifiableMap(detail);
        }
    }

    /** 用户消息只保留客户原话；系统侧判断与预取快照统一通过 RuntimeContext + Middleware 注入。 */
    private static Msg buildUserMsg(String userMessage) {
        return Msg.builderForRole(MsgRole.USER).textContent(safe(userMessage)).build();
    }

    /**
     * {@code prompt.compose} 的「输入」面板内容。
     *
     * <p>这一步真正的输入不只是客户原话，还包括即将被塞进 {@code RecoveryPromptContext} 的四份快照
     * —— 它们决定了 {@code RecoveryPromptContextMiddleware} 在 {@code onSystemPrompt} 阶段会往系统
     * 提示词里追加什么。只记 {@code userMessageLength} 的话，"注入了什么"依然要靠翻上游节点拼，
     * 而这个节点存在的意义就是把这件事一次说清。
     */
    /**
     * {@code agent.run} 节点的输入 / 输出。
     *
     * <p>用 {@code messages} 数组的形状是为了和 LLM 子节点走同一套前端渲染路径；数组不能为空，
     * 否则前端会回退去 JSON.stringify 整个 bundle，面板上变成一行 {@code {"messages": []}}。
     * 先前这个节点只有 {@code replyLength} / {@code eventCount} 两个平铺字段——知道回复有多长，
     * 却看不到回复本身，是这轮里最反直觉的一处。
     */
    private static Map<String, Object> agentRunInput(Msg userMsg, RuntimeContext runtimeContext) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("text", userMsg == null ? "" : safe(userMsg.getTextContent()));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", List.of(java.util.Collections.unmodifiableMap(message)));
        if (runtimeContext != null) {
            input.put("sessionId", safe(runtimeContext.getSessionId()));
            input.put("userId", safe(runtimeContext.getUserId()));
        }
        return java.util.Collections.unmodifiableMap(input);
    }

    static Map<String, Object> agentRunOutput(String reply, int eventCount) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("text", safe(reply));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("messages", List.of(java.util.Collections.unmodifiableMap(message)));
        output.put("eventCount", eventCount);
        return java.util.Collections.unmodifiableMap(output);
    }

    /**
     * {@code workspace.project} 节点明细：把"投影自哪份内容"和"落盘出了什么"拆到输入 / 输出两栏。
     *
     * <p>{@code Projection} 本身既有来源标识（sourceId / sourceVersion）也有落盘结果
     * （relativePath / agentsMdBytes / skills），先前整份直接当平铺字段，前端两栏都是"无"。
     */
    private static Map<String, Object> workspaceProjectDetail(
            String userId, TenantWorkspaceProjector.Projection projection) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userId", safe(userId));
        input.put("sourceId", safe(projection.sourceId()));
        input.put("sourceVersion", projection.sourceVersion());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("namespace", safe(projection.namespace()));
        output.put("relativePath", safe(projection.relativePath()));
        output.put("agentsMdBytes", projection.agentsMdBytes());
        output.put("skills", projection.skills());
        return ioDetail(
                java.util.Collections.unmodifiableMap(input),
                java.util.Collections.unmodifiableMap(output),
                projection.toTimelineDetail());
    }

    /**
     * {@code context.map} / {@code blueprint.resolve} 这类"引导期"节点的输入快照。
     *
     * <p>这些节点先前只放平铺字段，等于只有输出没有输入：时间线上"输入"栏一片"无"，而排查租户走错
     * 蓝图、userId 拼错这类问题时，恰恰要先确认<b>进来的请求参数是什么</b>。
     */
    private static Map<String, Object> customerContextInput(CustomerContext customerContext) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("clientCode", customerContext.normalizedClientCode());
        input.put("cluster", customerContext.normalizedCluster());
        input.put("sceneCode", customerContext.normalizedSceneCode());
        input.put("conversationId", customerContext.normalizedConversationId());
        input.put("chatUser", customerContext.normalizedChatUser());
        input.put("userId", customerContext.normalizedUserId());
        input.put("robotKey", customerContext.normalizedRobotKey());
        input.put("messageId", customerContext.normalizedMessageId());
        return java.util.Collections.unmodifiableMap(input);
    }

    private static Map<String, Object> blueprintResolveInput(
            CustomerContext customerContext, BlueprintSelection selection) {
        BlueprintSelection effective = selection == null ? BlueprintSelection.scoped() : selection;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("clientCode", customerContext.normalizedClientCode());
        input.put("cluster", customerContext.normalizedCluster());
        input.put("sceneCode", customerContext.normalizedSceneCode());
        input.put("runtimeAgentId", customerContext.normalizedRuntimeAgentId());
        input.put("requestedVersion", customerContext.normalizedBlueprintVersion());
        input.put("selectorMode", effective.selectorModeOrDefault());
        input.put("selectionId", safe(effective.selectionId()));
        return java.util.Collections.unmodifiableMap(input);
    }

    private static Map<String, Object> promptComposeInput(
            String userMessage,
            RecoveryDecision recovery,
            HistorySummarySnapshot history,
            IntentTaskSnapshot queue,
            CustomerProfileSnapshot profile) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("userMessage", safe(userMessage));
        input.put("recoveryMode", safe(recovery.recoveryMode()));
        input.put("targetIntent", safe(recovery.targetIntent()));
        input.put("historySummary", trimPreview(history.summaryText()));
        input.put("historyRecoveryPending", history.recoveryPending());
        input.put("activeIntentCode", safe(history.activeIntentCode()));
        input.put("queueVersion", safe(queue.queueVersion()));
        input.put("queueTotalTasks", queue.totalTasks());
        input.put("queueActiveTasks", queue.activeTasks());
        input.put("queueSuspendedTasks", queue.suspendedTasks());
        input.put("profileVersion", safe(profile.profileVersion()));
        return java.util.Collections.unmodifiableMap(input);
    }

    /**
     * {@code prompt.compose} 的「输出」面板内容：这一步的产物就是那条 USER {@code Msg}。
     *
     * <p>用 {@code messages} 数组的形状而不是平铺 text，是为了和 LLM 节点的
     * {@code prompt/input/output} 保持同一套渲染路径（前端 {@code formatTimelineMessageBundle}
     * 同时接受裸数组和 {@code {messages: [...]}}）。注意数组不能为空：空数组会让前端回退去
     * {@code JSON.stringify} 整个 bundle，面板上就变成一行 {@code {"messages": []}}。
     */
    static Map<String, Object> promptComposeOutput(Msg composed) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", composed == null || composed.getRole() == null
                ? "user"
                : composed.getRole().name().toLowerCase());
        message.put("text", composed == null ? "" : safe(composed.getTextContent()));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("messages", List.of(java.util.Collections.unmodifiableMap(message)));
        return java.util.Collections.unmodifiableMap(output);
    }

    private record MultiStageExecutionContext(
            CustomerContext customerContext,
            String userMessage,
            SessionBootstrapSnapshot sessionSnapshot,
            HistorySummarySnapshot history,
            IntentTaskSnapshot queue,
            CustomerProfileSnapshot profile,
            RuleContextSnapshot ruleContext,
            RecoveryDecision deterministicRecovery) {}

    private record RecoveryStageResult(
            boolean replyToPrevious, String resumeIntentCode, String reason) {
        static RecoveryStageResult notExecuted() {
            return new RecoveryStageResult(false, "", "");
        }
    }

    /**
     * @param intentCode 本轮生效的主意图；经 {@code intent-priority} 补正后可能与模型声明值不同
     * @param branch 分支键，用于查 {@code stage.onResult}
     * @param reason 判断来源：模型给的原因、{@code fallback-from-heuristic} 或
     *     {@code corrected-by-intent-priority}
     * @param confidence 模型自评 {@code high|medium|low}
     * @param priorityLabel 优先级分档；规则生效时由规则算出，否则取模型声明值
     * @param evidence 命中关键词，最多 3 个
     * @param violationType 非空表示确定性规则否决了模型判断，见
     *     {@link IntentPriorityRule#VIOLATION_PRIORITY_CORRECTED}
     * @param handoffSignals 转人工的三个语义信号，供 {@code human-handoff-trigger} 合并判断
     */
    private record IntentRouteStageResult(
            String intentCode,
            String branch,
            String reason,
            String confidence,
            String priorityLabel,
            List<String> evidence,
            String violationType,
            HandoffSignals handoffSignals) {

        IntentRouteStageResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            handoffSignals = handoffSignals == null ? HandoffSignals.none() : handoffSignals;
        }

        static IntentRouteStageResult skipped() {
            return new IntentRouteStageResult(
                    "", "", "", "", "", List.of(), "", HandoffSignals.none());
        }

        /** 模型没判出主意图也没给分支，需要走编排层兜底。 */
        boolean unresolved() {
            return intentCode().isBlank() && branch().isBlank();
        }
    }

    /**
     * 转人工的三个<b>语义</b>信号，由意图识别阶段的结构化输出给出。
     *
     * <p>{@link HumanHandoffTriggerRule} 的第四个信号"客户明确要求人工"不在这里：它等价于
     * {@code intentCode == transfer_to_human}，已经由九码优先级表确定性地算出来了，
     * 再让模型多输出一个布尔值只是给它一次和主意图自相矛盾的机会。
     *
     * <p>这三个信号本身必须由模型识别——"这句话算不算情绪激烈"、"这是不是孕期用药咨询"
     * 都要语义理解，Rule 只做"任一命中即触发"的合并（见 {@code HumanHandoffTriggerRule} 类注释）。
     */
    private record HandoffSignals(
            boolean severeAllergy, boolean sensitiveMedicalContext, boolean emotionalOrOutOfScope) {

        static HandoffSignals none() {
            return new HandoffSignals(false, false, false);
        }
    }

    private record BusinessProcessStageResult(
            String reply, String historySummary, String intentCode, String nextStep) {}

    /**
     * 画像双闸（{@code profile-completeness} + {@code follow-up-round-limit}）的合并结论。
     *
     * <p>两条规则各自可开可关，所以要分别记录"这一闸这轮到底算了没有"：{@code canRecommend=false}
     * 在"规则没开"和"规则算出画像不够"两种情况下都是 false，业务处理提示词不能把二者当成一回事——
     * 前者不该往提示词里写任何追问约束，后者必须写。
     *
     * @param completenessEvaluated {@code profile-completeness} 本轮是否真的算过
     * @param canRecommend 画像是否已充分到可以推荐；未算过时为 {@code true}（不加约束）
     * @param missingFields 还缺哪组信号；未算过时为空
     * @param roundLimitEvaluated {@code follow-up-round-limit} 本轮是否真的算过
     * @param shouldStopAsking 是否该停止追问；未算过时为 {@code false}
     * @param followUpRoundCount 本轮用到的追问轮次计数，留痕用
     */
    private record ProfileGateResult(
            boolean completenessEvaluated,
            boolean canRecommend,
            List<String> missingFields,
            boolean roundLimitEvaluated,
            boolean shouldStopAsking,
            int followUpRoundCount) {

        ProfileGateResult {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }

        static ProfileGateResult notEvaluated(int followUpRoundCount) {
            return new ProfileGateResult(false, true, List.of(), false, false, followUpRoundCount);
        }

        /** 两闸都没算：编排层可以据此完全不往提示词里加画像约束行。 */
        boolean anyEvaluated() {
            return completenessEvaluated || roundLimitEvaluated;
        }
    }

    /**
     * {@code human-handoff-trigger} 的结论。
     *
     * <p>{@code evaluated} 和 {@link ProfileGateResult} 的两个 {@code *Evaluated} 同理：
     * {@code shouldHandoff=false} 在"规则没开"和"规则算过、四个信号都没命中"两种情况下都是
     * false，但前者不该往提示词里写任何交接约束行。
     *
     * @param triggerReason 命中的那个原因码（{@code explicit_human_request} /
     *     {@code severe_allergy} / {@code sensitive_medical_context} /
     *     {@code emotional_or_out_of_scope}）；没命中是空串
     */
    private record HandoffDecision(boolean evaluated, boolean shouldHandoff, String triggerReason) {

        HandoffDecision {
            triggerReason = triggerReason == null ? "" : triggerReason;
        }

        static HandoffDecision notEvaluated() {
            return new HandoffDecision(false, false, "");
        }
    }

    /**
     * {@code queue-version-guard} 的结论。
     *
     * @param evaluated 规则这轮有没有算过；没算时 {@code effectiveQueueVersion} 就是原样的本地版本号
     * @param conflict 本地与远端版本号不同，说明这条会话读到任务板之后有人改过它
     * @param remoteQueueVersion 写回前重读到的远端版本号；读不到时是空串或那个 bootstrap 占位值
     * @param effectiveQueueVersion 真正回传给 {@code syncIntentQueue} 的版本号。冲突时是远端的那个
     *     ——拿旧版本号强写正是这条规则要挡的事
     */
    private record QueueVersionDecision(
            boolean evaluated,
            boolean conflict,
            String localQueueVersion,
            String remoteQueueVersion,
            String effectiveQueueVersion) {

        QueueVersionDecision {
            localQueueVersion = localQueueVersion == null ? "" : localQueueVersion;
            remoteQueueVersion = remoteQueueVersion == null ? "" : remoteQueueVersion;
            effectiveQueueVersion = effectiveQueueVersion == null ? "" : effectiveQueueVersion;
        }

        static QueueVersionDecision notEvaluated(String localQueueVersion) {
            return new QueueVersionDecision(false, false, localQueueVersion, "", localQueueVersion);
        }

        /**
         * 重读失败：算过、但没得出结论。仍然用本地版本号写回，因为"读不到"不代表"变了"。
         */
        static QueueVersionDecision remoteUnreadable(
                String localQueueVersion, String remoteQueueVersion) {
            return new QueueVersionDecision(
                    true, false, localQueueVersion, remoteQueueVersion, localQueueVersion);
        }

        boolean remoteReadable() {
            return !remoteQueueVersion.isBlank()
                    && (!remoteQueueVersion.startsWith("bootstrap-")
                            || remoteQueueVersion.equals(localQueueVersion));
        }
    }

    /**
     * {@code closure-writeback-required-fields} 的结论。
     *
     * @param evaluated 规则这轮有没有算过；没算时按"可写"处理，规则关掉不等于要拦写回
     * @param complete 四个必填字段是否齐全
     * @param missingFields 缺的字段名，原样来自规则输出，只进时间线不参与判断
     */
    private record ClosureWritebackDecision(
            boolean evaluated, boolean complete, List<String> missingFields) {

        ClosureWritebackDecision {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }

        static ClosureWritebackDecision notEvaluated() {
            return new ClosureWritebackDecision(false, true, List.of());
        }

        /**
         * 摘要为空时不写历史摘要。
         *
         * <p>只看 {@code summaryText} 这一项而不是 {@code complete}：见
         * {@code evaluateClosureWriteback} 的注释——四项缺失的后果不对等，拿整体完整度当闸门会把
         * "首轮没有 queueVersion" 这种正常情形也拦掉。
         */
        boolean historyWritable() {
            return !missingFields.contains("summaryText");
        }
    }

    private record BootstrapContext(RuntimeContext runtimeContext, TenantAgentBinding agentBinding) {}

    private record SingleStageExecutionResult(
            String reply,
            RecoveryDecision recovery,
            CustomerProfileSnapshot profile,
            HistorySummaryWriteResult persistedHistory,
            IntentQueueSyncResult syncedQueue) {}

    private record ResultCloseContext(
            HistorySummaryWriteResult persistedHistory, IntentQueueSyncResult syncedQueue) {}

    public record RuntimeBindingSummary(
            RuntimeBindingCurrent current, List<RuntimeBindingOption> runtimeAgents) {}

    public record RuntimeBindingCurrent(
            String clientCode,
            String cluster,
            String sceneCode,
            String requestedRuntimeAgentId,
            String requestedVersion,
            String runtimeAgentId,
            String templateDisplayName,
            String templateCategory,
            String blueprintId,
            String blueprintIdentity,
            String runtimeMode,
            int stageCount,
            String matchLevel,
            String sourceType,
            String sourcePath,
            String selectionId,
            String selectorMode,
            String version,
            boolean resolved) {}

    public record RuntimeBindingOption(
            String runtimeAgentId,
            String displayName,
            String category,
            String description,
            boolean enabled,
            List<String> versions,
            boolean selected) {}

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeNodeSegment(String value) {
        String normalized = safe(value).replaceAll("[^a-zA-Z0-9:_\\-#.]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private ChatResponse finishTelemetry(
            RuntimeTelemetry.RunScope telemetry, ChatResponse response) {
        if (response != null) {
            telemetry.success(response.reply());
        } else {
            telemetry.success("");
        }
        return response;
    }

    private String resolveTelemetryAgentName(TenantAgentBinding agentBinding) {
        if (agentBinding == null) {
            return agentName;
        }
        if (agentBinding.blueprint() != null && !safe(agentBinding.blueprint().runtimeAgentId()).isBlank()) {
            return safe(agentBinding.blueprint().runtimeAgentId());
        }
        if (!safe(agentBinding.requestedRuntimeAgentId()).isBlank()) {
            return safe(agentBinding.requestedRuntimeAgentId());
        }
        return agentName;
    }

    private TenantKnowledgeContext knowledgeContextFor(
            CustomerContext customerContext, TenantAgentBinding agentBinding) {
        String runtimeAgentId = resolveTelemetryAgentName(agentBinding);
        return accountKnowledgeBindingRepository
                .resolve(
                        customerContext.normalizedClientCode(),
                        customerContext.normalizedCluster())
                .map(
                        resolved ->
                                new TenantKnowledgeContext(
                                        customerContext.normalizedClientCode(),
                                        customerContext.normalizedCluster(),
                                        runtimeAgentId,
                                        resolved.matchLevel(),
                                        resolved.binding()))
                .orElseGet(
                        () ->
                                new TenantKnowledgeContext(
                                        customerContext.normalizedClientCode(),
                                        customerContext.normalizedCluster(),
                                        runtimeAgentId,
                                        "",
                                        null));
    }

    private static String extractReply(Msg replyMsg) {
        if (replyMsg == null || replyMsg.getTextContent() == null || replyMsg.getTextContent().isBlank()) {
            return "模型已返回空结果，请稍后重试。";
        }
        return replyMsg.getTextContent().trim();
    }

    private String runAgentWithTimeline(
            HarnessAgent salesAgent,
            Msg userMsg,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener) {
        return runAgentWithTimeline(
                salesAgent,
                userMsg,
                runtimeContext,
                progressListener,
                agentEventListener,
                "agent.run",
                "Agent 推理");
    }

    private String runAgentWithTimeline(
            HarnessAgent salesAgent,
            Msg userMsg,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener,
            String step,
            String label) {
        String nodeId = buildNodeId("agent", step);
        String parentNodeId = ToolTelemetry.currentNodeId();
        String stageKey = ToolTelemetry.currentStageKey();
        Map<String, Object> startDetail = new LinkedHashMap<>();
        startDetail.put("nodeId", nodeId);
        startDetail.put("parentNodeId", parentNodeId);
        startDetail.put("nodeLayer", "node");
        startDetail.put("stageKey", stageKey);
        // 这一层的"输入"就是真正交给 Agent 的那条消息。start 事件就写：前端按 nodeId 合并 start/end，
        // end 里没有 input 时会沿用 start 的，所以推理还在跑的时候面板上也能看到入参。
        startDetail.put("input", agentRunInput(userMsg, runtimeContext));
        progressListener.onUpdate(
                new ExecutionProgressUpdate("agent", step, "start", label, null, startDetail));
        long startedAt = System.currentTimeMillis();
        try {
            int[] eventCount = new int[] {0};
            String reply =
                    ToolTelemetry.withParentNode(
                            nodeId,
                            inferTelemetryNodeType("agent", step),
                            label,
                            "node",
                            stageKey,
                            () -> {
                                AgentStreamAccumulator accumulator =
                                        new AgentStreamAccumulator(
                                                progressListener,
                                                llmTraceMiddleware,
                                                nodeId,
                                                stageKey,
                                                label);
                                List<AgentEvent> events =
                                        new ArrayList<>(
                                                salesAgent
                                                        .streamEvents(List.of(userMsg), runtimeContext)
                                                        .doOnNext(
                                                                event -> {
                                                                    if (agentEventListener != null) {
                                                                        agentEventListener.accept(event);
                                                                    }
                                                                    accumulator.accept(event);
                                                                })
                                                        .collectList()
                                                        .block());
                                eventCount[0] = events.size();
                                return accumulator.finalReply();
                            });
            Map<String, Object> endDetail = new LinkedHashMap<>();
            endDetail.put("input", agentRunInput(userMsg, runtimeContext));
            endDetail.put("output", agentRunOutput(reply, eventCount[0]));
            endDetail.put("replyLength", reply.length());
            endDetail.put("eventCount", eventCount[0]);
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "agent",
                            step,
                            "end",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, parentNodeId, "node", stageKey, endDetail)));
            return reply;
        } catch (RuntimeException exception) {
            Map<String, Object> errorDetail = new LinkedHashMap<>();
            errorDetail.put("error", exception.getClass().getSimpleName());
            errorDetail.put("message", safe(exception.getMessage()));
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "agent",
                            step,
                            "error",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, parentNodeId, "node", stageKey, errorDetail)));
            throw exception;
        }
    }

    private static final class AgentStreamAccumulator {

        /**
         * 单次工具调用参数的累加上限。
         *
         * <p>参数是流式片段拼起来的，模型偶发退化成刷屏时这个 buffer 会无上界地涨，
         * 而它每一轮都要塞进 SSE 事件，所以在累加处就截断，而不是等到序列化时才发现太大。
         */
        private static final int MAX_TOOL_ARGUMENTS_LENGTH = 4000;

        private final ExecutionProgressListener progressListener;
        private final LlmTraceMiddleware llmTraceMiddleware;
        private final String runNodeId;
        private final String runStageKey;
        private final String runLabel;
        private final Map<String, ModelCallContext> modelContextsByReplyId = new HashMap<>();
        private final Map<String, RuntimeTelemetry.LlmScope> llmScopesByReplyId = new HashMap<>();
        private final Map<String, ToolCallContext> toolContextsByToolCallId = new HashMap<>();
        private final Map<String, StringBuilder> toolResultPreviewByToolCallId = new HashMap<>();
        /** 工具参数的流式累加缓冲，key 是 toolCallId；end 事件取走后即清理。 */
        private final Map<String, StringBuilder> toolCallArgumentsByToolCallId = new HashMap<>();
        // 耗时必须自己记开始时间：AgentEvent 只带 createdAt（事件自己的时刻），
        // 拿不到"这次调用开始于何时"，先前 elapsedMs(AgentEvent) 直接返回 null，
        // 导致 Agent 层所有 end 节点耗时都是空的，而编排层节点都有真实耗时。
        private final Map<String, Long> modelCallStartedAtByReplyId = new HashMap<>();
        private final Map<String, Long> toolCallStartedAtByToolCallId = new HashMap<>();
        private final Map<String, Long> toolResultStartedAtByToolCallId = new HashMap<>();
        private int modelIndex = 0;
        private int toolIndex = 0;
        private String reply = "";
        private final StringBuilder replyDelta = new StringBuilder();

        private AgentStreamAccumulator(
                ExecutionProgressListener progressListener,
                LlmTraceMiddleware llmTraceMiddleware,
                String runNodeId,
                String runStageKey,
                String runLabel) {
            this.progressListener = progressListener;
            this.llmTraceMiddleware = llmTraceMiddleware;
            this.runNodeId = safe(runNodeId);
            this.runStageKey = safe(runStageKey);
            this.runLabel = safe(runLabel);
        }

        private void accept(AgentEvent event) {
            if (event instanceof ModelCallStartEvent modelCallStartEvent) {
                String replyId = safe(modelCallStartEvent.getReplyId());
                int index = ++modelIndex;
                ModelCallContext context = captureModelCallContext(replyId, index);
                modelContextsByReplyId.put(replyId, context);
                modelCallStartedAtByReplyId.put(replyId, System.currentTimeMillis());
                llmScopesByReplyId.put(
                        replyId,
                        RuntimeTelemetry.startLlmCall(replyId, context.displayLabel()));
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("replyId", replyId);
                // 这里刻意不写 prompt / input / output：提示词快照要等 end 事件才从中间件取。
                // 曾经放过 prompt = {"messages": []} 占位，反倒更糟——前端 formatTimelineMessageBundle
                // 遇到空数组会回退去 JSON.stringify 整个 bundle，面板上直接显示一行 {"messages": []}。
                // 键不存在时前端显示"无"，语义正确得多；且 end 事件按 nodeId 合并时会补上真实数据。
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                "model.call#" + index,
                                "start",
                                context.displayLabel(),
                                null,
                                detail));
                return;
            }

            if (event instanceof ModelCallEndEvent modelCallEndEvent) {
                String replyId = safe(modelCallEndEvent.getReplyId());
                ModelCallContext context = modelContextsByReplyId.get(replyId);
                if (context == null) {
                    int index = ++modelIndex;
                    context = captureModelCallContext(replyId, index);
                    modelContextsByReplyId.put(replyId, context);
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("replyId", replyId);
                detail.put("usage", modelCallEndEvent.getUsage() == null ? "-" : modelCallEndEvent.getUsage().toString());
                Map<String, Object> llmTraceDetail =
                        llmTraceMiddleware == null
                                ? Map.<String, Object>of()
                                : llmTraceMiddleware.consumeTraceDetail(replyId);
                if (!llmTraceDetail.isEmpty()) {
                    detail.putAll(llmTraceDetail);
                } else {
                    // 拿不到快照时只写 output（说明这次调用确实结束了、但埋点没接上），
                    // 不再补 prompt = {"messages": []}：空数组会被前端渲染成字面量文本。
                    detail.put(
                            "output",
                            Map.of(
                                    "replyId", replyId,
                                    "usage",
                                            modelCallEndEvent.getUsage() == null
                                                    ? "-"
                                                    : modelCallEndEvent.getUsage().toString()));
                }
                RuntimeTelemetry.LlmScope llmScope = llmScopesByReplyId.remove(replyId);
                if (llmScope != null) {
                    try {
                        llmScope.success(
                                llmTraceDetail,
                                modelCallEndEvent.getUsage(),
                                replyDelta.toString());
                    } finally {
                        llmScope.close();
                    }
                }
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                "model.call#" + context.index(),
                                "end",
                                context.displayLabel(),
                                elapsedSince(modelCallStartedAtByReplyId.remove(replyId)),
                                detail));
                return;
            }

            if (event instanceof ToolCallStartEvent toolCallStartEvent) {
                int index = ++toolIndex;
                ToolCallContext context = captureToolCallContext(toolCallStartEvent, index);
                toolContextsByToolCallId.put(safe(toolCallStartEvent.getToolCallId()), context);
                toolCallStartedAtByToolCallId.put(
                        safe(toolCallStartEvent.getToolCallId()), System.currentTimeMillis());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("toolName", context.toolName());
                detail.put("toolCallId", context.toolCallId());
                detail.put("replyId", context.replyId());
                // start 事件只占位：真实参数由随后的 ToolCallDeltaEvent 流式送达，到 end 事件才完整。
                // 前端按 nodeId 合并且 end 的 detail 覆盖 start，所以这里不写 input——写了反而会在
                // 参数到齐之前先把面板占住。
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                context.step(),
                                "start",
                                context.label(),
                                null,
                                detail));
                return;
            }

            // 工具参数是流式来的：AgentScope 把每个 ToolUseBlock 的 content 片段包成 ToolCallDeltaEvent
            // （见 ReActAgent.emitBlockEvents），按 toolCallId 拼起来才是模型真正传给工具的 JSON。
            // 早先这里没有分支，工具节点只能挂一句"当前 AgentScope 事件未暴露原始工具参数"的假占位。
            if (event instanceof ToolCallDeltaEvent toolCallDeltaEvent) {
                String toolCallId = safe(toolCallDeltaEvent.getToolCallId());
                String delta = toolCallDeltaEvent.getDelta();
                if (toolCallId.isEmpty() || delta == null || delta.isEmpty()) {
                    return;
                }
                StringBuilder buffer =
                        toolCallArgumentsByToolCallId.computeIfAbsent(
                                toolCallId, ignored -> new StringBuilder());
                if (buffer.length() < MAX_TOOL_ARGUMENTS_LENGTH) {
                    buffer.append(delta);
                }
                return;
            }

            if (event instanceof ToolCallEndEvent toolCallEndEvent) {
                ToolCallContext context = resolveToolCallContext(toolCallEndEvent);
                String toolCallId = safe(toolCallEndEvent.getToolCallId());
                String arguments =
                        toolCallArgumentsByToolCallId.containsKey(toolCallId)
                                ? toolCallArgumentsByToolCallId.get(toolCallId).toString()
                                : "";
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("toolName", context.toolName());
                input.put("toolCallId", context.toolCallId());
                input.put("replyId", context.replyId());
                if (!arguments.isBlank()) {
                    input.put("arguments", trimToolArguments(arguments));
                }
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("toolName", context.toolName());
                output.put("toolCallId", context.toolCallId());
                // 工具的执行结果走独立的 ToolResultEndEvent 节点（"工具结果 <name>"），这里只到
                // "参数已收齐、准备执行"为止，明确说出来，免得看的人以为输出丢了。
                output.put("state", "dispatched");
                output.put("resultNodeId", context.resultNodeId());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("toolName", context.toolName());
                detail.put("toolCallId", context.toolCallId());
                detail.put("replyId", context.replyId());
                if (!arguments.isBlank()) {
                    detail.put("arguments", trimToolArguments(arguments));
                }
                detail.put("input", java.util.Collections.unmodifiableMap(input));
                detail.put("output", java.util.Collections.unmodifiableMap(output));
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                context.step(),
                                "end",
                                context.label(),
                                elapsedSince(toolCallStartedAtByToolCallId.remove(toolCallId)),
                                detail));
                // 工具真正开始执行的时刻就是参数收齐、派发出去的这一刻——框架没有
                // ToolResultStartEvent，用它给"工具结果"节点计时。
                toolResultStartedAtByToolCallId.put(toolCallId, System.currentTimeMillis());
                return;
            }

            if (event instanceof ToolResultTextDeltaEvent toolResultTextDeltaEvent) {
                toolResultPreviewByToolCallId
                        .computeIfAbsent(toolResultTextDeltaEvent.getToolCallId(), ignored -> new StringBuilder())
                        .append(toolResultTextDeltaEvent.getDelta());
                return;
            }

            if (event instanceof TextBlockDeltaEvent textBlockDeltaEvent) {
                replyDelta.append(textBlockDeltaEvent.getDelta());
                return;
            }

            if (event instanceof ToolResultEndEvent toolResultEndEvent) {
                ToolCallContext context = resolveToolResultContext(toolResultEndEvent);
                String toolCallId = safe(toolResultEndEvent.getToolCallId());
                String preview =
                        toolResultPreviewByToolCallId.containsKey(toolResultEndEvent.getToolCallId())
                                ? trimPreview(toolResultPreviewByToolCallId.get(toolResultEndEvent.getToolCallId()).toString())
                                : "";
                String arguments =
                        toolCallArgumentsByToolCallId.containsKey(toolCallId)
                                ? toolCallArgumentsByToolCallId.get(toolCallId).toString()
                                : "";
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.resultNodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("toolName", context.toolName());
                detail.put("toolCallId", context.toolCallId());
                detail.put("replyId", context.replyId());
                detail.put("state", String.valueOf(toolResultEndEvent.getState()));
                if (!preview.isBlank()) {
                    detail.put("preview", preview);
                }
                // 结果节点也带上入参：排查"工具返回不对"时第一件想确认的就是它到底收到了什么，
                // 让人不必在时间线上来回跳到上一个节点。
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("toolName", context.toolName());
                input.put("toolCallId", context.toolCallId());
                if (!arguments.isBlank()) {
                    input.put("arguments", trimToolArguments(arguments));
                }
                detail.put("input", java.util.Collections.unmodifiableMap(input));
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("toolName", context.toolName());
                output.put("state", String.valueOf(toolResultEndEvent.getState()));
                if (!preview.isBlank()) {
                    output.put("preview", preview);
                }
                detail.put("output", java.util.Collections.unmodifiableMap(output));
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                context.resultStep(),
                                "end",
                                context.resultLabel(),
                                elapsedSince(toolResultStartedAtByToolCallId.remove(toolCallId)),
                                detail));
                toolResultPreviewByToolCallId.remove(toolCallId);
                toolCallArgumentsByToolCallId.remove(toolCallId);
                return;
            }

            if (event instanceof ExceedMaxItersEvent exceedMaxItersEvent) {
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                "agent.abnormal.exceed-max-iters",
                                "error",
                                "超过最大迭代次数",
                                null,
                                Map.of(
                                        "nodeId", "agent:agent.abnormal.exceed-max-iters",
                                        "parentNodeId", ToolTelemetry.currentNodeId(),
                                        "nodeLayer", "child_call",
                                        "stageKey", ToolTelemetry.currentStageKey(),
                                        "replyId", safe(exceedMaxItersEvent.getReplyId()),
                                        "currentIter", exceedMaxItersEvent.getCurrentIter(),
                                        "maxIters", exceedMaxItersEvent.getMaxIters())));
                return;
            }

            if (event instanceof AllToolsDeniedEvent allToolsDeniedEvent) {
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                "agent.abnormal.all-tools-denied",
                                "error",
                                "工具调用全部被拒绝",
                                null,
                                Map.of(
                                        "nodeId", "agent:agent.abnormal.all-tools-denied",
                                        "parentNodeId", ToolTelemetry.currentNodeId(),
                                        "nodeLayer", "child_call",
                                        "stageKey", ToolTelemetry.currentStageKey(),
                                        "deniedCount", allToolsDeniedEvent.getDeniedToolCalls().size())));
                return;
            }

            if (event instanceof AgentResultEvent agentResultEvent) {
                reply = extractReply(agentResultEvent.getResult());
            }
        }

        private String finalReply() {
            if (reply.isBlank() && replyDelta.length() > 0) {
                reply = replyDelta.toString();
            }
            for (RuntimeTelemetry.LlmScope llmScope : llmScopesByReplyId.values()) {
                try {
                    llmScope.failure(new IllegalStateException("llm span closed without end event"));
                } finally {
                    llmScope.close();
                }
            }
            llmScopesByReplyId.clear();
            return reply.isBlank() ? "模型已返回空结果，请稍后重试。" : reply;
        }

        private ModelCallContext captureModelCallContext(String replyId, int index) {
            String parentNodeId = firstNonBlank(ToolTelemetry.currentNodeId(), runNodeId);
            String stageKey = firstNonBlank(ToolTelemetry.currentStageKey(), runStageKey);
            String stageDisplayName = resolveStageDisplayName(stageKey);
            return new ModelCallContext(
                    index,
                    buildModelCallNodeId(index, stageKey, parentNodeId, replyId),
                    parentNodeId,
                    stageKey,
                    buildModelCallLabel(index, stageDisplayName));
        }

        private ToolCallContext captureToolCallContext(ToolCallStartEvent event, int index) {
            String toolName = safe(event.getToolCallName());
            String toolCallId = safe(event.getToolCallId());
            String replyId = safe(event.getReplyId());
            String parentNodeId = firstNonBlank(ToolTelemetry.currentNodeId(), runNodeId);
            String stageKey = firstNonBlank(ToolTelemetry.currentStageKey(), runStageKey);
            return new ToolCallContext(
                    index,
                    toolName,
                    toolCallId,
                    replyId,
                    "agent:agent.tool." + toolName + "#" + index,
                    "agent.tool." + toolName + "#" + index,
                    "工具 " + toolName,
                    "agent:agent.tool." + toolName + ".result#" + index,
                    "agent.tool." + toolName + ".result#" + index,
                    "工具结果 " + toolName,
                    parentNodeId,
                    stageKey);
        }

        private ToolCallContext resolveToolCallContext(ToolCallEndEvent event) {
            String toolCallId = safe(event.getToolCallId());
            ToolCallContext context = toolContextsByToolCallId.get(toolCallId);
            if (context != null) {
                return context;
            }
            int index = ++toolIndex;
            return fallbackToolContext(
                    index,
                    firstNonBlank(event.getToolCallName(), ""),
                    toolCallId,
                    safe(event.getReplyId()));
        }

        private ToolCallContext resolveToolResultContext(ToolResultEndEvent event) {
            String toolCallId = safe(event.getToolCallId());
            ToolCallContext context = toolContextsByToolCallId.get(toolCallId);
            if (context != null) {
                return context;
            }
            int index = ++toolIndex;
            return fallbackToolContext(
                    index,
                    firstNonBlank(event.getToolCallName(), ""),
                    toolCallId,
                    "");
        }

        private ToolCallContext fallbackToolContext(
                int index, String rawToolName, String toolCallId, String replyId) {
            String toolName = safe(rawToolName);
            String parentNodeId = firstNonBlank(ToolTelemetry.currentNodeId(), runNodeId);
            String stageKey = firstNonBlank(ToolTelemetry.currentStageKey(), runStageKey);
            return new ToolCallContext(
                    index,
                    toolName,
                    toolCallId,
                    replyId,
                    "agent:agent.tool." + toolName + "#" + index,
                    "agent.tool." + toolName + "#" + index,
                    "工具 " + toolName,
                    "agent:agent.tool." + toolName + ".result#" + index,
                    "agent.tool." + toolName + ".result#" + index,
                    "工具结果 " + toolName,
                    parentNodeId,
                    stageKey);
        }

        private String resolveStageDisplayName(String stageKey) {
            if (!safe(stageKey).isBlank() && safe(stageKey).equals(runStageKey) && !runLabel.isBlank()) {
                return runLabel;
            }
            return humanizeStageKey(stageKey);
        }
    }

    private record ModelCallContext(
            int index,
            String nodeId,
            String parentNodeId,
            String stageKey,
            String displayLabel) {}

    private record ToolCallContext(
            int index,
            String toolName,
            String toolCallId,
            String replyId,
            String nodeId,
            String step,
            String label,
            String resultNodeId,
            String resultStep,
            String resultLabel,
            String parentNodeId,
            String stageKey) {}

    private static <T> T runStageGroup(
            ExecutionProgressListener progressListener,
            String stageKey,
            String displayName,
            String stageType,
            Supplier<T> supplier) {
        String normalizedStageKey = safe(stageKey);
        String nodeId = buildNodeId("stage", normalizedStageKey);
        Map<String, Object> startDetail = new LinkedHashMap<>();
        startDetail.put("stageType", safe(stageType));
        startDetail.put("nodeId", nodeId);
        startDetail.put("parentNodeId", "");
        startDetail.put("nodeLayer", "stage");
        startDetail.put("stageKey", normalizedStageKey);
        progressListener.onUpdate(
                new ExecutionProgressUpdate(
                        "stage", normalizedStageKey, "start", displayName, null, startDetail));
        long startedAt = System.currentTimeMillis();
        try {
            T result =
                    ToolTelemetry.withParentNode(
                            nodeId, "stage", displayName, "stage", normalizedStageKey, supplier);
            Map<String, Object> endDetail = new LinkedHashMap<>();
            endDetail.put("stageType", safe(stageType));
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "stage",
                            normalizedStageKey,
                            "end",
                            displayName,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, "", "stage", normalizedStageKey, endDetail)));
            return result;
        } catch (RuntimeException exception) {
            Map<String, Object> errorDetail = new LinkedHashMap<>();
            errorDetail.put("stageType", safe(stageType));
            errorDetail.put("error", exception.getClass().getSimpleName());
            errorDetail.put("message", safe(exception.getMessage()));
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            "stage",
                            normalizedStageKey,
                            "error",
                            displayName,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, "", "stage", normalizedStageKey, errorDetail)));
            throw exception;
        }
    }

    private static <T> T runStep(
            ExecutionProgressListener progressListener,
            String phase,
            String step,
            String label,
            Supplier<T> supplier,
            Function<T, Map<String, Object>> detailBuilder) {
        String nodeId = buildNodeId(phase, step);
        String parentNodeId = ToolTelemetry.currentNodeId();
        String stageKey = ToolTelemetry.currentStageKey();
        Map<String, Object> startDetail = new LinkedHashMap<>();
        startDetail.put("nodeId", nodeId);
        startDetail.put("parentNodeId", parentNodeId);
        startDetail.put("nodeLayer", "node");
        startDetail.put("stageKey", stageKey);
        progressListener.onUpdate(
                new ExecutionProgressUpdate(
                        phase, step, "start", label, null, startDetail));
        long startedAt = System.currentTimeMillis();
        try {
            T result =
                    ToolTelemetry.withParentNode(
                            nodeId,
                            inferTelemetryNodeType(phase, step),
                            label,
                            "node",
                            stageKey,
                            supplier);
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            phase,
                            step,
                            "end",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(
                                    nodeId,
                                    parentNodeId,
                                    "node",
                                    stageKey,
                                    detailBuilder == null ? Map.of() : detailBuilder.apply(result))));
            return result;
        } catch (RuntimeException exception) {
            Map<String, Object> errorDetail = new LinkedHashMap<>();
            errorDetail.put("error", exception.getClass().getSimpleName());
            errorDetail.put("message", safe(exception.getMessage()));
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            phase,
                            step,
                            "error",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, parentNodeId, "node", stageKey, errorDetail)));
            throw exception;
        }
    }

    private static <T> T runSoftStep(
            ExecutionProgressListener progressListener,
            String phase,
            String step,
            String label,
            Supplier<T> supplier,
            Function<T, Map<String, Object>> detailBuilder,
            T fallback) {
        String nodeId = buildNodeId(phase, step);
        String parentNodeId = ToolTelemetry.currentNodeId();
        String stageKey = ToolTelemetry.currentStageKey();
        Map<String, Object> startDetail = new LinkedHashMap<>();
        startDetail.put("nodeId", nodeId);
        startDetail.put("parentNodeId", parentNodeId);
        startDetail.put("nodeLayer", "node");
        startDetail.put("stageKey", stageKey);
        progressListener.onUpdate(
                new ExecutionProgressUpdate(
                        phase, step, "start", label, null, startDetail));
        long startedAt = System.currentTimeMillis();
        try {
            T result =
                    ToolTelemetry.withParentNode(
                            nodeId,
                            inferTelemetryNodeType(phase, step),
                            label,
                            "node",
                            stageKey,
                            supplier);
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            phase,
                            step,
                            "end",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(
                                    nodeId,
                                    parentNodeId,
                                    "node",
                                    stageKey,
                                    detailBuilder == null ? Map.of() : detailBuilder.apply(result))));
            return result;
        } catch (RuntimeException exception) {
            Map<String, Object> errorDetail = new LinkedHashMap<>();
            errorDetail.put("error", exception.getClass().getSimpleName());
            errorDetail.put("message", safe(exception.getMessage()));
            errorDetail.put("degraded", true);
            progressListener.onUpdate(
                    new ExecutionProgressUpdate(
                            phase,
                            step,
                            "error",
                            label,
                            System.currentTimeMillis() - startedAt,
                            attachNodeMeta(nodeId, parentNodeId, "node", stageKey, errorDetail)));
            return fallback;
        }
    }

    private static Map<String, Object> attachNodeMeta(
            String nodeId,
            String parentNodeId,
            String nodeLayer,
            String stageKey,
            Map<String, Object> detail) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (detail != null && !detail.isEmpty()) {
            enriched.putAll(detail);
        }
        enriched.put("nodeId", nodeId);
        enriched.put("parentNodeId", safe(parentNodeId));
        enriched.put("nodeLayer", safe(nodeLayer));
        enriched.put("stageKey", safe(stageKey));
        return java.util.Collections.unmodifiableMap(enriched);
    }

    private static String buildNodeId(String phase, String step) {
        return (safe(phase) + ":" + safe(step)).replace(' ', '_');
    }

    private static String buildModelCallNodeId(
            int index, String stageKey, String parentNodeId, String replyId) {
        return new StringBuilder("agent:model.call#")
                .append(index)
                .append('@')
                .append(
                        safeNodeSegment(
                                firstNonBlank(
                                        safe(stageKey),
                                        firstNonBlank(safe(parentNodeId), firstNonBlank(safe(replyId), "root")))))
                .toString();
    }

    private static String buildModelCallLabel(int index, String stageDisplayName) {
        String normalized = safe(stageDisplayName);
        String base = "模型调用 #" + index;
        return normalized.isBlank() ? base : normalized + " · " + base;
    }

    private static String humanizeStageKey(String stageKey) {
        String normalized = safe(stageKey);
        return normalized.isBlank() ? "" : normalized.replace('_', ' ').trim();
    }

    private static String inferTelemetryNodeType(String phase, String step) {
        if ("orchestration".equals(safe(phase)) && safe(step).startsWith("tool.")) {
            return "orchestration_tool";
        }
        if ("orchestration".equals(safe(phase)) && safe(step).startsWith("blueprint.")) {
            return "blueprint";
        }
        if ("orchestration".equals(safe(phase)) && safe(step).startsWith("rule.")) {
            return "rule";
        }
        if ("orchestration".equals(safe(phase)) && safe(step).startsWith("prompt.")) {
            return "prompt";
        }
        if ("stage".equals(safe(phase))) {
            if (safe(step).endsWith(".project") || safe(step).endsWith(".prompt_compose")) {
                return "prompt";
            }
            if (safe(step).startsWith("preload_context.") || safe(step).startsWith("result_close.")) {
                return "orchestration_tool";
            }
            return "stage";
        }
        return safe(phase);
    }

    /**
     * 时间线节点明细的标准形状：{@code input} / {@code output} 两个专用面板，外加把 {@code output}
     * 平铺到顶层供「补充明细」一眼扫读。
     *
     * <p>平铺只对"字段都是标量"的 output 成立。如果 output 里带 {@code messages} 这类结构化数组，
     * 平铺会把整个数组塞进补充明细刷屏——那种节点请用
     * {@link #ioDetail(Map, Map, Map)} 显式指定要平铺哪几个键。
     */
    private static Map<String, Object> toolDetail(
            Map<String, Object> input, Map<String, Object> output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("input", input);
        detail.put("output", output);
        detail.putAll(output);
        return detail;
    }

    /**
     * 同 {@link #toolDetail(Map, Map)}，但顶层平铺哪些键由调用方显式给出，而不是无脑摊开整个
     * {@code output}。用于 output 内含结构化字段（如 {@code messages}）的节点。
     */
    private static Map<String, Object> ioDetail(
            Map<String, Object> input, Map<String, Object> output, Map<String, Object> flatFields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("input", input);
        detail.put("output", output);
        if (flatFields != null) {
            detail.putAll(flatFields);
        }
        return detail;
    }

    /**
     * 按"自己记下的开始时刻"算耗时。
     *
     * <p>这里不用 {@code AgentEvent}：它只带 {@code createdAt}（该事件自身产生的时刻），没有配对的
     * 开始时刻，所以先前那个 {@code elapsedMs(AgentEvent)} 只能 {@code return null}——效果是 Agent 层
     * 每个 end 节点耗时都是空的，而编排层节点都有真实耗时，看起来像埋点坏了。改成由累加器在 start
     * 分支记 {@code System.currentTimeMillis()}，end 分支配对相减。
     *
     * @param startedAt 开始时刻；配不上（比如只收到 end 事件）时为 null，此时返回 null 而不是 0，
     *     避免把"不知道"显示成"0ms"
     */
    private static Long elapsedSince(Long startedAt) {
        if (startedAt == null) {
            return null;
        }
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    /** 工具参数按整体长度截断（保留原始换行，JSON 缩进对读参数有用）。 */
    private static String trimToolArguments(String value) {
        String normalized = safe(value);
        if (normalized.length() <= 2000) {
            return normalized;
        }
        return normalized.substring(0, 2000) + "...(共 " + normalized.length() + " 字符)";
    }

    private static String trimPreview(String value) {
        String normalized = safe(value).replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157) + "...";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static String deriveIntentCode(
            RecoveryDecision recovery, HistorySummarySnapshot history) {
        return firstNonBlank(
                safe(recovery.targetIntent()),
                firstNonBlank(safe(history.activeIntentCode()), "general_consultation"));
    }

    /**
     * @param needHumanHandoff 多阶段链路由 {@code human-handoff-trigger} 给出；单 Agent 模式则取单阶段
     *     结构化输出中的显式结论
     */
    private static HistorySummaryWriteRequest buildHistorySummaryWriteRequest(
            String userMessage,
            String reply,
            RecoveryDecision recovery,
            HistorySummarySnapshot history,
            String resolvedIntentCode,
            CustomerContext customerContext,
            boolean needHumanHandoff) {
        String nextStep =
                safe(recovery.targetIntent()).isBlank()
                        ? "继续跟进当前客户诉求并等待下一轮消息"
                        : "继续跟进意图 " + resolvedIntentCode;
        String summary =
                "最近一轮客户消息：" + safe(userMessage)
                        + "\n最近一轮 Agent 回复：" + trimPreview(reply)
                        + "\n当前意图：" + resolvedIntentCode
                        + "\n下一步：" + nextStep;
        return new HistorySummaryWriteRequest(
                summary,
                safe(userMessage),
                safe(reply),
                resolvedIntentCode,
                "sales_customer_agent",
                nextStep,
                "chat_workbench",
                "assistant_replied",
                needHumanHandoff,
                parseTurnIndex(customerContext.normalizedAddMsgCount()) + 1,
                compactQuestionFocus(userMessage),
                looksLikeDirectRecommendation(userMessage),
                !safe(userMessage).isBlank(),
                false,
                safe(recovery.targetIntent()),
                safe(history.activeIntentCode()),
                safe(recovery.recoveryMode()));
    }

    private static List<IntentQueueSyncUpdate> buildIntentQueueUpdates(
            CustomerContext customerContext,
            String userMessage,
            String reply,
            String intentCode) {
        Map<String, Object> surfaceSignals = new LinkedHashMap<>();
        surfaceSignals.put("originRawContext", safe(userMessage));
        surfaceSignals.put("latestRawContext", safe(userMessage));
        surfaceSignals.put("recoverPromptSeed", safe(userMessage));
        surfaceSignals.put("rawContext", safe(userMessage));
        return List.of(
                new IntentQueueSyncUpdate(
                        intentCode,
                        intentCode,
                        "activate",
                        looksLikeDirectRecommendation(userMessage) ? "high" : "medium",
                        safe(userMessage),
                        trimPreview(reply),
                        surfaceSignals,
                        parseTurnIndex(customerContext.normalizedAddMsgCount())));
    }

    private static String inferQueueAction(HistorySummarySnapshot history, String intentCode) {
        return safe(history.activeIntentCode()).equals(intentCode) ? "update" : "activate";
    }

    private static boolean looksLikeDirectRecommendation(String userMessage) {
        String normalized = safe(userMessage);
        return normalized.contains("推荐")
                || normalized.contains("产品")
                || normalized.contains("方案")
                || normalized.contains("搭配");
    }

    static String compactQuestionFocus(String userMessage) {
        String normalized = safe(userMessage).replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 32) {
            return normalized;
        }
        return normalized.substring(0, 32);
    }

    private static int parseTurnIndex(String value) {
        try {
            return Integer.parseInt(safe(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /**
     * "没写成"的历史摘要结果：{@code summaryVersion=0} 表示后端没有落库。
     *
     * <p>两处用到——{@code saveHistorySummary} 调用失败时的 {@code runSoftStep} 回落值，以及
     * {@code closure-writeback-required-fields} 判定摘要为空、刻意不发这次写回时的返回值。
     * 两种情形对下游是一回事：本轮没有新的摘要版本，下一轮会读到上一轮那份。
     */
    private static HistorySummaryWriteResult unwrittenHistorySummary(
            CustomerContext customerContext, HistorySummaryWriteRequest request) {
        return new HistorySummaryWriteResult(
                customerContext.normalizedConversationId(),
                customerContext.normalizedChatUser(),
                request.historySummary(),
                request.lastIntent(),
                request.lastNextStep(),
                0);
    }

    private static int parseQueueVersion(String value) {
        try {
            return Integer.parseInt(safe(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    record SingleStageAgentOutput(String reply, String intentCode, boolean needHumanHandoff) {}
}
