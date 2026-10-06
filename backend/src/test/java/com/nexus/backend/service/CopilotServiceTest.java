package com.nexus.backend.service;

import com.nexus.backend.dto.AnalyticsResponse;
import com.nexus.backend.dto.CopilotAnswerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Copilot's contract: always answer, and say why when the answer did not
 * come from the model.
 *
 * <p>Before the failure taxonomy these cases were indistinguishable. Every one
 * of them produced the same grounded answer with no explanation, which is why a
 * Copilot whose key had been revoked was indistinguishable from a healthy one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CopilotServiceTest {

    @Mock private AnalyticsService analyticsService;
    @Mock private LlmClient llmClient;

    private CopilotService copilot;

    @BeforeEach
    void setUp() {
        copilot = new CopilotService(analyticsService, llmClient);
        when(analyticsService.getOverview(any())).thenReturn(emptyOverview());
    }

    // ---------- the happy path ----------

    @Test
    void usesTheModelAnswerWhenTheCallSucceeds() {
        when(llmClient.configurationFailure()).thenReturn(null);
        when(llmClient.complete(anyString(), anyString()))
            .thenReturn(LlmResult.answered("Achal is carrying the most open work."));

        CopilotAnswerResponse response = copilot.ask("who is overloaded", null);

        assertThat(response.mode()).isEqualTo("llm");
        assertThat(response.answer()).isEqualTo("Achal is carrying the most open work.");
        // No reason is shown when the model actually answered.
        assertThat(response.reason()).isNull();
    }

    // ---------- configuration failures ----------

    @Test
    void reportsWhenCopilotIsNotConfiguredAtAll() {
        when(llmClient.configurationFailure()).thenReturn(LlmFailure.NOT_CONFIGURED);

        CopilotAnswerResponse response = copilot.ask("what is blocking", null);

        assertThat(response.mode()).isEqualTo("grounded");
        assertThat(response.reason()).isEqualTo(LlmFailure.NOT_CONFIGURED.safeReason());
        assertThat(response.answer()).isNotBlank();
        // A misconfigured Copilot must not still attempt a request.
        verify(llmClient, never()).complete(anyString(), anyString());
    }

    /**
     * The bug this phase fixes: a key without a model used to be attempted
     * against a hardcoded id, so the only symptom was a feature that never
     * worked. The specific missing setting is now named.
     */
    @Test
    void reportsAMissingModelDistinctlyFromAMissingKey() {
        when(llmClient.configurationFailure()).thenReturn(LlmFailure.MODEL_NOT_CONFIGURED);

        CopilotAnswerResponse response = copilot.ask("what is blocking", null);

        assertThat(response.reason()).isEqualTo(LlmFailure.MODEL_NOT_CONFIGURED.safeReason());
        assertThat(response.reason()).isNotEqualTo(LlmFailure.NOT_CONFIGURED.safeReason());
        assertThat(response.reason()).containsIgnoringCase("model");
        verify(llmClient, never()).complete(anyString(), anyString());
    }

    // ---------- call failures ----------

    @Test
    void fallsBackAndNamesTheReasonWhenTheCallFails() {
        when(llmClient.configurationFailure()).thenReturn(null);
        when(llmClient.complete(anyString(), anyString())).thenReturn(LlmResult.failed(LlmFailure.INVALID_API_KEY));

        CopilotAnswerResponse response = copilot.ask("what is blocking", null);

        assertThat(response.mode()).isEqualTo("grounded");
        assertThat(response.reason()).isEqualTo(LlmFailure.INVALID_API_KEY.safeReason());
        assertThat(response.answer()).isNotBlank();
    }

    @Test
    void everyFailureModeStillYieldsAUsableGroundedAnswer() {
        when(llmClient.configurationFailure()).thenReturn(null);

        for (LlmFailure failure : LlmFailure.values()) {
            when(llmClient.complete(anyString(), anyString())).thenReturn(LlmResult.failed(failure));

            CopilotAnswerResponse response = copilot.ask("who is overloaded", null);

            assertThat(response.answer()).as("%s fallback answer", failure).isNotBlank();
            assertThat(response.reason()).as("%s reason", failure).isEqualTo(failure.safeReason());
        }
    }

    /**
     * A refusal is not a failure: Gemini returns HTTP 200 with an empty
     * candidates list when a prompt is blocked. The user must still get the
     * grounded answer, with a reason that does not blame them.
     */
    @Test
    void anEmptyButSuccessfulResponseStillFallsBackWithAReason() {
        when(llmClient.configurationFailure()).thenReturn(null);
        when(llmClient.complete(anyString(), anyString())).thenReturn(LlmResult.failed(LlmFailure.MALFORMED_RESPONSE));

        CopilotAnswerResponse response = copilot.ask("summarize urgent tasks", null);

        assertThat(response.mode()).isEqualTo("grounded");
        assertThat(response.reason()).isEqualTo(LlmFailure.MALFORMED_RESPONSE.safeReason());
    }

    // ---------- what reaches the client ----------

    @Test
    void theReasonIsAlwaysSafeToDisplay() {
        when(llmClient.configurationFailure()).thenReturn(null);

        for (LlmFailure failure : LlmFailure.values()) {
            when(llmClient.complete(anyString(), anyString())).thenReturn(LlmResult.failed(failure));

            String reason = copilot.ask("what is blocking", null).reason();

            assertThat(reason).isNotBlank();
            assertThat(reason).doesNotContain("sk-");
            assertThat(reason).doesNotContain("java.");
            assertThat(reason).doesNotContain("http");
        }
    }

    @Test
    void passesTheQuestionThroughToTheModel() {
        when(llmClient.configurationFailure()).thenReturn(null);
        when(llmClient.complete(anyString(), anyString())).thenReturn(LlmResult.answered("ok"));

        copilot.ask("What is blocking the sprint?", 7L);

        verify(llmClient).complete(anyString(), org.mockito.ArgumentMatchers.eq("What is blocking the sprint?"));
    }

    private static AnalyticsResponse emptyOverview() {
        return new AnalyticsResponse(
            new AnalyticsResponse.VelocityData(List.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new AnalyticsResponse.Summary(0L, 0L, 0.0, 0, 0)
        );
    }
}