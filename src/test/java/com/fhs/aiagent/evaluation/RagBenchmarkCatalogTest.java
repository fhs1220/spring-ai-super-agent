package com.fhs.aiagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhs.aiagent.rag.multiagent.AdaptiveMultiAgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RagBenchmarkCatalogTest {

    @Test
    void benchmarkHasInterviewGradeCoverageAndLabelsMatchRouter() {
        RagAbEvaluationService service = new RagAbEvaluationService(
                (variant, evaluationCase, chatId) -> {
                    throw new UnsupportedOperationException("not executed");
                },
                new RagAnswerScorer(),
                new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader(),
                "classpath:evaluation/love-rag-ab.jsonl",
                Path.of("target", "benchmark-catalog-test").toString(),
                100,
                30,
                0.72,
                0.02,
                0.8,
                1.10,
                "model-test",
                "a".repeat(64),
                "b".repeat(64),
                "reward-v2",
                "test-deployment"
        );
        List<RagEvaluationCase> cases = service.loadCases();
        ChatModel chatModel = mock(ChatModel.class);
        AdaptiveMultiAgentOrchestrator router =
                new AdaptiveMultiAgentOrchestrator(
                        ChatClient.builder(chatModel).build(),
                        true,
                        2,
                        3
                );

        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases)
                .filteredOn(item -> item.tags().contains("simple"))
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(cases)
                .filteredOn(item -> item.tags().contains("composite"))
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(cases)
                .filteredOn(item -> item.tags().contains("safety"))
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(cases)
                .filteredOn(item -> item.tags().contains("adversarial"))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(cases)
                .allSatisfy(item -> assertThat(
                        router.route(item.question()).mode())
                        .as(item.id())
                        .isEqualTo(item.expectedExecutionMode()));

        RagAbEvaluationService.BenchmarkMetadata metadata =
                service.benchmarkMetadata();
        assertThat(metadata.caseCount()).isEqualTo(cases.size());
        assertThat(metadata.expectedSingleAgentCases()).isPositive();
        assertThat(metadata.expectedMultiAgentCases()).isPositive();
        assertThat(metadata.fingerprint()).hasSize(64);
    }
}
