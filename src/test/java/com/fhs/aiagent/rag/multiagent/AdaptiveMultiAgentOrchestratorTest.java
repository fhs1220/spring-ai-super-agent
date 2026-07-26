package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rag.AgentTelemetryCollector;
import com.fhs.aiagent.rag.AgentProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveMultiAgentOrchestratorTest {

    @Test
    void routesSimpleQuestionToSingleAgentAndCompositeQuestionToSpecialists() {
        ChatModel chatModel = mock(ChatModel.class);
        AdaptiveMultiAgentOrchestrator orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(chatModel).build(), true, 2, 3);

        MultiAgentDecision simple = orchestrator.route("异地恋怎样保持沟通？");
        MultiAgentDecision composite = orchestrator.route(
                "我们有孩子，最近因为育儿、家务分工和经济压力争吵，请制定一周计划。");

        assertThat(simple.multiAgent()).isFalse();
        assertThat(simple.mode()).isEqualTo(AdaptiveMultiAgentOrchestrator.SINGLE_MODE);
        assertThat(composite.multiAgent()).isTrue();
        assertThat(composite.selectedDomains())
                .containsExactly(AgentDomain.PARENTING, AgentDomain.HOUSEHOLD, AgentDomain.FINANCE);
    }

    @Test
    void isolatedEvaluationCanForceBothRoutingArms() {
        ChatModel chatModel = mock(ChatModel.class);
        AdaptiveMultiAgentOrchestrator orchestrator =
                new AdaptiveMultiAgentOrchestrator(
                        ChatClient.builder(chatModel).build(),
                        true,
                        2,
                        3
                );

        MultiAgentDecision forcedSingle = orchestrator.route(
                "孩子、家务和预算需要共同计划。",
                MultiAgentRoutingMode.FORCE_SINGLE
        );
        MultiAgentDecision forcedMulti = orchestrator.route(
                "异地恋怎样沟通？",
                MultiAgentRoutingMode.FORCE_MULTI
        );

        assertThat(forcedSingle.mode()).isEqualTo(
                AdaptiveMultiAgentOrchestrator.SINGLE_MODE);
        assertThat(forcedSingle.selectedDomains()).isEmpty();
        assertThat(forcedMulti.mode()).isEqualTo(
                AdaptiveMultiAgentOrchestrator.MULTI_MODE);
        assertThat(forcedMulti.selectedDomains())
                .containsExactly(AgentDomain.RELATIONSHIP);
        assertThat(forcedMulti.policySource())
                .isEqualTo("EVALUATION_OVERRIDE");
        assertThat(forcedMulti.policyExplorationEligible()).isFalse();
    }

    @Test
    void runsSelectedSpecialistsInParallelAndSynthesizesTheirContributions() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String contents = prompt.getContents();
            if (contents.contains("综合 Agent")) {
                return response("综合后的家庭改善计划[来源 1]");
            }
            if (contents.contains("共同育儿")) {
                return response(contribution("轮流照护孩子"));
            }
            if (contents.contains("家务负荷")) {
                return response(contribution("建立家务轮值表"));
            }
            return response(contribution("设置家庭预算会议"));
        });
        AdaptiveMultiAgentOrchestrator orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(chatModel).build(), true, 2, 3);
        MultiAgentDecision decision = orchestrator.route(
                "我们有孩子，最近因为育儿、家务和经济压力争吵，请给改善计划。");
        AgentTelemetryCollector telemetry = new AgentTelemetryCollector("test-model", 0.3, 0.6);
        List<AgentProgressEvent> progressEvents = new CopyOnWriteArrayList<>();

        MultiAgentAnswer result = orchestrator.execute(
                decision,
                new AgentRequest(
                        "我们有孩子，最近因为育儿、家务和经济压力争吵，请给改善计划。",
                        "（无历史会话）",
                        "[来源 1]\n夫妻应共同协商家庭分工。",
                        "你是恋爱心理顾问。"
                ),
                telemetry,
                progressEvents::add
        );

        assertThat(result.fallbackRequired()).isFalse();
        assertThat(result.answer()).isEqualTo("综合后的家庭改善计划[来源 1]");
        assertThat(result.contributions()).hasSize(3).allMatch(SpecialistContribution::success);
        assertThat(result.contributions())
                .extracting(SpecialistContribution::processReward)
                .allMatch(reward -> reward > 0);
        assertThat(telemetry.snapshot().modelCallCount()).isEqualTo(4);
        assertThat(progressEvents.stream()
                .filter(event -> event.phase().equals("SPECIALIST"))
                .filter(event -> event.status().equals("STARTED")))
                .hasSize(3);
        assertThat(progressEvents.stream()
                .filter(event -> event.phase().equals("SPECIALIST"))
                .filter(event -> event.status().equals("COMPLETED"))
                .filter(event -> !event.title().equals("并行专家")))
                .hasSize(3);
        assertThat(progressEvents)
                .anyMatch(event -> event.phase().equals("SPECIALIST")
                        && event.status().equals("COMPLETED")
                        && event.title().equals("并行专家"));
        assertThat(progressEvents)
                .extracting(AgentProgressEvent::phase, AgentProgressEvent::status)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("SYNTHESIZE", "STARTED"),
                        org.assertj.core.groups.Tuple.tuple("SYNTHESIZE", "COMPLETED")
                );
    }

    @Test
    void retriesOnlyTheFailedSpecialistAndKeepsTheCircuitClosedAfterRecovery() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger parentingCalls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String contents = invocation.<Prompt>getArgument(0).getContents();
            if (contents.contains("综合 Agent")) {
                return response("综合后的家庭方案[来源 1]");
            }
            if (contents.contains("共同育儿") && parentingCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary parenting failure");
            }
            return response(contribution("执行可复盘的家庭分工"));
        });
        AdaptiveMultiAgentOrchestrator orchestrator = resilientOrchestrator(
                chatModel, 2, 3, Duration.ofSeconds(60));
        MultiAgentDecision decision = orchestrator.route("孩子照护和家务分工需要一个共同计划。");
        List<AgentProgressEvent> events = new CopyOnWriteArrayList<>();

        MultiAgentAnswer result = orchestrator.execute(
                decision,
                request("孩子照护和家务分工需要一个共同计划。"),
                new AgentTelemetryCollector("test-model", 0.3, 0.6),
                events::add
        );

        assertThat(result.contributions()).hasSize(2).allMatch(SpecialistContribution::success);
        assertThat(parentingCalls).hasValue(2);
        assertThat(events)
                .anyMatch(event -> event.status().equals("RETRYING")
                        && event.title().equals("育儿协作 Agent"));
        assertThat(orchestrator.health())
                .filteredOn(health -> health.domain() == AgentDomain.PARENTING)
                .singleElement()
                .satisfies(health -> {
                    assertThat(health.status()).isEqualTo("CLOSED");
                    assertThat(health.consecutiveFailures()).isZero();
                });
    }

    @Test
    void opensCircuitAfterRepeatedFailureAndSkipsThatSpecialistOnNextRun() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger parentingCalls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String contents = invocation.<Prompt>getArgument(0).getContents();
            if (contents.contains("共同育儿")) {
                parentingCalls.incrementAndGet();
                throw new IllegalStateException("parenting endpoint unavailable");
            }
            if (contents.contains("综合 Agent")) {
                return response("由可用专业意见生成的降级方案[来源 1]");
            }
            return response(contribution("建立家务轮值表"));
        });
        AdaptiveMultiAgentOrchestrator orchestrator = resilientOrchestrator(
                chatModel, 1, 1, Duration.ofSeconds(60));
        MultiAgentDecision decision = orchestrator.route("孩子照护和家务分工需要一个共同计划。");
        List<AgentProgressEvent> events = new CopyOnWriteArrayList<>();
        AgentTelemetryCollector telemetry = new AgentTelemetryCollector("test-model", 0.3, 0.6);

        MultiAgentAnswer first = orchestrator.execute(
                decision, request("孩子照护和家务分工需要一个共同计划。"), telemetry, events::add);
        MultiAgentAnswer second = orchestrator.execute(
                decision, request("孩子照护和家务分工需要一个共同计划。"), telemetry, events::add);

        assertThat(first.fallbackRequired()).isFalse();
        assertThat(second.fallbackRequired()).isFalse();
        assertThat(parentingCalls).hasValue(1);
        assertThat(events)
                .anyMatch(event -> event.status().equals("SKIPPED")
                        && event.title().equals("育儿协作 Agent"));
        assertThat(orchestrator.health())
                .filteredOn(health -> health.domain() == AgentDomain.PARENTING)
                .singleElement()
                .satisfies(health -> {
                    assertThat(health.status()).isEqualTo("OPEN");
                    assertThat(health.consecutiveFailures()).isEqualTo(1);
                    assertThat(health.openUntil()).isNotNull();
                });
    }

    @Test
    void timesOutOneSpecialistWithoutBlockingAvailableSpecialists() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            String contents = invocation.<Prompt>getArgument(0).getContents();
            if (contents.contains("综合 Agent")) {
                return response("超时降级后的家庭方案[来源 1]");
            }
            if (contents.contains("共同育儿")) {
                Thread.sleep(5_000);
            }
            return response(contribution("使用当前可用专业意见"));
        });
        AdaptiveMultiAgentOrchestrator orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(chatModel).build(),
                true,
                2,
                3,
                1,
                Duration.ofMillis(250),
                1,
                Duration.ofSeconds(60),
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
        MultiAgentDecision decision = orchestrator.route("孩子照护和家务分工需要一个共同计划。");
        List<AgentProgressEvent> events = new CopyOnWriteArrayList<>();
        long startedAt = System.nanoTime();

        MultiAgentAnswer result = orchestrator.execute(
                decision,
                request("孩子照护和家务分工需要一个共同计划。"),
                new AgentTelemetryCollector("test-model", 0.3, 0.6),
                events::add
        );

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2_000);
        assertThat(result.fallbackRequired()).isFalse();
        assertThat(result.contributions())
                .filteredOn(contribution -> contribution.domain() == AgentDomain.PARENTING)
                .singleElement()
                .satisfies(contribution -> {
                    assertThat(contribution.success()).isFalse();
                    assertThat(contribution.error()).contains("Timeout");
                });
        assertThat(events)
                .anyMatch(event -> event.status().equals("TIMED_OUT")
                        && event.title().equals("育儿协作 Agent"));
    }

    private static AdaptiveMultiAgentOrchestrator resilientOrchestrator(
            ChatModel chatModel,
            int maxAttempts,
            int failureThreshold,
            Duration cooldown) {
        return new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(chatModel).build(),
                true,
                2,
                3,
                maxAttempts,
                Duration.ofSeconds(5),
                failureThreshold,
                cooldown,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static AgentRequest request(String question) {
        return new AgentRequest(
                question,
                "（无历史会话）",
                "[来源 1]\n夫妻应共同协商家庭分工。",
                "你是恋爱心理顾问。"
        );
    }

    private static String contribution(String recommendation) {
        return """
                {
                  "findings":["需要共同协作"],
                  "recommendations":["%s"],
                  "citedSources":[1],
                  "uncertainty":"",
                  "confidence":0.85
                }
                """.formatted(recommendation);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
