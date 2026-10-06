package com.nexus.backend.service;

import com.nexus.backend.dto.AnalyticsResponse;
import com.nexus.backend.dto.CopilotAnswerResponse;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Answers workspace questions. Always computes a grounded answer from live data;
 * when an LLM is configured it is asked to phrase the answer using that data as
 * context, and falls back to the grounded answer on any failure.
 *
 * <p>The fallback used to be silent, so a Copilot whose key had been revoked
 * looked identical to a healthy one. The reason for the fallback is now
 * reported alongside the answer, using only wording that is safe to display.
 */
@Service
public class CopilotService {

    private final AnalyticsService analyticsService;
    private final LlmClient llmClient;

    public CopilotService(AnalyticsService analyticsService, LlmClient llmClient) {
        this.analyticsService = analyticsService;
        this.llmClient = llmClient;
    }

    public CopilotAnswerResponse ask(String question, Long projectId) {
        AnalyticsResponse overview = analyticsService.getOverview(projectId);
        String grounded = groundedAnswer(question, overview);

        // Checked up front so a misconfigured Copilot reports the actual missing
        // setting instead of the generic failure of an impossible request.
        LlmFailure notConfigured = llmClient.configurationFailure();
        if (notConfigured != null) {
            return new CopilotAnswerResponse(grounded, "grounded", notConfigured.safeReason());
        }

        String systemPrompt = """
            You are the Nexus delivery copilot. Answer only from the provided
            workspace facts. Be concise (2-4 sentences), concrete and specific:
            name tasks and people. If the facts do not cover the question, say so.

            FACTS:
            """ + facts(overview);

        LlmResult result = llmClient.complete(systemPrompt, question);
        if (result.answered()) {
            return new CopilotAnswerResponse(result.text(), "llm", null);
        }
        return new CopilotAnswerResponse(grounded, "grounded", result.failure().safeReason());
    }

    /** Compact description of live workspace state, used as LLM context. */
    private String facts(AnalyticsResponse o) {
        String velocity = o.velocity().perSprint().stream()
            .map(v -> "sprint " + v.number() + " (" + v.donePoints() + "/" + v.committedPoints() + " pts, goal: " + v.goal() + ")")
            .collect(Collectors.joining("; "));
        String risks = o.risks().stream()
            .map(r -> r.title() + " — " + r.reason() + " [" + r.priority() + "]")
            .collect(Collectors.joining("; "));
        String load = o.teamLoad().stream()
            .map(m -> m.name() + ": " + m.openTasks() + " open tasks / " + m.openPoints() + " pts")
            .collect(Collectors.joining("; "));
        String statuses = o.statusBreakdown().stream()
            .map(s -> s.count() + " " + s.status())
            .collect(Collectors.joining(", "));

        return """
            Total tasks: %d, done: %d (%.1f%% complete)
            Task statuses: %s
            Velocity: %s
            Risks: %s
            Team load: %s
            """.formatted(
            o.summary().totalTasks(),
            o.summary().doneTasks(),
            o.summary().completionRate(),
            statuses.isBlank() ? "none" : statuses,
            velocity.isBlank() ? "no sprints" : velocity,
            risks.isBlank() ? "none detected" : risks,
            load.isBlank() ? "no assigned open work" : load
        );
    }

    /** Deterministic, data-only answer — always available, no API key needed. */
    private String groundedAnswer(String question, AnalyticsResponse o) {
        String q = question == null ? "" : question.toLowerCase();

        if (q.contains("block") || q.contains("risk")) {
            if (o.risks().isEmpty()) return "No risks detected right now — nothing is blocked or stalled.";
            return o.risks().size() + " risk" + (o.risks().size() > 1 ? "s" : "") + " detected in real task data: "
                + o.risks().stream().limit(4)
                    .map(r -> "\"" + r.title() + "\" (" + r.reason() + ")")
                    .collect(Collectors.joining("; ")) + ".";
        }

        if (q.contains("urgent") || q.contains("priority")) {
            var urgent = o.risks().stream()
                .filter(r -> "URGENT".equals(r.priority()))
                .toList();
            if (urgent.isEmpty()) return "No urgent-priority work is at risk.";
            return urgent.size() + " urgent task" + (urgent.size() > 1 ? "s need" : " needs") + " attention: "
                + urgent.stream().map(r -> r.title()).collect(Collectors.joining(", ")) + ".";
        }

        if (q.contains("sprint") || q.contains("track") || q.contains("velocity")) {
            var sprints = o.velocity().perSprint();
            if (sprints.isEmpty()) return "No sprint data yet — create a sprint from the Sprints page.";
            var latest = sprints.get(sprints.size() - 1);
            int pct = latest.committedPoints() > 0
                ? (int) Math.round(100.0 * latest.donePoints() / latest.committedPoints())
                : 0;
            return "Sprint " + latest.number() + " (\"" + latest.goal() + "\") is " + pct + "% complete — "
                + latest.donePoints() + " of " + latest.committedPoints()
                + " committed points done. Overall completion is " + o.summary().completionRate() + "%.";
        }

        if (q.contains("overload") || q.contains("capacity") || q.contains("workload") || q.contains("who")) {
            if (o.teamLoad().isEmpty()) return "No open assigned work — nobody is carrying a backlog.";
            var top = o.teamLoad().get(0);
            StringBuilder sb = new StringBuilder(top.name() + " is carrying the most open work at "
                + top.openPoints() + " points across " + top.openTasks() + " task"
                + (top.openTasks() == 1 ? "" : "s"));
            var rest = o.teamLoad().stream().skip(1).limit(2)
                .map(m -> m.name() + " (" + m.openPoints() + " pts)")
                .toList();
            if (!rest.isEmpty()) sb.append(". Also loaded: ").append(String.join(", ", rest));
            return sb.append(".").toString();
        }

        return "There are " + o.summary().totalTasks() + " tasks with a " + o.summary().completionRate()
            + "% completion rate, " + o.risks().size() + " detected risk"
            + (o.risks().size() == 1 ? "" : "s") + ", and " + o.teamLoad().size()
            + " people with open work. Ask about blockers, urgent items, sprint progress, or workload.";
    }
}
