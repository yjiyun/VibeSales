package com.agentteams.salesagent.conversation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 平台内部会话 ID 生成器。
 *
 * <p>当前创建会话接口不依赖接入层特有字段，因此这里统一生成
 * `sales-customer-agent` 自己的全局唯一 `conversationId`。
 */
public final class ConversationIdGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String nextId() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "sca_" + timestamp + "_" + suffix;
    }
}
