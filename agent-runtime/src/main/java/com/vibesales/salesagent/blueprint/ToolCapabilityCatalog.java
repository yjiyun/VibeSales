package com.vibesales.salesagent.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本工程当前实际支持的 Tool 能力清单。
 *
 * <p>这份清单是 {@code Blueprint.tools.allow/deny} 静态校验的唯一依据。它刻意按"调用模式"分四类，
 * 因为这几类的运行时含义完全不同，混成一个大 Set 会让校验结果失去意义：
 *
 * <ol>
 *   <li><b>编排骨架 Tool</b>（{@link #ORCHESTRATION_TOOLS}）：会话准备、状态读取、收口写回。
 *       这些是 Java 编排层<b>固定调用</b>的普通 Java 类，不注册进 AgentScope {@code Toolkit}，
 *       模型看不见也无法自由裁量调用时机——它们承担会话治理与真值一致性，不适合交给 LLM 决定。
 *       Blueprint 可以声明"是否可用"，但调用时机仍在编排层。
 *   <li><b>Agent 内置 Tool</b>（{@link #AGENT_BUILTIN_TOOLS}）：真正注册进 Toolkit、模型可动态调用的。
 *       实测确认（{@code HarnessAgent.java:2374-2378}）：{@code disableMemoryTools} 未调用，所以
 *       {@code memory_search/memory_get/memory_save/session_search} 已注册；
 *       {@code load_skill_through_path}（{@code SkillLoadTool.TOOL_NAME}）由
 *       {@code HarnessSkillMiddleware} 在存在 skillRepository 时注册。
 *   <li><b>本工程注册的动态 Tool</b>（{@link #PROJECT_DYNAMIC_TOOLS}）：由本工程在共享 Agent 装配阶段
 *       主动注册进 Toolkit，模型可动态调用，但名字与行为由本工程自己维护，不属于框架默认内置集。
 *   <li><b>已实现但未接线的 Tool</b>（{@link #IMPLEMENTED_BUT_UNWIRED_TOOLS}）：Java 类已落地并有
 *       单测，但编排链里还没有调用点。Blueprint 声明它们只会得到 warning，不会得到"生效"的承诺。
 *   <li><b>MCP 提供的 Tool</b>（{@link #mcpProvidedTools()}）：由 {@code workspace/tools.json} 的
 *       {@code mcpServers[].enableTools} 注册，运行时经 MCP 传输到达，不是本工程的 Java 类。
 *       <b>这一类以前缺失，是一处真实的闸门事故</b>：P3C 装配侧
 *       （{@code agent-core/src/p3c/p3c.service.ts} 的 {@code listToolCandidates}）会把
 *       {@code crm_query} 写进 {@code tools.allow}，而本 catalog 只认 Java 工具，于是它落到
 *       {@code unsupported} → validator 报 "does not implement at all" → P4 dryRun HTTP 500，
 *       整个发布卡死。但 {@code crm_query} 其实是可用的：{@code workspace/tools.json} 就注册着它，
 *       且 {@code scripts/validate-agentteams.js} 强制要求这条注册存在。判定依据因此必须回到
 *       {@code tools.json} 本身，而不是在 Java 侧再抄一份名字——抄一份就会再次脱节。
 *   <li><b>已被显式关闭的 Tool</b>（{@link #DISABLED_BUILTIN_TOOLS}）：{@code SalesAgentFactory}
 *       调用了 {@code disableFilesystemTools()} / {@code disableShellTool()}，因此
 *       {@code read_file} 等文件工具与 {@code execute} <b>当前不存在</b>。这一点很关键：上游示例
 *       Blueprint 的 {@code tools.allow} 里带着 {@code read_file}（对应上游自检第9项"保留三个内置
 *       Tool"），而本工程出于最小权限把它关掉了——这是一处真实的契约冲突，必须显式暴露成 warning，
 *       而不是静默通过。
 * </ol>
 */
public final class ToolCapabilityCatalog {

    /** 编排骨架 Tool：Java 编排层固定调用，模型不可见。 */
    public static final Set<String> ORCHESTRATION_TOOLS =
            Set.of(
                    "createOrResumeSession",
                    "getHistorySummary",
                    "saveHistorySummary",
                    "getIntentQueue",
                    "syncIntentQueue",
                    "getCustomerProfile",
                    "getRuleContext");

    /**
     * 已实现但当前未接入主编排链的 Tool。
     *
     * <p>这两个是写回链的前两环（{@code mergeCustomerProfile → createDiagnosis →
     * saveHistorySummary → syncIntentQueue}）。Java 客户端与 Tool 类都已落地
     * （{@code MergeCustomerProfileTool} / {@code CreateDiagnosisTool}），但
     * {@code CustomerServiceOrchestratorAgent} 的收口阶段还没有调用点——它们需要
     * "本轮抽到的画像增量"与"推荐决策产物"作为入参，而这两样要等业务分支拆分后才有稳定来源。
     *
     * <p>放在这一类而不是直接进 {@link #ORCHESTRATION_TOOLS} 是刻意的：进了
     * {@code ORCHESTRATION_TOOLS} 就等于对 Blueprint 承诺"声明即生效"，而现在声明了并不会被调用。
     * 留在这里让 validator 出 warning，接线完成后再平移过去，warning 消失即为验收信号。
     */
    public static final Set<String> IMPLEMENTED_BUT_UNWIRED_TOOLS =
            Set.of("mergeCustomerProfile", "createDiagnosis");

    /** Agent 内置 Tool：已注册进 Toolkit，模型可动态调用。 */
    public static final Set<String> AGENT_BUILTIN_TOOLS =
            Set.of(
                    "memory_search",
                    "memory_get",
                    "memory_save",
                    "session_search",
                    "load_skill_through_path");

    /** 本工程主动注册到共享 Agent Toolkit 的动态 Tool。 */
    public static final Set<String> PROJECT_DYNAMIC_TOOLS = Set.of("retrieveKnowledgeBase");

    /** 被 {@code SalesAgentFactory} 显式关闭、当前不存在的内置 Tool。 */
    public static final Set<String> DISABLED_BUILTIN_TOOLS =
            Set.of(
                    "read_file",
                    "write_file",
                    "edit_file",
                    "grep_files",
                    "glob_files",
                    "list_files",
                    "execute");

    /** {@code workspace/tools.json} 的默认位置，可用系统属性覆盖（测试与非标准部署）。 */
    private static final String TOOLS_FILE_PROPERTY = "salesagent.tools.file";

    private static final String TOOLS_CLASSPATH_RESOURCE = "/tools.json";

    private static final List<String> TOOLS_FILE_CANDIDATES =
            List.of("workspace/tools.json", "agent-runtime/workspace/tools.json");

    /**
     * {@code "name"} 形式的字符串字面量。这里刻意用正则而不是引入 JSON 依赖：本类被静态校验
     * 路径调用，只需从 {@code enableTools} 数组里取出名字，不需要完整 JSON 语义。
     */
    private static final Pattern ENABLE_TOOLS_BLOCK =
            Pattern.compile("\"enableTools\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);

    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");

    /** 读一次就固定：校验期间 tools.json 不会变，避免每个工具名都读一次盘。 */
    private static volatile Set<String> mcpProvidedCache;

    private ToolCapabilityCatalog() {}

    /**
     * MCP 提供的 Tool 名称，来源是 {@code workspace/tools.json} 的
     * {@code mcpServers[].enableTools}。读不到文件时返回空集合——此时 MCP 工具会被判为
     * {@code unsupported}，与本类补齐前的行为一致，不会把未知名字静默放行。
     */
    public static Set<String> mcpProvidedTools() {
        Set<String> cached = mcpProvidedCache;
        if (cached == null) {
            synchronized (ToolCapabilityCatalog.class) {
                cached = mcpProvidedCache;
                if (cached == null) {
                    cached = parseEnableTools(readToolsJson());
                    mcpProvidedCache = cached;
                }
            }
        }
        return cached;
    }

    /** 供测试重置缓存，使 {@link #TOOLS_FILE_PROPERTY} 的改动生效。 */
    static void reloadMcpProvidedTools() {
        synchronized (ToolCapabilityCatalog.class) {
            mcpProvidedCache = null;
        }
    }

    private static String readToolsJson() {
        String override = System.getProperty(TOOLS_FILE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return readFile(Path.of(override.trim()));
        }
        for (String candidate : TOOLS_FILE_CANDIDATES) {
            String content = readFile(Path.of(candidate));
            if (content != null) {
                return content;
            }
        }
        try (InputStream stream =
                ToolCapabilityCatalog.class.getResourceAsStream(TOOLS_CLASSPATH_RESOURCE)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Set<String> parseEnableTools(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher blocks = ENABLE_TOOLS_BLOCK.matcher(json);
        while (blocks.find()) {
            Matcher quoted = QUOTED_NAME.matcher(blocks.group(1));
            while (quoted.find()) {
                String name = quoted.group(1).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return Set.copyOf(names);
    }

    /** 当前真正可用的全部 Tool 名称（骨架 + 内置 + MCP），不含未接入与已关闭的。 */
    public static Set<String> supported() {
        Set<String> all = new LinkedHashSet<>(ORCHESTRATION_TOOLS);
        all.addAll(AGENT_BUILTIN_TOOLS);
        all.addAll(PROJECT_DYNAMIC_TOOLS);
        all.addAll(mcpProvidedTools());
        return Set.copyOf(all);
    }

    public static String classify(String toolName) {
        String name = toolName == null ? "" : toolName.trim();
        if (ORCHESTRATION_TOOLS.contains(name)) {
            return "orchestration_fixed";
        }
        if (AGENT_BUILTIN_TOOLS.contains(name)) {
            return "agent_builtin_dynamic";
        }
        if (PROJECT_DYNAMIC_TOOLS.contains(name)) {
            return "project_dynamic";
        }
        if (mcpProvidedTools().contains(name)) {
            return "mcp_provided";
        }
        if (IMPLEMENTED_BUT_UNWIRED_TOOLS.contains(name)) {
            return "implemented_not_wired";
        }
        if (DISABLED_BUILTIN_TOOLS.contains(name)) {
            return "disabled_by_factory";
        }
        return "unsupported";
    }

    /** 把整份 allow/deny 投影成"每个 Tool 分别属于哪一类"的可观测输出。 */
    public static Map<String, String> classifyAll(List<String> toolNames) {
        Map<String, String> classified = new LinkedHashMap<>();
        if (toolNames == null) {
            return Map.of();
        }
        for (String toolName : toolNames) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            classified.put(toolName.trim(), classify(toolName));
        }
        return Map.copyOf(classified);
    }
}
