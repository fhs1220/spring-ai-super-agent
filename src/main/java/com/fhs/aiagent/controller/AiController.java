package com.fhs.aiagent.controller;

import com.fhs.aiagent.app.LoveApp;
import com.fhs.aiagent.rag.AgentProgressEvent;
import com.fhs.aiagent.rag.AgentRunCancelledException;
import com.fhs.aiagent.rag.multiagent.AdaptiveMultiAgentOrchestrator;
import com.fhs.aiagent.rag.multiagent.AgentDescriptor;
import com.fhs.aiagent.rl.AgentRlService;
import com.fhs.aiagent.rl.bailian.BailianRlDatasetService;
import com.fhs.aiagent.rl.model.AgentTrajectory;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/ai/love_app")
public class AiController {

    private final LoveApp loveApp;

    private final AgentRlService agentRlService;

    private final BailianRlDatasetService bailianRlDatasetService;

    private final AdaptiveMultiAgentOrchestrator multiAgentOrchestrator;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public AiController(LoveApp loveApp,
                        AgentRlService agentRlService,
                        BailianRlDatasetService bailianRlDatasetService,
                        AdaptiveMultiAgentOrchestrator multiAgentOrchestrator) {
        this.loveApp = loveApp;
        this.agentRlService = agentRlService;
        this.bailianRlDatasetService = bailianRlDatasetService;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
    }

    @PostMapping("/chat/agentic-rag")
    public AgenticRagResult agenticRag(@RequestBody AgenticRagRequest request) {
        return loveApp.doChatWithAgenticRagTrace(request.message(), request.chatId());
    }

    /**
     * 以结构化 SSE 推送 Agent 阶段事件，最终 complete 事件携带完整结果。
     */
    @PostMapping(value = "/chat/agentic-rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgenticRag(@RequestBody AgenticRagStreamRequest request) {
        String runId = normalizeRunId(request.runId());
        SseEmitter emitter = new SseEmitter(300_000L);
        ActiveRun activeRun = new ActiveRun(runId, emitter);
        if (activeRuns.putIfAbsent(runId, activeRun) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "runId is already active");
        }

        emitter.onTimeout(activeRun::cancel);
        emitter.onError(error -> activeRun.cancel());
        emitter.onCompletion(() -> {
            if (!activeRun.finished()) {
                activeRun.cancel();
            }
            activeRuns.remove(runId, activeRun);
        });

        Future<?> future = streamExecutor.submit(() -> executeStream(request, activeRun));
        activeRun.attach(future);
        return emitter;
    }

    @DeleteMapping("/chat/agentic-rag/runs/{runId}")
    public CancelRunResponse cancelAgenticRag(@PathVariable String runId) {
        ActiveRun activeRun = activeRuns.get(runId);
        boolean cancelled = activeRun != null && activeRun.cancel();
        return new CancelRunResponse(runId, cancelled);
    }

    @PostMapping("/agent-rl/feedback")
    public AgentTrajectory submitAgentRlFeedback(@RequestBody AgentRlFeedbackRequest request) {
        return agentRlService.submitFeedback(
                request.trajectoryId(), request.rating(), request.comment());
    }

    @GetMapping("/agent-rl/metrics")
    public AgentRlService.Metrics agentRlMetrics() {
        return agentRlService.metrics();
    }

    @GetMapping("/agent-rl/readiness")
    public BailianRlDatasetService.DatasetReadiness agentRlReadiness() {
        return bailianRlDatasetService.readinessDefault();
    }

    /**
     * 返回不含提示词和密钥的 Agent 能力卡，后续可直接映射到 A2A Agent Card。
     */
    @GetMapping("/agents")
    public List<AgentDescriptor> agents() {
        return multiAgentOrchestrator.descriptors();
    }

    @PreDestroy
    void shutdownStreamExecutor() {
        activeRuns.values().forEach(ActiveRun::cancel);
        streamExecutor.shutdownNow();
    }

    private void executeStream(AgenticRagStreamRequest request, ActiveRun activeRun) {
        try {
            send(activeRun.emitter(), "accepted",
                    new StreamAccepted(activeRun.runId(), "RUNNING"));
            AgenticRagResult result = loveApp.doChatWithAgenticRagTrace(
                    request.message(),
                    request.chatId(),
                    event -> send(activeRun.emitter(), "progress", event)
            );
            activeRun.markFinished();
            send(activeRun.emitter(), "complete", result);
            activeRun.emitter().complete();
        } catch (AgentRunCancelledException exception) {
            activeRun.markFinished();
            sendQuietly(activeRun.emitter(), "cancelled",
                    new StreamError(activeRun.runId(), "运行已取消"));
            activeRun.emitter().complete();
        } catch (RuntimeException exception) {
            activeRun.markFinished();
            boolean cancelled = activeRun.cancellationRequested()
                    || AgentRunCancelledException.isCancellation(exception);
            sendQuietly(activeRun.emitter(), cancelled ? "cancelled" : "error",
                    new StreamError(
                            activeRun.runId(),
                            cancelled ? "运行已取消" : safeMessage(exception)));
            activeRun.emitter().complete();
        } finally {
            activeRuns.remove(activeRun.runId(), activeRun);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException exception) {
            throw new AgentRunCancelledException("SSE client disconnected", exception);
        }
    }

    private void sendQuietly(SseEmitter emitter, String eventName, Object data) {
        try {
            send(emitter, eventName, data);
        } catch (AgentRunCancelledException ignored) {
            // 客户端已经离开，无需继续写入。
        }
    }

    private String normalizeRunId(String runId) {
        String normalized = runId == null || runId.isBlank()
                ? UUID.randomUUID().toString()
                : runId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid runId");
        }
        return normalized;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Agentic RAG 服务暂时不可用"
                : message;
    }

    public record AgenticRagRequest(String message, String chatId) {
    }

    public record AgenticRagStreamRequest(String message, String chatId, String runId) {
    }

    public record AgentRlFeedbackRequest(String trajectoryId, int rating, String comment) {
    }

    public record StreamAccepted(String runId, String status) {
    }

    public record StreamError(String runId, String message) {
    }

    public record CancelRunResponse(String runId, boolean cancelled) {
    }

    private static final class ActiveRun {

        private final String runId;

        private final SseEmitter emitter;

        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        private final AtomicBoolean finished = new AtomicBoolean();

        private volatile Future<?> future;

        private ActiveRun(String runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }

        private void attach(Future<?> future) {
            this.future = future;
            if (cancellationRequested.get()) {
                future.cancel(true);
            }
        }

        private boolean cancel() {
            if (finished.get() || !cancellationRequested.compareAndSet(false, true)) {
                return false;
            }
            Future<?> current = future;
            if (current != null) {
                current.cancel(true);
            }
            return true;
        }

        private void markFinished() {
            finished.set(true);
        }

        private boolean finished() {
            return finished.get();
        }

        private boolean cancellationRequested() {
            return cancellationRequested.get();
        }

        private String runId() {
            return runId;
        }

        private SseEmitter emitter() {
            return emitter;
        }
    }
}
