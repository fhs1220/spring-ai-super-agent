package com.fhs.aiagent.rag.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DeterministicRoutingContract {

    private static final String RESOURCE =
            "/multiagent/deterministic-routing-contract-v1.json";

    private static final DeterministicRoutingContract DEFAULT = loadDefault();

    private final String schemaVersion;

    private final int minimumDomains;

    private final int maxAgents;

    private final AgentDomain fallbackDomain;

    private final List<AgentDomain> selectionOrder;

    private final Map<AgentDomain, List<String>> domainKeywords;

    private DeterministicRoutingContract(
            String schemaVersion,
            int minimumDomains,
            int maxAgents,
            AgentDomain fallbackDomain,
            List<AgentDomain> selectionOrder,
            Map<AgentDomain, List<String>> domainKeywords) {
        this.schemaVersion = schemaVersion;
        this.minimumDomains = minimumDomains;
        this.maxAgents = maxAgents;
        this.fallbackDomain = fallbackDomain;
        this.selectionOrder = List.copyOf(selectionOrder);
        this.domainKeywords = Map.copyOf(domainKeywords);
    }

    static DeterministicRoutingContract defaultContract() {
        return DEFAULT;
    }

    String schemaVersion() {
        return schemaVersion;
    }

    int minimumDomains() {
        return minimumDomains;
    }

    int maxAgents() {
        return maxAgents;
    }

    AgentDomain fallbackDomain() {
        return fallbackDomain;
    }

    List<AgentDomain> selectionOrder() {
        return selectionOrder;
    }

    Map<AgentDomain, List<String>> domainKeywords() {
        return domainKeywords;
    }

    private static DeterministicRoutingContract loadDefault() {
        try (InputStream stream = Objects.requireNonNull(
                DeterministicRoutingContract.class.getResourceAsStream(RESOURCE),
                "Missing deterministic routing contract: " + RESOURCE)) {
            JsonNode root = new ObjectMapper().readTree(stream);
            String schemaVersion = requiredText(root, "schema_version");
            int minimumDomains = requiredPositiveInt(root, "minimum_domains");
            int maxAgents = requiredPositiveInt(root, "max_agents");
            AgentDomain fallbackDomain = parseDomain(
                    requiredText(root, "fallback_domain"),
                    "fallback_domain"
            );
            List<AgentDomain> selectionOrder = parseSelectionOrder(
                    root.path("domain_selection_order"));
            Map<AgentDomain, List<String>> domainKeywords = parseKeywords(
                    root.path("domain_keywords"));

            Set<AgentDomain> domains = domainKeywords.keySet();
            if (!new LinkedHashSet<>(selectionOrder).equals(domains)
                    || selectionOrder.size() != domains.size()) {
                throw new IllegalStateException(
                        "Routing contract selection order must contain every domain exactly once");
            }
            if (!domains.contains(fallbackDomain)) {
                throw new IllegalStateException(
                        "Routing contract fallback domain must have keywords");
            }
            return new DeterministicRoutingContract(
                    schemaVersion,
                    minimumDomains,
                    maxAgents,
                    fallbackDomain,
                    selectionOrder,
                    domainKeywords
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read deterministic routing contract", exception);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "Routing contract field is required: " + field);
        }
        return value;
    }

    private static int requiredPositiveInt(JsonNode root, String field) {
        int value = root.path(field).asInt(0);
        if (value < 1) {
            throw new IllegalStateException(
                    "Routing contract field must be positive: " + field);
        }
        return value;
    }

    private static AgentDomain parseDomain(String value, String field) {
        try {
            return AgentDomain.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Routing contract has invalid domain in " + field + ": " + value,
                    exception
            );
        }
    }

    private static List<AgentDomain> parseSelectionOrder(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalStateException(
                    "Routing contract domain_selection_order must be a non-empty array");
        }
        List<AgentDomain> domains = new ArrayList<>();
        value.forEach(node -> domains.add(parseDomain(
                node.asText(""), "domain_selection_order")));
        if (new LinkedHashSet<>(domains).size() != domains.size()) {
            throw new IllegalStateException(
                    "Routing contract domain_selection_order contains duplicates");
        }
        return domains;
    }

    private static Map<AgentDomain, List<String>> parseKeywords(JsonNode value) {
        if (!value.isObject() || value.isEmpty()) {
            throw new IllegalStateException(
                    "Routing contract domain_keywords must be a non-empty object");
        }
        Map<AgentDomain, List<String>> keywords = new EnumMap<>(AgentDomain.class);
        value.fields().forEachRemaining(field -> {
            AgentDomain domain = parseDomain(field.getKey(), "domain_keywords");
            if (!field.getValue().isArray() || field.getValue().isEmpty()) {
                throw new IllegalStateException(
                        "Routing contract keywords must be non-empty for " + domain);
            }
            List<String> items = new ArrayList<>();
            field.getValue().forEach(node -> {
                String keyword = node.asText("").trim();
                if (keyword.isEmpty()) {
                    throw new IllegalStateException(
                            "Routing contract contains a blank keyword for " + domain);
                }
                items.add(keyword);
            });
            if (new LinkedHashSet<>(items).size() != items.size()) {
                throw new IllegalStateException(
                        "Routing contract contains duplicate keywords for " + domain);
            }
            keywords.put(domain, List.copyOf(items));
        });
        return keywords;
    }
}
