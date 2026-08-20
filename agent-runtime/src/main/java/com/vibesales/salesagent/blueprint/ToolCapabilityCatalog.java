package com.agentteams.salesagent.blueprint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本工程当前实际支持的 Tool 能力清单。
 *
 * <p>这份清单是 {@code Blueprint.tools.allow/deny} 静态校验的唯一依据。它刻意按"调用模式"分三类，
 * 因为这三类的运行时含义完全不同，混成一个大 Set 会让校验结果失去意义：
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

    /** 已实现但当前未接入主编排链的 Tool。 */
    public static final Set<String> IMPLEMENTED_BUT_UNWIRED_TOOLS = Set.of();

    /** Agent 内置 Tool：已注册进 Toolkit，模型可动态调用。 */
    public static final Set<String> AGENT_BUILTIN_TOOLS =
            Set.of(
                    "memory_search",
                    "memory_get",
                    "memory_save",
                    "session_search",
                    "load_skill_through_path");

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

    private ToolCapabilityCatalog() {}

    /** 当前真正可用的全部 Tool 名称（骨架 + 内置），不含未接入与已关闭的。 */
    public static Set<String> supported() {
        Set<String> all = new LinkedHashSet<>(ORCHESTRATION_TOOLS);
        all.addAll(AGENT_BUILTIN_TOOLS);
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
