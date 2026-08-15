package com.yjiyun.chatflows.runtime.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yjiyun.chatflows.runtime.InspectorFlag;
import com.yjiyun.chatflows.runtime.agent.RuntimeContextFactory;
import com.yjiyun.chatflows.runtime.blueprint.BlueprintProjector;
import com.yjiyun.chatflows.runtime.security.AuthService;
import java.io.IOException;
import java.util.Map;

/** Debug-only: compare PUBLISHED Blueprint prompt with projected workspace files. */
public final class InspectController implements HttpHandler {
  private final BlueprintProjector projector;
  private final AuthService auth;
  private final RuntimeContextFactory contexts = new RuntimeContextFactory();

  public InspectController(BlueprintProjector projector, AuthService auth) {
    this.projector = projector;
    this.auth = auth;
  }

  public void handle(HttpExchange x) throws IOException {
    if (!InspectorFlag.enabled()) {
      HttpSupport.json(x, 404, Map.of("error", "inspector disabled"));
      return;
    }
    if (!"GET".equals(x.getRequestMethod())) {
      HttpSupport.json(x, 405, Map.of("error", "GET required"));
      return;
    }
    try {
      auth.requireAdmin(HttpSupport.bearer(x), x.getRequestHeaders().getFirst("X-Role"));
      Map<String, String> q = HttpSupport.query(x);
      String client = req(q, "clientCode");
      String user = req(q, "userId");
      String agent = req(q, "runtimeAgentId");
      var rc = contexts.create(client, user, "inspect", agent);
      HttpSupport.json(x, 200, projector.inspectPublished(client, user, agent, rc));
    } catch (Exception e) {
      HttpSupport.error(x, e);
    }
  }

  private static String req(Map<String, String> q, String k) {
    String v = q.get(k);
    if (v == null || v.isBlank()) throw new IllegalArgumentException(k + " required");
    return v;
  }
}
