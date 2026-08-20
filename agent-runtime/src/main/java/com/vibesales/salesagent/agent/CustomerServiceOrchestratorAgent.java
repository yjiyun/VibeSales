package com.agentteams.salesagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentteams.salesagent.agent.middleware.RecoveryPromptContext;
import com.agentteams.salesagent.blueprint.AgentBlueprint;
import com.agentteams.salesagent.blueprint.AgentBlueprintRepository;
import com.agentteams.salesagent.blueprint.BlueprintCatalogItem;
import com.agentteams.salesagent.blueprint.BlueprintSelection;
import com.agentteams.salesagent.blueprint.BlueprintSourceFactory;
import com.agentteams.salesagent.blueprint.FallbackPromptAssets;
import com.agentteams.salesagent.blueprint.ResolvedBlueprint;
import com.agentteams.salesagent.blueprint.RuntimeAgentTemplateCatalog;
import com.agentteams.salesagent.blueprint.TenantWorkspaceProjector;
import com.agentteams.salesagent.config.AppConfig;
import com.agentteams.salesagent.context.CustomerContext;
import com.agentteams.salesagent.context.RuntimeContextMapper;
import com.agentteams.salesagent.model.ModelFactory;
import com.agentteams.salesagent.progress.ExecutionProgressListener;
import com.agentteams.salesagent.progress.ExecutionProgressUpdate;
import com.agentteams.salesagent.rule.recovery.RecoveryDetectionRule;
import com.agentteams.salesagent.skill.RecoveryDecision;
import com.agentteams.salesagent.skill.RecoveryHandlingService;
import com.agentteams.salesagent.skill.SkillRepositoryFactory;
import com.agentteams.salesagent.tool.RuntimeToolScope;
import com.agentteams.salesagent.tool.history.GetHistorySummaryTool;
import com.agentteams.salesagent.tool.history.HistorySummarySnapshot;
import com.agentteams.salesagent.tool.history.HistorySummaryWriteRequest;
import com.agentteams.salesagent.tool.history.HistorySummaryWriteResult;
import com.agentteams.salesagent.tool.history.SaveHistorySummaryTool;
import com.agentteams.salesagent.tool.profile.CustomerProfileSnapshot;
import com.agentteams.salesagent.tool.profile.GetCustomerProfileTool;
import com.agentteams.salesagent.tool.rulecontext.GetRuleContextTool;
import com.agentteams.salesagent.tool.rulecontext.RuleContextSnapshot;
import com.agentteams.salesagent.tool.session.CreateOrResumeSessionTool;
import com.agentteams.salesagent.tool.session.SessionBootstrapSnapshot;
import com.agentteams.salesagent.tool.taskboard.GetIntentQueueTool;
import com.agentteams.salesagent.tool.taskboard.IntentQueueSyncResult;
import com.agentteams.salesagent.tool.taskboard.IntentQueueSyncUpdate;
import com.agentteams.salesagent.tool.taskboard.IntentTaskSnapshot;
import com.agentteams.salesagent.tool.taskboard.SyncIntentQueueTool;
import com.agentteams.salesagent.tool.telemetry.ToolTelemetry;
import com.agentteams.salesagent.model.telemetry.TelemetryOpenAIFormatter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import java.util.Optional;
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
    private final AgentBlueprintRepository blueprintRepository;
    private final RuntimeAgentTemplateCatalog runtimeAgentTemplateCatalog;
    private final TenantWorkspaceProjector workspaceProjector;
    private final Model model;

    /**
     * 服务全部租户的<b>唯一</b> Agent 实例。
     *
     * <p>租户差异不在实例上，而在每请求投影出的 {@code <userId>/AGENTS.md} 与
     * {@code <userId>/skills/} 上（见 {@link SalesAgentFactory}）。
     */
    private final HarnessAgent sharedAgent;

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
        this.runtimeAgentTemplateCatalog = new RuntimeAgentTemplateCatalog();
        this.model = ModelFactory.createDefaultModel(config);
        this.workspaceProjector = new TenantWorkspaceProjector(config.resolvedWorkspaceRoot());
        this.fallbackSkills = SkillRepositoryFactory.loadDefaultSkills();
        this.fallbackPrompt = new FallbackPromptAssets().load();
        // 共享 Agent 不再持有 Prompt 正文；身份与工作准则都从命名空间 workspace 里按请求读取。
        this.sharedAgent =
                new SalesAgentFactory().createSharedAgent(model, workspaceProjector.workspaceRoot());
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
        try (ToolTelemetry.Scope ignored = ToolTelemetry.install(progressListener)) {
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
                                                () -> resolveTenantBinding(customerContext, selection),
                                                TenantAgentBinding::toTimelineDetail);
                                return new BootstrapContext(runtimeContext, agentBinding);
                            });
            RuntimeContext runtimeContext = bootstrap.runtimeContext();
            TenantAgentBinding agentBinding = bootstrap.agentBinding();
            if (agentBinding.isMultiStage()) {
                return handleMultiStage(
                        customerContext,
                        userMessage,
                        progressListener,
                        agentEventListener,
                        runtimeContext,
                        agentBinding);
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
                                        TenantWorkspaceProjector.Projection::toTimelineDetail);
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
                                                ignoredStep ->
                                                        Map.of(
                                                                "injectionMode",
                                                                "RuntimeContext + Middleware",
                                                                "userMessageLength",
                                                                safe(userMessage).length()));
                                String reply =
                                        runAgentWithTimeline(
                                                sharedAgent,
                                                userMsg,
                                                runtimeContext,
                                                progressListener,
                                                agentEventListener);
                                String resolvedIntentCode = deriveIntentCode(recovery, history);
                                HistorySummaryWriteRequest historyWriteRequest =
                                        buildHistorySummaryWriteRequest(
                                                userMessage,
                                                reply,
                                                recovery,
                                                history,
                                                resolvedIntentCode,
                                                customerContext);
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
                                                new HistorySummaryWriteResult(
                                                        customerContext.normalizedConversationId(),
                                                        customerContext.normalizedChatUser(),
                                                        historyWriteRequest.historySummary(),
                                                        historyWriteRequest.lastIntent(),
                                                        historyWriteRequest.lastNextStep(),
                                                        0));
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
            return new ChatResponse(
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
                    resolvedBlueprintSummary(agentBinding));
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
        String sceneCode = customerContext.normalizedSceneCode();
        String runtimeAgentId = customerContext.normalizedRuntimeAgentId();
        String version = customerContext.normalizedBlueprintVersion();
        return new TenantAgentBinding(
                blueprintRepository
                        .resolve(clientCode, cluster, sceneCode, runtimeAgentId, version, selection)
                        .orElse(null),
                clientCode,
                cluster,
                runtimeAgentId,
                version,
                selection == null ? BlueprintSelection.scoped() : selection);
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
                            blueprint, executionContext, runtimeContext, progressListener, agentEventListener);
        }

        String resolvedIntentCode =
                firstNonBlank(
                        recoveryStageResult.resumeIntentCode(),
                        firstNonBlank(
                                intentStageResult.intentCode(),
                                deriveIntentCode(deterministicRecovery, history)));
        BusinessProcessStageResult businessStageResult =
                executeBusinessProcessStage(
                        blueprint,
                        executionContext,
                        runtimeContext,
                        progressListener,
                        agentEventListener,
                        resolvedIntentCode,
                        recoveryStageResult,
                        intentStageResult);

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
                        customerContext);
        ResultCloseContext resultClose =
                runStageGroup(
                        progressListener,
                        "result_close",
                        "结果收口",
                        "tool",
                        () -> {
                            HistorySummaryWriteResult persistedHistory =
                                    runSoftStep(
                                            progressListener,
                                            "stage",
                                            "result_close.history",
                                            "结果收口：写回历史摘要",
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
                                                                                    result.historySummary()))),
                                            new HistorySummaryWriteResult(
                                                    customerContext.normalizedConversationId(),
                                                    customerContext.normalizedChatUser(),
                                                    historyWriteRequest.historySummary(),
                                                    historyWriteRequest.lastIntent(),
                                                    historyWriteRequest.lastNextStep(),
                                                    0));
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
                                                                            safe(sessionSnapshot.sessionId()),
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
        if (!parsed.intentCode().isBlank() || !parsed.branch().isBlank()) {
            return parsed;
        }
        String fallbackIntent =
                looksLikeDirectRecommendation(executionContext.userMessage())
                        ? "recommendation_consulting"
                        : "general_consultation";
        return new IntentRouteStageResult(fallbackIntent, fallbackIntent, "fallback-from-heuristic");
    }

    private BusinessProcessStageResult executeBusinessProcessStage(
            ResolvedBlueprint blueprint,
            MultiStageExecutionContext executionContext,
            RuntimeContext runtimeContext,
            ExecutionProgressListener progressListener,
            Consumer<AgentEvent> agentEventListener,
            String resolvedIntentCode,
            RecoveryStageResult recoveryStageResult,
            IntentRouteStageResult intentStageResult) {
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
                                intentStageResult),
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
                                                    blueprint.blueprint().promptOrEmpty().soulMd(),
                                                    stageSkills,
                                                    blueprint.blueprintId() + ":" + stage.stageKey(),
                                                    blueprint.version())),
                            TenantWorkspaceProjector.Projection::toTimelineDetail);
                    Msg userMsg =
                            runStep(
                                    progressListener,
                                    "stage",
                                    stage.stageKey() + ".prompt_compose",
                                    stage.displayName() + "：组装输入",
                                    () -> buildUserMsg(userMessage),
                                    ignored ->
                                            Map.of(
                                                    "stageKey", safe(stage.stageKey()),
                                                    "userMessageLength", safe(userMessage).length()));
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
                blueprint.blueprint().promptOrEmpty().agentsMd(),
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
                blueprint.blueprint().promptOrEmpty().agentsMd(),
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
            IntentRouteStageResult intentStageResult) {
        return composeStageAgentsMd(
                blueprint.blueprint().promptOrEmpty().agentsMd(),
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
                        "画像摘要：" + safe(executionContext.profile().summary()),
                        "规则上下文：" + safe(executionContext.ruleContext().promptText()),
                        "允许推荐 productId："
                                + String.join(", ", executionContext.ruleContext().allowedProductIds())));
    }

    private String resolveStagePrompt(AgentBlueprint.StageSpec stage) {
        String inlinePrompt = safe(stage.prompt());
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
                safe(json.path("reason").asText("")));
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

    private static HistorySummaryWriteRequest buildMultiStageHistorySummaryWriteRequest(
            String userMessage,
            String reply,
            BusinessProcessStageResult businessStageResult,
            RecoveryDecision recovery,
            HistorySummarySnapshot history,
            String resolvedIntentCode,
            CustomerContext customerContext) {
        if (businessStageResult.historySummary().isBlank()) {
            return buildHistorySummaryWriteRequest(
                    userMessage, reply, recovery, history, resolvedIntentCode, customerContext);
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
                false,
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
        AgentBlueprint.Prompt prompt = blueprint.blueprint().promptOrEmpty();
        return new TenantWorkspaceProjector.Content(
                prompt.agentsMd(),
                prompt.soulMd(),
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

    private record IntentRouteStageResult(String intentCode, String branch, String reason) {
        static IntentRouteStageResult skipped() {
            return new IntentRouteStageResult("", "", "");
        }
    }

    private record BusinessProcessStageResult(
            String reply, String historySummary, String intentCode, String nextStep) {}

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
                                                progressListener, nodeId, stageKey, label);
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
        private final ExecutionProgressListener progressListener;
        private final String runNodeId;
        private final String runStageKey;
        private final String runLabel;
        private final Map<String, ModelCallContext> modelContextsByReplyId = new HashMap<>();
        private final Map<String, ToolCallContext> toolContextsByToolCallId = new HashMap<>();
        private final Map<String, StringBuilder> toolResultPreviewByToolCallId = new HashMap<>();
        private int modelIndex = 0;
        private int toolIndex = 0;
        private String reply = "";
        private final StringBuilder replyDelta = new StringBuilder();

        private AgentStreamAccumulator(
                ExecutionProgressListener progressListener,
                String runNodeId,
                String runStageKey,
                String runLabel) {
            this.progressListener = progressListener;
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
                TelemetryOpenAIFormatter.bindPendingSnapshot(replyId);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("replyId", replyId);
                detail.put("prompt", Map.of("messages", List.of()));
                detail.put("input", Map.of("replyId", replyId));
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
                TelemetryOpenAIFormatter.bindPendingSnapshot(replyId);
                Map<String, Object> llmTraceDetail =
                        TelemetryOpenAIFormatter.consumeTraceDetail(replyId);
                if (!llmTraceDetail.isEmpty()) {
                    detail.putAll(llmTraceDetail);
                } else {
                    detail.put("prompt", Map.of("messages", List.of()));
                    detail.put(
                            "output",
                            Map.of(
                                    "replyId", replyId,
                                    "usage",
                                            modelCallEndEvent.getUsage() == null
                                                    ? "-"
                                                    : modelCallEndEvent.getUsage().toString()));
                }
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                "model.call#" + context.index(),
                                "end",
                                context.displayLabel(),
                                elapsedMs(event),
                                detail));
                return;
            }

            if (event instanceof ToolCallStartEvent toolCallStartEvent) {
                int index = ++toolIndex;
                ToolCallContext context = captureToolCallContext(toolCallStartEvent, index);
                toolContextsByToolCallId.put(safe(toolCallStartEvent.getToolCallId()), context);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("toolName", context.toolName());
                detail.put("toolCallId", context.toolCallId());
                detail.put("replyId", context.replyId());
                detail.put(
                        "input",
                        Map.of(
                                "toolName", context.toolName(),
                                "toolCallId", context.toolCallId(),
                                "replyId", context.replyId(),
                                "note", "当前 AgentScope 事件未暴露原始工具参数"));
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

            if (event instanceof ToolCallEndEvent toolCallEndEvent) {
                ToolCallContext context = resolveToolCallContext(toolCallEndEvent);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("nodeId", context.nodeId());
                detail.put("parentNodeId", context.parentNodeId());
                detail.put("nodeLayer", "child_call");
                detail.put("stageKey", context.stageKey());
                detail.put("toolName", context.toolName());
                detail.put("toolCallId", context.toolCallId());
                detail.put("replyId", context.replyId());
                this.progressListener.onUpdate(
                        new ExecutionProgressUpdate(
                                "agent",
                                context.step(),
                                "end",
                                context.label(),
                                elapsedMs(event),
                                detail));
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
                String preview =
                        toolResultPreviewByToolCallId.containsKey(toolResultEndEvent.getToolCallId())
                                ? trimPreview(toolResultPreviewByToolCallId.get(toolResultEndEvent.getToolCallId()).toString())
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
                                elapsedMs(event),
                                detail));
                toolResultPreviewByToolCallId.remove(safe(toolResultEndEvent.getToolCallId()));
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

    private static Map<String, Object> toolDetail(
            Map<String, Object> input, Map<String, Object> output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("input", input);
        detail.put("output", output);
        detail.putAll(output);
        return detail;
    }

    private static Long elapsedMs(AgentEvent event) {
        return null;
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

    private static HistorySummaryWriteRequest buildHistorySummaryWriteRequest(
            String userMessage,
            String reply,
            RecoveryDecision recovery,
            HistorySummarySnapshot history,
            String resolvedIntentCode,
            CustomerContext customerContext) {
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
                false,
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

    private static int parseQueueVersion(String value) {
        try {
            return Integer.parseInt(safe(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
