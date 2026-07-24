package com.fhs.aiagent.rl;

import com.fhs.aiagent.rl.model.AgentStep;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgentRunMetrics;
import com.fhs.aiagent.rl.model.AgentTrajectory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AgentTrajectoryRecorder {

    private final String trajectoryId = UUID.randomUUID().toString();

    private final String chatId;

    private final String policyVersion;

    private final String model;

    private final String question;

    private final Instant startedAt = Instant.now();

    private final List<AgentStep> steps = new ArrayList<>();

    private final Set<String> retrievedDocumentIds = new LinkedHashSet<>();

    public AgentTrajectoryRecorder(String chatId, String policyVersion, String model, String question) {
        this.chatId = chatId;
        this.policyVersion = policyVersion;
        this.model = model;
        this.question = question;
    }

    public Instant startStep() {
        return Instant.now();
    }

    public void record(AgentStepType type,
                       Instant stepStartedAt,
                       boolean success,
                       Map<String, Object> input,
                       Map<String, Object> output) {
        steps.add(new AgentStep(
                UUID.randomUUID().toString(),
                type,
                stepStartedAt,
                Math.max(0, Duration.between(stepStartedAt, Instant.now()).toMillis()),
                success,
                Map.copyOf(input),
                Map.copyOf(output)
        ));
    }

    public void recordWithDuration(AgentStepType type,
                                   long durationMs,
                                   boolean success,
                                   Map<String, Object> input,
                                   Map<String, Object> output) {
        steps.add(new AgentStep(
                UUID.randomUUID().toString(),
                type,
                Instant.now().minusMillis(Math.max(0, durationMs)),
                Math.max(0, durationMs),
                success,
                Map.copyOf(input),
                Map.copyOf(output)
        ));
    }

    public void addRetrievedDocumentIds(Iterable<String> documentIds) {
        documentIds.forEach(retrievedDocumentIds::add);
    }

    public AgentTrajectory complete(String finalAnswer) {
        return complete(finalAnswer, null);
    }

    public AgentTrajectory complete(String finalAnswer, AgentRunMetrics telemetry) {
        return snapshot("COMPLETED", finalAnswer, telemetry, null);
    }

    public AgentTrajectory fail(Throwable throwable) {
        return fail(throwable, null);
    }

    public AgentTrajectory fail(Throwable throwable, AgentRunMetrics telemetry) {
        String error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        return snapshot("FAILED", null, telemetry, error);
    }

    public String trajectoryId() {
        return trajectoryId;
    }

    private AgentTrajectory snapshot(String status,
                                     String finalAnswer,
                                     AgentRunMetrics telemetry,
                                     String error) {
        return new AgentTrajectory(
                trajectoryId,
                chatId,
                policyVersion,
                model,
                question,
                startedAt,
                Instant.now(),
                status,
                List.copyOf(steps),
                List.copyOf(retrievedDocumentIds),
                finalAnswer,
                null,
                null,
                null,
                telemetry,
                error
        );
    }
}
