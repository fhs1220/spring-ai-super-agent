package com.fhs.aiagent.controller;

import com.fhs.aiagent.rag.multiagent.RoutingPolicyDeploymentService;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyDeploymentState;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyMode;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyOffPolicyEvaluator;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyQualityGuard;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyRegistryCoordinator;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyRegistryService;
import com.fhs.aiagent.rag.multiagent.TrajectoryAwareRoutingPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/agent-routing-policy")
@ConditionalOnProperty(
        name = "agent.rag.routing-policy.management-api-enabled",
        havingValue = "true")
public class RoutingPolicyController {

    private final RoutingPolicyDeploymentService deploymentService;

    private final TrajectoryAwareRoutingPolicy routingPolicy;

    private final RoutingPolicyQualityGuard qualityGuard;

    private final RoutingPolicyRegistryService registryService;

    private final RoutingPolicyRegistryCoordinator registryCoordinator;

    private final RoutingPolicyOffPolicyEvaluator offPolicyEvaluator;

    public RoutingPolicyController(RoutingPolicyDeploymentService deploymentService,
                                   TrajectoryAwareRoutingPolicy routingPolicy,
                                   RoutingPolicyQualityGuard qualityGuard,
                                   RoutingPolicyRegistryService registryService,
                                   RoutingPolicyRegistryCoordinator registryCoordinator,
                                   RoutingPolicyOffPolicyEvaluator offPolicyEvaluator) {
        this.deploymentService = deploymentService;
        this.routingPolicy = routingPolicy;
        this.qualityGuard = qualityGuard;
        this.registryService = registryService;
        this.registryCoordinator = registryCoordinator;
        this.offPolicyEvaluator = offPolicyEvaluator;
    }

    @GetMapping("/deployments")
    public RoutingPolicyDeploymentState deployments() {
        return deploymentService.state();
    }

    @PostMapping("/deployments")
    public RoutingPolicyDeploymentState deploy(@RequestBody DeploymentRequest request) {
        if (request == null || request.mode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is required");
        }
        TrajectoryAwareRoutingPolicy.RoutingPolicyStatus status = routingPolicy.status();
        RoutingPolicyQualityGuard.QualityGuardReport guardReport =
                qualityGuard.evaluateAndMaybeRollback();
        RoutingPolicyOffPolicyEvaluator.OffPolicyEvaluationReport offPolicyReport =
                offPolicyEvaluator.evaluate();
        try {
            if (request.mode() == RoutingPolicyMode.CANARY
                    || request.mode() == RoutingPolicyMode.ACTIVE) {
                registryCoordinator.reconcileNow();
            }
            String policyVersion = resolvePolicyVersion(
                    request.mode(),
                    request.policyVersion()
            );
            return deploymentService.deploy(
                    request.mode(),
                    request.canaryRate(),
                    policyVersion,
                    request.reason(),
                    new RoutingPolicyDeploymentService.PromotionEvidence(
                            status.ready(),
                            guardReport.canary().sampleCount(),
                            guardReport.healthyForPromotion(),
                            offPolicyReport.healthyForPromotion()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private String resolvePolicyVersion(RoutingPolicyMode target, String requested) {
        String normalized = requested == null ? "" : requested.trim();
        if (target == RoutingPolicyMode.CANARY) {
            String version = normalized.isBlank()
                    ? registryService.latestValidatedCandidate().version()
                    : normalized;
            return registryService.requireDeployable(version).version();
        }
        if (target == RoutingPolicyMode.ACTIVE) {
            String current = deploymentService.current().policyVersion();
            if (!normalized.isBlank() && !normalized.equals(current)) {
                throw new IllegalStateException(
                        "ACTIVE promotion must keep the current CANARY policy artifact");
            }
            return registryService.requireDeployable(current).version();
        }
        return deploymentService.current().policyVersion();
    }

    @PostMapping("/rollback")
    public RoutingPolicyDeploymentState rollback(
            @RequestBody(required = false) RollbackRequest request) {
        try {
            return deploymentService.rollback(request == null ? "" : request.reason());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    public record DeploymentRequest(
            RoutingPolicyMode mode,
            Double canaryRate,
            String policyVersion,
            String reason
    ) {
    }

    public record RollbackRequest(String reason) {
    }
}
