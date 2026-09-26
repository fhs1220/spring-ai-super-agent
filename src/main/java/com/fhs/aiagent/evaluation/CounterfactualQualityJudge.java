package com.fhs.aiagent.evaluation;

public interface CounterfactualQualityJudge {

    PairJudgment judge(String question, String questionFingerprint,
                       String singleAnswer, String multiAnswer);

    record PairJudgment(
            double singleQuality,
            double multiQuality,
            double confidence,
            String rationale,
            String contractVersion
    ) {

        public PairJudgment {
            singleQuality = clamp(singleQuality);
            multiQuality = clamp(multiQuality);
            confidence = clamp(confidence);
            rationale = rationale == null ? "" : rationale.trim();
            contractVersion = contractVersion == null ? "" : contractVersion;
        }

        private static double clamp(double value) {
            return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
        }
    }
}
