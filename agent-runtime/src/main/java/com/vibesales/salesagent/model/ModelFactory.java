package com.vibesales.salesagent.model;

import com.vibesales.salesagent.config.AppConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 当前阶段的最小模型装配工厂。
 *
 * <p>这里只负责把项目统一配置装配成一个可用的 OpenAI 兼容模型实例，
 * 不在这里叠加业务规则、Skill 或 Tool 逻辑。
 *
 * <p><b>不要在这里装饰 {@code Formatter} 来做埋点。</b>曾经用 {@code TelemetryOpenAIFormatter}
 * 包一层来抓提示词与响应，实测不可行：{@code OpenAIChatModel} 在调用方线程调 {@code format(...)}，
 * 却把 {@code parseResponse(...)} 放到 {@code boundedElastic} 上，两者无法用 ThreadLocal 关联，
 * 且流式强制开启的 {@code include_usage} 尾包会污染被复用的 worker。LLM 输入输出埋点已改由
 * {@code LlmTraceMiddleware} 在 {@code onModelCall} 层采集，详见该类的类注释。
 */
public final class ModelFactory {

    private ModelFactory() {
    }

    public static OpenAIChatModel createDefaultModel(AppConfig config) {
        return OpenAIChatModel.builder()
                .modelName(config.modelName())
                .baseUrl(config.modelBaseUrl())
                .apiKey(config.modelApiKey())
                .build();
    }
}
