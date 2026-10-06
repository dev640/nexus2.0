package com.nexus.backend.service.ai;

import com.nexus.backend.domain.ai.AiMessage;
import com.nexus.backend.domain.ai.AiRole;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    private static RetrievedContext context(String title, String content) {
        return new RetrievedContext(KnowledgeSourceType.WIKI_PAGE, 1L, title, content,
            "WIKI", 1L, LocalDateTime.of(2026, 9, 1, 12, 0), 0.9);
    }

    private static AiMessage message(AiRole role, String content) {
        AiMessage message = new AiMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    void systemMessageNumbersAndDatesContext() {
        String system = builder.systemMessage(List.of(context("Project Alpha", "Alpha ships in October.")));

        assertThat(system).contains("[1] Wiki page \"Project Alpha\" (updated 2026-09-01)");
        assertThat(system).contains("Alpha ships in October.");
        assertThat(system).contains("Never invent facts");
    }

    @Test
    void systemMessageTruncatesOversizedChunks() {
        String system = builder.systemMessage(List.of(context("Big", "A".repeat(5000))));

        assertThat(system).contains("…");
        assertThat(system.length()).isLessThan(PromptBuilder.MAX_CHUNK_CHARS + 1500);
    }

    @Test
    void systemMessageStatesWhenNothingWasFound() {
        assertThat(builder.systemMessage(List.of()))
            .contains("No relevant Nexus information was found");
    }

    @Test
    void fallbackAnswersCiteSourcesAndOfferConfigurationHint() {
        String answer = builder.fallbackAnswer("What is Alpha?", List.of(context("Project Alpha", "Ships in October.")));

        assertThat(answer).contains("I found the following information in your Nexus data");
        assertThat(answer).contains("[1] Wiki page — Project Alpha");
        assertThat(answer).contains("OPENAI_API_KEY");
    }

    @Test
    void fallbackWithoutContextAdmitsInsufficiency() {
        assertThat(builder.fallbackAnswer("Anything?", List.of()))
            .contains("could not find sufficient information");
    }

    @Test
    void messagesAreSystemHistoryThenQuestion() {
        List<AiMessage> history = List.of(
            message(AiRole.USER, "first question"),
            message(AiRole.ASSISTANT, "first answer"));

        List<AiEngine.Message> messages = builder.messages("new question", List.of(context("T", "C")), history);

        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(messages.size() - 1).role()).isEqualTo("user");
        assertThat(messages.get(messages.size() - 1).content()).isEqualTo("new question");
        assertThat(messages).extracting(AiEngine.Message::content)
            .contains("first question", "first answer");
    }

    @Test
    void olderHistoryIsSummarizedAndRecentKeptVerbatim() {
        List<AiMessage> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(message(i % 2 == 0 ? AiRole.USER : AiRole.ASSISTANT,
                "turn ".repeat(100) + i));
        }

        List<AiMessage> usable = builder.usableHistory(history);

        assertThat(usable).hasSize(15);
        // Oldest turns are digested: role-prefixed and short.
        assertThat(usable.get(0).getContent()).startsWith("User: ").hasSizeLessThan(200);
        // The most recent turn is kept verbatim (not role-prefixed), within the per-message cap.
        assertThat(usable.get(usable.size() - 1).getContent()).startsWith("turn");
        assertThat(usable.get(usable.size() - 1).getContent()).hasSizeLessThan(400);
    }

    @Test
    void historyStaysWithinBudget() {
        List<AiMessage> history = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            history.add(message(AiRole.USER, "question number " + i + " " + "details ".repeat(300)));
        }

        int total = builder.usableHistory(history).stream()
            .mapToInt(m -> m.getContent().length()).sum();

        assertThat(total).isLessThanOrEqualTo(PromptBuilder.HISTORY_CHAR_BUDGET);
    }
}
