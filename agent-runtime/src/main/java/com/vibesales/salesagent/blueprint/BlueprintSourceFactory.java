package com.agentteams.salesagent.blueprint;

import com.agentteams.salesagent.config.AppConfig;

/** 统一装配 Blueprint 来源。 */
public final class BlueprintSourceFactory {

    private BlueprintSourceFactory() {}

    public static BlueprintSource create(AppConfig config) {
        String source = safe(config.blueprintSource());
        BlueprintSource classpath = new ClasspathBlueprintSource();
        if ("jdbc".equalsIgnoreCase(source) || config.blueprintJdbcConfigured()) {
            BlueprintSource jdbc = new JdbcPublishedBlueprintSource(config);
            if (config.blueprintFallbackToClasspath()) {
                return new CompositeBlueprintSource(
                        jdbc, classpath, config.blueprintFailOnRemoteError());
            }
            return jdbc;
        }
        if (!"remote".equalsIgnoreCase(source) && !config.blueprintRemoteEnabled()) {
            return classpath;
        }

        BlueprintSource remote = new RemotePublishedBlueprintSource(config);
        if (config.blueprintFallbackToClasspath()) {
            return new CompositeBlueprintSource(
                    remote, classpath, config.blueprintFailOnRemoteError());
        }
        return remote;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
