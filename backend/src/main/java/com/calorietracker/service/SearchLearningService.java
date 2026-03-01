package com.calorietracker.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.SearchLearnedAlias;
import com.calorietracker.entity.SearchLearningDomain;
import com.calorietracker.entity.SearchMissLog;
import com.calorietracker.repository.SearchLearnedAliasRepository;
import com.calorietracker.repository.SearchMissLogRepository;

@Service
public class SearchLearningService {

    private static final int MIN_QUERY_LENGTH = 3;
    private static final int MAX_QUERY_LENGTH = 180;
    private static final int MAX_CACHE_SIZE = 3000;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final SearchLearnedAliasRepository searchLearnedAliasRepository;
    private final SearchMissLogRepository searchMissLogRepository;
    private final FoodMetadataService foodMetadataService;
    private final ConcurrentMap<String, CachedAlias> aliasCache = new ConcurrentHashMap<>();

    public SearchLearningService(
        SearchLearnedAliasRepository searchLearnedAliasRepository,
        SearchMissLogRepository searchMissLogRepository,
        FoodMetadataService foodMetadataService
    ) {
        this.searchLearnedAliasRepository = searchLearnedAliasRepository;
        this.searchMissLogRepository = searchMissLogRepository;
        this.foodMetadataService = foodMetadataService;
    }

    @Transactional(readOnly = true)
    public Set<String> expandLearnedTerms(String query, SearchLearningDomain domain, int maxTerms) {
        String normalizedQuery = normalizeQuery(query);
        if (!isTrackableQuery(normalizedQuery)) {
            return Set.of();
        }

        String cacheKey = buildCacheKey(domain, normalizedQuery);
        SearchLearnedAlias alias = getCachedAlias(cacheKey);
        if (alias == null) {
            alias = searchLearnedAliasRepository.findFirstByNormalizedAlias(normalizedQuery)
                .filter(candidate -> candidate.getDomain() != null && candidate.getDomain().matches(domain))
                .orElse(null);
            cacheAlias(cacheKey, alias);
        }

        if (alias == null || !StringUtils.hasText(alias.getCanonicalText())) {
            return Set.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        terms.add(alias.getCanonicalText());
        terms.addAll(foodMetadataService.expandSearchTerms(alias.getCanonicalText(), Math.max(2, maxTerms)));
        return terms.stream().limit(Math.max(2, maxTerms)).collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String query, SearchLearningDomain domain, List<String> orderedCandidates) {
        String trimmedQuery = query == null ? "" : query.trim();
        String normalizedQuery = normalizeQuery(trimmedQuery);
        if (!isTrackableQuery(normalizedQuery)) {
            return;
        }

        List<String> cleanedCandidates = sanitizeCandidates(orderedCandidates);
        SearchMissLog missLog = searchMissLogRepository.findFirstByDomainAndNormalizedQuery(domain, normalizedQuery)
            .orElseGet(() -> createMissLog(domain, trimmedQuery, normalizedQuery));

        missLog.setQueryText(safeText(trimmedQuery, MAX_QUERY_LENGTH));
        missLog.setLastSeenAt(Instant.now());

        if (cleanedCandidates.isEmpty()) {
            missLog.setMissCount(missLog.getMissCount() + 1);
            missLog.setStatus("OPEN");
            searchMissLogRepository.save(missLog);
            return;
        }

        missLog.setHitCount(missLog.getHitCount() + 1);
        CandidateConfidence confidence = evaluateCandidates(normalizedQuery, cleanedCandidates);
        missLog.setBestCandidate(safeText(confidence.bestCandidate(), MAX_QUERY_LENGTH));
        missLog.setBestConfidence((double) confidence.bestScore());

        if (shouldAutoLearn(normalizedQuery, confidence)) {
            SearchLearnedAlias alias = upsertAlias(trimmedQuery, normalizedQuery, confidence.bestCandidate(), confidence.bestScore(), domain);
            missLog.setStatus("AUTO_MAPPED");
            cacheAlias(buildCacheKey(domain, normalizedQuery), alias);
        } else {
            missLog.setStatus("REVIEW");
        }

        searchMissLogRepository.save(missLog);
    }

    private SearchMissLog createMissLog(SearchLearningDomain domain, String query, String normalizedQuery) {
        SearchMissLog missLog = new SearchMissLog();
        missLog.setDomain(domain);
        missLog.setQueryText(safeText(query, MAX_QUERY_LENGTH));
        missLog.setNormalizedQuery(normalizedQuery);
        return missLog;
    }

    private SearchLearnedAlias upsertAlias(
        String query,
        String normalizedQuery,
        String canonicalText,
        double confidence,
        SearchLearningDomain domain
    ) {
        Optional<SearchLearnedAlias> existing = searchLearnedAliasRepository.findFirstByNormalizedAlias(normalizedQuery);
        SearchLearnedAlias alias = existing.orElseGet(SearchLearnedAlias::new);
        if (existing.isPresent() && StringUtils.hasText(alias.getCanonicalText())) {
            String currentCanonical = foodMetadataService.normalizeToken(alias.getCanonicalText());
            String nextCanonical = foodMetadataService.normalizeToken(canonicalText);
            if (!currentCanonical.equals(nextCanonical) && alias.getConfidence() != null && alias.getConfidence() > confidence + 8) {
                return alias;
            }
        }

        alias.setAliasText(safeText(query, MAX_QUERY_LENGTH));
        alias.setNormalizedAlias(normalizedQuery);
        alias.setCanonicalText(safeText(canonicalText, MAX_QUERY_LENGTH));
        if (existing.isPresent()) {
            alias.setDomain(resolveDomain(alias.getDomain(), domain));
        } else {
            alias.setDomain(domain == null ? SearchLearningDomain.BOTH : domain);
        }
        alias.setSource("AUTO_LEARNED");
        alias.setConfidence(confidence);
        alias.setLastUsedAt(Instant.now());
        alias.setUsageCount((alias.getUsageCount() == null ? 0 : alias.getUsageCount()) + 1);
        return searchLearnedAliasRepository.save(alias);
    }

    private SearchLearningDomain resolveDomain(SearchLearningDomain current, SearchLearningDomain incoming) {
        if (current == null) {
            return incoming == null ? SearchLearningDomain.BOTH : incoming;
        }
        if (incoming == null) {
            return current;
        }
        if (current == incoming) {
            return current;
        }
        return SearchLearningDomain.BOTH;
    }

    private CandidateConfidence evaluateCandidates(String normalizedQuery, List<String> candidates) {
        String bestCandidate = candidates.get(0);
        int bestScore = scoreCandidate(normalizedQuery, bestCandidate);
        int secondScore = Integer.MIN_VALUE;

        for (int i = 1; i < candidates.size(); i++) {
            int score = scoreCandidate(normalizedQuery, candidates.get(i));
            if (score > secondScore) {
                secondScore = score;
            }
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestCandidate = candidates.get(i);
            }
        }

        if (secondScore == Integer.MIN_VALUE) {
            secondScore = 0;
        }
        return new CandidateConfidence(bestCandidate, bestScore, secondScore);
    }

