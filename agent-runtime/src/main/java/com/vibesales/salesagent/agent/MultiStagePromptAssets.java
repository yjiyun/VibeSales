package com.vibesales.salesagent.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * multi_stage 阶段提示词资源加载器。
 *
 * <p>首轮只支持少量内置 ref，避免把阶段 Prompt 再硬编码回编排类里。
 */
public final class MultiStagePromptAssets {

    public String loadRequired(String promptRef) {
        String resourcePath = resolveResourcePath(promptRef);
        try (InputStream inputStream =
                MultiStagePromptAssets.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "stage prompt asset not found for ref '" + promptRef + "' at " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to read stage prompt asset for ref '" + promptRef + "'", exception);
        }
    }

    private static String resolveResourcePath(String promptRef) {
        String normalized = promptRef == null ? "" : promptRef.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("stage prompt ref must not be blank");
        }
        if (normalized.startsWith("prompts/")) {
            return normalized;
        }
        if (normalized.startsWith("/")) {
            return normalized.substring(1);
        }
        if (normalized.startsWith("prompt.stage.")) {
            return "prompts/stages/" + normalized.substring("prompt.stage.".length()) + ".md";
        }
        throw new IllegalArgumentException(
                "unsupported stage prompt ref: " + normalized + " (expected prompts/... or prompt.stage....)");
    }
}
