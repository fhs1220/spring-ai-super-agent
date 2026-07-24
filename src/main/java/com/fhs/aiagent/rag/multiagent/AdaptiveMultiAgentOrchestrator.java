package com.fhs.aiagent.rag.multiagent;

import com.fhs.aiagent.rag.AgentTelemetryCollector;
import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rag.AgentProgressListener;
import com.fhs.aiagent.rag.AgentRunCancelledException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Instant;

/**
 * 自适应层级式多 Agent 编排器。
 * <p>
 * 简单任务保持单 Agent 快速路径；可分解的复合任务才并行调用专业 Agent，
 * 最后由一个综合 Agent 基于共享证据黑板生成候选答案。
 */
@Component
public class AdaptiveMultiAgentOrchestrator {

    public static final String SINGLE_MODE = "SINGLE_AGENT";

    public static final String MULTI_MODE = "ADAPTIVE_MULTI_AGENT";

    private static final Map<AgentDomain, List<String>> DOMAIN_KEYWORDS = domainKeywords();

    private final ChatClient chatClient;

    private final boolean enabled;

    private final int minimumDomains;

    private final int maxAgents;

    private final Map<AgentDomain, DomainSpecialistAgent> specialists;

    @Autowired
    public AdaptiveMultiAgentOrchestrator(
            ChatModel dashscopeChatModel,
            @Value("${agent.rag.multi-agent.enabled:true}") boolean enabled,
            @Value("${agent.rag.multi-agent.minimum-domains:2}") int minimumDomains,
            @Value("${agent.rag.multi-agent.max-agents:3}") int maxAgents) {
        this(ChatClient.builder(dashscopeChatModel).build(), enabled, minimumDomains, maxAgents);
    }

    public AdaptiveMultiAgentOrchestrator(ChatClient chatClient,
                                          boolean enabled,
                                          int minimumDomains,
                                          int maxAgents) {
        this.chatClient = chatClient;
        this.enabled = enabled;
        this.minimumDomains = Math.max(1, minimumDomains);
        this.maxAgents = Math.max(1, Math.min(5, maxAgents));
        this.specialists = createSpecialists(chatClient);
    }

    public MultiAgentDecision route(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Set<AgentDomain> domains = new LinkedHashSet<>();
        DOMAIN_KEYWORDS.forEach((domain, keywords) -> {
            if (keywords.stream().anyMatch(normalized::contains)) {
                domains.add(domain);
            }
        });
        if (domains.isEmpty()) {
            domains.add(AgentDomain.RELATIONSHIP);
        }

        boolean structuredTask = containsAny(normalized, List.of(
                "计划", "方案", "步骤", "每天", "星期", "表格", "分别", "同时", "必须包含", "全面"
        ));
        boolean longQuestion = normalized.codePointCount(0, normalized.length()) >= 70;
        double complexity = Math.min(1.0,
                0.18 * domains.size() + (structuredTask ? 0.25 : 0) + (longQuestion ? 0.15 : 0));
        boolean useMultiAgent = enabled
                && (domains.size() >= minimumDomains || (structuredTask && domains.size() >= 2));

        List<AgentDomain> selected = selectDomains(domains);
        String reason;
        if (!enabled) {
            reason = "多 Agent 功能已关闭，使用单 Agent 基线";
        } else if (useMultiAgent) {
            reason = "问题覆盖 %d 个能力域，可并行分解".formatted(domains.size());
        } else {
            reason = "问题集中在单一能力域，避免不必要的多 Agent 成本";
        }
        return new MultiAgentDecision(
                useMultiAgent ? MULTI_MODE : SINGLE_MODE,
                useMultiAgent,
                round(complexity),
                reason,
                useMultiAgent ? selected : List.of()
        );
    }

    public MultiAgentAnswer execute(MultiAgentDecision decision,
                                    AgentRequest request,
                                    AgentTelemetryCollector telemetry) {
        return execute(decision, request, telemetry, AgentProgressListener.NONE);
    }

