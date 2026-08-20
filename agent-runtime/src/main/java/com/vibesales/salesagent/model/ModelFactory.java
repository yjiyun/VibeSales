package com.agentteams.salesagent.model;

import com.agentteams.salesagent.config.AppConfig;
import com.agentteams.salesagent.model.telemetry.TelemetryOpenAIFormatter;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

/**
 * 当前阶段的最小模型装配工厂。
 *
 * <p>这里只负责把项目统一配置装配成一个可用的 OpenAI 兼容模型实例，
 * 不在这里叠加业务规则、Skill 或 Tool 逻辑。
 */
public final class ModelFactory {

    private ModelFactory() {
    }

    public static OpenAIChatModel createDefaultModel(AppConfig config) {
        return OpenAIChatModel.builder()
                .modelName(config.modelName())
                .baseUrl(config.modelBaseUrl())
                .apiKey(config.modelApiKey())
                .formatter(new TelemetryOpenAIFormatter(new OpenAIChatFormatter()))
                .build();
    }
}
