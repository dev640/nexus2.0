package com.nexus.backend.service.ai;

import com.nexus.backend.domain.chat.ChatMessage;
import com.nexus.backend.domain.chat.ChatRole;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private AiEngine engine;

    private AiService aiService;

    private final List<RetrievedContext> context = List.of(
        new RetrievedContext(KnowledgeSourceType.WIKI_PAGE, 1L, "Project Alpha",
            "Alpha ships in October.", "WIKI", 1L, null, 0.9));

    @BeforeEach
    void setUp() {
        aiService = new AiService(engine, new PromptBuilder());
    }

    @Test
    void withoutEngineKeyItAnswersFromContextOnly() {
        when(engine.isConfigured()).thenReturn(false);

        List<String> deltas = new ArrayList<>();
        AiService.AiAnswer answer = aiService.ask("When does Alpha ship?", context, List.of(), deltas::add);

        assertThat(answer.mode()).isEqualTo("context");
        assertThat(answer.model()).isNull();
        assertThat(answer.content()).contains("Project Alpha").contains("ships in October");
        assertThat(deltas).containsExactly(answer.content());
    }

    @Test
    void configuredEngineStreamsDeltasAndReportsModel() {
        when(engine.isConfigured()).thenReturn(true);
        when(engine.answerModel()).thenReturn("gpt-4o-mini");
        when(engine.streamAnswer(anyList(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("Alpha ");
            onDelta.accept("ships in October.");
            return "Alpha ships in October.";
        });

        List<String> deltas = new ArrayList<>();
        AiService.AiAnswer answer = aiService.ask("When does Alpha ship?", context, List.of(), deltas::add);

        assertThat(answer.mode()).isEqualTo("openai");
        assertThat(answer.model()).isEqualTo("gpt-4o-mini");
        assertThat(answer.content()).isEqualTo("Alpha ships in October.");
        assertThat(deltas).containsExactly("Alpha ", "ships in October.");
    }

    @Test
    void partialAnswerSurvivesAMidStreamFailure() {
        when(engine.isConfigured()).thenReturn(true);
        when(engine.answerModel()).thenReturn("gpt-4o-mini");
        when(engine.streamAnswer(anyList(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("Partial ");
            throw new AiEngineException(AiEngineException.Kind.RATE_LIMIT, "HTTP 429");
        });

        AiService.AiAnswer answer = aiService.ask("q", context, List.of(), delta -> {});

        assertThat(answer.content()).startsWith("Partial ").contains("Answer interrupted");
    }

    @Test
    void engineFailureWithoutContentPropagatesForAUserFriendlyError() {
        when(engine.isConfigured()).thenReturn(true);
        when(engine.streamAnswer(anyList(), any()))
            .thenThrow(new AiEngineException(AiEngineException.Kind.TIMEOUT, "timed out"));

        assertThatThrownBy(() -> aiService.ask("q", context, List.of(), delta -> {}))
            .isInstanceOf(AiEngineException.class)
            .hasFieldOrPropertyWithValue("kind", AiEngineException.Kind.TIMEOUT);
    }

    @Test
    void engineFailuresExposeUserFriendlyMessages() {
        assertThat(new AiEngineException(AiEngineException.Kind.RATE_LIMIT, "x").userMessage())
            .contains("rate-limiting");
        assertThat(new AiEngineException(AiEngineException.Kind.AUTH, "x").userMessage())
            .contains("OPENAI_API_KEY");
        assertThat(new AiEngineException(AiEngineException.Kind.NOT_CONFIGURED, "x").userMessage())
            .contains("not configured");
    }
}
