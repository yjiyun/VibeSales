package com.agentteams.salesagent.web;

import com.agentteams.salesagent.agent.ChatResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * chat run 持久化读写抽象。
 *
 * <p>首版职责只覆盖工作台 run/event 的最小闭环：创建 run、逐条落事件、终态更新、历史回读。
 */
public interface ChatRunStore {

    boolean enabled();

    void createRun(RunCreate run);

    void appendEvent(ChatRunEvent event);

    void markCompleted(RunCompletion completion);

    Optional<RunSnapshot> loadRun(String runId);

    List<RunHistoryItem> listRecent(int limit);

    static ChatRunStore noop() {
        return NoopHolder.INSTANCE;
    }

    record RunCreate(
            String runId,
            Instant createdAt,
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String conversationId,
            String chatUser,
            String messagePreview,
            String selectorMode,
            String selectionId,
            String requestJson) {}

    record RunCompletion(
            String runId,
            Instant completedAt,
            String status,
            String failureMessage,
            ChatResponse response,
            int eventCount) {}

    record RunHistoryItem(
            String runId,
            Instant createdAt,
            Instant completedAt,
            String status,
            String clientCode,
            String cluster,
            String sceneCode,
            String runtimeAgentId,
            String conversationId,
            String chatUser,
            String messagePreview,
            String selectorMode,
            String selectionId,
            String blueprintId,
            String replyPreview,
            String failureMessage,
            int eventCount,
            boolean persisted) {}

    record RunSnapshot(
            RunCreate run,
            Instant completedAt,
            String status,
            String failureMessage,
            ChatResponse response,
            List<ChatRunEvent> events) {}

    final class NoopHolder {
        private static final ChatRunStore INSTANCE =
                new ChatRunStore() {
                    @Override
                    public boolean enabled() {
                        return false;
                    }

                    @Override
                    public void createRun(RunCreate run) {}

                    @Override
                    public void appendEvent(ChatRunEvent event) {}

                    @Override
                    public void markCompleted(RunCompletion completion) {}

                    @Override
                    public Optional<RunSnapshot> loadRun(String runId) {
                        return Optional.empty();
                    }

                    @Override
                    public List<RunHistoryItem> listRecent(int limit) {
                        return List.of();
                    }
                };

        private NoopHolder() {}
    }
}
