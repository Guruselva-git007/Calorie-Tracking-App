package com.calorietracker.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.CreateIngredientRequest;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.entity.SearchLearningDomain;
import com.calorietracker.repository.IngredientRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class IngredientService {

    private static final long SEARCH_CACHE_TTL_MS = 300_000;
    private static final int SEARCH_CACHE_MAX = 420;
    private static final int SEARCH_TERM_LIMIT = 5;
    private static final int SEARCH_CONTEXT_LIMIT = 6;
    private static final int MIN_CANDIDATE_POOL = 16;
    private static final int MAX_CANDIDATE_POOL = 160;
    private static final int MIN_CONTAINS_TERM_LENGTH = 3;

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final SearchLearningService searchLearningService;
    private final ConcurrentMap<String, TimedSearchCacheEntry<List<IngredientResponse>>> searchCache = new ConcurrentHashMap<>();

    public IngredientService(
        IngredientRepository ingredientRepository,
        FoodMetadataService foodMetadataService,
        SearchLearningService searchLearningService
    ) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
        this.searchLearningService = searchLearningService;
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> findIngredients(String search, String category, String cuisine) {
        return findIngredients(search, category, cuisine, null);
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> findIngredients(String search, String category, String cuisine, Integer limit) {
        boolean hasSearch = StringUtils.hasText(search);
        int maxResults = resolveLimit(limit, hasSearch ? 60 : 600);
        boolean cacheable = hasSearch && !StringUtils.hasText(category) && !StringUtils.hasText(cuisine);
        String cacheKey = cacheable ? buildSearchCacheKey(search, maxResults) : null;
        Set<String> expandedTerms = hasSearch ? new LinkedHashSet<>(foodMetadataService.expandSearchTerms(search, SEARCH_TERM_LIMIT)) : Set.of();
        if (hasSearch) {
            expandedTerms.addAll(searchLearningService.expandLearnedTerms(search, SearchLearningDomain.INGREDIENT, SEARCH_TERM_LIMIT));
        }

        if (cacheable) {
            List<IngredientResponse> cached = getCachedSearch(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Ingredient> ingredients;

        if (hasSearch) {
            Map<Long, Ingredient> merged = new LinkedHashMap<>();
            int candidateTarget = resolveCandidatePoolSize(maxResults);
            int prefixFetchSize = resolveFetchSize(candidateTarget, Math.max(18, maxResults * 2));
            int containsFetchSize = resolveFetchSize(candidateTarget, Math.max(24, maxResults * 3));
            PageRequest prefixPage = PageRequest.of(0, prefixFetchSize);
            PageRequest containsPage = PageRequest.of(0, containsFetchSize);

            for (String term : expandedTerms) {
                if (!StringUtils.hasText(term)) {
                    continue;
                }

                ingredientRepository.findByNameStartingWithIgnoreCaseOrderByNameAsc(term, prefixPage)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));
                ingredientRepository.findByAliasesStartingWithIgnoreCaseOrderByNameAsc(term, prefixPage)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));

                if (merged.size() >= candidateTarget) {
                    break;
                }

                if (!shouldRunContainsLookup(term, merged.size(), maxResults)) {
                    continue;
                }

                ingredientRepository.findByNameContainingIgnoreCaseOrderByNameAsc(term, containsPage)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));
                if (merged.size() >= candidateTarget) {
                    break;
                }

                ingredientRepository.findByAliasesContainingIgnoreCaseOrderByNameAsc(term, containsPage)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));

                if (merged.size() >= candidateTarget) {
                    break;
                }
            }

            if (merged.size() < Math.min(6, Math.max(2, maxResults / 2))) {
                addFuzzyIngredientCandidates(merged, search, candidateTarget);
            }

            ingredients = merged.isEmpty()
                ? List.of()
                : merged.values().stream().limit(candidateTarget).collect(Collectors.toList());
        } else {
            ingredients = loadTopIngredients(maxResults);
        }

        if (StringUtils.hasText(category)) {
            IngredientCategory parsedCategory = parseCategory(category);
            ingredients = ingredients.stream()
                .filter(ingredient -> ingredient.getCategory() == parsedCategory)
                .collect(Collectors.toList());
        }

        if (StringUtils.hasText(cuisine)) {
            String normalizedCuisine = cuisine.trim().toLowerCase(Locale.ROOT);
            ingredients = ingredients.stream()
                .filter(ingredient -> ingredient.getCuisine().toLowerCase(Locale.ROOT).contains(normalizedCuisine))
                .collect(Collectors.toList());
        }

        if (hasSearch) {
            List<SearchQueryContext> searchContexts = buildSearchContexts(search, expandedTerms);
            String normalizedSearch = searchContexts.isEmpty() ? foodMetadataService.normalizeToken(search) : searchContexts.get(0).query();
            List<String> searchTokens = searchContexts.isEmpty() ? List.of() : searchContexts.get(0).tokens();
            int minimumScore = minimumIngredientSearchScore(normalizedSearch, searchTokens);
            ingredients = ingredients.stream()
                .map(ingredient -> new ScoredIngredient(ingredient, ingredientRelevanceScore(ingredient, searchContexts)))
                .filter(scored -> scored.score() >= minimumScore)
                .sorted(
                    Comparator
                        .comparingInt(ScoredIngredient::score)
                        .reversed()
                        .thenComparing(scored -> scored.ingredient().getName(), String.CASE_INSENSITIVE_ORDER)
                )
                .map(ScoredIngredient::ingredient)
                .collect(Collectors.toList());
        }

        if (hasSearch && ingredients.isEmpty()) {
            ingredients = fuzzyIngredientFallback(search, maxResults);
        }

        List<IngredientResponse> response = ingredients.stream()
            .limit(maxResults)
            .map(this::ensureMetadata)
            .map(this::toResponse)
            .collect(Collectors.toList());

        if (hasSearch) {
            searchLearningService.recordOutcome(
                search,
                SearchLearningDomain.INGREDIENT,
                response.stream().map(IngredientResponse::getName).limit(8).collect(Collectors.toList())
            );
        }

        if (cacheable) {
            cacheSearch(cacheKey, response);
        }

        return response;
    }

    private boolean shouldRunContainsLookup(String term, int mergedSize, int maxResults) {
        if (!StringUtils.hasText(term)) {
            return false;
        }
        if (term.trim().length() >= MIN_CONTAINS_TERM_LENGTH) {
            return true;
        }
        return mergedSize < Math.max(8, maxResults / 2);
    }

    private void addFuzzyIngredientCandidates(Map<Long, Ingredient> merged, String search, int candidateTarget) {
        String normalizedSearch = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedSearch) || normalizedSearch.length() < 4) {
            return;
        }

        int fallbackPoolSize = Math.min(1400, Math.max(candidateTarget * 5, 360));
        List<Ingredient> fallbackPool = ingredientRepository.findByOrderByNameAsc(PageRequest.of(0, fallbackPoolSize));
        List<ScoredIngredient> fuzzyCandidates = fallbackPool.stream()
            .filter(ingredient -> hasFuzzyAnchor(foodMetadataService.normalizeToken(ingredient.getName()), normalizedSearch))
            .map(ingredient -> new ScoredIngredient(ingredient, fuzzyIngredientScore(ingredient, normalizedSearch)))
            .filter(scored -> scored.score() >= 58)
            .sorted(
                Comparator
                    .comparingInt(ScoredIngredient::score)
                    .reversed()
                    .thenComparing(scored -> scored.ingredient().getName(), String.CASE_INSENSITIVE_ORDER)
            )
            .limit(Math.max(candidateTarget, 40))
            .collect(Collectors.toList());

        for (ScoredIngredient candidate : fuzzyCandidates) {
            merged.putIfAbsent(candidate.ingredient().getId(), candidate.ingredient());
            if (merged.size() >= candidateTarget) {
                break;
            }
        }
    }

    private int fuzzyIngredientScore(Ingredient ingredient, String normalizedSearch) {
        String name = foodMetadataService.normalizeToken(ingredient.getName());
        String aliases = foodMetadataService.normalizeToken(ingredient.getAliases());

        if (!StringUtils.hasText(name)) {
            return 0;
        }

        int score = similarityScore(name, normalizedSearch);
        int bestTokenSimilarity = tokenizeSearch(name).stream()
            .mapToInt(token -> similarityScore(token, normalizedSearch))
            .max()
            .orElse(0);
        score = Math.max(score, bestTokenSimilarity);

        if (aliases.contains(normalizedSearch)) {
            score += 16;
        }
        if (name.contains(normalizedSearch)) {
            score += 20;
        }
        if (bestTokenSimilarity >= 80) {
            score += 20;
        } else if (bestTokenSimilarity >= 70) {
            score += 12;
        }

        for (String token : tokenizeSearch(normalizedSearch)) {
            if (name.contains(token) || aliases.contains(token)) {
                score += 6;
            }
        }
        return score;
    }

    private List<Ingredient> fuzzyIngredientFallback(String search, int maxResults) {
        String normalizedSearch = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedSearch) || normalizedSearch.length() < 4) {
            return List.of();
        }

        int fallbackPoolSize = Math.min(1800, Math.max(maxResults * 10, 600));
        return ingredientRepository.findByOrderByNameAsc(PageRequest.of(0, fallbackPoolSize)).stream()
            .filter(ingredient -> hasFuzzyAnchor(foodMetadataService.normalizeToken(ingredient.getName()), normalizedSearch))
            .map(ingredient -> new ScoredIngredient(ingredient, fuzzyIngredientScore(ingredient, normalizedSearch)))
            .filter(scored -> scored.score() >= 58)
            .sorted(
                Comparator
                    .comparingInt(ScoredIngredient::score)
                    .reversed()
                    .thenComparing(scored -> scored.ingredient().getName(), String.CASE_INSENSITIVE_ORDER)
            )
            .limit(Math.max(1, maxResults))
            .map(ScoredIngredient::ingredient)
            .collect(Collectors.toList());
    }

    private boolean hasFuzzyAnchor(String candidateName, String query) {
        if (!StringUtils.hasText(candidateName) || !StringUtils.hasText(query)) {
            return false;
        }

        String normalizedQuery = query.replace(" ", "");
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }

        String compactName = candidateName.replace(" ", "");
        if (compactName.contains(normalizedQuery) || normalizedQuery.contains(compactName)) {
            return true;
        }

        for (String token : tokenizeSearch(candidateName)) {
            if (token.length() < 3) {
                continue;
            }
            if (token.contains(normalizedQuery) || normalizedQuery.contains(token)) {
                return true;
            }
            if (token.charAt(0) != normalizedQuery.charAt(0)) {
                continue;
            }
            if (token.length() >= 2
                && normalizedQuery.length() >= 2
                && token.substring(0, 2).equals(normalizedQuery.substring(0, 2))) {
                return true;
            }
            if (sharedBigramCount(token, normalizedQuery) >= 2) {
                return true;
            }
        }

        return false;
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

    @Transactional(readOnly = true)
    public Ingredient getIngredientOrThrow(Long id) {
        return ingredientRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ingredient not found: " + id));
    }

    @Transactional(readOnly = true)
    public IngredientResponse getIngredientById(Long id) {
        return toResponse(ensureMetadata(getIngredientOrThrow(id)));
    }

    public IngredientResponse toResponse(Ingredient ingredient) {
        IngredientResponse response = new IngredientResponse();
        response.setId(ingredient.getId());
        response.setName(ingredient.getName());
        response.setCategory(ingredient.getCategory());
        response.setCuisine(ingredient.getCuisine());
        response.setCaloriesPer100g(ingredient.getCaloriesPer100g());
        response.setProteinPer100g(ingredient.getProteinPer100g());
        response.setCarbsPer100g(ingredient.getCarbsPer100g());
        response.setFatsPer100g(ingredient.getFatsPer100g());
        response.setFiberPer100g(ingredient.getFiberPer100g());
        response.setAveragePriceUsd(ingredient.getAveragePriceUsd());
        response.setAveragePriceUnit(ingredient.getAveragePriceUnit());
        response.setServingNote(ingredient.getServingNote());
        response.setFactChecked(ingredient.getFactChecked());
        response.setSource(ingredient.getSource());
        List<String> aliases = foodMetadataService.fromCsv(ingredient.getAliases());
        List<String> regionalAvailability = foodMetadataService.fromCsv(ingredient.getRegionalAvailability());
        response.setAliases(trimList(aliases, 16));
        response.setRegionalAvailability(trimList(regionalAvailability, 10));
        response.setImageUrl(
            foodMetadataService.resolveIngredientImageUrl(
                ingredient.getImageUrl(),
                ingredient.getName(),
                ingredient.getCategory(),
                ingredient.getCuisine()
            )
        );
        return response;
    }

    private List<String> trimList(List<String> items, int max) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, max);
        if (items.size() <= limit) {
            return items;
        }
        return new ArrayList<>(items.subList(0, limit));
    }

    @Transactional
    public IngredientResponse createIngredient(CreateIngredientRequest request) {
        String name = request.getName().trim();
        if (ingredientRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(CONFLICT, "Ingredient already exists: " + name);
        }

        if (!Boolean.TRUE.equals(request.getFactConfirmed())) {
            throw new ResponseStatusException(BAD_REQUEST, "Fact confirmation is required.");
        }

        Ingredient ingredient = new Ingredient(
            name,
            parseCategoryOrThrow(request.getCategory()),
            request.getCuisine().trim(),
            CalorieMath.round(request.getCaloriesPer100g()),
            request.getServingNote().trim(),
            true,
            normalizeSource(request.getSource(), "USER_CONFIRMED")
        );

        ingredient.setProteinPer100g(request.getProteinPer100g());
        ingredient.setCarbsPer100g(request.getCarbsPer100g());
        ingredient.setFatsPer100g(request.getFatsPer100g());
        ingredient.setFiberPer100g(request.getFiberPer100g());
        ingredient.setAveragePriceUsd(request.getAveragePriceUsd());
        ingredient.setAveragePriceUnit(request.getAveragePriceUnit());
        ingredient.setImageUrl(
            foodMetadataService.resolveIngredientImageUrl(
                request.getImageUrl(),
                name,
                ingredient.getCategory(),
                ingredient.getCuisine()
            )
        );

        if (request.getAliases() != null && !request.getAliases().isEmpty()) {
            ingredient.setAliases(foodMetadataService.toCsv(request.getAliases()));
        }
        if (request.getRegionalAvailability() != null && !request.getRegionalAvailability().isEmpty()) {
            ingredient.setRegionalAvailability(foodMetadataService.toCsv(request.getRegionalAvailability()));
        }

        foodMetadataService.applyDefaults(ingredient);
        Ingredient saved = ingredientRepository.save(ingredient);
        clearSearchCache();
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Optional<Ingredient> findByNameIgnoreCase(String name) {
        return ingredientRepository.findFirstByNameIgnoreCase(name);
    }

    @Transactional
    public Ingredient save(Ingredient ingredient) {
        foodMetadataService.applyDefaults(ingredient);
        Ingredient saved = ingredientRepository.save(ingredient);
        clearSearchCache();
        return saved;
    }

    public void fillMissingMetadata(Ingredient ingredient) {
        foodMetadataService.applyDefaults(ingredient);
    }

    public IngredientCategory parseCategoryOrThrow(String category) {
        try {
            return IngredientCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown category: " + category);
        }
    }

    private IngredientCategory parseCategory(String category) {
        return parseCategoryOrThrow(category);
    }

    private String normalizeSource(String source, String fallback) {
        String candidate = StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : fallback;
        if (candidate.length() > 50) {
            return candidate.substring(0, 50);
        }
        return candidate;
    }

    private Ingredient ensureMetadata(Ingredient ingredient) {
        boolean missingMetadata = ingredient.getProteinPer100g() == null
            || ingredient.getCarbsPer100g() == null
            || ingredient.getFatsPer100g() == null
            || ingredient.getFiberPer100g() == null
            || ingredient.getAveragePriceUsd() == null
            || !StringUtils.hasText(ingredient.getAveragePriceUnit())
            || !StringUtils.hasText(ingredient.getAliases())
            || !StringUtils.hasText(ingredient.getRegionalAvailability());

        if (!missingMetadata) {
            return ingredient;
        }

        foodMetadataService.applyDefaults(ingredient);
        return ingredient;
    }

    private List<String> tokenizeSearch(String normalizedSearch) {
        if (!StringUtils.hasText(normalizedSearch)) {
            return List.of();
        }
        return List.of(normalizedSearch.split("\\s+"))
            .stream()
            .map(String::trim)
            .filter(token -> token.length() >= 2)
            .distinct()
            .collect(Collectors.toList());
    }

    private List<SearchQueryContext> buildSearchContexts(String search) {
        String normalizedOriginal = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedOriginal)) {
            return List.of();
        }

        Set<String> filtered = new LinkedHashSet<>();
        for (String term : foodMetadataService.expandSearchTerms(search, SEARCH_CONTEXT_LIMIT)) {
            String candidate = foodMetadataService.normalizeToken(term);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= SEARCH_CONTEXT_LIMIT) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            filtered.add(normalizedOriginal);
        }

        return filtered.stream()
            .map(query -> new SearchQueryContext(query, tokenizeSearch(query)))
            .collect(Collectors.toList());
    }

    private List<SearchQueryContext> buildSearchContexts(String search, Set<String> expandedTerms) {
        String normalizedOriginal = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedOriginal)) {
            return List.of();
        }

        if (expandedTerms == null || expandedTerms.isEmpty()) {
            return buildSearchContexts(search);
        }

        Set<String> filtered = new LinkedHashSet<>();
        for (String term : expandedTerms) {
            String candidate = foodMetadataService.normalizeToken(term);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= SEARCH_CONTEXT_LIMIT) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            filtered.add(normalizedOriginal);
        }

        return filtered.stream()
            .map(query -> new SearchQueryContext(query, tokenizeSearch(query)))
            .collect(Collectors.toList());
    }

    private int ingredientRelevanceScore(Ingredient ingredient, List<SearchQueryContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 0;
        }

        int best = Integer.MIN_VALUE;
        for (SearchQueryContext context : contexts) {
            int score = ingredientRelevanceScoreSingle(ingredient, context.query(), context.tokens());
            if (score > best) {
                best = score;
            }
        }
        return best == Integer.MIN_VALUE ? 0 : best;
    }

    private int ingredientRelevanceScoreSingle(Ingredient ingredient, String normalizedSearch, List<String> searchTokens) {
        String name = foodMetadataService.normalizeToken(ingredient.getName());
        String aliases = foodMetadataService.normalizeToken(ingredient.getAliases());
        String cuisine = foodMetadataService.normalizeToken(ingredient.getCuisine());
        String category = ingredient.getCategory() == null ? "" : ingredient.getCategory().name().toLowerCase(Locale.ROOT);

        int score = 0;

        if (StringUtils.hasText(normalizedSearch)) {
            if (name.equals(normalizedSearch)) {
                score += 520;
            } else if (name.startsWith(normalizedSearch)) {
                score += 360;
            } else if (name.contains(normalizedSearch)) {
                score += 260;
            }

            if (aliases.contains(normalizedSearch)) {
                score += 220;
            }
            if (cuisine.contains(normalizedSearch)) {
                score += 120;
            }
            if (category.contains(normalizedSearch)) {
                score += 80;
            }
        }

        int matchedTokens = 0;
        for (String token : searchTokens) {
            if (name.startsWith(token)) {
                matchedTokens++;
                score += 90;
            } else if (name.contains(token)) {
                matchedTokens++;
                score += 60;
            } else if (aliases.contains(token)) {
                matchedTokens++;
                score += 45;
            } else if (cuisine.contains(token) || category.contains(token)) {
                matchedTokens++;
                score += 25;
            }
        }

        if (!searchTokens.isEmpty()) {
            score += Math.round((matchedTokens * 100.0f) / searchTokens.size());
        }

        if (StringUtils.hasText(normalizedSearch) && name.length() > normalizedSearch.length()) {
            score -= Math.min(24, name.length() - normalizedSearch.length());
        }

        return score;
    }

    private int minimumIngredientSearchScore(String normalizedSearch, List<String> searchTokens) {
        if (!StringUtils.hasText(normalizedSearch)) {
            return Integer.MIN_VALUE;
        }
        if (searchTokens.size() >= 3) {
            return 90;
        }
        if (searchTokens.size() == 2) {
            return 70;
        }
        if (normalizedSearch.length() >= 6) {
            return 60;
        }
        return 45;
    }

    private List<Ingredient> loadTopIngredients(int maxResults) {
        return ingredientRepository.findByOrderByNameAsc(PageRequest.of(0, Math.max(1, maxResults)));
    }

    private int resolveLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, 1200);
    }

    private int resolveCandidatePoolSize(int maxResults) {
        int scaled = Math.max(MIN_CANDIDATE_POOL, maxResults * 4);
        return Math.min(MAX_CANDIDATE_POOL, scaled);
    }

    private int resolveFetchSize(int candidateTarget, int preferredSize) {
        return Math.max(1, Math.min(candidateTarget, preferredSize));
    }

    private String buildSearchCacheKey(String search, int maxResults) {
        return foodMetadataService.normalizeToken(search) + "|" + maxResults;
    }

    private List<IngredientResponse> getCachedSearch(String key) {
        TimedSearchCacheEntry<List<IngredientResponse>> entry = searchCache.get(key);
        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() - entry.cachedAtMs() > SEARCH_CACHE_TTL_MS) {
            searchCache.remove(key);
            return null;
        }
        return entry.value();
    }

    private void cacheSearch(String key, List<IngredientResponse> value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        searchCache.put(key, new TimedSearchCacheEntry<>(value, System.currentTimeMillis()));

        if (searchCache.size() <= SEARCH_CACHE_MAX) {
            return;
        }

        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, TimedSearchCacheEntry<List<IngredientResponse>>> entry : searchCache.entrySet()) {
            if (entry.getValue().cachedAtMs() < oldestTime) {
                oldestTime = entry.getValue().cachedAtMs();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            searchCache.remove(oldestKey);
        }
    }

    private void clearSearchCache() {
        searchCache.clear();
    }

    private record TimedSearchCacheEntry<T>(T value, long cachedAtMs) {
    }

    private record ScoredIngredient(Ingredient ingredient, int score) {
    }

    private record SearchQueryContext(String query, List<String> tokens) {
    }
}
