package com.fhs.aiagent.evaluation;

public interface CounterfactualQualityJudge {

    PairJudgment judge(String question, String questionFingerprint,
                       String singleAnswer, String multiAnswer);

    record PairJudgment(
            double singleQuality,
            double multiQuality,
            double confidence,
            String rationale,
            String contractVersion,
            JudgeUsage usage
    ) {

        public PairJudgment(double singleQuality, double multiQuality, double confidence,
                            String rationale, String contractVersion) {
            this(singleQuality, multiQuality, confidence, rationale, contractVersion,
                    JudgeUsage.unknown());
        }

        public PairJudgment {
            singleQuality = clamp(singleQuality);
            multiQuality = clamp(multiQuality);
            confidence = clamp(confidence);
            rationale = rationale == null ? "" : rationale.trim();
            contractVersion = contractVersion == null ? "" : contractVersion;
            usage = usage == null ? JudgeUsage.unknown() : usage;
        }

        private static double clamp(double value) {
            return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
        }
    }

    record JudgeUsage(String model, String responseId, long inputTokens, long outputTokens,
                      double estimatedCostCny, boolean measured, String promptFingerprint) {
        public static JudgeUsage unknown() {
            return new JudgeUsage("UNSPECIFIED", "", 0, 0, 0, false, "");
        }
    }
}