    private boolean shouldAutoLearn(String normalizedQuery, CandidateConfidence confidence) {
        if (!StringUtils.hasText(confidence.bestCandidate())) {
            return false;
        }
        String normalizedCandidate = foodMetadataService.normalizeToken(confidence.bestCandidate());
        if (!StringUtils.hasText(normalizedCandidate)) {
            return false;
        }
        if (normalizedCandidate.equals(normalizedQuery)) {
            return false;
        }

        boolean anchorMatch = hasAnchor(normalizedQuery, normalizedCandidate);
        if (!anchorMatch) {
            return false;
        }

        int separation = confidence.bestScore() - confidence.secondBestScore();
        return confidence.bestScore() >= 84 && separation >= 8;
    }

    private int scoreCandidate(String normalizedQuery, String candidate) {
        String normalizedCandidate = foodMetadataService.normalizeToken(candidate);
        if (!StringUtils.hasText(normalizedCandidate)) {
            return 0;
        }

        int score = similarityScore(normalizedQuery, normalizedCandidate);
        if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
            score += 16;
        }

        List<String> queryTokens = tokenize(normalizedQuery);
        List<String> candidateTokens = tokenize(normalizedCandidate);
        for (String token : queryTokens) {
            if (candidateTokens.contains(token)) {
                score += 8;
            }
        }

