package com.agentteams.salesagent.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.config.AppConfig;
import org.junit.jupiter.api.Test;

class BlueprintSourceFactoryTest {

    @Test
    void shouldUseClasspathSourceByDefault() {
        BlueprintSource source = BlueprintSourceFactory.create(config("classpath", false, false, true));

        assertTrue(source instanceof ClasspathBlueprintSource);
        assertEquals(
                "yjiyuncom_test_v1",
                source.resolve("yjiyuncom", "test", "", "yjiyuncom_test_agent", "")
                        .orElseThrow()
                        .blueprint()
                        .blueprintId());
    }

    @Test
    void remoteSourceShouldFallbackToClasspathWhenConfigured() {
        BlueprintSource source = BlueprintSourceFactory.create(config("remote", true, false, true));

        assertEquals(
                "yjiyuncom_test_v1",
                source.resolve("yjiyuncom", "test", "", "yjiyuncom_test_agent", "")
                        .orElseThrow()
                        .blueprint()
                        .blueprintId());
    }

    @Test
    void remoteSourceShouldFailFastWhenFallbackDisabled() {
        BlueprintSource source = BlueprintSourceFactory.create(config("remote", true, true, false));

        assertThrows(
                IllegalStateException.class,
                () -> source.resolve("yjiyuncom", "test", "", "yjiyuncom_test_agent", ""));
    }

    private static AppConfig config(
            String blueprintSource,
            boolean blueprintRemoteEnabled,
            boolean blueprintFailOnRemoteError,
            boolean blueprintFallbackToClasspath) {
        return new AppConfig(
                "model",
                "http://model",
                "secret",
                "bailian",
                "",
                "",
                "",
                "",
                "127.0.0.1",
                "3306",
                "",
                "",
                "",
                "",
                "agent_conversations",
                "agent_chat_runs",
                "agent_chat_run_events",
                "http://localhost:3002",
                "",
                blueprintSource,
                blueprintRemoteEnabled,
                "http://localhost:8088",
                "",
                "/api/v1/blueprints/published",
                3000,
                5000,
                5000,
                "",
                "",
                "",
                5,
                blueprintFailOnRemoteError,
                blueprintFallbackToClasspath,
                "",
                "",
                "BEAUTY_SKINCARE",
                ".agentscope/workspace",
                "sales-customer-agent",
                false,
                "",
                false,
                "",
                "",
                "",
                "");
    }
}
