package com.yjiyun.chatflows.manager;

import com.yjiyun.chatflows.manager.platform.PlatformClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ManifestApplySelfTest {
  public static void main(String[] args) throws Exception {
    if (!"generated".equals(ManagerApplication.envOr(Map.of("AGENTTEAMS_RUN_ID", ""), "AGENTTEAMS_RUN_ID", "generated"))) {
      throw new AssertionError("blank run id did not fall back");
    }
    ManagerApplication.requireExpectedStatus("SUCCEEDED", "SUCCEEDED");
    boolean terminalRejected = false;
    try { ManagerApplication.requireExpectedStatus("ABORTED", "SUCCEEDED"); }
    catch (IllegalStateException error) { terminalRejected = true; }
    if (!terminalRejected) throw new AssertionError("non-success terminal status was accepted");
    Path dir = Files.createTempDirectory("manifest-apply");
    Files.writeString(dir.resolve("000-worker.yaml"), "apiVersion: v1\nkind: Worker\nmetadata: {name: worker}\nspec: {runtime: qwenpaw}\n");
    Files.writeString(dir.resolve("001-team.yaml"), "apiVersion: v1\nkind: Team\nmetadata: {name: team}\nspec: {leader: worker, workers: []}\n");
    Files.writeString(dir.resolve("manifest.json"), "{\"version\":1,\"files\":[\"000-worker.yaml\",\"001-team.yaml\"]}");
    List<String> applied = new ArrayList<>();
    PlatformClient platform = (kind, name, yaml) -> applied.add(kind + ":" + name);
    ManagerApplication.applyManifest(platform, dir.resolve("manifest.json"));
    if (!applied.equals(List.of("Worker:worker", "Team:team"))) throw new AssertionError("manifest order lost: " + applied);
    Files.writeString(dir.resolve("bad.json"), "{\"version\":1,\"files\":[\"../secret.yaml\"]}");
    boolean rejected = false;
    try { ManagerApplication.applyManifest(platform, dir.resolve("bad.json")); }
    catch (IllegalArgumentException error) { rejected = true; }
    if (!rejected) throw new AssertionError("manifest path traversal accepted");
    System.out.println("[PASS] rendered CR manifest applies in dependency order, rejects traversal, and defaults blank run_id");
  }
}
