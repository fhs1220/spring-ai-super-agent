package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.multiagent.AgentDomain;
import com.fhs.aiagent.rag.multiagent.SystemOneRoutingAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemOneCalibrationServiceTest {

    @Test
    void loadsIndependentDevelopmentSetAndFindsBestThreshold() {
        SystemOneRoutingAdvisor advisor = question -> {
            boolean multi = question.contains("联合计划")
                    || question.contains("照护轮班")
                    || question.contains("带权重")
                    || question.contains("规则试运行")
                    || question.contains("优化学习时段")
                    || question.contains("72 小时");
            boolean safety = question.contains("跟踪")
                    || question.contains("伤害自己")
                    || question.contains("拿走我的证件")
                    || question.contains("推搡行为");
            return new SystemOneRoutingAdvisor.RoutingAdvice(
                    "SHADOW", "SUCCESS", multi, multi ? 0.8 : 0.2,
                    safety, safety ? 0.9 : 0.1,
                    Map.of(AgentDomain.SAFETY, safety ? 0.9 : 0.1),
                    10, "JEV:test", 100, 5, 0.0000042);
        };
        SystemOneCalibrationService service = new SystemOneCalibrationService(
                new ObjectMapper(), advisor, new DefaultResourceLoader(),
                "classpath:evaluation/system-one-calibration-v1.jsonl",
                0.65, 0.5);

        SystemOneCalibrationReport report = service.evaluate();

        assertThat(report.caseCount()).isEqualTo(24);
        assertThat(report.successfulDecisions()).isEqualTo(24);
        assertThat(report.configuredRouting().balancedAccuracy()).isEqualTo(1);
        assertThat(report.configuredRouting().truePositives()).isEqualTo(6);
        assertThat(report.configuredRouting().trueNegatives()).isEqualTo(18);
        assertThat(report.safetyGuard().recall()).isEqualTo(1);
        assertThat(report.safetyGuard().falseNegatives()).isZero();
        assertThat(report.datasetFingerprint()).hasSize(64);
        assertThat(report.thresholdSweep()).hasSize(21);
    }
}
