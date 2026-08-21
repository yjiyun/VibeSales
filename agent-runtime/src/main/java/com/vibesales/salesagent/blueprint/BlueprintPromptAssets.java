package com.vibesales.salesagent.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 顶层 Prompt 资源加载器。
 *
 * <p>用于把 Blueprint 的 {@code agentsMdRef}/{@code soulMdRef} 解析为 classpath 里的静态文本资源，
 * 兼容"正文内联"与"外联引用"两种资产表达方式。
 */
public final class BlueprintPromptAssets {

    public String loadRequired(String promptRef, String fieldName) {
        String resourcePath = resolveResourcePath(promptRef, fieldName);
        try (InputStream inputStream =
                BlueprintPromptAssets.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "blueprint prompt asset not found for "
                                + fieldName
                                + "='"
                                + promptRef
                                + "' at "
                                + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to read blueprint prompt asset for "
                            + fieldName
                            + "='"
                            + promptRef
                            + "'",
                    exception);
        }
    }

    private static String resolveResourcePath(String promptRef, String fieldName) {
        String normalized = promptRef == null ? "" : promptRef.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank when ref mode is used");
        }
        if (normalized.startsWith("prompts/")) {
            return normalized;
        }
        if (normalized.startsWith("/")) {
            return normalized.substring(1);
        }
        throw new IllegalArgumentException(
                "unsupported blueprint prompt ref for "
                        + fieldName
                        + ": "
                        + normalized
                        + " (expected prompts/... or /...)");
    }
}
