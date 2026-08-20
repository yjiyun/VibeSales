package com.yjiyun.chatflows.manager.matrix;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RestMatrixClientJoinInviteSelfTest {
  public static void main(String[] args) throws Exception {
    if (!RestMatrixClient.alreadyInvited(403, "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"already in the room\"}")) {
      throw new AssertionError("already-member invite not recognized");
    }
    if (RestMatrixClient.alreadyInvited(403, "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"cannot join a room that is not `public`\"}")) {
      throw new AssertionError("unrelated 403 treated as already invited");
    }
    List<String> calls = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      calls.add(method + " " + path);
      byte[] req = exchange.getRequestBody().readAllBytes();
      int code = 200;
      String body = "{}";
      if (path.endsWith("/join/room")) {
        long joins = calls.stream().filter(call -> call.contains("/join/")).count();
        if (joins == 1) { code = 403; body = "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"not in room\"}"; }
        else body = "{\"room_id\":\"room\"}";
      } else if (path.endsWith("/login")) {
        if (!new String(req, StandardCharsets.UTF_8).contains("@admin:local")) {
          code = 403; body = "{\"errcode\":\"M_FORBIDDEN\"}";
        } else body = "{\"user_id\":\"@admin:local\",\"access_token\":\"inviter-token\"}";
      } else if (path.endsWith("/invite")) {
        if (!new String(req, StandardCharsets.UTF_8).contains("@manager:local")) {
          code = 400; body = "{\"errcode\":\"M_BAD_JSON\"}";
        } else body = "{}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      URI matrix = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      MatrixTokenProvider tokens = new MatrixTokenProvider("manager-token", URI.create("http://127.0.0.1:1"), "controller-token");
      RestMatrixClient client = new RestMatrixClient(matrix, tokens, "@manager:local", "@admin:local", "admin-pass");
      client.join("room");
      if (!calls.contains("POST /_matrix/client/v3/login")) throw new AssertionError("invite login skipped: " + calls);
      if (calls.stream().noneMatch(call -> call.endsWith("/invite"))) throw new AssertionError("invite skipped: " + calls);
      if (calls.stream().filter(call -> call.contains("/join/")).count() != 2) throw new AssertionError("join not retried: " + calls);
      calls.clear();
      RestMatrixClient noInvite = new RestMatrixClient(matrix, tokens);
      boolean failed = false;
      try { noInvite.join("room"); } catch (java.io.IOException e) { failed = e.getMessage() != null && e.getMessage().contains("403"); }
      if (!failed) throw new AssertionError("403 join without inviter was accepted: " + calls);
    } finally {
      server.stop(0);
    }
    System.out.println("[PASS] 403 join invites via Human then retries");
  }
}
