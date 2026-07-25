package com.fhs.aiagent.controller;

import com.fhs.aiagent.rag.multiagent.RoutingPolicyDeploymentService;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyDeploymentState;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyMode;
import com.fhs.aiagent.rag.multiagent.RoutingPolicyQualityGuard;
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

    public RoutingPolicyController(RoutingPolicyDeploymentService deploymentService,
                                   TrajectoryAwareRoutingPolicy routingPolicy,
                                   RoutingPolicyQualityGuard qualityGuard) {
        this.deploymentService = deploymentService;
        this.routingPolicy = routingPolicy;
        this.qualityGuard = qualityGuard;
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
        try {
            return deploymentService.deploy(
                    request.mode(),
                    request.canaryRate(),
                    request.reason(),
                    new RoutingPolicyDeploymentService.PromotionEvidence(
                            status.ready(),
                            guardReport.canary().sampleCount(),
                            guardReport.healthyForPromotion()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
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
            String reason
    ) {
    }

    public record RollbackRequest(String reason) {
    }
}
