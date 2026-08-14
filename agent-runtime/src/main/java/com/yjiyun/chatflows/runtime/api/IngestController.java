package com.yjiyun.chatflows.runtime.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yjiyun.chatflows.runtime.blueprint.AgentBlueprint;
import com.yjiyun.chatflows.runtime.blueprint.BlueprintAdmin;
import com.yjiyun.chatflows.runtime.blueprint.BlueprintJson;
import com.yjiyun.chatflows.runtime.blueprint.InMemoryBlueprintRepository;
import com.yjiyun.chatflows.runtime.security.AuthService;
import java.io.IOException;
import java.util.Map;

/** Local-mode only: stage + publish a Blueprint so /api/v1/chat can project it. */
public final class IngestController implements HttpHandler {
  private final InMemoryBlueprintRepository store;
  private final AuthService auth;

  public IngestController(InMemoryBlueprintRepository store, AuthService auth) {
    this.store = store;
    this.auth = auth;
  }

  public void handle(HttpExchange x) throws IOException {
    if (!"POST".equals(x.getRequestMethod())) {
      HttpSupport.json(x, 405, Map.of("error", "POST required"));
      return;
    }
    try {
      auth.requireAdmin(HttpSupport.bearer(x), x.getRequestHeaders().getFirst("X-Role"));
      String actor = x.getRequestHeaders().getFirst("X-Actor");
      if (actor == null || actor.isBlank()) throw new IllegalArgumentException("X-Actor required");
      AgentBlueprint bp = BlueprintJson.read(x.getRequestBody().readAllBytes());
      if (bp.blueprintId() == null || bp.blueprintId().isBlank()) {
        throw new IllegalArgumentException("blueprintId required");
      }
      if (bp.clientCode() == null || bp.clientCode().isBlank()) {
        throw new IllegalArgumentException("clientCode required");
      }
      if (bp.runtimeAgentId() == null || bp.runtimeAgentId().isBlank()) {
        throw new IllegalArgumentException("runtimeAgentId required");
      }
      store.stage(bp, actor);
      BlueprintAdmin.Result published = store.publish(bp.blueprintId(), bp.clientCode(), actor);
      HttpSupport.json(
          x,
          200,
          Map.of(
              "blueprintId", published.blueprintId(),
              "version", published.version(),
              "status", published.status(),
              "runtimeAgentId", bp.runtimeAgentId(),
              "clientCode", bp.clientCode()));
    } catch (Exception e) {
      HttpSupport.error(x, e);
    }
  }
}