    public MultiAgentAnswer execute(MultiAgentDecision decision,
                                    AgentRequest request,
                                    AgentTelemetryCollector telemetry,
                                    AgentProgressListener progressListener) {
        if (!decision.multiAgent()) {
            return new MultiAgentAnswer(null, decision, List.of(), true, 0, 0);
        }

        long specialistStartedAt = System.nanoTime();
        List<SpecialistContribution> contributions = executeSpecialists(
                decision.selectedDomains(), request, telemetry, progressListener);
        long specialistDurationMs = elapsedMs(specialistStartedAt);
        List<SpecialistContribution> successful = contributions.stream()
                .filter(SpecialistContribution::success)
                .toList();
        emit(progressListener, new AgentProgressEvent(
                "SPECIALIST",
                successful.isEmpty() ? "FAILED" : "COMPLETED",
                "并行专家",
                "%d/%d 个专业 Agent 完成".formatted(successful.size(), contributions.size()),
                contributions.stream()
                        .map(contribution -> "%s：%s".formatted(
                                contribution.agentName(),
                                contribution.success() ? "成功" : "失败"))
                        .toList(),
                specialistDurationMs,
                Instant.now()
        ));
        if (successful.isEmpty()) {
            return new MultiAgentAnswer(
                    null, decision, contributions, true, specialistDurationMs, 0);
        }

        SharedEvidenceBlackboard blackboard = new SharedEvidenceBlackboard(
                request.question(), request.evidenceContext(), successful);
        String answer;
        long synthesisStartedAt = System.nanoTime();
        emit(progressListener, new AgentProgressEvent(
                "SYNTHESIZE",
                "STARTED",
                "综合 Agent",
                "正在合并 %d 个专业 Agent 的贡献".formatted(successful.size()),
                List.of(),
                0,
                Instant.now()
        ));
        try {
            answer = synthesize(request, blackboard, telemetry);
        } catch (RuntimeException exception) {
            if (AgentRunCancelledException.isCancellation(exception)) {
                throw exception;
            }
            answer = null;
        }
        long synthesisDurationMs = elapsedMs(synthesisStartedAt);
        boolean fallback = answer == null || answer.isBlank();
        emit(progressListener, new AgentProgressEvent(
                "SYNTHESIZE",
                fallback ? "FAILED" : "COMPLETED",
                "综合 Agent",
                fallback ? "综合失败，将切换单 Agent 降级路径" : "专业意见已合并",
                List.of(),
                synthesisDurationMs,
                Instant.now()
        ));
        return new MultiAgentAnswer(
                fallback ? null : answer,
                decision,
                contributions,
                fallback,
                specialistDurationMs,
                synthesisDurationMs
        );
    }

    public List<AgentDescriptor> descriptors() {
        return specialists.values().stream()
                .map(DomainSpecialistAgent::descriptor)
                .toList();
    }

