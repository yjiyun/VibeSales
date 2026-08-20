package com.agentteams.salesagent.rule.recovery;

import com.agentteams.salesagent.rule.Rule;
import com.agentteams.salesagent.rule.RuleResult;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断客户这句简短回复是否在续接上一轮未完成任务（对应场景卡片8的挂起任务恢复机制）。
 *
 * <p>改造自 07-Rule资产设计与接口规范.md 第3.3节的已知反例（{@code RecoveryHandlingService}
 * 硬编码 {@code startsWith("继续")} 判断）——把判断依据外部化为构造参数，不再写成类内部字面量常量，
 * 即使当前只有一个场景在用，也预留了"下一个场景传不同关键词进来"的接口形状。
 */
public final class RecoveryDetectionRule implements Rule<RecoveryDetectionRule.Input, RecoveryDetectionRule.Output> {

    private static final Pattern LEADING_FILLERS =
            Pattern.compile("^(?:\\s|[，。！？、,.!?;；:：~\\-]+|你好|您好|哈喽|hello|hi|那个|嗯|啊|哦|诶)+");

    private final List<String> continuationKeywords;

    public RecoveryDetectionRule(List<String> continuationKeywords) {
        this.continuationKeywords =
                continuationKeywords.stream()
                        .filter(keyword -> keyword != null && !keyword.isBlank())
                        .map(String::trim)
                        .toList();
    }

    /** 本实例实际生效的续接词表（已去空、已 trim），供可观测输出与断言使用。 */
    public List<String> continuationKeywords() {
        return continuationKeywords;
    }

    public record Input(String userMessage, boolean recoveryPending) {}

    public record Output(boolean looksLikeContinuation, String matchedKeyword) {}

    @Override
    public String ruleCode() {
        return "recovery-detection";
    }

    @Override
    public RuleResult<Output> evaluate(Input input) {
        if (input.recoveryPending()) {
            return RuleResult.pass(new Output(true, null));
        }

        String normalized = normalize(input.userMessage());
        for (String keyword : continuationKeywords) {
            if (matchesKeyword(normalized, normalize(keyword))) {
                return RuleResult.pass(new Output(true, keyword));
            }
        }
        return RuleResult.pass(new Output(false, null));
    }

    private static boolean matchesKeyword(String normalizedMessage, String normalizedKeyword) {
        if (normalizedMessage.isBlank() || normalizedKeyword.isBlank()) {
            return false;
        }

        int index = normalizedMessage.indexOf(normalizedKeyword);
        while (index >= 0) {
            if (hasAllowedPrefix(normalizedMessage, index)) {
                return true;
            }
            index = normalizedMessage.indexOf(normalizedKeyword, index + normalizedKeyword.length());
        }
        return false;
    }

    private static boolean hasAllowedPrefix(String normalizedMessage, int index) {
        if (index == 0) {
            return true;
        }
        char previous = normalizedMessage.charAt(index - 1);
        return Character.isWhitespace(previous)
                || "，。！？、,.!?;；:：~-我想还再要跟把就".indexOf(previous) >= 0;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized =
                value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace('\u3000', ' ')
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .replace('\t', ' ');
        normalized = LEADING_FILLERS.matcher(normalized).replaceFirst("");
        return normalized.trim();
    }
}
