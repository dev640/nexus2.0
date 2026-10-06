package com.nexus.backend.service.ai;

import com.nexus.backend.domain.ai.AiMessage;
import com.nexus.backend.domain.ai.AiRole;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the exact message package sent to the AI: a system prompt with only
 * the smallest useful set of retrieved context, plus conversation history that
 * keeps recent turns verbatim and summarizes older ones so long conversations
 * stay within a bounded budget.
 */
@Component
public class PromptBuilder {

    /** Per-chunk cap when packing context into the prompt. */
    static final int MAX_CHUNK_CHARS = 1200;
    /** Total context characters sent with a question. */
    static final int MAX_CONTEXT_CHARS = 6000;
    /** Most recent history messages kept verbatim. */
    static final int RECENT_MESSAGES = 10;
    /** Total history characters sent with a question. */
    static final int HISTORY_CHAR_BUDGET = 5000;
    /** Older turns are summarized down to this many characters. */
    static final int DIGEST_CHARS = 140;
    /** Verbatim recent messages are capped at this many characters. */
    static final int HISTORY_MESSAGE_CHARS = 1500;

    private static final String SYSTEM_TEMPLATE = """
        You are Nexus AI, the assistant embedded in the Nexus workspace.
        You answer questions using the Nexus knowledge context provided below.

        Rules:
        - Treat the CONTEXT as the primary, authoritative source of truth and prefer it over general assumptions.
        - Cite the sources you use inline with their bracket numbers, e.g. [1] or [2][3].
        - If the context does not contain the information needed, say clearly that you could not find
          sufficient information in Nexus. Never invent facts, names, numbers or dates.
        - Preserve important numbers, dates, names and technical details exactly as they appear.
        - Clearly distinguish what is known from what is uncertain.
        - The context was already filtered to the current user's permissions; only reference material from it.
        - Answer naturally and conversationally in Markdown (use code blocks and tables when they help).
        - Stay focused on the question; do not quote the entire context back.
        """;

    /** All messages for one engine call: system + history + the new question. */
    public List<AiEngine.Message> messages(
            String question, List<RetrievedContext> context, List<AiMessage> history) {
        List<AiEngine.Message> out = new ArrayList<>();
        out.add(AiEngine.Message.system(systemMessage(context)));

        for (AiMessage message : usableHistory(history)) {
            String content = truncate(message.getContent(), HISTORY_MESSAGE_CHARS);
            if (content.isBlank()) continue;
            out.add(message.getRole() == AiRole.USER
                ? AiEngine.Message.user(content)
                : AiEngine.Message.assistant(content));
        }

        out.add(AiEngine.Message.user(question));
        return out;
    }

    /**
     * History shaped for the prompt: recent turns verbatim, older turns
     * summarized, everything capped by {@link #HISTORY_CHAR_BUDGET} with the
     * oldest content dropped first.
     */
    public List<AiMessage> usableHistory(List<AiMessage> history) {
        if (history == null || history.isEmpty()) return List.of();

        int start = Math.max(0, history.size() - RECENT_MESSAGES);
        List<AiMessage> recent = history.subList(start, history.size());
        List<AiMessage> older = history.subList(0, start);

        // Half the budget keeps recent turns verbatim; the rest summarizes
        // older turns, dropping the oldest first once it is spent.
        int recentBudget = HISTORY_CHAR_BUDGET * 3 / 4;
        int perRecent = recent.isEmpty()
            ? HISTORY_MESSAGE_CHARS
            : Math.max(200, Math.min(HISTORY_MESSAGE_CHARS, recentBudget / recent.size()));

        List<AiMessage> digested = new ArrayList<>();
        int digestUsed = 0;
        for (AiMessage message : older) {
            String digest = summarize(message);
            if (digestUsed + digest.length() > HISTORY_CHAR_BUDGET - recentBudget) break;
            digestUsed += digest.length();
            digested.add(draft(message.getRole(), digest));
        }

        List<AiMessage> verbatim = new ArrayList<>();
        int verbatimUsed = 0;
        for (AiMessage message : recent) {
            String content = truncate(message.getContent(), perRecent);
            if (verbatimUsed + content.length() > recentBudget) break;
            verbatimUsed += content.length();
            verbatim.add(draft(message.getRole(), content));
        }

        digested.addAll(verbatim);
        return digested;
    }

    /** System prompt containing only the retrieved context package. */
    public String systemMessage(List<RetrievedContext> context) {
        StringBuilder sb = new StringBuilder(SYSTEM_TEMPLATE);
        sb.append("\nCONTEXT (Nexus knowledge, most relevant first):\n");

        if (context == null || context.isEmpty()) {
            sb.append("(No relevant Nexus information was found for this question.)\n");
            return sb.toString();
        }

        int budget = MAX_CONTEXT_CHARS;
        int number = 1;
        for (RetrievedContext item : context) {
            String content = truncate(item.content(), MAX_CHUNK_CHARS);
            String header = "[" + number + "] " + item.sourceType().label()
                + " \"" + item.title() + "\""
                + (item.updatedAt() != null ? " (updated " + item.updatedAt().toLocalDate() + ")" : "")
                + "\n";
            if (budget - header.length() - content.length() < 0) break;
            budget -= header.length() + content.length() + 2;
            sb.append(header).append(content).append("\n\n");
            number++;
        }
        if (number == 1) {
            sb.append("(No relevant Nexus information was found for this question.)\n");
        }
        return sb.toString();
    }

    /**
     * Answer used when no AI engine is configured: an honest, source-cited
     * digest of the retrieved context so the feature stays useful without a key.
     */
    public String fallbackAnswer(String question, List<RetrievedContext> context) {
        if (context == null || context.isEmpty()) {
            return "I could not find sufficient information in Nexus to answer that. "
                + "Try rephrasing the question, or check that the relevant pages and records are indexed.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("I found the following information in your Nexus data:\n\n");
        int number = 1;
        for (RetrievedContext item : context) {
            sb.append("**[").append(number++).append("] ")
                .append(item.sourceType().label()).append(" — ").append(item.title()).append("**\n\n")
                .append(truncate(item.content(), MAX_CHUNK_CHARS)).append("\n\n");
        }
        sb.append("_Context-only mode: set `OPENAI_API_KEY` on the server to enable AI-generated answers._");
        return sb.toString();
    }

    // ---------- helpers ----------

    private String summarize(AiMessage message) {
        String role = message.getRole() == AiRole.USER ? "User" : "Assistant";
        return role + ": " + truncate(message.getContent(), DIGEST_CHARS);
    }

    private AiMessage draft(AiRole role, String content) {
        AiMessage message = new AiMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        String normalized = text.trim();
        if (normalized.length() <= max) return normalized;
        int cut = normalized.lastIndexOf(' ', max);
        if (cut < max / 2) cut = max;
        return normalized.substring(0, cut).stripTrailing() + "…";
    }
}