    private List<SpecialistContribution> executeSpecialists(List<AgentDomain> domains,
                                                            AgentRequest request,
                                                            AgentTelemetryCollector telemetry,
                                                            AgentProgressListener progressListener) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<SpecialistContribution>> futures = List.of();
        try {
            futures = domains.stream()
                    .map(specialists::get)
                    .filter(java.util.Objects::nonNull)
                    .map(agent -> CompletableFuture.supplyAsync(
                            () -> executeSpecialist(
                                    agent, request, telemetry, progressListener), executor))
                    .toList();
            List<SpecialistContribution> contributions = new ArrayList<>();
            for (CompletableFuture<SpecialistContribution> future : futures) {
                try {
                    contributions.add(future.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AgentRunCancelledException(
                            "Multi-agent specialist execution cancelled", exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException("Specialist execution failed", cause);
                }
            }
            return List.copyOf(contributions);
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private SpecialistContribution executeSpecialist(DomainSpecialistAgent agent,
                                                     AgentRequest request,
                                                     AgentTelemetryCollector telemetry,
                                                     AgentProgressListener progressListener) {
        AgentDescriptor descriptor = agent.descriptor();
        emit(progressListener, new AgentProgressEvent(
                "SPECIALIST",
                "STARTED",
                descriptor.name(),
                "专业 Agent 已开始分析",
                descriptor.skills(),
                0,
                Instant.now()
        ));
        SpecialistContribution contribution = agent.execute(request, telemetry);
        emit(progressListener, new AgentProgressEvent(
                "SPECIALIST",
                contribution.success() ? "COMPLETED" : "FAILED",
                contribution.agentName(),
                contribution.success() ? "专业分析已完成" : "专业分析失败，其他 Agent 将继续",
                List.of(
                        "领域：" + contribution.domain().name(),
                        "置信度：" + contribution.confidence(),
                        "过程奖励：" + contribution.processReward()
                ),
                contribution.durationMs(),
                Instant.now()
        ));
        return contribution;
    }

    private void emit(AgentProgressListener listener, AgentProgressEvent event) {
        (listener == null ? AgentProgressListener.NONE : listener).onProgress(event);
    }

    private String synthesize(AgentRequest request,
                              SharedEvidenceBlackboard blackboard,
                              AgentTelemetryCollector telemetry) {
        String system = (request.systemPrompt() == null ? "" : request.systemPrompt() + "\n") + """
                你是自适应多 Agent 系统的综合 Agent。请基于统一知识库证据和专业 Agent 黑板，
                生成一个完整、直接、可执行的最终候选答案。
                规则：
                1. 专业 Agent 输出只是建议草稿，事实仍必须以知识库证据为准；
                2. 合并互补意见，删除重复、冲突和无证据的内容；
                3. 完整执行用户要求的格式与约束，不要用不必要的追问代替答案；
                4. 信息不足但可作安全假设时，明确标注假设并继续完成任务；
                5. 使用知识库内容时在句末保留正确的 [来源 n]，不得编造编号；
                6. 不要向用户暴露内部 Agent 名称、置信度、奖励或编排过程。
                7. 引用只使用 [来源 n] 格式，不要再写“参考来源 n”等重复文字。
                """;
        String user = """
                历史会话：
                %s

                统一知识库证据：
                %s

                专业 Agent 黑板：
                %s

                用户问题：
                %s
                """.formatted(
                request.conversationHistory(),
                request.evidenceContext(),
                blackboard.formatForSynthesis(),
                request.question()
        );
        return telemetry.captureContent(
                "SYNTHESIZE",
                system + "\n" + user,
                () -> chatClient.prompt()
                        .system(system)
                        .user(user)
                        .call()
                        .chatResponse()
        );
    }

    private List<AgentDomain> selectDomains(Set<AgentDomain> detected) {
        List<AgentDomain> ordered = new ArrayList<>();
        if (detected.contains(AgentDomain.SAFETY)) {
            ordered.add(AgentDomain.SAFETY);
        }
        for (AgentDomain domain : List.of(
                AgentDomain.PARENTING,
                AgentDomain.HOUSEHOLD,
                AgentDomain.FINANCE,
                AgentDomain.RELATIONSHIP
        )) {
            if (detected.contains(domain) && !ordered.contains(domain)) {
                ordered.add(domain);
            }
        }
        return ordered.stream().limit(maxAgents).toList();
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private Map<AgentDomain, DomainSpecialistAgent> createSpecialists(ChatClient client) {
        Map<AgentDomain, DomainSpecialistAgent> agents = new EnumMap<>(AgentDomain.class);
        agents.put(AgentDomain.RELATIONSHIP, specialist(
                client,
                AgentDomain.RELATIONSHIP,
                "relationship-specialist",
                "关系沟通 Agent",
                "分析伴侣互动、沟通方式、情绪和关系修复；避免贴标签或臆测诊断。",
                List.of("关系沟通", "冲突修复", "情绪表达")
        ));
        agents.put(AgentDomain.PARENTING, specialist(
                client,
                AgentDomain.PARENTING,
                "parenting-specialist",
                "育儿协作 Agent",
                "分析共同育儿、照护轮班和父母协作，给出对孩子与伴侣都可执行的安排。",
                List.of("共同育儿", "照护分工", "亲子安排")
        ));
        agents.put(AgentDomain.HOUSEHOLD, specialist(
                client,
                AgentDomain.HOUSEHOLD,
                "household-specialist",
                "家庭运营 Agent",
                "分析家务负荷、隐形劳动、时间分工和复盘机制，输出公平且可持续的家庭流程。",
                List.of("家务分工", "家庭流程", "时间管理")
        ));
        agents.put(AgentDomain.FINANCE, specialist(
                client,
                AgentDomain.FINANCE,
                "finance-specialist",
                "家庭财务 Agent",
                "只在知识库和用户信息范围内分析家庭预算与经济沟通，不提供投资、税务或法律结论。",
                List.of("家庭预算", "经济沟通", "共同目标")
        ));
        agents.put(AgentDomain.SAFETY, specialist(
                client,
                AgentDomain.SAFETY,
                "safety-specialist",
                "关系安全 Agent",
                "识别暴力、威胁、胁迫和严重安全风险，优先给出保护自身与寻求现实支持的边界建议；"
                        + "不提供未经上下文支持的热线号码。",
                List.of("风险识别", "安全边界", "现实支持")
        ));
        return Map.copyOf(agents);
    }

    private DomainSpecialistAgent specialist(ChatClient client,
                                             AgentDomain domain,
                                             String id,
                                             String name,
                                             String description,
                                             List<String> skills) {
        return new DomainSpecialistAgent(
                new AgentDescriptor(id, name, description, skills, "1.0", "1.0"),
                domain,
                description,
                client
        );
    }

    private static Map<AgentDomain, List<String>> domainKeywords() {
        Map<AgentDomain, List<String>> values = new EnumMap<>(AgentDomain.class);
        values.put(AgentDomain.RELATIONSHIP, List.of(
                "恋爱", "婚姻", "夫妻", "伴侣", "沟通", "争吵", "感情", "亲密", "异地", "信任"
        ));
        values.put(AgentDomain.PARENTING, List.of(
                "孩子", "育儿", "带娃", "教育", "接送", "哄睡", "喂养", "父母"
        ));
        values.put(AgentDomain.HOUSEHOLD, List.of(
                "家务", "做饭", "洗碗", "清洁", "分工", "隐形劳动", "家庭责任"
        ));
        values.put(AgentDomain.FINANCE, List.of(
                "经济", "预算", "收入", "支出", "存钱", "债务", "房贷", "财务", "钱"
        ));
        values.put(AgentDomain.SAFETY, List.of(
                "家暴", "暴力", "威胁", "控制", "殴打", "伤害", "自杀", "轻生"
        ));
        return Map.copyOf(values);
    }
}
