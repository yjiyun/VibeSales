package com.yjiyun.chatflows.manager.platform;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RestPlatformClientApplySelfTest {
  public static void main(String[] args) throws Exception {
    List<String> calls = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1", exchange -> {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      calls.add(method + " " + path);
      exchange.getRequestBody().readAllBytes();
      int code = "GET".equals(method) && path.endsWith("/missing-team") ? 404 : 200;
      byte[] body = "{\"name\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      RestPlatformClient client = new RestPlatformClient(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        "controller-token-0123456789"
      );
      String team = "apiVersion: agentteams.io/v1beta1\nkind: Team\nmetadata: {name: chatflows-build-team}\nspec: {leader: chatflows-leader, workers: [wizard-intent]}\n";
      client.apply("Team", "chatflows-build-team", team);
      if (calls.stream().anyMatch(call -> call.startsWith("PUT "))) {
        throw new AssertionError("existing Team was PUT: " + calls);
      }
      if (!calls.contains("GET /api/v1/teams/chatflows-build-team")) {
        throw new AssertionError("existing Team was not looked up: " + calls);
      }
      calls.clear();
      String worker = "apiVersion: agentteams.io/v1beta1\nkind: Worker\nmetadata: {name: wizard-intent}\nspec: {runtime: qwenpaw}\n";
      client.apply("Worker", "wizard-intent", worker);
      if (!calls.contains("PUT /api/v1/workers/wizard-intent")) {
        throw new AssertionError("existing Worker was not PUT: " + calls);
      }
    } finally {
      server.stop(0);
    }
    System.out.println("[PASS] existing Team is not PUT; Worker still hot-updates");
  }
}
