package com.vibesales.salesagent.blueprint;

/**
 * 把 Blueprint 的 prompt 段投影成系统提示词。
 *
 * <p>投影顺序刻意是 {@code soulMd}（身份）在前、{@code agentsMd}（工作准则）在后：身份先立住，
 * 准则再约束，和上游 {@code BlueprintProjector} 写 {@code SOUL.md} + {@code AGENTS.md} 两个文件后
 * 由 harness 拼接的语义顺序一致。
 *
 * <p>{@code knowledgeMd} 不参与投影——本工程走百炼知识库检索，把知识正文塞进系统提示词会和检索链
 * 重复并挤占上下文。
 */
public final class BlueprintPromptProjector {

    private final BlueprintPromptAssets promptAssets = new BlueprintPromptAssets();

    public Projection projectPrompt(AgentBlueprint blueprint) {
        AgentBlueprint.Prompt prompt = blueprint.promptOrEmpty();
        String soulMd = resolveSection(prompt.soulMd(), prompt.soulMdRef(), "prompt.soulMdRef");
        String agentsMd =
                resolveSection(prompt.agentsMd(), prompt.agentsMdRef(), "prompt.agentsMdRef");
        StringBuilder builder = new StringBuilder();
        appendSection(builder, soulMd);
        appendSection(builder, agentsMd);
        if (builder.length() == 0) {
            throw new IllegalStateException(
                    "blueprint "
                            + blueprint.blueprintId()
                            + " projects an empty system prompt; prompt.agentsMd or prompt.agentsMdRef is required");
        }
        return new Projection(agentsMd, soulMd, builder.toString().trim());
    }

    public String project(AgentBlueprint blueprint) {
        return projectPrompt(blueprint).systemPrompt();
    }

    private static void appendSection(StringBuilder builder, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(section.trim());
    }

    private String resolveSection(String inlineText, String ref, String fieldName) {
        if (inlineText != null && !inlineText.isBlank()) {
            return inlineText.trim();
        }
        if (ref != null && !ref.isBlank()) {
            return promptAssets.loadRequired(ref, fieldName);
        }
        return "";
    }

    public record Projection(String agentsMd, String soulMd, String systemPrompt) {}
}
