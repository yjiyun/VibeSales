package com.vibesales.salesagent.blueprint;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 把已解析的租户蓝图投影成 workspace 里的实际文件，供<b>共享</b> Agent 每请求按命名空间读取。
 *
 * <p>这是"单 Agent 服务多租户"的落地件。harness 侧的机制已实测确认（见
 * {@code SingleAgentMultiTenantProbeTest}）：
 *
 * <ul>
 *   <li>{@code WorkspaceContextMiddleware.onSystemPrompt} 每请求调
 *       {@code WorkspaceManager.readAgentsMd(rc)}，按 {@code IsolationScope.USER} 命名空间取
 *       {@code <ns>/AGENTS.md}；
 *   <li>默认注册的 {@code WorkspaceSkillRepository(fs, "skills", currentRcSupplier, ...)} 是
 *       {@code RuntimeContextSkillRepository}，{@code HarnessSkillMiddleware.mergeRepositories(ctx)}
 *       每请求重新枚举 {@code <ns>/skills/<name>/SKILL.md}。
 * </ul>
 *
 * <p><b>为什么必须 fail-closed</b>：{@code WorkspaceManager.readWithOverride} 先读命名空间下的文件，
 * 读不到会<b>回落到 workspace 根目录的同名文件</b>（实测其实现：{@code readTextThroughFilesystem}
 * 为空时 {@code readFileQuietly(workspace.resolve(relativePath))}，后者不带命名空间）。也就是说投影
 * 一旦漏写，表现不是"提示词为空"而是"读到别人的/全局的提示词"——多租户里最危险的一类静默故障。
 * 所以这里写完必须逐个校验落盘结果，任何一步不成立就抛异常，让请求失败而不是带着错误提示词继续。
 *
 * <p>投影是幂等的覆盖写：同一租户重复请求会重写同样的内容。旧 Skill 目录会先清掉，否则蓝图删掉一个
 * Skill 之后，磁盘上的残留还会继续被枚举进提示词。
 */
public final class TenantWorkspaceProjector {

    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String SOUL_FILE = "SOUL.md";
    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";

    private final Path workspaceRoot;

