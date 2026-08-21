package com.vibesales.salesagent.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 加载系统级 fallback Prompt 资产。 */
public final class FallbackPromptAssets {

    private static final String FALLBACK_AGENTS_PATH = "prompts/fallback/AGENTS.md";
    private static final String FALLBACK_SOUL_PATH = "prompts/fallback/SOUL.md";

    public record Content(String agentsMd, String soulMd) {}

    public Content load() {
        return new Content(readRequired(FALLBACK_AGENTS_PATH), readRequired(FALLBACK_SOUL_PATH));
    }

    private static String readRequired(String path) {
        try (InputStream stream =
                FallbackPromptAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing fallback prompt asset: " + path);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("fallback prompt asset is empty: " + path);
            }
            return text;
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to load fallback prompt asset: " + path, exception);
        }
    }
}
