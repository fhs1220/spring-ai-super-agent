package com.fhs.aiagent.rag.multiagent;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class RoutingPolicyDeploymentService {

    private final RoutingPolicyDeploymentRepository repository;

    private final RoutingPolicyMode defaultMode;

    private final double defaultCanaryRate;

    private final int minimumCanarySamples;

    private final int maximumHistory;

    private final Clock clock;

    private volatile RoutingPolicyDeploymentState state;

    @Autowired
    public RoutingPolicyDeploymentService(
            RoutingPolicyDeploymentRepository repository,
            @Value("${agent.rag.routing-policy.mode:SHADOW}") RoutingPolicyMode defaultMode,
            @Value("${agent.rag.routing-policy.canary-rate:0.1}") double defaultCanaryRate,
            @Value("${agent.rag.routing-policy.minimum-canary-samples:20}")
            int minimumCanarySamples,
            @Value("${agent.rag.routing-policy.maximum-deployment-history:20}")
            int maximumHistory) {
        this(
                repository,
                defaultMode,
                defaultCanaryRate,
                minimumCanarySamples,
                maximumHistory,
                Clock.systemUTC()
        );
    }

    RoutingPolicyDeploymentService(RoutingPolicyDeploymentRepository repository,
                                   RoutingPolicyMode defaultMode,
                                   double defaultCanaryRate,
                                   int minimumCanarySamples,
                                   int maximumHistory,
                                   Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.defaultMode = Objects.requireNonNull(defaultMode, "defaultMode");
        this.defaultCanaryRate = normalizeCanaryRate(defaultCanaryRate);
        this.minimumCanarySamples = Math.max(1, minimumCanarySamples);
        this.maximumHistory = Math.max(2, maximumHistory);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostConstruct
    public synchronized void initialize() {
        state = repository.load().orElseGet(this::initialState);
        if (state.current() == null) {
            state = initialState();
        }
        repository.save(state);
    }

    public RoutingPolicyDeployment current() {
        return requireState().current();
    }

    public RoutingPolicyDeploymentState state() {
        return requireState();
    }

    public int minimumCanarySamples() {
        return minimumCanarySamples;
    }

    public synchronized RoutingPolicyDeploymentState deploy(
            RoutingPolicyMode mode,
            Double canaryRate,
            String reason,
            PromotionEvidence evidence) {
        return deploy(mode, canaryRate, null, reason, evidence);
    }

    public synchronized RoutingPolicyDeploymentState deploy(
            RoutingPolicyMode mode,
            Double canaryRate,
            String policyVersion,
            String reason,
            PromotionEvidence evidence) {
        Objects.requireNonNull(mode, "mode");
        RoutingPolicyDeploymentState currentState = requireState();
        validatePromotion(currentState.current(), mode, evidence);
        double resolvedCanaryRate = mode == RoutingPolicyMode.CANARY
                ? normalizeCanaryRate(canaryRate == null
                        ? currentState.current().canaryRate()
                        : canaryRate)
                : currentState.current().canaryRate();
        RoutingPolicyDeployment deployment = new RoutingPolicyDeployment(
                "routing-" + UUID.randomUUID(),
                normalizePolicyVersion(
                        policyVersion,
                        currentState.current().policyVersion()
                ),
                mode,
                resolvedCanaryRate,
                clock.instant(),
                normalizeReason(reason, "manual deployment")
        );
        state = persist(deployment, currentState.history());
        return state;
    }

    public synchronized RoutingPolicyDeploymentState rollback(String reason) {
        RoutingPolicyDeploymentState currentState = requireState();
        List<RoutingPolicyDeployment> history = currentState.history();
        if (history.isEmpty()) {
            throw new NoSuchElementException("No previous routing policy deployment");
        }
        RoutingPolicyDeployment previous = history.getFirst();
        RoutingPolicyDeployment rollback = new RoutingPolicyDeployment(
                "routing-" + UUID.randomUUID(),
                previous.policyVersion(),
                previous.mode(),
                previous.canaryRate(),
                clock.instant(),
                normalizeReason(reason, "rollback to " + previous.version())
        );
        List<RoutingPolicyDeployment> remaining = new ArrayList<>(
                history.subList(1, history.size()));
        state = persist(rollback, remaining);
        return state;
    }

    private void validatePromotion(RoutingPolicyDeployment current,
                                   RoutingPolicyMode target,
                                   PromotionEvidence evidence) {
        if (target == RoutingPolicyMode.OFF || target == RoutingPolicyMode.SHADOW) {
            return;
        }
        if (evidence == null || !evidence.policyReady()) {
            throw new IllegalStateException(
                    "Routing policy does not have enough balanced evidence");
        }
        if (target == RoutingPolicyMode.ACTIVE) {
            if (current.mode() != RoutingPolicyMode.CANARY) {
                throw new IllegalStateException(
                        "ACTIVE promotion requires the current mode to be CANARY");
            }
            if (evidence.canarySelectedSamples() < minimumCanarySamples) {
                throw new IllegalStateException(
                        "ACTIVE promotion requires at least %d canary samples"
                                .formatted(minimumCanarySamples));
            }
            if (!evidence.canaryHealthy()) {
                throw new IllegalStateException(
                        "ACTIVE promotion requires the canary quality guard to be healthy");
            }
            if (!evidence.offPolicyHealthy()) {
                throw new IllegalStateException(
                        "ACTIVE promotion requires off-policy evaluation to be healthy");
            }
        }
    }

    private RoutingPolicyDeploymentState persist(
            RoutingPolicyDeployment deployment,
            List<RoutingPolicyDeployment> existingHistory) {
        List<RoutingPolicyDeployment> history = new ArrayList<>();
        RoutingPolicyDeployment current = state == null ? null : state.current();
        if (current != null) {
            history.add(current);
        }
        history.addAll(existingHistory);
        List<RoutingPolicyDeployment> bounded = history.stream()
                .limit(maximumHistory)
                .toList();
        return repository.save(new RoutingPolicyDeploymentState(deployment, bounded));
    }

    private RoutingPolicyDeploymentState initialState() {
        RoutingPolicyDeployment initial = new RoutingPolicyDeployment(
                "routing-initial",
                RoutingPolicyRegistryService.BASELINE_VERSION,
                defaultMode,
                defaultCanaryRate,
                clock.instant(),
                "configured default"
        );
        return new RoutingPolicyDeploymentState(initial, List.of());
    }

    private RoutingPolicyDeploymentState requireState() {
        RoutingPolicyDeploymentState current = state;
        if (current == null) {
            synchronized (this) {
                if (state == null) {
                    initialize();
                }
                current = state;
            }
        }
        return current;
    }

    private double normalizeCanaryRate(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            throw new IllegalArgumentException("canaryRate must be in (0, 1]");
        }
        return value;
    }

    private String normalizeReason(String reason, String fallback) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String normalizePolicyVersion(String requested, String fallback) {
        String normalized = requested == null ? "" : requested.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return fallback == null || fallback.isBlank()
                ? RoutingPolicyRegistryService.BASELINE_VERSION
                : fallback;
    }

    public record PromotionEvidence(
            boolean policyReady,
            int canarySelectedSamples,
            boolean canaryHealthy,
            boolean offPolicyHealthy
    ) {

        public PromotionEvidence(
                boolean policyReady,
                int canarySelectedSamples,
                boolean canaryHealthy) {
            this(policyReady, canarySelectedSamples, canaryHealthy, true);
        }
    }
}
