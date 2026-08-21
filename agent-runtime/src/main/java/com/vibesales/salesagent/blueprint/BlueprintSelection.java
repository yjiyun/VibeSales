package com.vibesales.salesagent.blueprint;

/**
 * 测试链路使用的 Blueprint 选择器。
 *
 * <p>正式业务链仍以作用域解析为主；只有调试/测试入口才会显式带上该对象。
 */
public record BlueprintSelection(String selectorMode, String selectionId) {

    public static final String MODE_SCOPED = "scoped";
    public static final String MODE_PINNED = "pinned";

    public BlueprintSelection {
        selectorMode = safe(selectorMode);
        selectionId = safe(selectionId);
    }

    public static BlueprintSelection scoped() {
        return new BlueprintSelection(MODE_SCOPED, "");
    }

    public static BlueprintSelection pinned(String selectionId) {
        return new BlueprintSelection(MODE_PINNED, selectionId);
    }

    public String selectorModeOrDefault() {
        return selectorMode.isEmpty() ? MODE_SCOPED : selectorMode;
    }

    public boolean isPinned() {
        return MODE_PINNED.equalsIgnoreCase(selectorModeOrDefault()) && !selectionId.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
