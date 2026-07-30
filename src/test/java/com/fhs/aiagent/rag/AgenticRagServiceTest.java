package com.fhs.aiagent.rag;

import com.fhs.aiagent.rl.AgentRewardCalculator;
import com.fhs.aiagent.rl.InMemoryAgentTrajectoryRepository;
import com.fhs.aiagent.rag.multiagent.MultiAgentRoutingMode;
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
    void evaluationRunDoesNotPolluteConversationOrTrainingTrajectories() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String validAnswer = longEnough(
                "先暂停情绪，再表达感受并共同协商。[来源 1]");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(validAnswer),
                response("{\"grounded\":true,\"taskCompleted\":true,"
                        + "\"revisedAnswer\":null}")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应冷静、表达感受并共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        InMemoryAgentTrajectoryRepository trajectoryRepository =
                new InMemoryAgentTrajectoryRepository();
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory,
                trajectoryRepository,
                new AgentRewardCalculator()
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么恢复沟通？",
                "isolated-evaluation",
                "你是恋爱心理顾问。",
                AgentProgressListener.NONE,
                AgenticRagService.RunOptions.evaluation(
                        MultiAgentRoutingMode.FORCE_SINGLE)
        );

        assertThat(result.trace().executionMode()).isEqualTo("SINGLE_AGENT");
        assertThat(result.trajectoryId()).isNotBlank();
        assertThat(trajectoryRepository.findAll()).isEmpty();
        assertThat(chatMemory.get("isolated-evaluation")).isEmpty();
    }

    @Test
    void followsUpReverifiesAndStoresOnlyUserConversation() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String validAnswer = longEnough(
                "先协商家务分工[来源 2]，再定期安排二人相处时间[来源 1]。");

        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"婚后亲密关系\"]}"),
                response("{\"sufficient\":false,\"missingInfo\":\"家务冲突\","
                        + "\"followUpQueries\":[\"夫妻家务分工冲突\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\",\"followUpQueries\":[]}"),
                response(validAnswer),
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

        assertThat(result.answer()).isEqualTo(validAnswer);
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
                        validAnswer
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
    void normalizesCommonCitationListsAndValidatesEverySourceIndex() {
        assertThat(AgenticRagService.normalizeCitationSyntax(
                "建议一[来源 1, 2, 2]；建议二来源：[3、4]；参考来源 [5]；"
                        + "安排（来源6, 7）；结论依据来源 8。"))
                .isEqualTo(
                        "建议一[来源 1][来源 2]；建议二[来源 3][来源 4]；"
                                + "[来源 5]；安排[来源 6][来源 7]；结论[来源 8]。");
        String context = """
                [来源 1 | a.md]
                证据一
                ---
                [来源 2 | b.md]
                证据二
                """;
        assertThat(AgenticRagService.satisfiesCitationContract(
                "行动[来源 1][来源 2]", context)).isTrue();
        assertThat(AgenticRagService.satisfiesCitationContract(
                "行动但没有引用", context)).isFalse();
        assertThat(AgenticRagService.satisfiesCitationContract(
                "行动[来源 3]", context)).isFalse();
        assertThat(AgenticRagService.satisfiesCitationContract(
                "无知识库时无需引用", "（未检索到相关文档）")).isTrue();
    }

    @Test
    void deterministicCitationGateRevisesOtherwiseApprovedDraft() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String validRevision = longEnough(
                "先暂停情绪，再共同协商。[来源 1]");
        String uncitedDraft = longEnough(
                "先暂停情绪，再共同协商。");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(uncitedDraft),
                response("{\"grounded\":true,\"taskCompleted\":true,"
                        + "\"revisedAnswer\":null}"),
                response(validRevision)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应暂停情绪并共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么沟通？",
                "citation-gate",
                "你是恋爱心理顾问。"
        );

        assertThat(result.answer()).contains("[来源 1]");
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsExactly(
                        "ROUTE", "PLAN", "RETRIEVE", "VERIFY",
                        "GENERATE", "REVIEW", "REVISE", "RLVR_SELECT");
        assertThat(result.trace().steps().get(5).details())
                .contains("引用契约：未通过");
        assertThat(result.trace().steps().getLast().details())
                .contains("候选：REVISED");
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void failedReviewCannotBypassDeterministicCitationGate() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String uncitedDraft = longEnough(
                "先暂停情绪，再共同协商。");
        String validRevision = longEnough(
                "先暂停情绪，再共同协商。[来源 1]");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(uncitedDraft),
                response("{\"grounded\":true,[来源 1]}"),
                response(validRevision)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应暂停情绪并共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么沟通？",
                "failed-review-citation-gate",
                "你是恋爱心理顾问。"
        );

        assertThat(result.answer()).isEqualTo(validRevision);
        assertThat(AgenticRagService.satisfiesCitationContract(
                result.answer(),
                "[来源 1 | 已婚篇.md]\n冲突后应暂停情绪并共同协商。"))
                .isTrue();
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsSubsequence("GENERATE", "REVIEW", "REVISE");
        assertThat(result.trace().steps().stream()
                .filter(step -> step.phase().equals("REVIEW"))
                .findFirst()
                .orElseThrow()
                .success()).isFalse();
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void deterministicLengthGateRevisesOtherwiseApprovedDraft() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String overlongDraft = "建议".repeat(800) + "[来源 1]";
        String validRevision = longEnough(
                "先暂停情绪，再共同协商。[来源 1]");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(overlongDraft),
                response("{\"grounded\":true,\"taskCompleted\":true,"
                        + "\"revisedAnswer\":null}"),
                response(validRevision)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应暂停情绪并共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么沟通？",
                "length-gate",
                "你是恋爱心理顾问。"
        );

        assertThat(result.answer()).isEqualTo(validRevision);
        assertThat(AgenticRagService.satisfiesAnswerLengthContract(
                result.answer(), "夫妻争吵后怎么沟通？")).isTrue();
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsSubsequence("GENERATE", "REVIEW", "REVISE");
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void storesReviewProvidedRevisionForRlvrCandidateAudit() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String validDraft = longEnough(
                "先暂停情绪，再共同协商并约定复盘。[来源 1]");
        String validRevision = longEnough(
                "先描述事实和感受，再共同协商并约定复盘。[来源 1]");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(validDraft),
                response("{\"grounded\":false,\"taskCompleted\":false,"
                        + "\"revisedAnswer\":\"" + validRevision + "\"}")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应描述事实和感受，再共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        InMemoryAgentTrajectoryRepository trajectoryRepository =
                new InMemoryAgentTrajectoryRepository();
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory,
                trajectoryRepository,
                new AgentRewardCalculator()
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么沟通？",
                "review-revision-audit",
                "你是恋爱心理顾问。"
        );

        assertThat(result.answer()).isEqualTo(validRevision);
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsSubsequence("GENERATE", "REVIEW", "RLVR_SELECT")
                .doesNotContain("REVISE");
        assertThat(trajectoryRepository.findById(result.trajectoryId())
                .orElseThrow().steps().stream()
                .filter(step -> step.type() == AgentStepType.REVIEW)
                .findFirst().orElseThrow().output())
                .containsEntry("revisedAnswer", validRevision);
        verify(chatModel, times(4)).call(any(Prompt.class));
    }

    @Test
    void deterministicMinimumLengthGateRevisesOtherwiseApprovedDraft() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String shortDraft = "先暂停情绪，再共同协商。[来源 1]";
        String validRevision = longEnough(shortDraft);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("{\"subQueries\":[\"夫妻沟通\"]}"),
                response("{\"sufficient\":true,\"missingInfo\":\"\","
                        + "\"followUpQueries\":[]}"),
                response(shortDraft),
                response("{\"grounded\":true,\"taskCompleted\":true,"
                        + "\"revisedAnswer\":null}"),
                response(validRevision)
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(
                        "doc-1",
                        "冲突后应暂停情绪并共同协商。",
                        Map.of("filename", "已婚篇.md")
                )));
        AgenticRagService service = new AgenticRagService(
                ChatClient.builder(chatModel).build(),
                vectorStore,
                chatMemory
        );

        AgenticRagResult result = service.doAgenticRagWithTrace(
                "夫妻争吵后怎么沟通？",
                "minimum-length-gate",
                "你是恋爱心理顾问。"
        );

        assertThat(result.answer()).isEqualTo(validRevision);
        assertThat(AgenticRagService.satisfiesAnswerLengthContract(
                result.answer(), "夫妻争吵后怎么沟通？")).isTrue();
        assertThat(result.trace().steps())
                .extracting(step -> step.phase())
                .containsSubsequence("GENERATE", "REVIEW", "REVISE");
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void mirrorsSeedAnswerLengthContracts() {
        assertThat(AgenticRagService.DEFAULT_POLICY_VERSION)
                .isEqualTo("agentic-rag-v8");
        assertThat(AgenticRagService.maximumAnswerChars(
                "请制定七天小计划，每天写行动和复盘。")).isEqualTo(2400);
        assertThat(AgenticRagService.maximumAnswerChars(
                "请给一份按优先级排序的检查清单。")).isEqualTo(1600);
        assertThat(AgenticRagService.minimumAnswerChars(
                "请制定七天小计划，每天写行动和复盘。")).isEqualTo(320);
        assertThat(AgenticRagService.minimumAnswerChars(
                "请给一份按优先级排序的检查清单。")).isEqualTo(140);
        assertThat(AgenticRagService.satisfiesAnswerLengthContract(
                "好".repeat(139), "请给检查清单")).isFalse();
        assertThat(AgenticRagService.satisfiesAnswerLengthContract(
                "好".repeat(1600), "请给检查清单")).isTrue();
        assertThat(AgenticRagService.satisfiesAnswerLengthContract(
                "好".repeat(1601), "请给检查清单")).isFalse();
    }

    @Test
    void rlvrCandidateSelectorPreventsARevisionContractRegression() {
        String context = "[来源 1 | 已婚篇.md]\n应共同协商并定期复盘。";
        String validDraft = longEnough(
                "1. 共同沟通。[来源 1]\n"
                        + "2. 明确分工。[来源 1]\n"
                        + "3. 定期复盘。[来源 1]");
        String regressedRevision = longEnough(
                "1. 共同沟通。\n2. 明确分工。\n3. 定期复盘。");

        AgenticRagService.CandidateSelection selection =
                AgenticRagService.selectHigherPrecisionCandidate(
                        "不要追问，直接给出三项行动。",
                        context,
                        validDraft,
                        regressedRevision);

        assertThat(selection.selectedCandidate()).isEqualTo("DRAFT");
        assertThat(selection.answer()).isEqualTo(validDraft);
        assertThat(selection.draftScore()).isGreaterThan(selection.revisedScore());
    }

    @Test
    void rlvrCandidateSelectorUsesTheRevisionWhenItImprovesContracts() {
        String context = "[来源 1 | 已婚篇.md]\n应共同协商并定期复盘。";
        String invalidDraft = "只给一句且没有引用。";
        String validRevision = longEnough(
                "1. 共同沟通。[来源 1]\n"
                        + "2. 明确分工。[来源 1]\n"
                        + "3. 定期复盘。[来源 1]");

        AgenticRagService.CandidateSelection selection =
                AgenticRagService.selectHigherPrecisionCandidate(
                        "不要追问，直接给出三项行动。",
                        context,
                        invalidDraft,
                        validRevision);

        assertThat(selection.selectedCandidate()).isEqualTo("REVISED");
        assertThat(selection.answer()).isEqualTo(validRevision);
        assertThat(selection.revisedScore()).isGreaterThan(selection.draftScore());
    }

    @Test
    void rlvrCandidateSelectorRejectsFabricatedResourcesAndIncompleteWeeklyPlans() {
        String context = "[来源 1 | 已婚篇.md]\n应共同协商并定期复盘。";
        String compliantDraft = longEnough(
                "第1天：共同协商并记录行动。[来源 1]\n"
                        + "第2天：检查执行情况。[来源 1]\n"
                        + "第3天：调整安排。[来源 1]\n"
                        + "第4天：双方轮换。[来源 1]\n"
                        + "第5天：整理分歧。[来源 1]\n"
                        + "第6天：确认下周安排。[来源 1]\n"
                        + "第7天：复盘并记录结果。[来源 1]");
        String fabricatedRevision = longEnough(
                "第一天：共同协商。[来源 1]\n推荐课程：虚构的速成课。");

        AgenticRagService.CandidateSelection selection =
                AgenticRagService.selectHigherPrecisionCandidate(
                        "不要反复追问，请制定七天小计划，每天行动和复盘。"
                                + "不要编造课程或案例。",
                        context,
                        compliantDraft,
                        fabricatedRevision);

        assertThat(selection.selectedCandidate()).isEqualTo("DRAFT");
        assertThat(selection.answer()).isEqualTo(compliantDraft);
        assertThat(selection.draftScore()).isGreaterThan(selection.revisedScore());
    }

    @Test
    void fallsBackToOriginalQuestionWhenPlannerTimesOut() {
        ChatModel chatModel = mock(ChatModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String validAnswer = longEnough(
                "先表达自己的感受，再共同确定沟通时间[来源 1]。");
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("planner timeout"))
                .thenReturn(
                        response("{\"sufficient\":true,\"missingInfo\":\"\",\"followUpQueries\":[]}"),
                        response(validAnswer),
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

    private static String longEnough(String content) {
        String padding = "执行后记录实际效果、遇到的阻碍和下一次调整，并在约定时间共同复盘。";
        StringBuilder answer = new StringBuilder(content);
        while (answer.codePointCount(0, answer.length()) < 140) {
            answer.append(padding);
        }
        return answer.toString();
    }
}
