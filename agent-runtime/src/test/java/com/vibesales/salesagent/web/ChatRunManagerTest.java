package com.agentteams.salesagent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentteams.salesagent.agent.ChatResponse;
import com.agentteams.salesagent.progress.ExecutionProgressUpdate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatRunManagerTest {

    @Test
    void shouldAppendCreatedAndProgressEventsInSequence() {
        ChatRunManager manager = new ChatRunManager();

        ChatRunManager.ChatRunState runState = manager.createRun();
        manager.listenerFor(runState.runId())
                .onUpdate(
                        new ExecutionProgressUpdate(
                                "orchestration",
                                "context.map",
                                "end",
                                "映射运行时上下文",
                                3L,
                                Map.of("sessionId", "conv-1")));

        assertEquals(2, runState.snapshotEvents().size());
        assertEquals("run.created", runState.snapshotEvents().get(0).step());
        assertEquals(1L, runState.snapshotEvents().get(0).seq());
        assertEquals("context.map", runState.snapshotEvents().get(1).step());
        assertEquals(2L, runState.snapshotEvents().get(1).seq());
        assertFalse(runState.completed());
    }

    @Test
    void shouldAppendTerminalCompletionEventWithResponsePayload() {
        ChatRunManager manager = new ChatRunManager();
        ChatRunManager.ChatRunState runState = manager.createRun();

        manager.completeRun(
                runState.runId(),
                new ChatResponse(
                        "你好，这里是回复",
                        "conv-1",
                        "robot-conv-1",
                        "user-1",
                        "robot-key",
                        "会话名",
                        "msg-1",
                        "resume-existing-intent",
                        "skin-care",
                        "历史摘要",
                        "画像摘要",
                        "queue-v1",
                        null));

        ChatRunEvent terminal = runState.snapshotEvents().get(runState.snapshotEvents().size() - 1);
        assertTrue(runState.completed());
        assertTrue(terminal.terminal());
        assertEquals("run.complete", terminal.step());
        assertEquals("end", terminal.status());
        assertEquals("你好，这里是回复", terminal.detail().get("reply"));
        assertEquals("queue-v1", terminal.detail().get("queueVersion"));
    }

    @Test
    void shouldInferStageNodeTypeFromStagePhase() {
        ChatRunManager manager = new ChatRunManager();
        ChatRunManager.ChatRunState runState = manager.createRun();

        manager.listenerFor(runState.runId())
                .onUpdate(
                        new ExecutionProgressUpdate(
                                "stage",
                                "business_process",
                                "end",
                                "业务处理",
                                10L,
                                Map.of(
                                        "nodeId", "stage:business_process",
                                        "nodeLayer", "stage",
                                        "stageKey", "business_process")));

        ChatRunEvent stageEvent = runState.snapshotEvents().get(runState.snapshotEvents().size() - 1);
        assertEquals("stage", stageEvent.nodeType());
        assertEquals("业务处理", stageEvent.nodeName());
    }

    @Test
    void shouldWriteRunAndEventsIntoStore() {
        FakeChatRunStore store = new FakeChatRunStore();
        ChatRunManager manager = new ChatRunManager(store);

        ChatRunManager.ChatRunState runState =
                manager.createRun(
                        new ChatRunStore.RunCreate(
                                "run-store-1",
                                Instant.parse("2026-08-19T12:00:00Z"),
                                "yjiyuncom",
                                "test",
                                "BEAUTY_SKINCARE",
                                "BEAUTY_SKINCARE",
                                "conv-1",
                                "user-1",
                                "你好",
                                "scoped",
                                "",
                                "{\"message\":\"你好\"}"));
        manager.listenerFor(runState.runId())
                .onUpdate(
                        new ExecutionProgressUpdate(
                                "orchestration",
                                "context.map",
                                "end",
                                "映射运行时上下文",
                                3L,
                                Map.of("sessionId", "conv-1")));

        assertEquals(1, store.createdRuns.size());
        assertEquals("run-store-1", store.createdRuns.get(0).runId());
        assertEquals(2, store.events.size());
        assertEquals("run.created", store.events.get(0).step());
        assertEquals("context.map", store.events.get(1).step());
    }

    @Test
    void shouldRestoreCompletedRunFromStore() {
        FakeChatRunStore store = new FakeChatRunStore();
        ChatRunEvent createdEvent =
                new ChatRunEvent(
                        "run-history-1",
                        1L,
                        "orchestration",
                        "run.created",
                        "start",
                        "创建运行",
                        "run",
                        "创建运行",
                        null,
                        Map.of("createdAt", "2026-08-19T12:00:00Z"),
                        1724068800000L,
                        false);
        ChatRunEvent terminalEvent =
                new ChatRunEvent(
                        "run-history-1",
                        2L,
                        "orchestration",
                        "run.complete",
                        "end",
                        "本轮执行完成",
                        "run",
                        "本轮执行完成",
                        120L,
                        Map.of("reply", "好的"),
                        1724068800120L,
                        true);
        store.snapshot =
                new ChatRunStore.RunSnapshot(
                        new ChatRunStore.RunCreate(
                                "run-history-1",
                                Instant.parse("2026-08-19T12:00:00Z"),
                                "yjiyuncom",
                                "test",
                                "BEAUTY_SKINCARE",
                                "BEAUTY_SKINCARE",
                                "conv-1",
                                "user-1",
                                "你好",
                                "pinned",
                                "bp-1",
                                "{\"message\":\"你好\"}"),
                        Instant.parse("2026-08-19T12:00:01Z"),
                        "completed",
                        "",
                        new ChatResponse(
                                "好的",
                                "conv-1",
                                "robot-conv-1",
                                "user-1",
                                "robot-key",
                                "会话名",
                                "msg-1",
                                "resume-existing-intent",
                                "skin-care",
                                "历史摘要",
                                "画像摘要",
                                "queue-v1",
                                new ChatResponse.ResolvedBlueprintSummary(
                                        "pinned",
                                        "bp-1",
                                        "yjiyuncom_multistage_v1",
                                        "multi_stage",
                                        4,
                                        "classpath",
                                        "pinned")),
                        List.of(createdEvent, terminalEvent));
        ChatRunManager manager = new ChatRunManager(store);

        ChatRunManager.ChatRunState restored = manager.find("run-history-1").orElse(null);

        assertNotNull(restored);
        assertTrue(restored.completed());
        assertEquals(2, restored.snapshotEvents().size());
        assertEquals("run.complete", restored.snapshotEvents().get(1).step());
        assertEquals("yjiyuncom_multistage_v1", manager.listRecent(10).get(0).blueprintId());
    }

    private static final class FakeChatRunStore implements ChatRunStore {
        private final List<RunCreate> createdRuns = new ArrayList<>();
        private final List<ChatRunEvent> events = new ArrayList<>();
        private RunSnapshot snapshot;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void createRun(RunCreate run) {
            createdRuns.add(run);
        }

        @Override
        public void appendEvent(ChatRunEvent event) {
            events.add(event);
        }

        @Override
        public void markCompleted(RunCompletion completion) {}

        @Override
        public Optional<RunSnapshot> loadRun(String runId) {
            if (snapshot == null || !snapshot.run().runId().equals(runId)) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        }

        @Override
        public List<RunHistoryItem> listRecent(int limit) {
            return List.of();
        }
    }
}
