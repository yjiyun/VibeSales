package com.agentteams.salesagent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 轻量 `.env` 加载器。
 *
 * <p>当前项目不是 Spring Boot，因此不会自动加载 `.env`。
 * 这里在应用启动时把 `.env` 中的键值写入 System Property，
 * 以便后续配置装配和健康检查可以直接读取。
 */
public final class EnvFileLoader {

    private EnvFileLoader() {
    }

    public static void loadIfPresent(Path envPath) {
        if (envPath == null || !Files.exists(envPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                applyLine(line);
            }
        } catch (IOException ignored) {
            // 启动期不因本地 .env 读取失败而中断，后续健康检查会给出明确提示。
        }
    }

    private static void applyLine(String rawLine) {
        if (rawLine == null) {
            return;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int separatorIndex = line.indexOf('=');
        if (separatorIndex <= 0) {
            return;
        }
        String key = line.substring(0, separatorIndex).trim();
        String value = line.substring(separatorIndex + 1).trim();
        if (key.isEmpty()) {
            return;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        if (System.getenv(key) != null && !System.getenv(key).isBlank()) {
            return;
        }
        if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
            System.setProperty(key, value);
        }
    }
}