    public TenantWorkspaceProjector(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** 命中蓝图时的投影：Prompt 取蓝图里的 {@code agentsMd + soulMd}，Skill 取蓝图投影出的那批。 */
    public Projection project(String namespace, ResolvedBlueprint blueprint) {
        return project(
                namespace,
                new Content(
                        blueprint.agentsMd(),
                        blueprint.soulMd(),
                        blueprint.skills().skills(),
                        blueprint.blueprintId(),
                        blueprint.version()));
    }

    /**
     * 把本轮该生效的提示词与 Skill 投影到 {@code <workspaceRoot>/<namespace>/} 下。
     *
     * <p>没命中蓝图的租户也必须走这里。共享 Agent 上不能挂任何构造期 Skill 仓库（构造期仓库对所有
     * 租户可见，实测见类注释引用的探测程序），所以"兜底那套默认 Skill"同样只能靠投影下发。
     *
     * @param namespace {@code RuntimeContext.userId}，形如 {@code yjiyuncom/test/vip-8821}；必须与
     *     {@code IsolationScope.USER} 推出的命名空间完全一致，否则 Agent 读的是另一个目录
     * @return 投影留痕，供时间线证明"这轮读的是哪个目录、写了哪些文件"
     */
    public Projection project(String namespace, Content content) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException(
                    "tenant workspace projection requires a non-blank namespace (RuntimeContext.userId)");
        }
        Path tenantRoot = resolveTenantRoot(namespace);
        try {
            Files.createDirectories(tenantRoot);
            Files.writeString(tenantRoot.resolve(AGENTS_FILE), content.agentsMd());
            Files.writeString(tenantRoot.resolve(SOUL_FILE), content.soulMd());

            Path skillsRoot = tenantRoot.resolve(SKILLS_DIR);
            deleteRecursively(skillsRoot);
            List<String> projectedSkills = new ArrayList<>();
            for (AgentSkill skill : content.skills()) {
                Path skillDir = skillsRoot.resolve(skill.getName());
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve(SKILL_FILE), SkillMarkdown.render(skill));
                writeSkillResources(skillDir, skill);
                projectedSkills.add(skill.getName());
            }
            return verify(namespace, tenantRoot, content, projectedSkills);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "failed to project tenant workspace for namespace '" + namespace + "'",
                    exception);
        }
    }

    /**
     * 一轮投影的内容。
     *
     * @param sourceId 内容来源标识：命中蓝图时是 {@code blueprintId}，兜底时是一个固定标记
     * @param sourceVersion 蓝图版本；兜底时为 0
     */
    public record Content(
            String agentsMd,
            String soulMd,
            List<AgentSkill> skills,
            String sourceId,
            int sourceVersion) {

        public Content {
            agentsMd = agentsMd == null ? "" : agentsMd;
            soulMd = soulMd == null ? "" : soulMd;
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    /**
     * 落盘校验。
     *
     * <p>不是"防御性编程"式的多余检查：漏投影会静默回落到根目录的共享 AGENTS.md（见类注释），
     * 所以"写了"和"读得到"必须分开确认一次。
     */
    private Projection verify(
            String namespace, Path tenantRoot, Content content, List<String> skills) {
        Path agentsMd = tenantRoot.resolve(AGENTS_FILE);
        if (!Files.isRegularFile(agentsMd)) {
            throw new IllegalStateException(
                    "tenant workspace projection did not produce " + agentsMd);
        }
        long agentsMdBytes;
        try {
            agentsMdBytes = Files.size(agentsMd);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to stat " + agentsMd, exception);
        }
        if (agentsMdBytes == 0L) {
            throw new IllegalStateException(
                    "tenant workspace projection wrote an empty "
                            + AGENTS_FILE
                            + "; the agent would silently fall back to the shared workspace root prompt");
        }
        Path soulMd = tenantRoot.resolve(SOUL_FILE);
        if (!Files.isRegularFile(soulMd)) {
            throw new IllegalStateException(
                    "tenant workspace projection did not produce " + soulMd);
        }
        for (String skill : skills) {
            Path skillMd = tenantRoot.resolve(SKILLS_DIR).resolve(skill).resolve(SKILL_FILE);
            if (!Files.isRegularFile(skillMd)) {
                throw new IllegalStateException(
                        "tenant workspace projection did not produce " + skillMd);
            }
        }
        return new Projection(
                namespace,
                workspaceRoot.relativize(tenantRoot).toString(),
                content.sourceId(),
                content.sourceVersion(),
                agentsMdBytes,
                List.copyOf(skills));
    }

    /**
     * 命名空间目录解析，带越界校验。
     *
     * <p>{@code namespace} 来自请求参数拼出的 {@code clientCode/cluster/chatUser}，把它当路径用就必须
     * 防 {@code ../} 逃逸——否则一个构造过的 clientCode 就能写到 workspace 之外。
     */
    private Path resolveTenantRoot(String namespace) {
        Path resolved = workspaceRoot.resolve(namespace).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalStateException(
                    "tenant namespace '" + namespace + "' escapes the workspace root");
        }
        if (resolved.equals(workspaceRoot)) {
            throw new IllegalStateException(
                    "tenant namespace '"
                            + namespace
                            + "' resolves to the workspace root itself; that file is the cross-tenant"
                            + " fallback and must never be written per request");
        }
        return resolved;
    }

    private static void writeSkillResources(Path skillDir, AgentSkill skill) throws IOException {
        Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String relativePath = entry.getKey();
            if (relativePath == null
                    || relativePath.isBlank()
                    || relativePath.startsWith("/")
                    || relativePath.contains("..")) {
                continue;
            }
            Path target = skillDir.resolve(relativePath).normalize();
            if (!target.startsWith(skillDir)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue() == null ? "" : entry.getValue());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 本轮投影留痕。键顺序服务于工作台"补充明细"只渲染前 8 键的约束。 */
    public record Projection(
            String namespace,
            String relativePath,
            String sourceId,
            int sourceVersion,
            long agentsMdBytes,
            List<String> skills) {

        public Map<String, Object> toTimelineDetail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("namespace", namespace);
            detail.put("relativePath", relativePath);
            detail.put("agentsMdBytes", agentsMdBytes);
            detail.put("skills", skills);
            detail.put("sourceId", sourceId);
            detail.put("sourceVersion", sourceVersion);
            return java.util.Collections.unmodifiableMap(detail);
        }
    }

    /**
     * {@code AgentSkill} 转回 {@code SKILL.md} 文本。
     *
     * <p>格式必须能被 {@code SkillUtil.createFrom} 读回去——{@code WorkspaceSkillRepository} 就是用它
     * 解析磁盘文件的。这里对齐 harness 自己的 {@code WorkspaceSkillRepository.toMarkdown}：YAML front
     * matter 只写 {@code name} / {@code description} 加其余元数据，正文原样跟在后面。
     */
    static final class SkillMarkdown {

        private SkillMarkdown() {}

        static String render(AgentSkill skill) {
            StringBuilder builder = new StringBuilder();
            builder.append("---\n");
            builder.append("name: ").append(scalar(skill.getName())).append('\n');
            builder.append("description: ").append(scalar(skill.getDescription())).append('\n');
            Map<String, Object> metadata = skill.getMetadata();
            if (metadata != null) {
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || "name".equals(key) || "description".equals(key)) {
                        continue;
                    }
                    if (entry.getValue() == null) {
                        continue;
                    }
                    builder.append(key).append(": ").append(scalar(String.valueOf(entry.getValue()))).append('\n');
                }
            }
            builder.append("---\n");
            builder.append(skill.getSkillContent() == null ? "" : skill.getSkillContent());
            return builder.toString();
        }

        /**
         * YAML 标量转义。
         *
         * <p>Skill 的 description 是租户在蓝图里自由填的中文短句，很可能带 {@code :} 或 {@code #}。
         * 裸写会让 front matter 解析出错，进而整个 Skill 静默消失，所以统一按双引号标量输出。
         */
        private static String scalar(String value) {
            String safe = value == null ? "" : value;
            return '"' + safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"';
        }
    }
}
