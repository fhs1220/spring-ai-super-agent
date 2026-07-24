package com.fhs.aiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 向量召回 + 本地关键词召回 + RRF 融合重排。
 */
@Component
public class HybridDocumentRetriever {

    public static final String STRATEGY = "VECTOR + LEXICAL + RRF LOCAL RERANK";

    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9]{2,}");

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;

    private final List<Document> lexicalCorpus;

    private final int minTopK;

    private final int maxTopK;

    private final double similarityThreshold;

    @Autowired
    public HybridDocumentRetriever(
            @Qualifier("loveAppVectorStore") VectorStore vectorStore,
            AppDocumentLoader documentLoader,
            @Value("${agent.rag.retrieval.min-top-k:3}") int minTopK,
            @Value("${agent.rag.retrieval.max-top-k:6}") int maxTopK,
            @Value("${agent.rag.retrieval.similarity-threshold:0.2}") double similarityThreshold) {
        this(vectorStore, documentLoader.loadMarkdowns(), minTopK, maxTopK, similarityThreshold);
    }

    HybridDocumentRetriever(VectorStore vectorStore,
                            List<Document> lexicalCorpus,
                            int minTopK,
                            int maxTopK,
                            double similarityThreshold) {
        if (minTopK <= 0 || maxTopK < minTopK) {
            throw new IllegalArgumentException("Invalid dynamic Top-K range");
        }
        this.vectorStore = vectorStore;
        this.lexicalCorpus = lexicalCorpus == null ? List.of() : List.copyOf(lexicalCorpus);
        this.minTopK = minTopK;
        this.maxTopK = maxTopK;
        this.similarityThreshold = similarityThreshold;
    }

    public HybridSearchResult search(String query) {
        int topK = dynamicTopK(query);
        int candidateLimit = Math.max(topK * 2, maxTopK);
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(candidateLimit)
                .similarityThreshold(similarityThreshold)
                .build();

        List<Document> vectorDocuments = safeDocuments(vectorStore.similaritySearch(searchRequest));
        List<ScoredDocument> lexicalDocuments = lexicalSearch(query, candidateLimit);
        Map<String, Candidate> candidates = new LinkedHashMap<>();

        for (int index = 0; index < vectorDocuments.size(); index++) {
            Document document = vectorDocuments.get(index);
            Candidate candidate = candidates.computeIfAbsent(
                    documentKey(document), ignored -> new Candidate(document));
            candidate.vectorRank = index + 1;
            candidate.vectorScore = normalizedScore(document.getScore());
        }
        for (int index = 0; index < lexicalDocuments.size(); index++) {
            ScoredDocument scored = lexicalDocuments.get(index);
            Candidate candidate = candidates.computeIfAbsent(
                    documentKey(scored.document()), ignored -> new Candidate(scored.document()));
            candidate.lexicalRank = index + 1;
            candidate.lexicalScore = scored.score();
        }

        List<Document> reranked = candidates.values().stream()
                .peek(candidate -> candidate.finalScore = rerankScore(query, candidate))
                .sorted(Comparator.comparingDouble((Candidate candidate) -> candidate.finalScore).reversed())
                .limit(topK)
                .map(candidate -> candidate.document.mutate().score(candidate.finalScore).build())
                .toList();

        return new HybridSearchResult(
                reranked,
                topK,
                vectorDocuments.size(),
                lexicalDocuments.size(),
                candidates.size(),
                STRATEGY
        );
    }

    int dynamicTopK(String query) {
        String normalized = normalize(query);
        int length = normalized.codePointCount(0, normalized.length());
        int topK = length <= 10 ? minTopK : length <= 22 ? minTopK + 1 : minTopK + 2;
        long intentSeparators = normalized.codePoints()
                .filter(character -> character == '、' || character == '，'
                        || character == '；' || character == ',' || character == ';')
                .count();
        if (intentSeparators > 0
                || normalized.contains("以及")
                || normalized.contains("同时")
                || normalized.contains("分别")) {
            topK++;
        }
        return Math.max(minTopK, Math.min(maxTopK, topK));
    }

    private List<ScoredDocument> lexicalSearch(String query, int limit) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || lexicalCorpus.isEmpty()) {
            return List.of();
        }
        return lexicalCorpus.stream()
                .filter(Objects::nonNull)
                .map(document -> new ScoredDocument(document, lexicalCoverage(queryTerms, document)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(limit)
                .toList();
    }

    private double rerankScore(String query, Candidate candidate) {
        double rrf = 0;
        if (candidate.vectorRank > 0) {
            rrf += 0.6 * (RRF_K + 1.0) / (RRF_K + candidate.vectorRank);
        }
        if (candidate.lexicalRank > 0) {
            rrf += 0.4 * (RRF_K + 1.0) / (RRF_K + candidate.lexicalRank);
        }
        double coverage = Math.max(
                candidate.lexicalScore,
                lexicalCoverage(terms(query), candidate.document));
        double phraseBonus = containsCompactPhrase(candidate.document, query) ? 1.0 : 0.0;
        return round(
                0.45 * rrf
                        + 0.30 * coverage
                        + 0.20 * candidate.vectorScore
                        + 0.05 * phraseBonus
        );
    }

    private double lexicalCoverage(Set<String> queryTerms, Document document) {
        if (queryTerms.isEmpty() || document == null) {
            return 0;
        }
        Set<String> documentTerms = terms(searchableText(document));
        double matchedWeight = 0;
        double totalWeight = 0;
        for (String term : queryTerms) {
            double weight = term.codePointCount(0, term.length()) >= 2 ? 1.0 : 0.25;
            totalWeight += weight;
            if (documentTerms.contains(term)) {
                matchedWeight += weight;
            }
        }
        return totalWeight == 0 ? 0 : matchedWeight / totalWeight;
    }

    private Set<String> terms(String value) {
        String normalized = normalize(value);
        Set<String> terms = new HashSet<>();
        Matcher latinMatcher = LATIN_TERM.matcher(normalized);
        while (latinMatcher.find()) {
            terms.add(latinMatcher.group());
        }

        StringBuilder hanRun = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                hanRun.appendCodePoint(codePoint);
            } else {
                addHanNgrams(hanRun, terms);
                hanRun.setLength(0);
            }
        });
        addHanNgrams(hanRun, terms);
        return terms;
    }

    private void addHanNgrams(StringBuilder hanRun, Set<String> terms) {
        int[] codePoints = hanRun.toString().codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(new String(codePoints, 0, 1));
            return;
        }
        for (int index = 0; index < codePoints.length - 1; index++) {
            terms.add(new String(codePoints, index, 2));
        }
    }

    private boolean containsCompactPhrase(Document document, String query) {
        String compactQuery = compact(query);
        return compactQuery.length() >= 2
                && compactQuery.length() <= 24
                && compact(searchableText(document)).contains(compactQuery);
    }

    private String searchableText(Document document) {
        StringBuilder value = new StringBuilder(Objects.toString(document.getText(), ""));
        document.getMetadata().forEach((key, metadataValue) -> value
                .append(' ')
                .append(key)
                .append(' ')
                .append(Objects.toString(metadataValue, "")));
        return value.toString();
    }

    private String documentKey(Document document) {
        String source = Objects.toString(document.getMetadata().get("filename"), "");
        return source + "\u0000" + normalize(document.getText());
    }

    private List<Document> safeDocuments(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream().filter(Objects::nonNull).toList();
    }

    private double normalizedScore(Double score) {
        if (score == null || score.isNaN()) {
            return 0;
        }
        return Math.max(0, Math.min(1, score));
    }

    private String compact(String value) {
        return normalize(value).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private String normalize(String value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT).trim();
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record HybridSearchResult(
            List<Document> documents,
            int topK,
            int vectorCandidateCount,
            int lexicalCandidateCount,
            int fusedCandidateCount,
            String strategy
    ) {
    }

    private record ScoredDocument(Document document, double score) {
    }

    private static final class Candidate {

        private final Document document;

        private int vectorRank;

        private int lexicalRank;

        private double vectorScore;

        private double lexicalScore;

        private double finalScore;

        private Candidate(Document document) {
            this.document = document;
        }
    }
}