        int bestTokenSimilarity = 0;
        for (String queryToken : queryTokens) {
            for (String candidateToken : candidateTokens) {
                bestTokenSimilarity = Math.max(bestTokenSimilarity, similarityScore(queryToken, candidateToken));
            }
        }
        if (bestTokenSimilarity >= 86) {
            score += 18;
        } else if (bestTokenSimilarity >= 76) {
            score += 10;
        }

        return Math.max(0, Math.min(100, score));
    }

    private boolean hasAnchor(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        if (left.charAt(0) == right.charAt(0)) {
            return true;
        }
        return sharedBigramCount(left, right) >= 2;
    }

    private int similarityScore(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return 0;
        }
        if (left.equals(right)) {
            return 100;
        }

        int maxLen = Math.max(left.length(), right.length());
        if (maxLen == 0) {
            return 0;
        }

        int distance = levenshteinDistance(left, right);
        double similarity = 1.0 - (distance / (double) maxLen);
        return (int) Math.round(Math.max(0.0, similarity) * 100.0);
    }

    private int levenshteinDistance(String left, String right) {
        int leftLen = left.length();
        int rightLen = right.length();
        int[] previous = new int[rightLen + 1];
        int[] current = new int[rightLen + 1];

        for (int j = 0; j <= rightLen; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftLen; i++) {
            current[0] = i;
            for (int j = 1; j <= rightLen; j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[rightLen];
    }

    private int sharedBigramCount(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return 0;
        }
        if (left.length() < 2 || right.length() < 2) {
            return 0;
        }

        Set<String> leftBigrams = new LinkedHashSet<>();
        for (int i = 0; i < left.length() - 1; i++) {
            leftBigrams.add(left.substring(i, i + 2));
        }

        int shared = 0;
        for (int i = 0; i < right.length() - 1; i++) {
            if (leftBigrams.contains(right.substring(i, i + 2))) {
                shared++;
            }
        }
        return shared;
    }

    private List<String> tokenize(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalizedText.split("\\s+")) {
            String cleaned = token.trim();
            if (cleaned.length() >= 2) {
                tokens.add(cleaned);
            }
        }
        return tokens;
    }

    private List<String> sanitizeCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            String value = safeText(candidate.trim(), MAX_QUERY_LENGTH);
            String normalized = foodMetadataService.normalizeToken(value);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (seen.add(normalized)) {
                cleaned.add(value);
            }
            if (cleaned.size() >= 10) {
                break;
            }
        }
        return cleaned;
    }

    private String normalizeQuery(String query) {
        return foodMetadataService.normalizeToken(query);
    }

    private boolean isTrackableQuery(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        if (normalizedQuery.length() < MIN_QUERY_LENGTH) {
            return false;
        }
        return !normalizedQuery.matches("^[0-9\\s]+$");
    }

    private String safeText(String text, int maxLength) {
        String value = text == null ? "" : text.trim();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() > maxLength) {
            return value.substring(0, maxLength).trim();
        }
        return value;
    }

    private String buildCacheKey(SearchLearningDomain domain, String normalizedQuery) {
        SearchLearningDomain resolved = domain == null ? SearchLearningDomain.BOTH : domain;
        return resolved.name().toLowerCase(Locale.ROOT) + ":" + normalizedQuery;
    }

    private SearchLearnedAlias getCachedAlias(String key) {
        CachedAlias cached = aliasCache.get(key);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.cachedAtMs() > CACHE_TTL_MS) {
            aliasCache.remove(key, cached);
            return null;
        }
        return cached.alias();
    }

    private void cacheAlias(String key, SearchLearnedAlias alias) {
        aliasCache.put(key, new CachedAlias(alias, System.currentTimeMillis()));
        if (aliasCache.size() <= MAX_CACHE_SIZE) {
            return;
        }

        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (var entry : aliasCache.entrySet()) {
            if (entry.getValue().cachedAtMs() < oldestTime) {
                oldestTime = entry.getValue().cachedAtMs();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            aliasCache.remove(oldestKey);
        }
    }

    private record CachedAlias(SearchLearnedAlias alias, long cachedAtMs) {
    }

    private record CandidateConfidence(String bestCandidate, int bestScore, int secondBestScore) {
    }
}
