package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagAbEvaluationServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsVersionedBenchmarkAlternatesOrderAndPersistsPassingReport() {
        List<RagEvaluationVariant> executionOrder = new ArrayList<>();
        RagEvaluationVariantExecutor executor = (variant, evaluationCase, chatId) -> {
            executionOrder.add(variant);
            if (variant == RagEvaluationVariant.TRADITIONAL_RAG) {
                return new RagVariantExecution(
                        variant,
                        "请你详细描述具体情况。",
                        200,
                        0,
                        0,
                        false,
                        "",
                        ""
                );
            }
            String answer = candidateAnswer(evaluationCase.id());
            String mode = "simple-long-distance".equals(evaluationCase.id())
                    ? "SINGLE_AGENT"
                    : "ADAPTIVE_MULTI_AGENT";
            return new RagVariantExecution(
                    variant,
                    answer,
                    500,
                    100,
                    0.002,
                    true,
                    mode,
                    ""
            );
        };
        RagAbEvaluationService service = new RagAbEvaluationService(
                executor,
                new RagAnswerScorer(),
                new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader(),
                "classpath:evaluation/love-rag-ab.jsonl",
                tempDirectory.toString(),
                50,
                0.72,
                0.02,
                0.8
        );
        List<RagAbEvaluationService.Progress> progress = new ArrayList<>();

        RagAbReport report = service.evaluate(
                "test-run",
                2,
                () -> false,
                progress::add
        );

        assertThat(service.loadCases()).hasSize(12);
        assertThat(executionOrder).containsExactly(
                RagEvaluationVariant.TRADITIONAL_RAG,
                RagEvaluationVariant.AGENTIC_RAG_V5,
                RagEvaluationVariant.AGENTIC_RAG_V5,
                RagEvaluationVariant.TRADITIONAL_RAG
        );
        assertThat(report.caseCount()).isEqualTo(2);
        assertThat(report.candidateWins()).isEqualTo(2);
        assertThat(report.candidate().averageScore()).isGreaterThan(0.9);
        assertThat(report.routeAccuracy()).isEqualTo(1);
        assertThat(report.regressionGatePassed()).isTrue();
        assertThat(report.candidate().totalTokens()).isEqualTo(200);
        assertThat(report.candidate().estimatedCostCny()).isEqualTo(0.004);
        assertThat(report.baseline().usageMeasuredCases()).isZero();
        assertThat(report.candidate().usageMeasuredCases()).isEqualTo(2);
        assertThat(progress).hasSize(4);
        assertThat(Files.exists(Path.of(report.reportPath()))).isTrue();
    }

    private String candidateAnswer(String caseId) {
        if ("simple-long-distance".equals(caseId)) {
            return "建议固定沟通时间和频率，交流时先倾听并理解对方，每周一起复盘误会与改进。[来源 1]";
        }
        String daily = """
                星期一到星期日都安排明确任务：每天由双方轮流完成育儿和孩子照护，
                按清单执行家务分工；用十分钟讨论经济预算和当天支出，睡前进行复盘回顾。
                """;
        return """
                假设孩子已进入稳定作息，双方工作日晚上都有三十分钟共同时间。
                星期一：建立育儿轮班、家务清单、经济预算和复盘规则。
                星期二：交换育儿任务，检查家务分工并回顾支出。
                星期三：共同陪伴孩子，调整清洁任务和家庭预算。
                星期四：核对育儿负担、家务公平性与经济目标。
                星期五：减少非必要支出，完成一周中遗漏的家务。
                星期六：双方各获得个人休息时间，同时轮流照顾孩子。
                星期日：完成育儿、家务、经济和关系的完整复盘，确定下周调整。
                %s
                %s
                %s
                [来源 1]
                """.formatted(daily, daily, daily);
    }
}
