package com.agentteams.salesagent.blueprint;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按租户作用域查找并解析 Blueprint。
 *
 * <p>租户作用域是 {@code clientCode + cluster} 两个维度，运行期都从调用参数里取。解析顺序是明确的
 * 两级：
 *
 * <ol>
 *   <li><b>精确命中</b>：按 {@code clientCode + cluster} 找该作用域专属蓝图
 *   <li><b>降级</b>：精确命中不到时，才退回只按 {@code clientCode} 找该租户默认蓝图
 *       （索引项 {@code cluster} 为空的那条）
 * </ol>
 *
 * <p>降级只发生在"这个 cluster 没有专属蓝图"的情况，不是"cluster 不参与路由"——两者区别在可观测性上
 * 必须能看出来，所以 {@link ResolvedBlueprint#matchLevel()} 会记录本轮是精确命中还是降级命中，
 * 否则上游配错 cluster 时会误以为专属蓝图生效了。
 *
 * <p>读取来源由 {@link BlueprintSource} 决定。当前默认实现仍然是 classpath 静态资源，但后续可以切到
 * 远端已发布 Blueprint 拉取，而不影响后面的校验、投影和运行主链。
 */
public final class AgentBlueprintRepository {

    private final BlueprintSource source;
    private final AgentBlueprintValidator validator;
    private final BlueprintPromptProjector promptProjector;
    private final BlueprintSkillProjector skillProjector;
    private final BlueprintRuleProjector ruleProjector;

    public AgentBlueprintRepository() {
        this(new ClasspathBlueprintSource());
    }

    public AgentBlueprintRepository(String indexPath) {
        this(new ClasspathBlueprintSource(indexPath));
    }

    public AgentBlueprintRepository(BlueprintSource source) {
        this.source = source;
        this.validator = new AgentBlueprintValidator();
        this.promptProjector = new BlueprintPromptProjector();
        this.skillProjector = new BlueprintSkillProjector();
        this.ruleProjector = new BlueprintRuleProjector();
    }

    /** 是否存在任何可用蓝图；为 {@code false} 时编排层应走兜底提示词路径。 */
    public boolean hasAnyBlueprint() {
        return source.hasAnyBlueprint();
    }

    /** 索引里登记的全部租户作用域，供 debug 接口列出可选项。 */
    public List<Map<String, String>> listScopes() {
        return source.listScopes();
    }

    public List<BlueprintCatalogItem> listCatalog(
            String clientCode, String cluster, String sceneCode, String runtimeAgentId, boolean selectableOnly) {
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        String normalizedSceneCode = safe(sceneCode);
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        List<BlueprintCatalogItem> items = new ArrayList<>();
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (BlueprintSource blueprintSource : flattenSources(source)) {
            for (Map<String, String> scope : blueprintSource.listScopes()) {
                String scopeClientCode = safe(scope.get("clientCode"));
                String scopeCluster = safe(scope.get("cluster"));
                String scopeSceneCode = firstNonBlank(safe(scope.get("sceneCode")), normalizedSceneCode);
                String scopeRuntimeAgentId = safe(scope.get("runtimeAgentId"));
                String scopeVersion = safe(scope.get("version"));
                if (!normalizedClientCode.isEmpty() && !normalizedClientCode.equals(scopeClientCode)) {
                    continue;
                }
                if (!normalizedCluster.isEmpty()
                        && !scopeCluster.isEmpty()
                        && !normalizedCluster.equals(scopeCluster)) {
                    continue;
                }
                if (!normalizedRuntimeAgentId.isEmpty()
                        && !normalizedRuntimeAgentId.equals(scopeRuntimeAgentId)) {
                    continue;
                }
                Optional<BlueprintSource.BlueprintHandle> resolved =
                        blueprintSource.resolve(
                                scopeClientCode,
                                scopeCluster,
                                scopeSceneCode,
                                scopeRuntimeAgentId,
                                scopeVersion);
                if (resolved.isEmpty()) {
                    if (!selectableOnly) {
                        String selectionId =
                                buildSelectionId(
                                        sourceTypeOf(blueprintSource),
                                        scopeClientCode,
                                        scopeCluster,
                                        scopeSceneCode,
                                        scopeRuntimeAgentId,
                                        scopeVersion);
                        if (dedup.add(selectionId)) {
                            items.add(
                                    new BlueprintCatalogItem(
                                            selectionId,
                                            sourceTypeOf(blueprintSource),
                                            sourceLabelOf(blueprintSource),
                                            scopeClientCode,
                                            scopeCluster,
                                            scopeSceneCode,
                                            scopeRuntimeAgentId,
                                            scopeVersion,
                                            safe(scope.get("blueprintId")),
                                            "",
                                            0,
                                            firstNonBlank(
                                                    safe(scope.get("blueprintId")), scopeRuntimeAgentId),
                                            matchHints(scopeClientCode, scopeCluster),
                                            false,
                                            safe(scope.get("path"))));
                        }
                    }
                    continue;
                }
                AgentBlueprint blueprint = resolved.get().blueprint();
                String itemSceneCode =
                        firstNonBlank(scopeSceneCode, firstScenario(blueprint), normalizedSceneCode);
                String selectionId =
                        buildSelectionId(
                                resolved.get().sourceType(),
                                safe(blueprint.clientCode()),
                                safe(blueprint.clusterOrEmpty()),
                                itemSceneCode,
                                safe(blueprint.runtimeAgentId()),
                                String.valueOf(blueprint.version()));
                if (!dedup.add(selectionId)) {
                    continue;
                }
                items.add(
                        new BlueprintCatalogItem(
                                selectionId,
                                resolved.get().sourceType(),
                                sourceLabel(resolved.get().sourceType()),
                                safe(blueprint.clientCode()),
                                safe(blueprint.clusterOrEmpty()),
                                itemSceneCode,
                                safe(blueprint.runtimeAgentId()),
                                String.valueOf(blueprint.version()),
                                safe(blueprint.blueprintId()),
                                blueprint.runtimeModeOrDefault(),
                                blueprint.stages().size(),
                                displayNameOf(blueprint),
                                matchHints(blueprint.clientCode(), blueprint.clusterOrEmpty()),
                                true,
                                resolved.get().sourceId()));
            }
        }
        return List.copyOf(items);
    }

    /**
     * 解析该租户作用域的 Blueprint 并完成投影。
     *
     * @throws IllegalStateException 校验失败时直接抛出——装配阶段报错，不留到运行时静默忽略
     */
    public Optional<ResolvedBlueprint> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return resolve(clientCode, cluster, sceneCode, runtimeAgentId, version, BlueprintSelection.scoped());
    }

    public Optional<ResolvedBlueprint> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection) {
        return resolve(clientCode, cluster, sceneCode, runtimeAgentId, version, selection, "");
    }

    /**
     * 带调用方 {@code userId} 的解析入口。
     *
     * <p>供真实试聊路径使用：{@code userId} 是当前登录/发起对话的身份，交给来源层去核对
     * {@code agent_binding}（谁批准发布、谁在跟这份蓝图对话）。调试/管理类调用（不代表某个真实用户）
     * 走上面不带 {@code userId} 的重载即可，内部按空字符串处理，直接降级为按租户作用域查找。
     */
    public Optional<ResolvedBlueprint> resolve(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection,
            String userId) {
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        String normalizedSceneCode = safe(sceneCode);
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        String normalizedVersion = safe(version);
        String normalizedUserId = safe(userId);
        Optional<SelectionTarget> pinnedTarget = selectionTarget(selection);
        if (pinnedTarget.isPresent()) {
            SelectionTarget target = pinnedTarget.get();
            normalizedClientCode = target.clientCode();
            normalizedCluster = target.cluster();
            normalizedSceneCode = target.sceneCode();
            normalizedRuntimeAgentId = target.runtimeAgentId();
            normalizedVersion = target.version();
        }
        if (normalizedClientCode.isEmpty()) {
            return Optional.empty();
        }
        Optional<BlueprintSource.BlueprintHandle> resolved =
                pinnedTarget.isPresent()
                        ? resolveFromSelectionTarget(pinnedTarget.get())
                        : source.resolve(
                                normalizedClientCode,
                                normalizedCluster,
                                normalizedSceneCode,
                                normalizedRuntimeAgentId,
                                normalizedVersion,
                                normalizedUserId);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        BlueprintSource.BlueprintHandle handle = resolved.get();
        AgentBlueprint blueprint = handle.blueprint();
        BlueprintValidationReport report =
                validator.validate(blueprint, normalizedClientCode, safe(handle.matchedCluster()));
        if (!report.valid()) {
            throw new IllegalStateException(
                    "blueprint " + handle.sourceId() + " failed validation: " + report.errorSummary());
        }
        BlueprintPromptProjector.Projection prompt = promptProjector.projectPrompt(blueprint);
        BlueprintSkillProjector.Projection projection = skillProjector.project(blueprint);
        BlueprintRuleProjector.Projection rules = ruleProjector.project(blueprint);
        return Optional.of(
                new ResolvedBlueprint(
                        blueprint,
                        normalizedClientCode,
                        normalizedCluster,
                        normalizedSceneCode,
                        normalizedRuntimeAgentId,
                        normalizedVersion,
                        pinnedTarget.map(SelectionTarget::selectionId).orElse(""),
                        handle.matchLevel(),
                        handle.sourceType(),
                        handle.sourceId(),
                        prompt.agentsMd(),
                        prompt.soulMd(),
                        prompt.systemPrompt(),
                        projection,
                        rules,
                        report));
    }

    /**
     * 用调用方直传的 Blueprint（不经 {@link BlueprintSource} 查询）走同一套校验 + 投影尾段。
     *
     * <p>供 {@code /api/v1/dryrun} 使用：P4 dry-run 传入的是当次生成、尚未（或刚)持久化的 Blueprint
     * 原文，不能也不需要按 clientCode/cluster 反查——直接用这份原文校验、投影，构造出与
     * {@link #resolve} 完全同构的 {@link ResolvedBlueprint}，交给编排层跑同一条执行主链。
     *
     * @throws IllegalStateException 校验失败时直接抛出，与 {@link #resolve} 同口径
     */
    public ResolvedBlueprint resolveAdHoc(AgentBlueprint blueprint) {
        String clientCode = safe(blueprint.clientCode());
        String cluster = blueprint.clusterOrEmpty();
        BlueprintValidationReport report = validator.validate(blueprint, clientCode, cluster);
        if (!report.valid()) {
            throw new IllegalStateException(
                    "ad-hoc blueprint " + safe(blueprint.blueprintId()) + " failed validation: "
                            + report.errorSummary());
        }
        BlueprintPromptProjector.Projection prompt = promptProjector.projectPrompt(blueprint);
        BlueprintSkillProjector.Projection projection = skillProjector.project(blueprint);
        BlueprintRuleProjector.Projection rules = ruleProjector.project(blueprint);
        return new ResolvedBlueprint(
                blueprint,
                clientCode,
                cluster,
                "",
                safe(blueprint.runtimeAgentId()),
                String.valueOf(blueprint.version()),
                "",
                AgentBlueprintRepository.MATCH_EXACT,
                "ad_hoc",
                "ad_hoc:" + safe(blueprint.blueprintId()),
                prompt.agentsMd(),
                prompt.soulMd(),
                prompt.systemPrompt(),
                projection,
                rules,
                report);
    }

    /** 只做加载 + 校验，不做投影；供 debug 接口在校验失败时也能返回原始内容与错误清单。 */
    public Optional<Inspection> inspect(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return inspect(clientCode, cluster, sceneCode, runtimeAgentId, version, BlueprintSelection.scoped());
    }

    public Optional<Inspection> inspect(
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version,
            BlueprintSelection selection) {
        String normalizedClientCode = safe(clientCode);
        String normalizedCluster = safe(cluster);
        String normalizedSceneCode = safe(sceneCode);
        String normalizedRuntimeAgentId = safe(runtimeAgentId);
        String normalizedVersion = safe(version);
        Optional<SelectionTarget> pinnedTarget = selectionTarget(selection);
        if (pinnedTarget.isPresent()) {
            SelectionTarget target = pinnedTarget.get();
            normalizedClientCode = target.clientCode();
            normalizedCluster = target.cluster();
            normalizedSceneCode = target.sceneCode();
            normalizedRuntimeAgentId = target.runtimeAgentId();
            normalizedVersion = target.version();
        }
        Optional<BlueprintSource.BlueprintHandle> resolved =
                pinnedTarget.isPresent()
                        ? resolveFromSelectionTarget(pinnedTarget.get())
                        : source.resolve(
                                normalizedClientCode,
                                normalizedCluster,
                                normalizedSceneCode,
                                normalizedRuntimeAgentId,
                                normalizedVersion);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        BlueprintSource.BlueprintHandle handle = resolved.get();
        AgentBlueprint blueprint = handle.blueprint();
        return Optional.of(
                new Inspection(
                        blueprint,
                        handle.sourceId(),
                        handle.sourceType(),
                        pinnedTarget.map(SelectionTarget::selectionId).orElse(""),
                        handle.matchLevel(),
                        validator.validate(
                                blueprint, normalizedClientCode, safe(handle.matchedCluster()))));
    }

    private Optional<SelectionTarget> selectionTarget(BlueprintSelection selection) {
        if (selection == null || !selection.isPinned()) {
            return Optional.empty();
        }
        return Optional.of(parseSelectionId(selection.selectionId()));
    }

    private Optional<BlueprintSource.BlueprintHandle> resolveFromSelectionTarget(SelectionTarget target) {
        for (BlueprintSource candidate : flattenSources(source)) {
            if (!target.sourceType().equals(sourceTypeOf(candidate))) {
                continue;
            }
            Optional<BlueprintSource.BlueprintHandle> resolved =
                    candidate.resolve(
                            target.clientCode(),
                            target.cluster(),
                            target.sceneCode(),
                            target.runtimeAgentId(),
                            target.version());
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private List<BlueprintSource> flattenSources(BlueprintSource current) {
        if (current instanceof CompositeBlueprintSource composite) {
            List<BlueprintSource> flattened = new ArrayList<>();
            flattened.addAll(flattenSources(composite.primary()));
            flattened.addAll(flattenSources(composite.fallback()));
            return List.copyOf(flattened);
        }
        return List.of(current);
    }

    private static SelectionTarget parseSelectionId(String selectionId) {
        String normalized = safe(selectionId);
        String[] parts = normalized.split(":", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid selectionId: " + normalized);
        }
        return new SelectionTarget(
                normalized,
                decodeSegment(parts[0]),
                decodeSegment(parts[1]),
                decodeSegment(parts[2]),
                decodeSegment(parts[3]),
                decodeSegment(parts[4]),
                decodeSegment(parts[5]));
    }

    public static String buildSelectionId(
            String sourceType,
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {
        return encodeSegment(sourceType)
                + ":"
                + encodeSegment(clientCode)
                + ":"
                + encodeSegment(cluster)
                + ":"
                + encodeSegment(sceneCode)
                + ":"
                + encodeSegment(runtimeAgentId)
                + ":"
                + encodeSegment(version);
    }

    private static String encodeSegment(String value) {
        String normalized = safe(value);
        if (normalized.isEmpty()) {
            return "~";
        }
        return URLEncoder.encode(normalized, StandardCharsets.UTF_8);
    }

    private static String decodeSegment(String value) {
        if (value == null || value.equals("~")) {
            return "";
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sourceTypeOf(BlueprintSource source) {
        if (source instanceof ClasspathBlueprintSource) {
            return ClasspathBlueprintSource.SOURCE_TYPE;
        }
        if (source instanceof JdbcPublishedBlueprintSource) {
            return JdbcPublishedBlueprintSource.SOURCE_TYPE;
        }
        if (source instanceof RemotePublishedBlueprintSource) {
            return RemotePublishedBlueprintSource.SOURCE_TYPE;
        }
        return "unknown";
    }

    private static String sourceLabelOf(BlueprintSource source) {
        return sourceLabel(sourceTypeOf(source));
    }

    private static String sourceLabel(String sourceType) {
        return switch (safe(sourceType)) {
            case ClasspathBlueprintSource.SOURCE_TYPE -> "Classpath 静态蓝图";
            case JdbcPublishedBlueprintSource.SOURCE_TYPE -> "数据库已发布蓝图";
            case RemotePublishedBlueprintSource.SOURCE_TYPE -> "远端发布蓝图";
            default -> "未知来源";
        };
    }

    private static String displayNameOf(AgentBlueprint blueprint) {
        String industry = safe(blueprint.metaOrEmpty().industry());
        return !industry.isEmpty() ? industry : firstNonBlank(safe(blueprint.blueprintId()), safe(blueprint.runtimeAgentId()));
    }

    private static String firstScenario(AgentBlueprint blueprint) {
        List<String> scenarios = blueprint.metaOrEmpty().scenarios();
        return scenarios.isEmpty() ? "" : safe(scenarios.get(0));
    }

    private static List<String> matchHints(String clientCode, String cluster) {
        List<String> hints = new ArrayList<>();
        if (!safe(clientCode).isEmpty()) {
            hints.add("clientCode=" + safe(clientCode));
        }
        if (!safe(cluster).isEmpty()) {
            hints.add("cluster=" + safe(cluster));
        }
        return List.copyOf(hints);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** 请求的 {@code clientCode + cluster} 都精确命中了专属蓝图。 */
    public static final String MATCH_EXACT = "exact";

    /** 请求没带 {@code cluster}，直接用该租户默认蓝图。 */
    public static final String MATCH_DEFAULT = "tenant_default";

    /** 请求带了 {@code cluster} 但没有对应蓝图，<b>降级</b>到该租户默认蓝图。 */
    public static final String MATCH_FALLBACK = "cluster_fallback";
    public record Inspection(
            AgentBlueprint blueprint,
            String sourcePath,
            String sourceType,
            String selectionId,
            String matchLevel,
            BlueprintValidationReport validation) {}

    private record SelectionTarget(
            String selectionId,
            String sourceType,
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String version) {}
}
