package com.calorietracker.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.CreateIngredientRequest;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.IngredientRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class IngredientService {

    private static final long SEARCH_CACHE_TTL_MS = 90_000;
    private static final int SEARCH_CACHE_MAX = 240;

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final ConcurrentMap<String, TimedSearchCacheEntry<List<IngredientResponse>>> searchCache = new ConcurrentHashMap<>();

    public IngredientService(IngredientRepository ingredientRepository, FoodMetadataService foodMetadataService) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> findIngredients(String search, String category, String cuisine) {
        return findIngredients(search, category, cuisine, null);
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> findIngredients(String search, String category, String cuisine, Integer limit) {
        boolean hasSearch = StringUtils.hasText(search);
        int maxResults = resolveLimit(limit, hasSearch ? 120 : 1200);
        boolean cacheable = hasSearch && !StringUtils.hasText(category) && !StringUtils.hasText(cuisine);
        String cacheKey = cacheable ? buildSearchCacheKey(search, maxResults) : null;

        if (cacheable) {
            List<IngredientResponse> cached = getCachedSearch(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<Ingredient> ingredients;

        if (hasSearch) {
            Map<Long, Ingredient> merged = new LinkedHashMap<>();
            Set<String> terms = foodMetadataService.expandSearchTerms(search);

            for (String term : terms) {
                if (!StringUtils.hasText(term)) {
                    continue;
                }

                ingredientRepository.findTop180ByNameStartingWithIgnoreCaseOrderByNameAsc(term)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));
                ingredientRepository.findTop180ByAliasesStartingWithIgnoreCaseOrderByNameAsc(term)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));

                if (merged.size() >= maxResults * 2) {
                    break;
                }

                ingredientRepository.findTop300ByNameContainingIgnoreCaseOrderByNameAsc(term)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));
                ingredientRepository.findTop300ByAliasesContainingIgnoreCaseOrderByNameAsc(term)
                    .forEach(ingredient -> merged.put(ingredient.getId(), ingredient));

                if (merged.size() >= maxResults * 3) {
                    break;
                }
            }

            ingredients = merged.isEmpty()
                ? List.of()
                : merged.values().stream().limit(maxResults).collect(Collectors.toList());
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
            String normalizedSearch = foodMetadataService.normalizeToken(search);
            List<String> searchTokens = tokenizeSearch(normalizedSearch);
            int minimumScore = minimumIngredientSearchScore(normalizedSearch, searchTokens);
            ingredients = ingredients.stream()
                .map(ingredient -> new ScoredIngredient(ingredient, ingredientRelevanceScore(ingredient, normalizedSearch, searchTokens)))
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

        List<IngredientResponse> response = ingredients.stream()
            .limit(maxResults)
            .map(this::ensureMetadata)
            .map(this::toResponse)
            .collect(Collectors.toList());

        if (cacheable) {
            cacheSearch(cacheKey, response);
        }

        return response;
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
        response.setAliases(foodMetadataService.fromCsv(ingredient.getAliases()));
        response.setRegionalAvailability(foodMetadataService.fromCsv(ingredient.getRegionalAvailability()));
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

    private int ingredientRelevanceScore(Ingredient ingredient, String normalizedSearch, List<String> searchTokens) {
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
        if (maxResults <= 500) {
            return ingredientRepository.findTop500ByOrderByNameAsc()
                .stream()
                .limit(maxResults)
                .collect(Collectors.toList());
        }
        return ingredientRepository.findTop1200ByOrderByNameAsc()
            .stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    private int resolveLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, 1200);
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
}
