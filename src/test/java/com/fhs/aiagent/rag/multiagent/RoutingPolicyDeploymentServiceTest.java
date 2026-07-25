package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingPolicyDeploymentServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enforcesShadowCanaryActivePromotionAndRollback() {
        RoutingPolicyDeploymentService service = service(repository());
        service.initialize();

        assertThat(service.current().mode()).isEqualTo(RoutingPolicyMode.SHADOW);
        assertThatThrownBy(() -> service.deploy(
                RoutingPolicyMode.CANARY,
                0.2,
                "not ready",
                new RoutingPolicyDeploymentService.PromotionEvidence(false, 0, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enough balanced evidence");

        RoutingPolicyDeploymentState canary = service.deploy(
                RoutingPolicyMode.CANARY,
                0.2,
                "routing-policy-candidate-1",
                "balanced evidence ready",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 0, false));
        assertThat(canary.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(canary.current().canaryRate()).isEqualTo(0.2);
        assertThat(canary.current().policyVersion())
                .isEqualTo("routing-policy-candidate-1");

        assertThatThrownBy(() -> service.deploy(
                RoutingPolicyMode.ACTIVE,
                null,
                "too early",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 19, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 20 canary samples");

        assertThatThrownBy(() -> service.deploy(
                RoutingPolicyMode.ACTIVE,
                null,
                "quality regression",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 20, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quality guard");

        RoutingPolicyDeploymentState active = service.deploy(
                RoutingPolicyMode.ACTIVE,
                null,
                "canary passed",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 20, true));
        assertThat(active.current().mode()).isEqualTo(RoutingPolicyMode.ACTIVE);
        assertThat(active.current().policyVersion())
                .isEqualTo("routing-policy-candidate-1");

        RoutingPolicyDeploymentState rolledBack = service.rollback("quality regression");
        assertThat(rolledBack.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(rolledBack.current().policyVersion())
                .isEqualTo("routing-policy-candidate-1");
        assertThat(rolledBack.history().getFirst().mode()).isEqualTo(RoutingPolicyMode.ACTIVE);
    }

    @Test
    void reloadsPersistedDeploymentAfterRestart() {
        FileRoutingPolicyDeploymentRepository repository = repository();
        RoutingPolicyDeploymentService first = service(repository);
        first.initialize();
        first.deploy(
                RoutingPolicyMode.CANARY,
                0.15,
                "first process",
                new RoutingPolicyDeploymentService.PromotionEvidence(true, 0, false));

        RoutingPolicyDeploymentService restarted = service(repository());
        restarted.initialize();

        assertThat(restarted.current().mode()).isEqualTo(RoutingPolicyMode.CANARY);
        assertThat(restarted.current().canaryRate()).isEqualTo(0.15);
        assertThat(restarted.state().history()).hasSize(1);
    }

    private RoutingPolicyDeploymentService service(
            RoutingPolicyDeploymentRepository repository) {
        return new RoutingPolicyDeploymentService(
                repository,
                RoutingPolicyMode.SHADOW,
                0.1,
                20,
                20,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private FileRoutingPolicyDeploymentRepository repository() {
        return new FileRoutingPolicyDeploymentRepository(
                new ObjectMapper().findAndRegisterModules(),
                temporaryDirectory.resolve("deployment.json").toString()
        );
    }
}
