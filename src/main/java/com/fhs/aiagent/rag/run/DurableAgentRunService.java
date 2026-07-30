package com.fhs.aiagent.rag.run;

import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rag.AnswerVerificationContract;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class DurableAgentRunService {

    private final AgentRunRepository repository;

    private final int maximumEvents;

    public DurableAgentRunService(
            AgentRunRepository repository,
            @Value("${agent.rag.runtime.maximum-events:200}") int maximumEvents) {
        this.repository = repository;
        this.maximumEvents = Math.max(20, maximumEvents);
    }

    /**
     * 创建新运行；相同 runId 和请求已完成时直接返回持久化结果，实现幂等回放。
     */
    public synchronized DurableAgentRun createOrReplay(
            String runId,
            String message,
            String chatId) {
        return createOrReplay(runId, message, chatId, null);
    }

    public synchronized DurableAgentRun createOrReplay(
            String runId,
            String message,
            String chatId,
            AnswerVerificationContract verificationContract) {
        DurableAgentRun existing = repository.findById(runId).orElse(null);
        if (existing != null) {
            requireSameRequest(existing, message, chatId, verificationContract);
            if (existing.status() == AgentRunStatus.COMPLETED) {
                return existing;
            }
            throw new IllegalStateException(
                    "runId already exists with status " + existing.status() + "; use the resume endpoint");
        }
        Instant now = Instant.now();
        return repository.save(new DurableAgentRun(
                runId,
                requireMessage(message),
                normalizeChatId(chatId),
                verificationContract,
                AgentRunStatus.QUEUED,
                1,
                List.of(),
                null,
                "",
                now,
                now
        ));
    }

    /**
     * 仅允许失败、取消或服务重启遗留的运行显式重试，避免无意产生重复模型费用。
     */
    public synchronized DurableAgentRun resume(String runId) {
        DurableAgentRun current = require(runId);
        if (current.status() != AgentRunStatus.FAILED
                && current.status() != AgentRunStatus.CANCELLED
                && current.status() != AgentRunStatus.RECOVERY_REQUIRED) {
            throw new IllegalStateException("run cannot be resumed from status " + current.status());
        }
        Instant now = Instant.now();
        List<AgentProgressEvent> events = appendEvent(
                current.events(),
                new AgentProgressEvent(
                        "RUN",
                        "RETRYING",
                        "恢复运行",
                        "正在启动第 %d 次执行".formatted(current.attempt() + 1),
                        List.of("上次状态：" + current.status()),
                        0,
                        now
                )
        );
        return repository.save(new DurableAgentRun(
                current.runId(),
                current.message(),
                current.chatId(),
                current.verificationContract(),
                AgentRunStatus.QUEUED,
                current.attempt() + 1,
                events,
                null,
                "",
                current.createdAt(),
                now
        ));
    }

    public synchronized DurableAgentRun markRunning(String runId) {
        DurableAgentRun current = require(runId);
        if (current.status() != AgentRunStatus.QUEUED) {
            throw new IllegalStateException("run cannot start from status " + current.status());
        }
        return saveState(current, AgentRunStatus.RUNNING, current.events(), null, "");
    }

    public synchronized DurableAgentRun appendProgress(String runId, AgentProgressEvent event) {
        DurableAgentRun current = require(runId);
        if (current.status() != AgentRunStatus.RUNNING) {
            return current;
        }
        return saveState(
                current,
                current.status(),
                appendEvent(current.events(), event),
                current.result(),
                current.error()
        );
    }

    public synchronized DurableAgentRun complete(String runId, AgenticRagResult result) {
        DurableAgentRun current = require(runId);
        if (current.status() == AgentRunStatus.COMPLETED) {
            return current;
        }
        if (current.status() != AgentRunStatus.RUNNING) {
            throw new IllegalStateException("run cannot complete from status " + current.status());
        }
        return saveState(current, AgentRunStatus.COMPLETED, current.events(), result, "");
    }

    public synchronized DurableAgentRun fail(String runId, String error) {
        DurableAgentRun current = require(runId);
        if (current.terminal()) {
            return current;
        }
        return saveState(
                current,
                AgentRunStatus.FAILED,
                current.events(),
                null,
                Objects.toString(error, "Agent run failed")
        );
    }

    public synchronized DurableAgentRun cancel(String runId) {
        DurableAgentRun current = require(runId);
        if (current.terminal()) {
            return current;
        }
        return saveState(
                current,
                AgentRunStatus.CANCELLED,
                current.events(),
                null,
                "运行已取消"
        );
    }

    public synchronized DurableAgentRun get(String runId) {
        return require(runId);
    }

    /**
     * JVM 非正常退出时无法保证恢复线程栈；启动后将未完成记录标记为可显式恢复。
     */
    @PostConstruct
    public synchronized void recoverInterruptedRuns() {
        for (DurableAgentRun run : repository.findAll()) {
            if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.QUEUED) {
                continue;
            }
            Instant now = Instant.now();
            List<AgentProgressEvent> events = appendEvent(
                    run.events(),
                    new AgentProgressEvent(
                            "RUN",
                            "RECOVERY_REQUIRED",
                            "检测到中断运行",
                            "服务重启后可从保存的请求安全重试",
                            List.of("中断前状态：" + run.status()),
                            0,
                            now
                    )
            );
            repository.save(new DurableAgentRun(
                    run.runId(),
                    run.message(),
                    run.chatId(),
                    run.verificationContract(),
                    AgentRunStatus.RECOVERY_REQUIRED,
                    run.attempt(),
                    events,
                    null,
                    "服务重启导致运行中断",
                    run.createdAt(),
                    now
            ));
        }
    }

    private DurableAgentRun saveState(DurableAgentRun current,
                                      AgentRunStatus status,
                                      List<AgentProgressEvent> events,
                                      AgenticRagResult result,
                                      String error) {
        return repository.save(new DurableAgentRun(
                current.runId(),
                current.message(),
                current.chatId(),
                current.verificationContract(),
                status,
                current.attempt(),
                events,
                result,
                error,
                current.createdAt(),
                Instant.now()
        ));
    }

    private List<AgentProgressEvent> appendEvent(List<AgentProgressEvent> existing,
                                                 AgentProgressEvent event) {
        List<AgentProgressEvent> events = new ArrayList<>(existing);
        events.add(event);
        int from = Math.max(0, events.size() - maximumEvents);
        return List.copyOf(events.subList(from, events.size()));
    }

    private DurableAgentRun require(String runId) {
        return repository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Agent run not found: " + runId));
    }

    private void requireSameRequest(
            DurableAgentRun run,
            String message,
            String chatId,
            AnswerVerificationContract verificationContract) {
        if (!run.message().equals(requireMessage(message))
                || !run.chatId().equals(normalizeChatId(chatId))
                || !Objects.equals(
                run.verificationContract(), verificationContract)) {
            throw new IllegalStateException("runId belongs to a different request");
        }
    }

    private String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message.trim();
    }

    private String normalizeChatId(String chatId) {
        return chatId == null || chatId.isBlank() ? "default" : chatId.trim();
    }
}
