package com.yjiyun.chatflows.runtime.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Map;

/**
 * AgentLoop OTLP 出口的全局 SDK 引导（最终方案第二版：OTLP 标准直发为准）。
 *
 * <p>{@code OtelTracingMiddleware} 用 {@code GlobalOpenTelemetry.getTracer} 取 tracer，因此这里
 * 只需在启动早期把一个带 OTLP/HTTP exporter 的 {@link OpenTelemetrySdk} 注册为全局，它产出的
 * {@code invoke_agent}/{@code chat}/{@code execute_tool} span 就会被导出——无需改动 AgentScope。
 *
 * <p>协议选择（与 Nest / 方案 §1.1 混合命名一致）：
 * <ul>
 *   <li>{@code AGENTLOOP_EXPORTER=off} 或缺失 → 不注册（保持 no-op，零上报）。</li>
 *   <li>{@code AGENTLOOP_PROTOCOL=roa} → 不注册全局 SDK，由 {@link BlueprintTraceMiddleware} 的
 *       ROA {@link AgentLoopExporter} 走回滚通路。</li>
 *   <li>{@code AGENTLOOP_PROTOCOL=otlp}（默认）+ {@code EXPORTER=on} → 注册 OTLP exporter。
 *       {@code EXPORTER=stderr} → 注册 logging 前不引额外依赖，这里退化为不注册，本地打点仍走 ROA sink 的 stderr 分支。</li>
 * </ul>
 *
 * <p>凭证只从环境注入（{@code ARMS_LICENSE_KEY} / {@code OTEL_EXPORTER_OTLP_HEADERS}），不打印。
 */
public final class RuntimeTelemetry {
  private RuntimeTelemetry() {}

  public static final String DEFAULT_SERVICE_NAME = "vibe-sales-runtime";

  /** 返回是否注册了 OTLP 全局 SDK（true = OTLP 通路生效，BlueprintTraceMiddleware 应停用 ROA 镜像）。 */
  public static boolean install(Map<String, String> env) {
    String mode = env.getOrDefault("AGENTLOOP_EXPORTER", "off").trim().toLowerCase();
    if (!mode.equals("on")) {
      // off / stderr：不注册 OTLP SDK。stderr 本地调试仍可用 ROA sink 的 stderr 分支。
      return false;
    }
    String protocol = env.getOrDefault("AGENTLOOP_PROTOCOL", "otlp").trim().toLowerCase();
    if (!protocol.equals("otlp")) {
      // roa：走回滚通路，不注册全局 SDK。
      return false;
    }
    String endpoint = trim(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
    if (endpoint == null) {
      throw new IllegalStateException(
          "AGENTLOOP_PROTOCOL=otlp + EXPORTER=on requires OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
    }

    var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
    for (Map.Entry<String, String> header : parseHeaders(env).entrySet()) {
      exporter.addHeader(header.getKey(), header.getValue());
    }

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter.build()).build())
            .setResource(Resource.getDefault().merge(resource(env)))
            .build();

    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> sdk.getSdkTracerProvider().shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS)));

    // 脱敏启动日志：只打 host / service，不打 license / headers。
    System.err.println(
        "[runtime-telemetry] OTLP on: service="
            + serviceName(env)
            + " endpoint="
            + safeHost(endpoint));
    return true;
  }

  private static Resource resource(Map<String, String> env) {
    AttributesBuilder attrs = Attributes.builder();
    attrs.put("service.name", serviceName(env));
    String raw = trim(env.get("OTEL_RESOURCE_ATTRIBUTES"));
    if (raw != null) {
      for (String pair : raw.split(",")) {
        int idx = pair.indexOf('=');
        if (idx > 0) {
          String key = pair.substring(0, idx).trim();
          String value = pair.substring(idx + 1).trim();
          if (!key.isEmpty() && !key.equals("service.name")) attrs.put(key, value);
        }
      }
    }
    return Resource.create(attrs.build());
  }

  private static String serviceName(Map<String, String> env) {
    String name = trim(env.get("OTEL_SERVICE_NAME"));
    return name != null ? name : DEFAULT_SERVICE_NAME;
  }

  private static Map<String, String> parseHeaders(Map<String, String> env) {
    java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
    String raw = trim(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
    if (raw != null) {
      for (String pair : raw.split(",")) {
        int idx = pair.indexOf('=');
        if (idx > 0) headers.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
      }
      return headers;
    }
    String licenseKey = trim(env.get("ARMS_LICENSE_KEY"));
    if (licenseKey != null) headers.put("x-arms-license-key", licenseKey);
    return headers;
  }

  private static String safeHost(String endpoint) {
    try {
      return java.net.URI.create(endpoint).getHost();
    } catch (RuntimeException e) {
      return "(invalid)";
    }
  }

  private static String trim(String value) {
    if (value == null) return null;
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
