package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeterministicRoutingContractTest {

    private static final String CONTRACT_RESOURCE =
            "/multiagent/deterministic-routing-contract-v1.json";

    @Test
    void javaRouterMatchesSharedOfflineSeedContract() throws IOException {
        JsonNode contract = loadContract();
        DeterministicRoutingContract runtimeContract =
                DeterministicRoutingContract.defaultContract();
        int minimumDomains = contract.path("minimum_domains").asInt();
        int maxAgents = contract.path("max_agents").asInt();
        AdaptiveMultiAgentOrchestrator orchestrator =
                new AdaptiveMultiAgentOrchestrator(
                        ChatClient.builder(mock(ChatModel.class)).build(),
                        true,
                        minimumDomains,
                        maxAgents
                );

        assertThat(contract.path("schema_version").asText())
                .isEqualTo(RoutingPolicyRegistryService.DETERMINISTIC_ROUTER_VERSION);
        assertThat(runtimeContract.minimumDomains()).isEqualTo(minimumDomains);
        assertThat(runtimeContract.maxAgents()).isEqualTo(maxAgents);
        assertThat(runtimeContract.fallbackDomain().name())
                .isEqualTo(contract.path("fallback_domain").asText());

        JsonNode keywordsByDomain = contract.path("domain_keywords");
        assertThat(runtimeContract.domainKeywords()).containsOnlyKeys(
                AgentDomain.values());
        Iterator<Map.Entry<String, JsonNode>> fields = keywordsByDomain.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            AgentDomain expectedDomain = AgentDomain.valueOf(field.getKey());
            assertThat(runtimeContract.domainKeywords().get(expectedDomain))
                    .containsExactlyElementsOf(
                            streamValues(field.getValue()));
            for (JsonNode keyword : field.getValue()) {
                MultiAgentDecision decision = orchestrator.route(
                        keyword.asText(),
                        MultiAgentRoutingMode.FORCE_MULTI
                );
                assertThat(decision.selectedDomains())
                        .as("keyword %s", keyword.asText())
                        .containsExactly(expectedDomain);
            }
        }

        AgentDomain fallback = AgentDomain.valueOf(
                contract.path("fallback_domain").asText());
        assertThat(orchestrator.route(
                "完全未知的话题",
                MultiAgentRoutingMode.FORCE_MULTI
        ).selectedDomains()).containsExactly(fallback);

        List<AgentDomain> expectedSelection = new ArrayList<>();
        List<String> combinedKeywords = new ArrayList<>();
        for (JsonNode domain : contract.path("domain_selection_order")) {
            String domainName = domain.asText();
            expectedSelection.add(AgentDomain.valueOf(domainName));
            combinedKeywords.add(
                    keywordsByDomain.path(domainName).path(0).asText());
        }
        assertThat(runtimeContract.selectionOrder())
                .containsExactlyElementsOf(expectedSelection);
        assertThat(orchestrator.route(
                String.join("、", combinedKeywords),
                MultiAgentRoutingMode.FORCE_MULTI
        ).selectedDomains()).containsExactlyElementsOf(
                expectedSelection.stream().limit(maxAgents).toList());

        List<String> adaptiveKeywords = new ArrayList<>();
        Iterator<String> domainNames = keywordsByDomain.fieldNames();
        while (domainNames.hasNext() && adaptiveKeywords.size() < minimumDomains) {
            String domain = domainNames.next();
            adaptiveKeywords.add(keywordsByDomain.path(domain).path(0).asText());
        }
        assertThat(orchestrator.route(adaptiveKeywords.getFirst()).multiAgent())
                .isFalse();
        assertThat(orchestrator.route(
                String.join("、", adaptiveKeywords)).multiAgent()).isTrue();
    }

    private JsonNode loadContract() throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getResourceAsStream(CONTRACT_RESOURCE),
                "missing routing contract")) {
            return new ObjectMapper().readTree(stream);
        }
    }

    private List<String> streamValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
