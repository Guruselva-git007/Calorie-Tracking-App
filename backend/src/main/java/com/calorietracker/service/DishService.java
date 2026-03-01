package com.calorietracker.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.dto.CreateCustomDishRequest;
import com.calorietracker.dto.CustomDishIngredientRequest;
import com.calorietracker.dto.DishComponentResponse;
import com.calorietracker.dto.DishResponse;
import com.calorietracker.dto.IngredientCalorieBreakdown;
import com.calorietracker.dto.IngredientLineRequest;
import com.calorietracker.entity.Dish;
import com.calorietracker.entity.DishComponent;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.entity.SearchLearningDomain;
import com.calorietracker.repository.DishComponentRepository;
import com.calorietracker.repository.DishRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DishService {

    private static final long SEARCH_CACHE_TTL_MS = 300_000;
    private static final int SEARCH_CACHE_MAX = 320;
    private static final int SEARCH_TERM_LIMIT = 5;
    private static final int SEARCH_CONTEXT_LIMIT = 6;
    private static final int MIN_CANDIDATE_POOL = 14;
    private static final int MAX_CANDIDATE_POOL = 160;
    private static final int MIN_CONTAINS_TERM_LENGTH = 3;

    private final DishRepository dishRepository;
    private final DishComponentRepository dishComponentRepository;
    private final IngredientService ingredientService;
    private final FoodMetadataService foodMetadataService;
    private final SearchLearningService searchLearningService;
    private final ConcurrentMap<String, TimedSearchCacheEntry<List<DishResponse>>> searchCache = new ConcurrentHashMap<>();

    public DishService(
        DishRepository dishRepository,
        DishComponentRepository dishComponentRepository,
        IngredientService ingredientService,
        FoodMetadataService foodMetadataService,
        SearchLearningService searchLearningService
    ) {
        this.dishRepository = dishRepository;
        this.dishComponentRepository = dishComponentRepository;
        this.ingredientService = ingredientService;
        this.foodMetadataService = foodMetadataService;
        this.searchLearningService = searchLearningService;
    }

    @Transactional(readOnly = true)
    public List<DishResponse> findDishes(String search, String cuisine) {
        return findDishes(search, cuisine, null);
    }

    @Transactional(readOnly = true)
    public List<DishResponse> findDishes(String search, String cuisine, Integer limit) {
        boolean hasSearch = StringUtils.hasText(search);
        int maxResults = resolveLimit(limit, hasSearch ? 40 : 120);
        boolean cacheable = hasSearch && !StringUtils.hasText(cuisine);
        String cacheKey = cacheable ? buildSearchCacheKey(search, maxResults) : null;
        Set<String> expandedTerms = hasSearch ? new LinkedHashSet<>(foodMetadataService.expandSearchTerms(search, SEARCH_TERM_LIMIT)) : Set.of();
        if (hasSearch) {
            expandedTerms.addAll(searchLearningService.expandLearnedTerms(search, SearchLearningDomain.DISH, SEARCH_TERM_LIMIT));
        }

        if (cacheable) {
            List<DishResponse> cached = getCachedSearch(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Dish> dishes;

        if (hasSearch) {
            Map<Long, Dish> merged = new LinkedHashMap<>();
            int candidateTarget = resolveCandidatePoolSize(maxResults);
            int prefixFetchSize = resolveFetchSize(candidateTarget, Math.max(14, maxResults * 2));
            int containsFetchSize = resolveFetchSize(candidateTarget, Math.max(18, maxResults * 3));
            PageRequest prefixPage = PageRequest.of(0, prefixFetchSize);
            PageRequest containsPage = PageRequest.of(0, containsFetchSize);

            for (String term : expandedTerms) {
                if (!StringUtils.hasText(term)) {
                    continue;
                }

                dishRepository.findByNameStartingWithIgnoreCaseOrderByNameAsc(term, prefixPage)
                    .forEach(dish -> merged.put(dish.getId(), dish));

                if (merged.size() >= candidateTarget) {
                    break;
                }

                if (!shouldRunContainsLookup(term, merged.size(), maxResults)) {
                    continue;
                }

                dishRepository
                    .findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByNameAsc(term, term, containsPage)
                    .forEach(dish -> merged.put(dish.getId(), dish));

                if (merged.size() >= candidateTarget) {
                    break;
                }
            }

            if (merged.size() < Math.min(6, Math.max(2, maxResults / 2))) {
                addFuzzyDishCandidates(merged, search, candidateTarget);
            }

            dishes = merged.isEmpty()
                ? List.of()
                : new ArrayList<>(merged.values().stream().limit(candidateTarget).toList());
        } else {
            dishes = dishRepository.findByOrderByNameAsc(PageRequest.of(0, Math.max(1, maxResults)));
        }

        if (StringUtils.hasText(cuisine)) {
            String normalized = cuisine.trim().toLowerCase(Locale.ROOT);
            dishes = dishes.stream()
                .filter(dish -> dish.getCuisine().toLowerCase(Locale.ROOT).contains(normalized))
                .collect(Collectors.toList());
        }

        if (hasSearch) {
            List<SearchQueryContext> searchContexts = buildSearchContexts(search, expandedTerms);
            String primaryQuery = searchContexts.isEmpty() ? "" : searchContexts.get(0).query();
            List<String> primaryTokens = searchContexts.isEmpty() ? List.of() : searchContexts.get(0).tokens();
            int minimumScore = minimumDishSearchScore(primaryQuery, primaryTokens);
            dishes = dishes.stream()
                .map(dish -> new ScoredDish(dish, dishRelevanceScore(dish, searchContexts)))
                .filter(scored -> scored.score() >= minimumScore)
                .sorted(
                    Comparator
                        .comparingInt(ScoredDish::score)
                        .reversed()
                        .thenComparing(scored -> scored.dish().getName(), String.CASE_INSENSITIVE_ORDER)
                )
                .map(ScoredDish::dish)
                .collect(Collectors.toList());
        }

        if (hasSearch && dishes.isEmpty()) {
            dishes = fuzzyDishFallback(search, maxResults);
        }

        List<DishResponse> response = toBulkResponses(dishes, maxResults);

        if (hasSearch) {
            searchLearningService.recordOutcome(
                search,
                SearchLearningDomain.DISH,
                response.stream().map(DishResponse::getName).limit(8).collect(Collectors.toList())
            );
        }

        if (cacheable) {
            cacheSearch(cacheKey, response);
        }

        return response;
    }

    @Transactional(readOnly = true)
    public List<DishResponse> findDishSuggestions(String search, Integer limit) {
        if (!StringUtils.hasText(search)) {
            return List.of();
        }

        int maxResults = Math.min(limit == null || limit <= 0 ? 12 : limit, 40);
        String cacheKey = "suggest|" + buildSearchCacheKey(search, maxResults);
        List<DishResponse> cached = getCachedSearch(cacheKey);
        if (cached != null) {
            return cached;
        }
        int candidateTarget = resolveCandidatePoolSize(maxResults);
        int prefixFetchSize = resolveFetchSize(candidateTarget, Math.max(12, maxResults * 2));
        int containsFetchSize = resolveFetchSize(candidateTarget, Math.max(15, maxResults * 3));
        PageRequest prefixPage = PageRequest.of(0, prefixFetchSize);
        PageRequest containsPage = PageRequest.of(0, containsFetchSize);
        Map<Long, Dish> merged = new LinkedHashMap<>();
        Set<String> terms = new LinkedHashSet<>(foodMetadataService.expandSearchTerms(search, SEARCH_TERM_LIMIT));
        terms.addAll(searchLearningService.expandLearnedTerms(search, SearchLearningDomain.DISH, SEARCH_TERM_LIMIT));

        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }

            dishRepository.findByNameStartingWithIgnoreCaseOrderByNameAsc(term, prefixPage)
                .forEach(dish -> merged.put(dish.getId(), dish));

            if (merged.size() >= maxResults) {
                break;
            }

            if (!shouldRunContainsLookup(term, merged.size(), maxResults)) {
                continue;
            }

            dishRepository.findByNameContainingIgnoreCaseOrderByNameAsc(term, containsPage)
                .forEach(dish -> merged.put(dish.getId(), dish));

            if (merged.size() >= candidateTarget) {
                break;
            }
        }

        if (merged.size() < Math.min(6, Math.max(2, maxResults / 2))) {
            addFuzzyDishCandidates(merged, search, candidateTarget);
        }

        List<SearchQueryContext> searchContexts = buildSearchContexts(search, terms);
        String primaryQuery = searchContexts.isEmpty() ? "" : searchContexts.get(0).query();
        List<String> primaryTokens = searchContexts.isEmpty() ? List.of() : searchContexts.get(0).tokens();
        int minimumScore = minimumDishSearchScore(primaryQuery, primaryTokens);

        List<DishResponse> ranked = merged.values().stream()
            .map(dish -> new ScoredDish(dish, dishRelevanceScore(dish, searchContexts)))
            .filter(scored -> scored.score() >= minimumScore)
            .sorted(
                Comparator
                    .comparingInt(ScoredDish::score)
                    .reversed()
                    .thenComparing(scored -> scored.dish().getName(), String.CASE_INSENSITIVE_ORDER)
            )
            .limit(maxResults)
            .map(ScoredDish::dish)
            .map(this::toSuggestionResponse)
            .collect(Collectors.toList());

        if (!ranked.isEmpty()) {
            searchLearningService.recordOutcome(
                search,
                SearchLearningDomain.DISH,
                ranked.stream().map(DishResponse::getName).limit(8).collect(Collectors.toList())
            );
            cacheSearch(cacheKey, ranked);
            return ranked;
        }

        List<DishResponse> fallback = fuzzyDishFallback(search, maxResults).stream()
            .map(this::toSuggestionResponse)
            .collect(Collectors.toList());
        searchLearningService.recordOutcome(
            search,
            SearchLearningDomain.DISH,
            fallback.stream().map(DishResponse::getName).limit(8).collect(Collectors.toList())
        );
        cacheSearch(cacheKey, fallback);
        return fallback;
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

    private void addFuzzyDishCandidates(Map<Long, Dish> merged, String search, int candidateTarget) {
        String normalizedSearch = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedSearch) || normalizedSearch.length() < 4) {
            return;
        }

        int fallbackPoolSize = Math.min(900, Math.max(candidateTarget * 4, 280));
        List<Dish> fallbackPool = dishRepository.findByOrderByNameAsc(PageRequest.of(0, fallbackPoolSize));
        List<ScoredDish> fuzzyCandidates = fallbackPool.stream()
            .filter(dish -> hasFuzzyAnchor(foodMetadataService.normalizeToken(dish.getName()), normalizedSearch))
            .map(dish -> new ScoredDish(dish, fuzzyDishScore(dish, normalizedSearch)))
            .filter(scored -> scored.score() >= 58)
            .sorted(
                Comparator
                    .comparingInt(ScoredDish::score)
                    .reversed()
                    .thenComparing(scored -> scored.dish().getName(), String.CASE_INSENSITIVE_ORDER)
            )
            .limit(Math.max(candidateTarget, 30))
            .collect(Collectors.toList());

        for (ScoredDish candidate : fuzzyCandidates) {
            merged.putIfAbsent(candidate.dish().getId(), candidate.dish());
            if (merged.size() >= candidateTarget) {
                break;
            }
        }
    }

    private int fuzzyDishScore(Dish dish, String normalizedSearch) {
        String name = foodMetadataService.normalizeToken(dish.getName());
        String description = foodMetadataService.normalizeToken(dish.getDescription());

        if (!StringUtils.hasText(name)) {
            return 0;
        }

        int score = similarityScore(name, normalizedSearch);
        int bestTokenSimilarity = tokenizeSearch(name).stream()
            .mapToInt(token -> similarityScore(token, normalizedSearch))
            .max()
            .orElse(0);
        score = Math.max(score, bestTokenSimilarity);

        if (name.contains(normalizedSearch)) {
            score += 24;
        } else if (description.contains(normalizedSearch)) {
            score += 14;
        }
        if (bestTokenSimilarity >= 80) {
            score += 22;
        } else if (bestTokenSimilarity >= 70) {
            score += 14;
        }

        for (String token : tokenizeSearch(normalizedSearch)) {
            if (name.contains(token)) {
                score += 7;
            } else if (description.contains(token)) {
                score += 4;
            }
        }
        return score;
    }

    private List<Dish> fuzzyDishFallback(String search, int maxResults) {
        String normalizedSearch = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedSearch) || normalizedSearch.length() < 4) {
            return List.of();
        }

        int fallbackPoolSize = Math.min(1200, Math.max(maxResults * 8, 400));
        return dishRepository.findByOrderByNameAsc(PageRequest.of(0, fallbackPoolSize)).stream()
            .filter(dish -> hasFuzzyAnchor(foodMetadataService.normalizeToken(dish.getName()), normalizedSearch))
            .map(dish -> new ScoredDish(dish, fuzzyDishScore(dish, normalizedSearch)))
            .filter(scored -> scored.score() >= 58)
            .sorted(
                Comparator
                    .comparingInt(ScoredDish::score)
                    .reversed()
                    .thenComparing(scored -> scored.dish().getName(), String.CASE_INSENSITIVE_ORDER)
            )
            .limit(Math.max(1, maxResults))
            .map(ScoredDish::dish)
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
    public DishResponse getDishById(Long id) {
        return toResponse(getDishOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Dish getDishOrThrow(Long id) {
        return dishRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Dish not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<DishComponent> getDishComponents(Dish dish) {
        return dishComponentRepository.findByDishOrderByIdAsc(dish);
    }

    @Transactional
    public DishResponse createCustomDish(CreateCustomDishRequest request) {
        if (!Boolean.TRUE.equals(request.getConfirmAllFacts())) {
            throw new ResponseStatusException(BAD_REQUEST, "You must confirm all ingredients for fact-check.");
        }

        String name = request.getName().trim();
        if (dishRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(CONFLICT, "Dish already exists: " + name);
        }

        List<ResolvedIngredientLine> resolvedLines = new ArrayList<>();
        for (CustomDishIngredientRequest line : request.getIngredients()) {
            if (line.getGrams() == null || line.getGrams() <= 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Each ingredient needs grams greater than 0.");
            }

            if (!Boolean.TRUE.equals(line.getFactConfirmed())) {
                throw new ResponseStatusException(BAD_REQUEST, "Each ingredient must be confirmed for fact-check.");
            }

            Ingredient ingredient = resolveIngredient(line);
            resolvedLines.add(new ResolvedIngredientLine(ingredient, line.getGrams()));
        }

        Dish dish = new Dish(
            name,
            request.getCuisine().trim(),
            StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : "User added dish",
            true,
            normalizeSource(request.getSource(), "USER_CONFIRMED")
        );
        dish.setImageUrl(foodMetadataService.resolveDishImageUrl(request.getImageUrl(), name, request.getCuisine()));

        Dish savedDish = dishRepository.save(dish);

        for (ResolvedIngredientLine line : resolvedLines) {
            DishComponent component = new DishComponent(savedDish, line.ingredient(), CalorieMath.round(line.grams()));
            dishComponentRepository.save(component);
        }

        clearSearchCache();
        return toResponse(savedDish);
    }

    public DishResponse toResponse(Dish dish) {
        return toResponse(dish, getDishComponents(dish));
    }

    private List<DishResponse> toBulkResponses(List<Dish> dishes, int maxResults) {
        if (dishes == null || dishes.isEmpty() || maxResults <= 0) {
            return List.of();
        }

        List<Dish> limitedDishes = dishes.stream().limit(maxResults).collect(Collectors.toList());
        Map<Long, List<DishComponent>> componentsByDishId = buildComponentsByDishId(limitedDishes);

        return limitedDishes.stream()
            .map(dish -> toResponse(dish, componentsByDishId.getOrDefault(dish.getId(), List.of())))
            .collect(Collectors.toList());
    }

    private Map<Long, List<DishComponent>> buildComponentsByDishId(List<Dish> dishes) {
        if (dishes == null || dishes.isEmpty()) {
            return Map.of();
        }

        return dishComponentRepository.findByDishInWithIngredientOrderByDishIdAscIdAsc(dishes).stream()
            .collect(Collectors.groupingBy(component -> component.getDish().getId(), LinkedHashMap::new, Collectors.toList()));
    }

    private DishResponse toResponse(Dish dish, List<DishComponent> components) {
        DishResponse response = new DishResponse();
        response.setId(dish.getId());
        response.setName(dish.getName());
        response.setCuisine(dish.getCuisine());
        response.setDescription(compactDescription(dish.getDescription(), 420));
        response.setFactChecked(dish.getFactChecked());
        response.setSource(dish.getSource());
        response.setImageUrl(foodMetadataService.resolveDishImageUrl(dish.getImageUrl(), dish.getName(), dish.getCuisine()));

        List<DishComponentResponse> componentResponses = (components == null ? List.<DishComponent>of() : components).stream()
            .map(this::toComponentResponse)
            .collect(Collectors.toList());

        double totalCalories = componentResponses.stream()
            .mapToDouble(DishComponentResponse::getCalories)
            .sum();
        double totalProtein = componentResponses.stream().mapToDouble(valueOrZero(DishComponentResponse::getProtein)).sum();
        double totalCarbs = componentResponses.stream().mapToDouble(valueOrZero(DishComponentResponse::getCarbs)).sum();
        double totalFats = componentResponses.stream().mapToDouble(valueOrZero(DishComponentResponse::getFats)).sum();
        double totalFiber = componentResponses.stream().mapToDouble(valueOrZero(DishComponentResponse::getFiber)).sum();
        double totalPrice = componentResponses.stream().mapToDouble(valueOrZero(DishComponentResponse::getEstimatedPriceUsd)).sum();

        response.setCaloriesPerServing(CalorieMath.round(totalCalories));
        response.setProteinPerServing(CalorieMath.round(totalProtein));
        response.setCarbsPerServing(CalorieMath.round(totalCarbs));
        response.setFatsPerServing(CalorieMath.round(totalFats));
        response.setFiberPerServing(CalorieMath.round(totalFiber));
        response.setEstimatedPriceUsdPerServing(CalorieMath.round(totalPrice));
        response.setComponents(componentResponses);
        return response;
    }

    public CalculationResponse calculateDishCalories(Long dishId, Double servings, List<IngredientLineRequest> customIngredients) {
        Dish dish = getDishOrThrow(dishId);
        double servingFactor = servings == null || servings <= 0 ? 1.0 : servings;

        List<IngredientCalorieBreakdown> breakdown;
        if (customIngredients != null && !customIngredients.isEmpty()) {
            breakdown = calculateFromLines(customIngredients, servingFactor);
        } else {
            breakdown = getDishComponents(dish).stream()
                .map(component -> toBreakdown(component.getIngredient(), component.getGrams() * servingFactor))
                .collect(Collectors.toList());
        }

        CalculationResponse response = new CalculationResponse();
        response.setBreakdown(breakdown);
        response.setTotalCalories(CalorieMath.round(breakdown.stream().mapToDouble(IngredientCalorieBreakdown::getCalories).sum()));
        response.setTotalProtein(CalorieMath.round(breakdown.stream().mapToDouble(valueOrZero(IngredientCalorieBreakdown::getProtein)).sum()));
        response.setTotalCarbs(CalorieMath.round(breakdown.stream().mapToDouble(valueOrZero(IngredientCalorieBreakdown::getCarbs)).sum()));
        response.setTotalFats(CalorieMath.round(breakdown.stream().mapToDouble(valueOrZero(IngredientCalorieBreakdown::getFats)).sum()));
        response.setTotalFiber(CalorieMath.round(breakdown.stream().mapToDouble(valueOrZero(IngredientCalorieBreakdown::getFiber)).sum()));
        response.setEstimatedTotalPriceUsd(
            CalorieMath.round(breakdown.stream().mapToDouble(valueOrZero(IngredientCalorieBreakdown::getEstimatedPriceUsd)).sum())
        );
        return response;
    }

    private Ingredient resolveIngredient(CustomDishIngredientRequest line) {
        if (line.getIngredientId() != null) {
            return ingredientService.getIngredientOrThrow(line.getIngredientId());
        }

        if (!StringUtils.hasText(line.getIngredientName())) {
            throw new ResponseStatusException(BAD_REQUEST, "Custom ingredient name is required.");
        }

        String ingredientName = line.getIngredientName().trim();
        return ingredientService.findByNameIgnoreCase(ingredientName)
            .orElseGet(() -> createCustomIngredient(line, ingredientName));
    }

    private Ingredient createCustomIngredient(CustomDishIngredientRequest line, String ingredientName) {
        if (!StringUtils.hasText(line.getCategory())) {
            throw new ResponseStatusException(BAD_REQUEST, "Category is required for new ingredient: " + ingredientName);
        }

        if (line.getCaloriesPer100g() == null || line.getCaloriesPer100g() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Calories per 100g is required for new ingredient: " + ingredientName);
        }

        IngredientCategory category = ingredientService.parseCategoryOrThrow(line.getCategory());
        String cuisine = StringUtils.hasText(line.getCuisine()) ? line.getCuisine().trim() : "Custom";

        Ingredient ingredient = new Ingredient(
            ingredientName,
            category,
            cuisine,
            CalorieMath.round(line.getCaloriesPer100g()),
            "100 g",
            true,
            "USER_CONFIRMED"
        );

        return ingredientService.save(ingredient);
    }

    private List<IngredientCalorieBreakdown> calculateFromLines(List<IngredientLineRequest> lines, double servingFactor) {
        List<IngredientCalorieBreakdown> breakdown = new ArrayList<>();
        for (IngredientLineRequest line : lines) {
            Ingredient ingredient = ingredientService.getIngredientOrThrow(line.getIngredientId());
            breakdown.add(toBreakdown(ingredient, line.getGrams() * servingFactor));
        }
        return breakdown;
    }

    private DishComponentResponse toComponentResponse(DishComponent component) {
        DishComponentResponse response = new DishComponentResponse();
        response.setIngredientId(component.getIngredient().getId());
        response.setIngredientName(component.getIngredient().getName());
        response.setIngredientImageUrl(
            foodMetadataService.resolveIngredientImageUrl(
                component.getIngredient().getImageUrl(),
                component.getIngredient().getName(),
                component.getIngredient().getCategory(),
                component.getIngredient().getCuisine()
            )
        );
        response.setGrams(component.getGrams());
        response.setCaloriesPer100g(component.getIngredient().getCaloriesPer100g());
        response.setCalories(CalorieMath.caloriesFromGrams(component.getIngredient().getCaloriesPer100g(), component.getGrams()));
        response.setProtein(CalorieMath.nutrientFromGrams(valueOrZero(component.getIngredient().getProteinPer100g()), component.getGrams()));
        response.setCarbs(CalorieMath.nutrientFromGrams(valueOrZero(component.getIngredient().getCarbsPer100g()), component.getGrams()));
        response.setFats(CalorieMath.nutrientFromGrams(valueOrZero(component.getIngredient().getFatsPer100g()), component.getGrams()));
        response.setFiber(CalorieMath.nutrientFromGrams(valueOrZero(component.getIngredient().getFiberPer100g()), component.getGrams()));
        response.setEstimatedPriceUsd(
            CalorieMath.priceFromBaseQuantity(
                valueOrZero(component.getIngredient().getAveragePriceUsd()),
                component.getIngredient().getAveragePriceUnit(),
                component.getGrams()
            )
        );
        return response;
    }

    private IngredientCalorieBreakdown toBreakdown(Ingredient ingredient, double grams) {
        IngredientCalorieBreakdown line = new IngredientCalorieBreakdown();
        line.setIngredientId(ingredient.getId());
        line.setIngredientName(ingredient.getName());
        line.setGrams(CalorieMath.round(grams));
        line.setCalories(CalorieMath.caloriesFromGrams(ingredient.getCaloriesPer100g(), grams));
        line.setProtein(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getProteinPer100g()), grams));
        line.setCarbs(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getCarbsPer100g()), grams));
        line.setFats(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getFatsPer100g()), grams));
        line.setFiber(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getFiberPer100g()), grams));
        line.setEstimatedPriceUsd(
            CalorieMath.priceFromBaseQuantity(valueOrZero(ingredient.getAveragePriceUsd()), ingredient.getAveragePriceUnit(), grams)
        );
        return line;
    }

    private String normalizeSource(String source, String fallback) {
        String candidate = StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : fallback;
        if (candidate.length() > 50) {
            return candidate.substring(0, 50);
        }
        return candidate;
    }

    private record ResolvedIngredientLine(Ingredient ingredient, Double grams) {
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private <T> java.util.function.ToDoubleFunction<T> valueOrZero(java.util.function.Function<T, Double> getter) {
        return target -> {
            Double value = getter.apply(target);
            return value == null ? 0.0 : value;
        };
    }

    private int resolveLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, 200);
    }

    private int resolveCandidatePoolSize(int maxResults) {
        int scaled = Math.max(MIN_CANDIDATE_POOL, maxResults * 4);
        return Math.min(MAX_CANDIDATE_POOL, scaled);
    }

    private int resolveFetchSize(int candidateTarget, int preferredSize) {
        return Math.max(1, Math.min(candidateTarget, preferredSize));
    }

    private DishResponse toSuggestionResponse(Dish dish) {
        DishResponse response = new DishResponse();
        response.setId(dish.getId());
        response.setName(dish.getName());
        response.setCuisine(dish.getCuisine());
        response.setDescription(compactDescription(dish.getDescription(), 180));
        response.setFactChecked(dish.getFactChecked());
        response.setSource(dish.getSource());
        response.setImageUrl(foodMetadataService.resolveDishImageUrl(dish.getImageUrl(), dish.getName(), dish.getCuisine()));
        return response;
    }

    private String compactDescription(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        if (maxLength <= 3) {
            return normalized.substring(0, maxLength);
        }
        return normalized.substring(0, maxLength - 3) + "...";
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
        return buildSearchContexts(search, foodMetadataService.expandSearchTerms(search, SEARCH_CONTEXT_LIMIT));
    }

    private List<SearchQueryContext> buildSearchContexts(String search, Set<String> expandedTerms) {
        String normalizedOriginal = foodMetadataService.normalizeToken(search);
        if (!StringUtils.hasText(normalizedOriginal)) {
            return List.of();
        }

        if (expandedTerms == null || expandedTerms.isEmpty()) {
            return List.of(new SearchQueryContext(normalizedOriginal, tokenizeSearch(normalizedOriginal)));
        }

        boolean originalIsPhrase = normalizedOriginal.contains(" ");
        String compactOriginal = normalizedOriginal.replace(" ", "");
        Set<String> filtered = new LinkedHashSet<>();

        for (String term : expandedTerms) {
            String candidate = foodMetadataService.normalizeToken(term);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }

            if (originalIsPhrase) {
                boolean keepPhrase = candidate.contains(" ");
                boolean keepCompact = candidate.equals(compactOriginal);
                boolean keepExactOriginal = candidate.equals(normalizedOriginal);
                if (!(keepPhrase || keepCompact || keepExactOriginal)) {
                    continue;
                }
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

    private int dishRelevanceScore(Dish dish, List<SearchQueryContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 0;
        }

        int best = Integer.MIN_VALUE;
        for (SearchQueryContext context : contexts) {
            int score = dishRelevanceScoreSingle(dish, context.query(), context.tokens());
            if (score > best) {
                best = score;
            }
        }
        return best == Integer.MIN_VALUE ? 0 : best;
    }

    private int dishRelevanceScoreSingle(Dish dish, String normalizedSearch, List<String> searchTokens) {
        String name = foodMetadataService.normalizeToken(dish.getName());
        String description = foodMetadataService.normalizeToken(dish.getDescription());
        String cuisine = foodMetadataService.normalizeToken(dish.getCuisine());

        int score = 0;

        if (StringUtils.hasText(normalizedSearch)) {
            if (name.equals(normalizedSearch)) {
                score += 600;
            } else if (name.startsWith(normalizedSearch)) {
                score += 420;
            } else if (name.contains(normalizedSearch)) {
                score += 300;
            }

            if (containsQuery(description, normalizedSearch)) {
                score += 140;
            }
            if (containsQuery(cuisine, normalizedSearch)) {
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
            } else if (containsWholeToken(description, token)) {
                matchedTokens++;
                score += 35;
            } else if (containsWholeToken(cuisine, token)) {
                matchedTokens++;
                score += 25;
            }
        }

        if (!searchTokens.isEmpty()) {
            double coverage = matchedTokens / (double) searchTokens.size();
            score += Math.round(coverage * 140.0);

            if (searchTokens.size() >= 2) {
                if (coverage >= 0.999) {
                    score += 170;
                } else if (coverage >= 0.67) {
                    score -= 20;
                } else if (coverage >= 0.5) {
                    score -= 120;
                } else {
                    score -= 220;
                }
            }
        }

        if (StringUtils.hasText(normalizedSearch) && name.length() > normalizedSearch.length()) {
            score -= Math.min(24, name.length() - normalizedSearch.length());
        }

        return score;
    }

    private boolean containsWholeToken(String text, String token) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(token)) {
            return false;
        }
        String haystack = " " + text + " ";
        String needle = " " + token + " ";
        return haystack.contains(needle);
    }

    private boolean containsQuery(String text, String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        if (query.contains(" ")) {
            return StringUtils.hasText(text) && text.contains(query);
        }
        return containsWholeToken(text, query);
    }

    private int minimumDishSearchScore(String normalizedSearch, List<String> searchTokens) {
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

    private String buildSearchCacheKey(String search, int maxResults) {
        return foodMetadataService.normalizeToken(search) + "|" + maxResults;
    }

    private List<DishResponse> getCachedSearch(String key) {
        TimedSearchCacheEntry<List<DishResponse>> entry = searchCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.cachedAtMs() > SEARCH_CACHE_TTL_MS) {
            searchCache.remove(key);
            return null;
        }
        return entry.value();
    }

    private void cacheSearch(String key, List<DishResponse> value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }

        searchCache.put(key, new TimedSearchCacheEntry<>(value, System.currentTimeMillis()));
        if (searchCache.size() <= SEARCH_CACHE_MAX) {
            return;
        }

        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, TimedSearchCacheEntry<List<DishResponse>>> entry : searchCache.entrySet()) {
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

    private record ScoredDish(Dish dish, int score) {
    }

    private record SearchQueryContext(String query, List<String> tokens) {
    }
}
