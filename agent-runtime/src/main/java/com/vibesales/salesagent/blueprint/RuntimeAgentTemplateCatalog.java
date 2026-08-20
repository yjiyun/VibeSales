package com.agentteams.salesagent.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运行时 Agent 模板目录的最小实现。 */
public final class RuntimeAgentTemplateCatalog {

    private static final List<RuntimeAgentTemplate> BUILT_IN_TEMPLATES =
            List.of(
                    new RuntimeAgentTemplate(
                            "BEAUTY_SKINCARE",
                            "美妆护肤测试",
                            "面向 BEAUTY_SKINCARE 场景的常用测试模板，默认用于 yjiyuncom/test 联调。",
                            "美妆护肤",
                            "{\"runtimeAgentId\":\"BEAUTY_SKINCARE\",\"clientCode\":\"yjiyuncom\",\"cluster\":\"test\",\"sceneCodes\":[\"BEAUTY_SKINCARE\"]}",
                            true,
                            15,
                            "code_builtin"),
                    new RuntimeAgentTemplate(
                            "sales_consultant",
                            "销售顾问",
                            "面向销售转化、推荐搭配与成交推进的通用模板。",
                            "销售",
                            "{\"runtimeAgentId\":\"sales_consultant\",\"sceneCodes\":[\"BEAUTY_SKINCARE\"]}",
                            true,
                            10,
                            "code_builtin"),
                    new RuntimeAgentTemplate(
                            "customer_service",
                            "客服接待",
                            "面向售前接待、常见问题答疑与服务跟进的通用模板。",
                            "客服",
                            "{\"runtimeAgentId\":\"customer_service\",\"sceneCodes\":[\"BEAUTY_SKINCARE\"]}",
                            true,
                            20,
                            "code_builtin"),
                    new RuntimeAgentTemplate(
                            "hr_recruiter",
                            "HR 招聘",
                            "面向招聘咨询、候选人初筛与岗位答疑的通用模板。",
                            "招聘",
                            "{\"runtimeAgentId\":\"hr_recruiter\",\"sceneCodes\":[\"HIRING\"]}",
                            true,
                            30,
                            "code_builtin"));

    public List<RuntimeAgentTemplate> listTemplates(List<Map<String, String>> scopes) {
        LinkedHashMap<String, RuntimeAgentTemplate> merged = new LinkedHashMap<>();
        for (RuntimeAgentTemplate template : BUILT_IN_TEMPLATES) {
            merged.put(template.runtimeAgentId(), template);
        }
        for (Map<String, String> scope : scopes) {
            RuntimeAgentTemplate discovered = fromScope(scope);
            if (discovered == null) {
                continue;
            }
            merged.merge(discovered.runtimeAgentId(), discovered, RuntimeAgentTemplateCatalog::merge);
        }
        List<RuntimeAgentTemplate> result = new ArrayList<>(merged.values());
        result.sort(
                Comparator.comparingInt(RuntimeAgentTemplate::sortOrder)
                        .thenComparing(RuntimeAgentTemplate::runtimeAgentId));
        return List.copyOf(result);
    }

    private static RuntimeAgentTemplate merge(
            RuntimeAgentTemplate existing, RuntimeAgentTemplate discovered) {
        return new RuntimeAgentTemplate(
                existing.runtimeAgentId(),
                firstNonBlank(existing.displayName(), discovered.displayName()),
                firstNonBlank(existing.description(), discovered.description()),
                firstNonBlank(existing.category(), discovered.category()),
                firstNonBlank(discovered.blueprintExample(), existing.blueprintExample()),
                existing.enabled() || discovered.enabled(),
                Math.min(existing.sortOrder(), discovered.sortOrder()),
                firstNonBlank(existing.source(), discovered.source()));
    }

    private static RuntimeAgentTemplate fromScope(Map<String, String> scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        String runtimeAgentId = safe(scope.get("runtimeAgentId"));
        if (runtimeAgentId.isEmpty()) {
            return null;
        }
        String clientCode = safe(scope.get("clientCode"));
        String cluster = safe(scope.get("cluster"));
        String blueprintId = safe(scope.get("blueprintId"));
        String version = safe(scope.get("version"));
        return new RuntimeAgentTemplate(
                runtimeAgentId,
                guessDisplayName(runtimeAgentId),
                "从当前 Blueprint 作用域自动发现的运行时 Agent 模板。",
                guessCategory(runtimeAgentId),
                buildBlueprintExample(runtimeAgentId, clientCode, cluster, blueprintId, version),
                true,
                100 + builtInSortOffset(runtimeAgentId),
                "blueprint_scope");
    }

    private static int builtInSortOffset(String runtimeAgentId) {
        return switch (safe(runtimeAgentId)) {
            case "BEAUTY_SKINCARE" -> 15;
            case "sales_consultant" -> 10;
            case "customer_service" -> 20;
            case "hr_recruiter" -> 30;
            default -> 90;
        };
    }

    private static String buildBlueprintExample(
            String runtimeAgentId,
            String clientCode,
            String cluster,
            String blueprintId,
            String version) {
        return "{"
                + "\"runtimeAgentId\":\""
                + escape(runtimeAgentId)
                + "\",\"clientCode\":\""
                + escape(clientCode)
                + "\",\"cluster\":\""
                + escape(cluster)
                + "\",\"blueprintId\":\""
                + escape(blueprintId)
                + "\",\"version\":\""
                + escape(version)
                + "\"}";
    }

    private static String guessDisplayName(String runtimeAgentId) {
        String normalized = safe(runtimeAgentId).toLowerCase();
        if (normalized.contains("sales")) {
            return "销售顾问";
        }
        if (normalized.contains("beauty_skincare")) {
            return "美妆护肤测试";
        }
        if (normalized.contains("customer")
                || normalized.contains("service")
                || normalized.contains("_cs")
                || normalized.contains("客服")) {
            return "客服接待";
        }
        if (normalized.contains("hr") || normalized.contains("recruit")) {
            return "HR 招聘";
        }
        return runtimeAgentId;
    }

    private static String guessCategory(String runtimeAgentId) {
        String normalized = safe(runtimeAgentId).toLowerCase();
        if (normalized.contains("sales")) {
            return "销售";
        }
        if (normalized.contains("beauty_skincare")) {
            return "美妆护肤";
        }
        if (normalized.contains("customer")
                || normalized.contains("service")
                || normalized.contains("_cs")
                || normalized.contains("客服")) {
            return "客服";
        }
        if (normalized.contains("hr") || normalized.contains("recruit")) {
            return "招聘";
        }
        return "已发布资产";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return safe(preferred).isEmpty() ? safe(fallback) : preferred.trim();
    }

    private static String escape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record RuntimeAgentTemplate(
            String runtimeAgentId,
            String displayName,
            String description,
            String category,
            String blueprintExample,
            boolean enabled,
            int sortOrder,
            String source) {}
}
