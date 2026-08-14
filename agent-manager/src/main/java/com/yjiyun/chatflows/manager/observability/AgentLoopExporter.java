package com.yjiyun.chatflows.manager.observability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Best-effort AgentLoop ROA exporter for the Java orchestration edge (A22).
 * Three modes (off / stderr / on) plus run_id sampling; a failed export disables only this exporter (A12).
 * Envelope building filters credential-shaped attributes and always discloses stock Worker usage availability.
 */
public final class AgentLoopExporter {
  private final String mode;
  private final String endpoint;
  private final String key;
  private final String secret;
  private final double sampleRate;
  private final HttpClient http;
  private volatile boolean enabled = true;

  public AgentLoopExporter(Map<String, String> env) {
    mode = env.getOrDefault("AGENTLOOP_EXPORTER", "off").trim().toLowerCase(Locale.ROOT);
    endpoint = trim(env.get("AGENTLOOP_ENDPOINT"));
    key = trim(env.get("AGENTLOOP_ACCESS_KEY"));
    secret = trim(env.get("AGENTLOOP_ACCESS_SECRET"));
    sampleRate = Double.parseDouble(env.getOrDefault("AGENTLOOP_SAMPLE_RATE", "1.0"));
    if (!Set.of("off", "stderr", "on").contains(mode)) throw new IllegalStateException("AGENTLOOP_EXPORTER must be off, stderr or on");
    if (!Double.isFinite(sampleRate) || sampleRate < 0 || sampleRate > 1) throw new IllegalStateException("AGENTLOOP_SAMPLE_RATE must be 0.0..1.0");
    if ("on".equals(mode) && (endpoint == null || key == null || secret == null)) throw new IllegalStateException("AgentLoop on mode requires endpoint/access key/access secret");
    http = HttpClient.newHttpClient();
  }

  public void emit(String name, TraceCarrier carrier, String phase, String target, String taskDir) {
    if (!enabled || "off".equals(mode) || !sampled(carrier.runId())) return;
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("gen_ai.operation.name", "execute_tool");
    attrs.put("agentteams.run_id", carrier.runId());
    attrs.put("agentteams.client_code", carrier.clientCode());
    attrs.put("agentteams.phase", phase);
    attrs.put("agentteams.agent", "orchestrator");
    attrs.put("agentteams.target_worker", target);
    attrs.put("agentteams.task_dir", taskDir);
    attrs.put("agentteams.trace.link", "traceparent");
    attrs.put("agentteams.usage.scope", "run");
    attrs.put("agentteams.worker_usage_available", false);
    send(envelope(name, carrier.traceparent(), attrs));
  }

  private void send(String body) {
    try {
      if ("stderr".equals(mode)) { System.err.println("[agentloop] " + body); return; }
      String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).POST(HttpRequest.BodyPublishers.ofString(body));
      roaHeaders(endpoint, body, key, secret, date, UUID.randomUUID().toString()).forEach(request::header);
      http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
        .thenAccept(response -> { if (response.statusCode() / 100 != 2) disable("HTTP " + response.statusCode()); })
        .exceptionally(error -> { disable(error.getClass().getSimpleName()); return null; });
    } catch (Exception error) { disable(error.getClass().getSimpleName()); }
  }

  /** Serializes an AgentLoop envelope, dropping credential-shaped keys and non-scalar values. */
  public static String envelope(String name, String traceparent, Map<String, Object> attributes) {
    StringBuilder out = new StringBuilder("{\"name\":\"").append(esc(name)).append("\",\"timestamp\":\"").append(Instant.now()).append("\",\"attributes\":{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      Object value = entry.getValue();
      if (sensitive(entry.getKey()) || !(value instanceof String || value instanceof Number || value instanceof Boolean)) continue;
      if (!first) out.append(',');
      first = false;
      out.append('"').append(esc(entry.getKey())).append("\":");
      if (value instanceof String text) out.append('"').append(esc(text)).append('"');
      else out.append(value);
    }
    out.append('}');
    if (traceparent != null && !traceparent.isBlank()) out.append(",\"traceparent\":\"").append(esc(traceparent)).append('"');
    return out.append('}').toString();
  }

  private boolean sampled(String runId) {
    if (sampleRate == 1) return true;
    if (sampleRate == 0) return false;
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(runId.getBytes(StandardCharsets.UTF_8));
      long value = ((long) (hash[0] & 255) << 24) | ((long) (hash[1] & 255) << 16) | ((long) (hash[2] & 255) << 8) | (hash[3] & 255);
      return value / 4294967296.0 < sampleRate;
    } catch (Exception error) { return false; }
  }

  private void disable(String reason) {
    if (enabled) { enabled = false; System.err.println("[agentloop-exporter] disabled: " + reason); }
  }

  public static Map<String, String> roaHeaders(String endpoint, String body, String key, String secret, String date, String nonce) {
    try {
      String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8)));
      Map<String, String> acs = new TreeMap<>();
      acs.put("x-acs-signature-method", "HMAC-SHA1"); acs.put("x-acs-signature-nonce", nonce); acs.put("x-acs-signature-version", "1.0"); acs.put("x-acs-version", "2026-05-20");
      StringBuilder canonical = new StringBuilder(); acs.forEach((k, v) -> canonical.append(k).append(':').append(v).append('\n'));
      URI uri = URI.create(endpoint); String resource = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      String sign = "POST\napplication/json\n" + md5 + "\napplication/json\n" + date + "\n" + canonical + resource;
      Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      Map<String, String> headers = new LinkedHashMap<>(); headers.put("accept", "application/json"); headers.put("content-type", "application/json"); headers.put("content-md5", md5); headers.put("date", date); headers.putAll(acs); headers.put("authorization", "acs " + key + ":" + Base64.getEncoder().encodeToString(mac.doFinal(sign.getBytes(StandardCharsets.UTF_8)))); return headers;
    } catch (Exception error) { throw new IllegalStateException("AgentLoop ROA signing unavailable", error); }
  }

  private static boolean sensitive(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
    return segment(normalized, "token") || segment(normalized, "secret") || segment(normalized, "password") || segment(normalized, "authorization") || normalized.contains("api_key") || normalized.contains("access_key");
  }

  private static boolean segment(String key, String marker) { return Arrays.asList(key.split("[._]")).contains(marker); }

  private static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n"); }

  private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
