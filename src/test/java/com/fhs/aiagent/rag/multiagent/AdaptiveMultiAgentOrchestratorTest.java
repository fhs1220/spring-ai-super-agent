package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rag.AgentTelemetryCollector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

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

        MultiAgentAnswer result = orchestrator.execute(
                decision,
                new AgentRequest(
                        "我们有孩子，最近因为育儿、家务和经济压力争吵，请给改善计划。",
                        "（无历史会话）",
                        "[来源 1]\n夫妻应共同协商家庭分工。",
                        "你是恋爱心理顾问。"
                ),
                telemetry
        );

        assertThat(result.fallbackRequired()).isFalse();
        assertThat(result.answer()).isEqualTo("综合后的家庭改善计划[来源 1]");
        assertThat(result.contributions()).hasSize(3).allMatch(SpecialistContribution::success);
        assertThat(result.contributions())
                .extracting(SpecialistContribution::processReward)
                .allMatch(reward -> reward > 0);
        assertThat(telemetry.snapshot().modelCallCount()).isEqualTo(4);
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
