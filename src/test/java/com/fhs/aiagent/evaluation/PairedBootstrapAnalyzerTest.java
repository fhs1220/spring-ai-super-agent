package com.fhs.aiagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PairedBootstrapAnalyzerTest {

    private final PairedBootstrapAnalyzer analyzer =
            new PairedBootstrapAnalyzer();

    @Test
    void producesDeterministicPairedConfidenceIntervalAndSignTest() {
        List<Double> baseline = IntStream.range(0, 36)
                .mapToObj(index -> 0.70 + (index % 3) * 0.01)
                .toList();
        List<Double> candidate = baseline.stream()
                .map(score -> score + 0.05)
                .toList();

        AlignmentAblationReport.PairedQualityComparison first = analyzer.analyze(
                "fingerprint:full",
                baseline,
                candidate,
                5_000,
                0.95,
                0.03,
                0.02,
                30
        );
        AlignmentAblationReport.PairedQualityComparison second = analyzer.analyze(
                "fingerprint:full",
                baseline,
                candidate,
                5_000,
                0.95,
                0.03,
                0.02,
                30
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.comparable()).isTrue();
        assertThat(first.enoughSamples()).isTrue();
        assertThat(first.meanDelta()).isEqualTo(0.05);
        assertThat(first.lowerConfidenceBound()).isEqualTo(0.05);
        assertThat(first.upperConfidenceBound()).isEqualTo(0.05);
        assertThat(first.wins()).isEqualTo(36);
        assertThat(first.ties()).isZero();
        assertThat(first.losses()).isZero();
        assertThat(first.twoSidedSignTestPValue()).isZero();
        assertThat(first.probabilityOfImprovement()).isEqualTo(1);
        assertThat(first.statisticallySignificant()).isTrue();
        assertThat(first.nonInferiorityPassed()).isTrue();
    }

    @Test
    void rejectsStatisticallyClearRegressionAgainstNonInferiorityMargin() {
        List<Double> baseline = IntStream.range(0, 36)
                .mapToObj(index -> 0.75)
                .toList();
        List<Double> candidate = IntStream.range(0, 36)
                .mapToObj(index -> 0.70)
                .toList();

        AlignmentAblationReport.PairedQualityComparison result = analyzer.analyze(
                "fingerprint:regression",
                baseline,
                candidate,
                2_000,
                0.95,
                0.03,
                0.02,
                30
        );

        assertThat(result.meanDelta()).isEqualTo(-0.05);
        assertThat(result.upperConfidenceBound()).isEqualTo(-0.05);
        assertThat(result.losses()).isEqualTo(36);
        assertThat(result.statisticallySignificant()).isTrue();
        assertThat(result.nonInferiorityPassed()).isFalse();
    }
}
