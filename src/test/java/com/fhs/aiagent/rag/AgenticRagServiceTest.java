package com.fhs.aiagent.rag;

import com.fhs.aiagent.rl.AgentRewardCalculator;
import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rl.model.AgentStepType;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticRagServiceTest {

    @Test
    void followsUpReverifiesAndStoresOnlyUserConversation() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();

        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"婚后亲密关系\"]}"),
                response("{\"sufficient\":false,\"missingInfo\":\"家务冲突\","
                        + "\"followUpQueries\":[\"夫妻家务分工冲突\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\",\"followUpQueries\":[]}"),
                response("先协商家务分工[来源 2]，再定期安排二人相处时间[来源 1]。"),
                response("{\"grounded\":true,\"taskCompleted\":true,\"revisedAnswer\":null}")
        );

        Document firstDocument = new Document(
                "doc-1", "定期安排二人世界并保持深入交流。", Map.of("filename", "已婚篇.md"));
        Document followUpDocument = new Document(
                "doc-2", "与配偶共同协商家务分工。", Map.of("filename", "已婚篇.md"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(firstDocument), List.of(followUpDocument));

        InMemoryAgentTrajectoryRepository trajectoryRepository = new InMemoryAgentTrajectoryRepository();
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(), vectorStore, chatMemory,
                trajectoryRepository, new AgentRewardCalculator());
        List<AgentProgressEvent> progressEvents = new ArrayList<>();

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "婚后不亲密，还总因家务吵架怎么办？",
                "chat-1",
                "你是恋爱心理顾问。",
                progressEvents::add);

        assertThat(result.answer()).isEqualTo("先协商家务分工[来源 2]，再定期安排二人相处时间[来源 1]。");
        assertThat(result.reward().total()).isEqualTo(0.93);
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsExactly(
                        "ROUTE", "PLAN", "RETRIEVE", "VERIFY",
                        "FOLLOW_UP", "VERIFY", "GENERATE", "REVIEW");
        assertThat(result.trace().executionMode()).isEqualTo("SINGLE_AGENT");
        assertThat(result.trace().citations())
                .extracting(citation -> citation.source())
                .containsExactly("已婚篇.md", "已婚篇.md");
        assertThat(result.trace().citations().getFirst().excerpt())
                .contains("定期安排二人世界");
        assertThat(result.trace().steps().get(2).details())
                .anyMatch(detail -> detail.contains("RRF LOCAL RERANK"));
        assertThat(result.trace().telemetry().modelCallCount()).isEqualTo(5);
        assertThat(result.trace().telemetry().totalTokens()).isPositive();
        assertThat(result.trace().telemetry().usageEstimated()).isTrue();
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
        verify(chatModel, times(5)).call(any(Prompt.class));
        assertThat(chatMemory.get("chat-1"))
                .extracting(message -> message.getText())
                .containsExactly(
                        "婚后不亲密，还总因家务吵架怎么办？",
                        "先协商家务分工[来源 2]，再定期安排二人相处时间[来源 1]。"
                );
        assertThat(trajectoryRepository.findById(result.trajectoryId())).isPresent();
        assertThat(trajectoryRepository.findById(result.trajectoryId()).orElseThrow().steps())
                .extracting(step -> step.type())
                .containsExactly(
                        AgentStepType.ROUTE,
                        AgentStepType.PLAN,
                        AgentStepType.RETRIEVE,
                        AgentStepType.VERIFY,
                        AgentStepType.FOLLOW_UP,
                        AgentStepType.VERIFY,
                        AgentStepType.GENERATE,
                        AgentStepType.REVIEW
                );
        assertThat(progressEvents)
                .extracting(AgentProgressEvent::phase)
                .containsSubsequence(
                        "ROUTE", "PLAN", "PLAN", "RETRIEVE", "RETRIEVE",
                        "VERIFY", "VERIFY", "FOLLOW_UP", "FOLLOW_UP",
                        "VERIFY", "VERIFY", "GENERATE", "GENERATE", "REVIEW", "REVIEW"
                );
        assertThat(progressEvents.getFirst().status()).isEqualTo("COMPLETED");
        assertThat(progressEvents.getLast().summary()).isEqualTo("答案已通过审查");
    }

    @Test
    void normalizesAndLimitsModelGeneratedQueries() {
        assertThat(AgenticRagService.normalizeQueries(
                List.of("  查询一  ", "", "查询一", "查询二", "查询三"), 2))
                .containsExactly("查询一", "查询二");
        assertThat(AgenticRagService.normalizeQueries(null, 2)).isEmpty();
    }

    @Test
    void fallsBackToOriginalQuestionWhenPlannerTimesOut() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("planner timeout"))
                .thenReturn(
                        response("{\"sufficient\":true,\"missingInfo\":\"\",\"followUpQueries\":[]}"),
                        response("先表达自己的感受，再共同确定沟通时间[来源 1]。"),
                        response("{\"grounded\":true,\"taskCompleted\":true,\"revisedAnswer\":null}")
                );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "沟通时应真诚表达感受并共同讨论解决方式。",
                        Map.of("filename", "恋爱篇.md")
                )));
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(), vectorStore, chatMemory);

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "异地恋应该怎样沟通？", "chat-fallback", "你是恋爱心理顾问。");

        assertThat(result.answer()).contains("共同确定沟通时间");
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsExactly("ROUTE", "PLAN", "RETRIEVE", "VERIFY", "GENERATE", "REVIEW");
        assertThat(result.trace().steps().get(1).success()).isFalse();
        assertThat(result.trace().steps().get(1).summary()).contains("使用原问题");
        assertThat(result.trace().telemetry().timeoutCount()).isEqualTo(1);
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
