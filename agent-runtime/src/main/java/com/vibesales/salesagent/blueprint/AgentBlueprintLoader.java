package com.vibesales.salesagent.blueprint;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从 classpath 读取 {@link AgentBlueprint} JSON。
 *
 * <p>首轮模拟刻意不接数据库、不接远程发布服务：Blueprint 直接作为 classpath 静态资源存放在
 * {@code resources/blueprints/} 下，上游生成工程可以直接对照 JSON 文件开发。
 *
 * <p>走 classpath 而不是文件系统路径，理由和 {@code SkillRepositoryFactory} 一致：本工程既可能通过
 * {@code exec-maven-plugin} 跑 {@code target/classes}，也可能改成 {@code java -jar}，classpath
 * 对两种运行方式一致工作。
 */
public final class AgentBlueprintLoader {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 读取指定 classpath 资源路径下的 Blueprint。资源不存在时返回 {@code null}。 */
    public AgentBlueprint loadFromClasspath(String resourcePath) {
        String normalized = normalize(resourcePath);
        try (InputStream inputStream =
                AgentBlueprintLoader.class.getClassLoader().getResourceAsStream(normalized)) {
            if (inputStream == null) {
                return null;
            }
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json, normalized);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read agent blueprint resource: " + normalized, exception);
        }
    }

    public AgentBlueprint parse(String json, String origin) {
        try {
            return objectMapper.readValue(json, AgentBlueprint.class);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to parse agent blueprint JSON from " + origin, exception);
        }
    }

    /** 读取蓝图索引；索引缺失时返回空索引，让上层按"未配置蓝图"处理而不是直接崩。 */
    public BlueprintIndex loadIndex(String resourcePath) {
        String normalized = normalize(resourcePath);
        try (InputStream inputStream =
                AgentBlueprintLoader.class.getClassLoader().getResourceAsStream(normalized)) {
            if (inputStream == null) {
                return BlueprintIndex.empty();
            }
            return objectMapper.readValue(inputStream, BlueprintIndex.class);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read agent blueprint index: " + normalized, exception);
        }
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("blueprint resource path must not be blank");
        }
        String trimmed = resourcePath.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }
}
