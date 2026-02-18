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
import com.calorietracker.repository.DishComponentRepository;
import com.calorietracker.repository.DishRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DishService {

    private static final long SEARCH_CACHE_TTL_MS = 90_000;
    private static final int SEARCH_CACHE_MAX = 180;

    private final DishRepository dishRepository;
    private final DishComponentRepository dishComponentRepository;
    private final IngredientService ingredientService;
    private final FoodMetadataService foodMetadataService;
    private final ConcurrentMap<String, TimedSearchCacheEntry<List<DishResponse>>> searchCache = new ConcurrentHashMap<>();

    public DishService(
        DishRepository dishRepository,
        DishComponentRepository dishComponentRepository,
        IngredientService ingredientService,
        FoodMetadataService foodMetadataService
    ) {
        this.dishRepository = dishRepository;
        this.dishComponentRepository = dishComponentRepository;
        this.ingredientService = ingredientService;
        this.foodMetadataService = foodMetadataService;
    }

    @Transactional(readOnly = true)
    public List<DishResponse> findDishes(String search, String cuisine) {
        return findDishes(search, cuisine, null);
    }

    @Transactional(readOnly = true)
    public List<DishResponse> findDishes(String search, String cuisine, Integer limit) {
        boolean hasSearch = StringUtils.hasText(search);
        int maxResults = resolveLimit(limit, hasSearch ? 80 : 200);
        boolean cacheable = hasSearch && !StringUtils.hasText(cuisine);
        String cacheKey = cacheable ? buildSearchCacheKey(search, maxResults) : null;

        if (cacheable) {
            List<DishResponse> cached = getCachedSearch(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Dish> dishes;

        if (hasSearch) {
            Map<Long, Dish> merged = new LinkedHashMap<>();
            Set<String> terms = foodMetadataService.expandSearchTerms(search);
            for (String term : terms) {
                if (!StringUtils.hasText(term)) {
                    continue;
                }

                dishRepository.findTop80ByNameStartingWithIgnoreCaseOrderByNameAsc(term)
                    .forEach(dish -> merged.put(dish.getId(), dish));

                if (merged.size() >= maxResults * 2) {
                    break;
                }

                dishRepository
                    .findTop200ByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByNameAsc(term, term)
                    .forEach(dish -> merged.put(dish.getId(), dish));

                if (merged.size() >= maxResults * 2) {
                    break;
                }
            }

            dishes = merged.isEmpty()
                ? List.of()
                : new ArrayList<>(merged.values().stream().limit(maxResults).toList());
        } else {
            dishes = dishRepository.findTop200ByOrderByNameAsc().stream().limit(maxResults).collect(Collectors.toList());
        }

        if (StringUtils.hasText(cuisine)) {
            String normalized = cuisine.trim().toLowerCase(Locale.ROOT);
            dishes = dishes.stream()
                .filter(dish -> dish.getCuisine().toLowerCase(Locale.ROOT).contains(normalized))
                .collect(Collectors.toList());
        }

        if (hasSearch) {
            List<SearchQueryContext> searchContexts = buildSearchContexts(search);
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

        List<DishResponse> response = dishes.stream()
            .limit(maxResults)
            .map(this::toResponse)
            .collect(Collectors.toList());

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
        Map<Long, Dish> merged = new LinkedHashMap<>();
        Set<String> terms = foodMetadataService.expandSearchTerms(search);

        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }

            dishRepository.findTop80ByNameStartingWithIgnoreCaseOrderByNameAsc(term)
                .forEach(dish -> merged.put(dish.getId(), dish));

            if (merged.size() >= maxResults) {
                break;
            }

            dishRepository.findTop120ByNameContainingIgnoreCaseOrderByNameAsc(term)
                .forEach(dish -> merged.put(dish.getId(), dish));

            if (merged.size() >= maxResults * 2) {
                break;
            }
        }

        List<SearchQueryContext> searchContexts = buildSearchContexts(search);
        String primaryQuery = searchContexts.isEmpty() ? "" : searchContexts.get(0).query();
        List<String> primaryTokens = searchContexts.isEmpty() ? List.of() : searchContexts.get(0).tokens();
        int minimumScore = minimumDishSearchScore(primaryQuery, primaryTokens);

        return merged.values().stream()
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
        DishResponse response = new DishResponse();
        response.setId(dish.getId());
        response.setName(dish.getName());
        response.setCuisine(dish.getCuisine());
        response.setDescription(dish.getDescription());
        response.setFactChecked(dish.getFactChecked());
        response.setSource(dish.getSource());
        response.setImageUrl(foodMetadataService.resolveDishImageUrl(dish.getImageUrl(), dish.getName(), dish.getCuisine()));

        List<DishComponent> components = getDishComponents(dish);
        List<DishComponentResponse> componentResponses = components.stream()
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

    private DishResponse toSuggestionResponse(Dish dish) {
        DishResponse response = new DishResponse();
        response.setId(dish.getId());
        response.setName(dish.getName());
        response.setCuisine(dish.getCuisine());
        response.setDescription(dish.getDescription());
        response.setFactChecked(dish.getFactChecked());
        response.setSource(dish.getSource());
        response.setImageUrl(foodMetadataService.resolveDishImageUrl(dish.getImageUrl(), dish.getName(), dish.getCuisine()));
        return response;
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

        boolean originalIsPhrase = normalizedOriginal.contains(" ");
        String compactOriginal = normalizedOriginal.replace(" ", "");
        Set<String> filtered = new LinkedHashSet<>();

        for (String term : foodMetadataService.expandSearchTerms(search)) {
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
            if (filtered.size() >= 12) {
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
            score += Math.round((matchedTokens * 120.0f) / searchTokens.size());
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
