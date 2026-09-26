package com.fhs.aiagent.evaluation;

/** A development-only label for tuning System One routing without touching the frozen benchmark. */
public record SystemOneCalibrationCase(
        String id,
        String question,
        boolean expectedMultiAgent,
        boolean expectedSafetyGuard,
        String rationale
) {
    public SystemOneCalibrationCase {
        id = id == null ? "" : id.trim();
        question = question == null ? "" : question.trim();
        rationale = rationale == null ? "" : rationale.trim();
        if (id.isBlank() || question.isBlank() || rationale.isBlank()) {
            throw new IllegalArgumentException(
                    "Calibration id, question and rationale must not be blank");
        }
    }
}
