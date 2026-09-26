package com.fhs.aiagent.rag.multiagent;

/**
 * Fire-and-forget dual-provider observation. Implementations must never own the
 * authoritative routing decision or block the request on provider inference.
 */
public interface SystemOneShadowComparison {

    Submission submit(String question, boolean authoritativeMultiAgent, String featureBucket);

    static SystemOneShadowComparison disabled() {
        return (question, authoritativeMultiAgent, featureBucket) -> Submission.disabled();
    }

    record Submission(boolean active, boolean accepted, String sampleId, String status) {

        public Submission {
            sampleId = sampleId == null ? "" : sampleId;
            status = status == null ? "DISABLED" : status;
        }

        static Submission disabled() {
            return new Submission(false, false, "", "DISABLED");
        }

        static Submission queued(String sampleId) {
            return new Submission(true, true, sampleId, "QUEUED_DUAL_SHADOW");
        }

        static Submission saturated() {
            return new Submission(true, false, "", "SKIPPED_CAPACITY");
        }
    }
}
