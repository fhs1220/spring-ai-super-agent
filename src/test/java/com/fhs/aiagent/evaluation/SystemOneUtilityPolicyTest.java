package com.fhs.aiagent.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class SystemOneUtilityPolicyTest {

    @Test
    void retainsMarginalCostAndLatencyPenaltiesAboveBothScales() {
        SystemOneUtilityPolicy policy = SystemOneUtilityPolicy.defaults();

        assertThat(policy.utility(0.9, 1, 60_000)).isCloseTo(0.8, offset(1e-9));
        assertThat(policy.utility(0.9, 2, 120_000)).isCloseTo(0.7, offset(1e-9));
        assertThat(policy.utility(0.9, 3, 180_000)).isCloseTo(0.6, offset(1e-9));
    }

    @Test
    void rejectsInvalidScalesAndUnmeasurableNumericalInputs() {
        assertThatThrownBy(() -> new SystemOneUtilityPolicy(0.05, 0, 0.05, 60_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemOneUtilityPolicy.defaults().utility(0.9, Double.NaN, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemOneUtilityPolicy.defaults().utility(0.9, 1, -10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
