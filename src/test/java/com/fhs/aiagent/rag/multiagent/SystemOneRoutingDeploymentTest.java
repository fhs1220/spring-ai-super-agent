package com.fhs.aiagent.rag.multiagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SystemOneRoutingDeploymentTest {

    @TempDir Path temporaryDirectory;

    private static final String MODEL = "OPENROUTER_JEV:jev-frozen";

    @Test
    void offAndShadowCannotTakeOver() {
        var advice = advice(true, MODEL);
        for (var mode : List.of(SystemOneRoutingDeployment.Mode.OFF,
                SystemOneRoutingDeployment.Mode.SHADOW)) {
            var deployment = new SystemOneRoutingDeployment(mode, 0, "", "", "", 0.2, false);
            var decision = deployment.decide("孩子和家务", advice, false, List.of(), false, 3);
            assertThat(decision.applied()).isFalse();
            assertThat(decision.multiAgent()).isFalse();
        }
    }

    @Test
    void canarySelectionIsStableAndKeepsControls() throws Exception {
        var deployment = deployment(SystemOneRoutingDeployment.Mode.CANARY, 5, true);
        long selected = java.util.stream.IntStream.range(0, 1000)
                .filter(index -> deployment.selects("问题-" + index)).count();
        assertThat(selected).isBetween(20L, 80L);
        assertThat(deployment.selects("孩子和家务"))
                .isEqualTo(deployment.selects("孩子和家务"));
        assertThatThrownBy(() -> deployment(SystemOneRoutingDeployment.Mode.CANARY, 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesOnlyValidBoundAdviceAndExecutableExperts() throws Exception {
        var deployment = deployment(SystemOneRoutingDeployment.Mode.ACTIVE, 0, true);
        var baselineDomains = List.of(AgentDomain.PARENTING, AgentDomain.HOUSEHOLD);
        var accepted = deployment.decide("安排家务和预算", advice(true, MODEL),
                false, List.of(), false, 3);
        assertThat(accepted.applied()).isTrue();
        assertThat(accepted.domains()).containsExactly(AgentDomain.PARENTING, AgentDomain.HOUSEHOLD);

        var wrongModel = deployment.decide("安排家务和预算", advice(true, "LAYA:other"),
                true, baselineDomains, false, 3);
        assertThat(wrongModel.status()).isEqualTo("ADVICE_FALLBACK");
        assertThat(wrongModel.domains()).isEqualTo(baselineDomains);

        var insufficient = new SystemOneRoutingAdvisor.RoutingAdvice(
                "SHADOW", "SUCCESS", true, 0.9,
                Map.of(AgentDomain.PARENTING, 0.8), 1, MODEL);
        assertThat(deployment.decide("育儿", insufficient, false, List.of(), false, 3).status())
                .isEqualTo("EXPERT_FALLBACK");

        assertThat(deployment.decide("危险情境", advice(false, MODEL),
                true, baselineDomains, true, 3).status()).isEqualTo("SAFETY_FALLBACK");
    }

    @Test
    void rejectsUnapprovedOrTamperedReleaseEvidence() throws Exception {
        assertThatThrownBy(() -> deployment(SystemOneRoutingDeployment.Mode.ACTIVE, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match deployment");
        Path manifest = manifest(true);
        Files.writeString(temporaryDirectory.resolve("report.json"), "{}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.ACTIVE, 0, "release-1", MODEL,
                manifest.toString(), 0.2, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash mismatch");
        assertThatThrownBy(() -> new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.ACTIVE, 0, "release-1", MODEL,
                manifest.toString(), 0.2, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncompatibleLiveConfigurations() throws Exception {
        String path = manifest(true).toString();
        assertThatThrownBy(() -> new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.CANARY, 5, "release-1", MODEL,
                path, 0.2, true, "OFF", false, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.CANARY, 5, "release-1", MODEL,
                path, 0.2, true, "SHADOW", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.CANARY, 5, "release-1", MODEL,
                path, 0.2, true, "SHADOW", false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orchestratorAppliesCandidateButForcedEvaluationStillBypassesIt() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(model).build(), true, 2, 3);
        orchestrator.setSystemOneRoutingDeployment(
                deployment(SystemOneRoutingDeployment.Mode.ACTIVE, 0, true));
        orchestrator.setSystemOneRoutingAdvisor(ignored -> advice(true, MODEL));

        var live = orchestrator.route("请给我一个安排方案");
        assertThat(live.multiAgent()).isTrue();
        assertThat(live.selectedDomains())
                .containsExactly(AgentDomain.PARENTING, AgentDomain.HOUSEHOLD);
        assertThat(live.systemOneApplied()).isTrue();
        assertThat(live.systemOneReleaseVersion()).isEqualTo("release-1");
        assertThat(live.systemOneApplicationStatus()).isEqualTo("APPLIED");

        var forced = orchestrator.route("请给我一个安排方案", MultiAgentRoutingMode.FORCE_SINGLE);
        assertThat(forced.multiAgent()).isFalse();
        assertThat(forced.systemOneApplied()).isFalse();
        assertThat(forced.systemOneApplicationStatus()).isEqualTo("SKIPPED_EVALUATION_OVERRIDE");
    }

    @Test
    void onlineAdvisorFailureFallsBackToBaseline() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(model).build(), true, 2, 3);
        orchestrator.setSystemOneRoutingDeployment(
                deployment(SystemOneRoutingDeployment.Mode.ACTIVE, 0, true));
        orchestrator.setSystemOneRoutingAdvisor(ignored -> {
            throw new IllegalStateException("provider unavailable");
        });

        var decision = orchestrator.route("异地恋怎样沟通？");
        assertThat(decision.multiAgent()).isFalse();
        assertThat(decision.systemOneCanarySelected()).isTrue();
        assertThat(decision.systemOneApplied()).isFalse();
        assertThat(decision.systemOneApplicationStatus()).isEqualTo("ADVICE_FALLBACK");
    }

    @Test
    void operationalStopPreventsFurtherCandidateCalls() throws Exception {
        ChatModel model = mock(ChatModel.class);
        var orchestrator = new AdaptiveMultiAgentOrchestrator(
                ChatClient.builder(model).build(), true, 2, 3);
        var guard = new SystemOneRolloutGuard(temporaryDirectory.resolve("rollout"),
                "release-1", 2, 20, 0.10);
        orchestrator.setSystemOneRoutingDeployment(new SystemOneRoutingDeployment(
                SystemOneRoutingDeployment.Mode.ACTIVE, 0, "release-1", MODEL,
                manifest(true).toString(), 0.2, true, "SHADOW", false, true, guard));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        orchestrator.setSystemOneRoutingAdvisor(ignored -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        });

        assertThat(orchestrator.route("问题一").systemOneApplicationStatus())
                .isEqualTo("ADVICE_FALLBACK");
        assertThat(orchestrator.route("问题二").systemOneApplicationStatus())
                .isEqualTo("AUTO_PAUSED");
        assertThat(orchestrator.route("问题三").systemOneApplicationStatus())
                .isEqualTo("AUTO_PAUSED");
        assertThat(calls).hasValue(2);
    }

    private SystemOneRoutingDeployment deployment(SystemOneRoutingDeployment.Mode mode,
                                                   int percent, boolean approved) throws Exception {
        return new SystemOneRoutingDeployment(mode, percent, "release-1", MODEL,
                manifest(approved).toString(), 0.2, true);
    }

    private Path manifest(boolean approved) throws Exception {
        byte[] report = ("{\"releaseGatePassed\":true,\"gateFailures\":[],"
                + "\"caseCount\":40,\"model\":\"" + MODEL + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(temporaryDirectory.resolve("report.json"), report);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(report));
        Path manifest = temporaryDirectory.resolve("manifest.json");
        Files.writeString(manifest, "{\"release_version\":\"release-1\","
                + "\"model\":\"" + MODEL + "\","
                + "\"report_path\":\"report.json\","
                + "\"report_sha256\":\"" + sha + "\","
                + "\"dataset_sha256\":\"" + "a".repeat(64) + "\","
                + "\"approved_by\":\"reviewer\","
                + "\"independent_holdout_approved\":" + approved + ","
                + "\"multi_agent_threshold\":0.2}", StandardCharsets.UTF_8);
        return manifest;
    }

    private SystemOneRoutingAdvisor.RoutingAdvice advice(boolean multi, String model) {
        return new SystemOneRoutingAdvisor.RoutingAdvice("SHADOW", "SUCCESS", multi,
                multi ? 0.9 : 0.1,
                Map.of(AgentDomain.PARENTING, 0.9, AgentDomain.HOUSEHOLD, 0.8), 2, model);
    }
}
